#!/usr/bin/env python3
"""AndroidForge — Build Project.

Runs the configured build commands from the detected project root. For nested
Android projects, resolves Gradle wrapper commands to the directory containing
the wrapper/settings files, so Gradle is never launched from the wrong folder.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


def run(
    cmd: list[str],
    cwd: Path,
    log_file: Path,
    env: dict[str, str] | None = None,
) -> tuple[int, str]:
    """Run a command, streaming output to the console and a log file."""
    log_file.parent.mkdir(parents=True, exist_ok=True)
    print(f"\n=== Running: {' '.join(cmd)}", flush=True)
    print(f"    cwd: {cwd}", flush=True)
    print(f"    log: {log_file}", flush=True)
    start = time.time()
    full_env = os.environ.copy()
    if env:
        full_env.update(env)

    with log_file.open("w", encoding="utf-8", errors="replace") as handle:
        handle.write(f"$ {' '.join(cmd)}\n")
        handle.write(f"# cwd: {cwd}\n\n")
        handle.flush()
        proc = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            env=full_env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            handle.write(line)
        rc = proc.wait()

    elapsed = time.time() - start
    print(f"=== Exit {rc} after {elapsed:.1f}s", flush=True)
    return rc, str(log_file)


def maybe_regenerate_wrapper(
    project_root: Path, gradle_version: str | None, log_dir: Path
) -> bool:
    """Regenerate a missing Gradle wrapper JAR using system Gradle if possible."""
    jar = project_root / "gradle" / "wrapper" / "gradle-wrapper.jar"
    if jar.is_file():
        return True
    if not gradle_version:
        print("WARNING: cannot regenerate wrapper: no Gradle version specified", flush=True)
        return False
    command = [
        "gradle", "wrapper", "--gradle-version", gradle_version,
        "--distribution-type", "bin",
    ]
    rc, _ = run(command, project_root, log_dir / "regenerate-wrapper.log")
    return rc == 0 and jar.is_file()


def _load_json_argument(value: str) -> Any:
    path = Path(value)
    if path.is_file():
        return json.loads(path.read_text(encoding="utf-8"))
    return json.loads(value)


def _gradle_root_from_wrapper(command: list[str], fallback_root: Path) -> Path:
    """Resolve the cwd from a Gradle wrapper path, preserving the supplied root.

    A command may contain an absolute or relative gradlew path. If that path
    exists, its parent is the Gradle root. If the path is not directly found,
    search likely nested Android project roots, especially CyanBridge.
    """
    if command and command[0].endswith(("gradlew", "gradlew.bat")):
        wrapper = Path(command[0])
        if not wrapper.is_absolute():
            wrapper = fallback_root / wrapper
        wrapper = wrapper.resolve()
        if wrapper.is_file():
            return wrapper.parent

    preferred = [
        fallback_root / "android" / "CyanBridge",
        fallback_root / "Alternative-HeyCyan-App-and-SDK-2.3.0" / "android" / "CyanBridge",
        fallback_root / "CyanBridge",
    ]
    for candidate in preferred:
        if candidate.is_dir() and any(
            (candidate / name).is_file()
            for name in ("settings.gradle", "settings.gradle.kts")
        ):
            return candidate.resolve()

    # Generic fallback: search shallowly for the Gradle settings file and
    # choose the deepest candidate containing an app module.
    settings_roots: list[Path] = []
    for settings_name in ("settings.gradle", "settings.gradle.kts"):
        try:
            settings_roots.extend(fallback_root.rglob(settings_name))
        except OSError:
            pass
    settings_roots = [
        path.parent for path in settings_roots
        if not any(part in {"build", ".gradle", ".git", "node_modules"}
                   for part in path.parts)
    ]
    app_roots = [
        root for root in settings_roots
        if (root / "app").is_dir()
        and any((root / "app" / name).is_file()
                for name in ("build.gradle", "build.gradle.kts"))
    ]
    if app_roots:
        return max(app_roots, key=lambda path: len(path.parts)).resolve()
    if settings_roots:
        return settings_roots[0].resolve()
    return fallback_root.resolve()


def main() -> int:
    parser = argparse.ArgumentParser(description="AndroidForge build project")
    parser.add_argument("--root", required=True, help="Project root path")
    parser.add_argument("--detect", required=True, help="Detection JSON (string or path)")
    parser.add_argument("--toolchain", required=True, help="Toolchain JSON (string or path)")
    parser.add_argument("--variant", default="auto",
                        choices=["auto", "debug", "release", "bundle"])
    parser.add_argument("--log-dir", default=None, help="Directory for build logs")
    parser.add_argument("--output", default=None, help="Write JSON summary to this file")
    args = parser.parse_args()

    detect = _load_json_argument(args.detect)
    toolchain = _load_json_argument(args.toolchain)

    root = Path(args.root).resolve()
    project_root_value = detect.get("project_root")
    if project_root_value:
        detected_root = Path(project_root_value)
        if not detected_root.is_absolute():
            detected_root = root / detected_root
        if detected_root.is_dir():
            root = detected_root.resolve()

    log_dir = Path(args.log_dir).resolve() if args.log_dir else root.parent / "androidforge-logs"
    log_dir.mkdir(parents=True, exist_ok=True)

    project_type = detect.get("project_type", "unknown")
    needs_flutter = toolchain.get("needs_flutter", False)
    use_wrapper = toolchain.get("use_wrapper", False)

    all_commands = toolchain.get("build_commands", []) or []
    prep_commands = toolchain.get("prep_commands", []) or []
    if args.variant != "auto":
        filtered = []
        for command in all_commands:
            command_text = " ".join(command).lower()
            if args.variant == "debug" and "debug" in command_text:
                filtered.append(command)
            elif args.variant == "release" and "release" in command_text:
                filtered.append(command)
            elif args.variant == "bundle" and "bundle" in command_text:
                filtered.append(command)
        if filtered:
            all_commands = filtered

    # For nested Gradle projects, correct the working directory and wrapper
    # command together. The wrapper is run from its own parent directory.
    def normalize_command(command: list[str]) -> tuple[list[str], Path]:
        command = list(command)
        cwd = root
        if command and command[0].endswith(("gradlew", "gradlew.bat")):
            wrapper = Path(command[0])
            if not wrapper.is_absolute():
                wrapper = root / wrapper
            wrapper = wrapper.resolve()
            if wrapper.is_file():
                cwd = wrapper.parent
                command[0] = str(wrapper)
        return command, cwd

    normalized_build_commands: list[list[str]] = []
    for command in all_commands:
        normalized, cwd = normalize_command(command)
        if use_wrapper and normalized and normalized[0].endswith("gradlew"):
            try:
                Path(normalized[0]).chmod(Path(normalized[0]).stat().st_mode | 0o111)
            except OSError:
                pass
        normalized_build_commands.append(normalized)
    all_commands = normalized_build_commands

    if use_wrapper and project_type != "flutter":
        wrapper_path = root / "gradlew"
        if not wrapper_path.is_file():
            nested = _gradle_root_from_wrapper([], root)
            wrapper_path = nested / "gradlew"
            if wrapper_path.is_file():
                root = nested
            else:
                print(f"WARNING: Gradle wrapper not found at {wrapper_path}", flush=True)
                maybe_regenerate_wrapper(root, toolchain.get("gradle_version"), log_dir)

    build_env: dict[str, str] = {}
    if needs_flutter:
        pass

    if prep_commands:
        print(f"\n=== Running {len(prep_commands)} prep command(s) ===", flush=True)
    for command in prep_commands:
        normalized, cwd = normalize_command(command)
        if use_wrapper and normalized and normalized[0].endswith("gradlew"):
            wrapper_path = Path(normalized[0])
            try:
                wrapper_path.chmod(wrapper_path.stat().st_mode | 0o111)
            except OSError:
                pass
            normalized = ["/bin/sh", str(wrapper_path)] + normalized[1:]
        rc, _ = run(
            normalized, cwd,
            log_dir / f"prep-{int(time.time())}-{len(normalized)}.log",
            build_env,
        )
        if rc != 0:
            print(f"WARNING: prep command failed (rc={rc}): {' '.join(normalized)}", flush=True)
        else:
            print(f"Prep command succeeded: {' '.join(normalized)}", flush=True)

    success = False
    last_rc = 1
    last_log = ""
    successful_command: list[str] | None = None
    attempted_commands: list[list[str]] = []

    for command in all_commands:
        normalized, cwd = normalize_command(command)
        if use_wrapper and normalized and normalized[0].endswith("gradlew"):
            wrapper_path = Path(normalized[0])
            try:
                wrapper_path.chmod(wrapper_path.stat().st_mode | 0o111)
            except OSError:
                pass
            normalized = ["/bin/sh", str(wrapper_path)] + normalized[1:]
        attempted_commands.append(normalized)
        rc, log = run(
            normalized, cwd,
            log_dir / f"build-{int(time.time())}-{len(normalized)}.log",
            build_env,
        )
        last_rc, last_log = rc, log
        if rc == 0:
            success = True
            successful_command = normalized
            break

    result = {
        "project_root": str(root),
        "project_type": project_type,
        "build_succeeded": success,
        "successful_command": successful_command,
        "exit_code": last_rc,
        "log_file": last_log,
        "prep_commands_run": prep_commands,
        "build_commands_attempted": attempted_commands,
    }
    output = json.dumps(result, indent=2)
    if args.output:
        Path(args.output).write_text(output, encoding="utf-8")
    else:
        print(output)

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as handle:
            handle.write(f"build_succeeded={'true' if success else 'false'}\n")
            handle.write(f"exit_code={last_rc}\n")
            handle.write(f"log_file={last_log}\n")
            if successful_command:
                handle.write(f"successful_command={' '.join(successful_command)}\n")
            handle.write(f"json<<EOF\n{json.dumps(result)}\nEOF\n")

    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
