#!/usr/bin/env python3
"""AndroidForge — Universal Android Project Detection.

Detects Gradle/Flutter project roots in an extracted source ZIP and emits
metadata as JSON plus GitHub Actions outputs.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Any


INDICATOR_FILES = {
    "settings.gradle": "gradle",
    "settings.gradle.kts": "gradle",
    "build.gradle": "gradle",
    "build.gradle.kts": "gradle",
    "gradlew": "gradle-wrapper",
    "gradlew.bat": "gradle-wrapper",
    "AndroidManifest.xml": "android",
    "pubspec.yaml": "flutter",
    "local.properties": "android-local",
    "gradle.properties": "gradle-properties",
    "CMakeLists.txt": "native-cmake",
    "Android.mk": "native-ndk",
    "Application.mk": "native-ndk",
    "gradle/libs.versions.toml": "version-catalog",
}

AGP_PATTERNS = [
    r'id\s*\(\s*["\']com\.android\.application["\']\s*\)\s*version\s*["\']([^"\']+)["\']',
    r'id\s+["\']com\.android\.application["\'].*?version\s*["\']([^"\']+)["\']',
    r"classpath\s+['\"]com\.android\.tools\.build:gradle:([^'\"]+)['\"]",
    r"com\.android\.tools\.build:gradle:([^'\"\s]+)",
    r"androidGradlePlugin\s*=\s*['\"]([^'\"]+)['\"]",
    r"agp\s*=\s*['\"]([^'\"]+)['\"]",
    r"com-android-application\s*=\s*\{[^}]*version\s*=\s*['\"]([^'\"]+)['\"]",
]
KOTLIN_PATTERNS = [
    r"kotlinVersion\s*=\s*['\"]([^'\"]+)['\"]",
    r"kotlin_version\s*=\s*['\"]([^'\"]+)['\"]",
    r"\bkotlin\s*=\s*['\"]([^'\"]+)['\"]",
    r"org\.jetbrains\.kotlin\.android\s*=\s*\{[^}]*version\s*=\s*['\"]([^'\"]+)['\"]",
    r"org\.jetbrains\.kotlin:kotlin-gradle-plugin:([0-9][^'\"\s]*)",
    r"org\.jetbrains\.kotlin\.plugin:[^:]+:([0-9][^'\"\s]*)",
]
JAVA_PATTERNS = [
    r"sourceCompatibility\s*=?\s*(?:JavaVersion\.VERSION_)?(\w+)",
    r"targetCompatibility\s*=?\s*(?:JavaVersion\.VERSION_)?(\w+)",
    r"jvmTarget\s*=?\s*['\"](\d+)['\"]",
    r"JavaVersion\.VERSION_(\w+)",
]
NDK_PATTERNS = [r"ndkVersion\s*=?\s*['\"]([^'\"]+)['\"]"]
SDK_PATTERNS = [
    (r"compileSdk\s*(?:=|:|\s)\s*(\d+)", "compile_sdk"),
    (r"compileSdkVersion\s+(\d+)", "compile_sdk"),
    (r"targetSdk\s*(?:=|:|\s)\s*(\d+)", "target_sdk"),
    (r"targetSdkVersion\s+(\d+)", "target_sdk"),
    (r"minSdk\s*(?:=|:|\s)\s*(\d+)", "min_sdk"),
    (r"minSdkVersion\s+(\d+)", "min_sdk"),
]


def read_file(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except Exception:
        return ""


def extract_version(content: str, patterns: list[str]) -> str | None:
    for pattern in patterns:
        match = re.search(pattern, content, re.MULTILINE | re.IGNORECASE)
        if match and match.group(1).strip():
            return match.group(1).strip()
    return None


def normalize_java_version(raw: str | None) -> str | None:
    if not raw:
        return None
    raw = str(raw).strip()
    if raw.isdigit():
        return raw
    if raw.startswith("1_") and raw[2:].isdigit():
        return raw.split("_", 1)[1]
    if "VERSION_" in raw:
        parts = raw.split("_")
        if len(parts) >= 3 and parts[1] == "1":
            return parts[2]
        if len(parts) >= 2:
            return parts[-1]
    return raw


def is_gradle_root(path: Path) -> bool:
    return any((path / name).is_file() for name in (
        "settings.gradle", "settings.gradle.kts"
    ))


def find_project_root(extract_dir: Path) -> Path:
    """Find the real build root, preferring CyanBridge in multi-project ZIPs."""
    extract_dir = extract_dir.resolve()

    # Handle common ZIP layouts explicitly. AndroidForge extracts a ZIP into
    # a directory which may itself contain the archive's top-level folder.
    preferred_suffixes = (
        Path("android/CyanBridge"),
        Path("Alternative-HeyCyan-App-and-SDK-2.3.0/android/CyanBridge"),
        Path("CyanBridge"),
    )
    for base in (extract_dir, *[p for p in extract_dir.iterdir() if p.is_dir()]):
        for suffix in preferred_suffixes:
            candidate = base / suffix
            if candidate.is_dir() and is_gradle_root(candidate):
                return candidate

    candidates: list[Path] = []

    def scan(directory: Path, depth: int = 0) -> None:
        if depth > 6 or not directory.is_dir():
            return
        if is_gradle_root(directory) or (directory / "pubspec.yaml").is_file():
            candidates.append(directory)
            return
        try:
            children = list(directory.iterdir())
        except OSError:
            return
        for child in children:
            if not child.is_dir():
                continue
            if child.name.startswith(".") or child.name in {
                "build", "gradle", ".gradle", "__MACOSX",
                "node_modules", ".idea", ".git"
            }:
                continue
            scan(child, depth + 1)

    scan(extract_dir)
    if not candidates:
        return extract_dir

    # Prefer Gradle roots containing an Android app module.
    app_candidates = [
        root for root in candidates
        if (root / "app").is_dir()
        and any((root / "app" / name).is_file()
                for name in ("build.gradle", "build.gradle.kts"))
    ]
    if app_candidates:
        # Prefer the most specific candidate if there are nested builds.
        return max(app_candidates, key=lambda path: len(path.parts))

    gradle_candidates = [root for root in candidates if is_gradle_root(root)]
    if gradle_candidates:
        return gradle_candidates[0]
    return candidates[0]


def parse_gradle_wrapper(root: Path) -> dict[str, Any]:
    result: dict[str, Any] = {
        "present": False,
        "version": None,
        "distribution_url": None,
        "distribution_sha256": None,
        "uses_http": False,
    }
    props = root / "gradle" / "wrapper" / "gradle-wrapper.properties"
    wrapper_jar = root / "gradle" / "wrapper" / "gradle-wrapper.jar"
    if (root / "gradlew").exists() or (root / "gradlew.bat").exists() or props.exists():
        result["present"] = True
    if props.exists():
        content = read_file(props)
        match = re.search(r"distributionUrl=([^\s]+)", content)
        if match:
            url = match.group(1).strip()
            url = url.replace(r"https\://services.gradle.org/distributions",
                              "https://services.gradle.org/distributions")
            url = url.replace("$BASEURL", "https://services.gradle.org/distributions")
            result["distribution_url"] = url
            result["uses_http"] = url.startswith("http://")
            version_match = re.search(r"gradle-([\d.]+)-\w+\.zip", url)
            if version_match:
                result["version"] = version_match.group(1)
        sha = re.search(r"distributionSha256Sum=([a-fA-F0-9]+)", content)
        if sha:
            result["distribution_sha256"] = sha.group(1)
    if not wrapper_jar.exists() and result["present"]:
        result["present"] = False
        result["missing_jar"] = True
    return result


def find_modules(root: Path) -> list[str]:
    modules: list[str] = []
    settings = next((root / name for name in ("settings.gradle", "settings.gradle.kts")
                     if (root / name).is_file()), None)
    if settings:
        content = read_file(settings)
        for block in re.findall(r"include\s*([^;\n]+)", content, re.IGNORECASE):
            for module in re.findall(r"['\"]([^'\"]+)['\"]", block):
                module = module.lstrip(":")
                if module and module not in modules:
                    modules.append(module)
    try:
        children = list(root.iterdir())
    except OSError:
        children = []
    for child in children:
        if child.is_dir() and child.name not in {
            "build", "gradle", ".gradle", ".idea", "node_modules"
        } and any((child / name).is_file() for name in ("build.gradle", "build.gradle.kts")):
            if child.name not in modules:
                modules.append(child.name)
    return modules


def detect_native(root: Path) -> dict[str, Any]:
    result = {"has_native": False, "build_system": None,
              "has_cmake": False, "has_ndk_build": False, "cpp_files": 0}
    scan_roots = [root]
    try:
        scan_roots += [p for p in root.iterdir() if p.is_dir()
                       and p.name not in {"build", ".git", ".gradle"}]
    except OSError:
        pass
    for sub in scan_roots:
        if (sub / "CMakeLists.txt").exists():
            result.update(has_native=True, build_system="cmake", has_cmake=True)
            break
        if (sub / "Android.mk").exists():
            result.update(has_native=True, build_system="ndk-build", has_ndk_build=True)
            break
    try:
        result["cpp_files"] = sum(
            1 for pattern in ("*.cpp", "*.c", "*.cc", "*.cxx")
            for path in root.rglob(pattern)
            if not any(part in {"build", ".git", ".gradle"} for part in path.parts)
        )
        if result["cpp_files"] and not result["has_native"]:
            result.update(has_native=True, build_system="cmake", has_cmake=True)
    except OSError:
        pass
    return result


def detect_flutter(root: Path) -> dict[str, Any]:
    result = {"is_flutter": False, "flutter_version_constraint": None,
              "has_android_dir": False}
    pubspec = root / "pubspec.yaml"
    if pubspec.exists():
        result["is_flutter"] = True
        content = read_file(pubspec)
        match = re.search(r"environment:[\s\S]*?flutter:\s*[\"']?([^\"'\n]+)", content)
        if match:
            result["flutter_version_constraint"] = match.group(1).strip()
        result["has_android_dir"] = (root / "android").is_dir()
    return result


def scan_build_files(root: Path) -> dict[str, Any]:
    info: dict[str, Any] = {
        "agp_version": None, "kotlin_version": None, "java_version": None,
        "ndk_version": None, "compile_sdk": None, "target_sdk": None, "min_sdk": None,
    }
    files: list[Path] = []
    for name in ("build.gradle", "build.gradle.kts"):
        if (root / name).is_file():
            files.append(root / name)
    try:
        for sub in root.iterdir():
            if sub.is_dir() and sub.name not in {
                "build", "gradle", ".gradle", ".idea", "node_modules"
            }:
                for name in ("build.gradle", "build.gradle.kts"):
                    path = sub / name
                    if path.is_file():
                        files.append(path)
    except OSError:
        pass
    all_content = "\n".join(read_file(path) for path in files)
    catalog = root / "gradle" / "libs.versions.toml"
    catalog_content = read_file(catalog) if catalog.exists() else ""
    combined = all_content + "\n" + catalog_content
    info["agp_version"] = (extract_version(catalog_content, AGP_PATTERNS)
                           or extract_version(all_content, AGP_PATTERNS))
    info["kotlin_version"] = (extract_version(catalog_content, KOTLIN_PATTERNS)
                              or extract_version(all_content, KOTLIN_PATTERNS))
    info["ndk_version"] = extract_version(combined, NDK_PATTERNS)
    info["java_version"] = normalize_java_version(extract_version(combined, JAVA_PATTERNS))
    for pattern, key in SDK_PATTERNS:
        match = re.search(pattern, combined, re.MULTILINE)
        if match and not info[key]:
            info[key] = match.group(1)
    props = root / "gradle.properties"
    if props.exists():
        content = read_file(props)
        if not info["kotlin_version"]:
            match = re.search(r"kotlinVersion\s*=\s*['\"]?([^'\"\n]+)", content)
            if match:
                info["kotlin_version"] = match.group(1).strip()
        if not info["agp_version"]:
            match = re.search(r"androidGradlePlugin\s*=\s*['\"]?([^'\"\n]+)", content)
            if match:
                info["agp_version"] = match.group(1).strip()
    return info


def detect_kotlin(root: Path, gradle_content: str) -> bool:
    if re.search(r"kotlin[_-]android|kotlin-android|org\.jetbrains\.kotlin",
                 gradle_content, re.IGNORECASE):
        return True
    try:
        return next(root.rglob("*.kt"), None) is not None
    except OSError:
        return False


def assess_legacy(result: dict[str, Any]) -> tuple[bool, list[str]]:
    legacy = False
    reasons: list[str] = []
    versions = result.get("versions", {}) or {}
    wrapper = result.get("wrapper", {}) or {}
    for key, label in (("version", "Gradle wrapper"),):
        value = wrapper.get(key)
        if value:
            try:
                if int(str(value).split(".")[0]) < 7:
                    legacy = True
                    reasons.append(f"Old {label}: {value}")
            except (ValueError, IndexError):
                pass
    agp = versions.get("agp_version")
    if agp:
        try:
            if int(str(agp).split(".")[0]) < 7:
                legacy = True
                reasons.append(f"Old Android Gradle Plugin: {agp}")
        except (ValueError, IndexError):
            pass
    java = versions.get("java_version")
    if java:
        try:
            if int(str(java)) <= 11:
                legacy = True
                reasons.append(f"Targets Java {java}")
        except ValueError:
            pass
    sdk = versions.get("compile_sdk")
    if sdk:
        try:
            if int(sdk) < 30:
                legacy = True
                reasons.append(f"Low compileSdk: {sdk}")
        except (ValueError, TypeError):
            pass
    if not wrapper.get("present"):
        legacy = True
        reasons.append("No Gradle wrapper present")
    return legacy, reasons


def main() -> int:
    parser = argparse.ArgumentParser(description="AndroidForge project detection")
    parser.add_argument("--root", required=True, help="Path to extracted project root")
    parser.add_argument("--output", default=None, help="Optional output JSON file")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    if not root.exists():
        print(f"ERROR: root path does not exist: {root}", file=sys.stderr)
        return 1

    project_root = find_project_root(root)
    found_files: dict[str, str] = {}
    try:
        children = [p for p in project_root.iterdir() if p.is_dir()
                    and p.name not in {"build", "gradle", ".gradle", ".idea", "node_modules"}]
    except OSError:
        children = []
    for rel in INDICATOR_FILES:
        possibilities = [project_root / rel] + [child / rel for child in children]
        for path in possibilities:
            if path.exists():
                found_files[rel] = str(path.relative_to(project_root))
                break

    if (project_root / "pubspec.yaml").exists():
        project_type, sub_type = "flutter", "flutter"
    elif is_gradle_root(project_root) or any(
        (project_root / name).exists() for name in ("build.gradle", "build.gradle.kts")
    ):
        project_type = "gradle"
        gradle_files = [project_root / name for name in ("build.gradle", "build.gradle.kts")
                        if (project_root / name).exists()]
        gradle_files += [child / name for child in children
                         for name in ("build.gradle", "build.gradle.kts")
                         if (child / name).exists()]
        content = "\n".join(read_file(path) for path in gradle_files)
        sub_type = "kotlin" if detect_kotlin(project_root, content) else "java"
    else:
        project_type, sub_type = "unknown", None

    wrapper = parse_gradle_wrapper(project_root)
    modules = find_modules(project_root)
    native = detect_native(project_root)
    flutter = detect_flutter(project_root)
    versions = scan_build_files(project_root)
    structure = "multi-module" if len(modules) > 1 else (
        "single-module" if modules or project_type == "gradle" else "unknown"
    )

    result: dict[str, Any] = {
        "project_root": str(project_root),
        "project_type": project_type,
        "sub_type": sub_type,
        "structure": structure,
        "indicator_files": found_files,
        "wrapper": wrapper,
        "modules": modules,
        "native": native,
        "flutter": flutter,
        "versions": versions,
        "is_legacy": False,
        "legacy_reasons": [],
    }
    legacy, reasons = assess_legacy(result)
    result["is_legacy"], result["legacy_reasons"] = legacy, reasons
    output = json.dumps(result, indent=2)
    if args.output:
        Path(args.output).write_text(output, encoding="utf-8")
    else:
        print(output)

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as handle:
            handle.write(f"project_root={project_root}\n")
            handle.write(f"project_type={project_type}\n")
            handle.write(f"sub_type={sub_type or ''}\n")
            handle.write(f"structure={structure}\n")
            handle.write(f"is_legacy={'true' if legacy else 'false'}\n")
            handle.write(f"needs_gradle={'true' if project_type in ('gradle', 'flutter') else 'false'}\n")
            handle.write(f"needs_flutter={'true' if flutter['is_flutter'] else 'false'}\n")
            handle.write(f"has_native={'true' if native['has_native'] else 'false'}\n")
            handle.write(f"wrapper_present={'true' if wrapper.get('present') else 'false'}\n")
            for output_key, value in (
                ("gradle_version", wrapper.get("version")),
                ("agp_version", versions.get("agp_version")),
                ("kotlin_version", versions.get("kotlin_version")),
                ("java_version", versions.get("java_version")),
                ("ndk_version", versions.get("ndk_version")),
                ("compile_sdk", versions.get("compile_sdk")),
            ):
                if value:
                    handle.write(f"{output_key}={value}\n")
            handle.write(f"json<<EOF\n{json.dumps(result)}\nEOF\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
