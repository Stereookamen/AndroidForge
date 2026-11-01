#!/usr/bin/env python3
"""AndroidForge — Acquire Source ZIP.

Resolves the source Android project ZIP from one of three GitHub-native
sources and writes its local path to $GITHUB_OUTPUT.

Sources:
  1. release-asset — download a release asset from this repo.
     Requires --tag to identify the GitHub Release.
  2. download-url — fetch the ZIP from a public URL.
     Requires --url.
  3. input-directory — use a ZIP committed under input/.
     When --path is omitted, choose the newest AndroidForge web upload
     by the timestamp in its filename, not by filesystem iteration order.

For release-asset downloads, we use the GitHub REST API and the GITHUB_TOKEN
that the runner automatically provides (no PAT required, since the asset
lives in the same repository). The token is never printed or logged.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
INPUT_DIR = REPO_ROOT / "input"

# Example: web-build-1793544430997-Stereoo_VPN_AndroidForge_AutoLogin_v2.zip
WEB_UPLOAD_ZIP = re.compile(r"web-build-(\d{10,17})-.+\.zip", re.IGNORECASE)


def find_input_zip() -> Path | None:
    """Find the most recent Pages upload, never an arbitrary old ZIP."""
    if not INPUT_DIR.exists():
        return None

    files = sorted(
        (p for p in INPUT_DIR.iterdir()
         if p.is_file() and p.suffix.lower() == ".zip"),
        key=lambda p: p.name,
    )
    if not files:
        return None
    if len(files) == 1:
        return files[0]

    uploads = []
    for file in files:
        match = WEB_UPLOAD_ZIP.fullmatch(file.name)
        if match:
            uploads.append((int(match.group(1)), file.name, file))

    if uploads:
        return max(uploads)[2]

    raise ValueError(
        "Several ZIP files found in input/, but none has an AndroidForge "
        "upload timestamp. Set zip_path to the exact input/<filename>.zip."
    )


def download(url: str, dest: Path) -> None:
    print(f"Downloading from URL: {url}", flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "AndroidForge-Action"})
    with urllib.request.urlopen(req, timeout=600) as resp, dest.open("wb") as f:
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            f.write(chunk)
    print(f"Downloaded {dest.stat().st_size} bytes → {dest}", flush=True)


def get_release_asset_url(repo: str, tag: str, token: str | None) -> tuple[str, str]:
    """Use GitHub API to find the first .zip asset in a release."""
    api_url = f"https://api.github.com/repos/{repo}/releases/tags/{tag}"
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "AndroidForge-Action",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(api_url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError(
            f"GitHub API error {e.code} fetching release {tag}: {e.reason}"
        ) from e

    assets = data.get("assets", []) or []
    for asset in assets:
        name = asset.get("name", "")
        if name.lower().endswith(".zip"):
            return name, asset.get("browser_download_url") or asset.get("url")
    raise RuntimeError(f"No .zip asset found in release {tag}")


def main() -> int:
    parser = argparse.ArgumentParser(description="AndroidForge ZIP acquisition")
    parser.add_argument(
        "--source", default="input-directory",
        choices=["input-directory", "download-url", "release-asset"],
    )
    parser.add_argument("--url", default=None, help="Direct download URL")
    parser.add_argument("--tag", default=None, help="GitHub Release tag")
    parser.add_argument(
        "--path", default=None,
        help="Exact ZIP path (relative to repo root) in input/. When omitted, "
             "the newest AndroidForge web upload is selected.",
    )
    parser.add_argument("--dest-dir", default=None, help="Where to place the ZIP (default: temp)")
    args = parser.parse_args()

    dest_dir = Path(args.dest_dir) if args.dest_dir else (REPO_ROOT / ".androidforge" / "downloads")
    dest_dir.mkdir(parents=True, exist_ok=True)

    zip_path: Path | None = None

    if args.source == "input-directory":
        if args.path:
            candidate = Path(args.path)
            if not candidate.is_absolute():
                candidate = REPO_ROOT / candidate
            if not candidate.resolve().is_relative_to(INPUT_DIR.resolve()):
                print("ERROR: --path must refer to a ZIP under input/", file=sys.stderr)
                return 1
            if not candidate.is_file():
                print(f"ERROR: --path not found or not a file: {candidate}", file=sys.stderr)
                return 1
            if candidate.suffix.lower() != ".zip":
                print(f"ERROR: --path does not end in .zip: {candidate}", file=sys.stderr)
                return 1
            zip_path = candidate
            print(f"Using explicit ZIP path: {zip_path}", flush=True)
        else:
            try:
                zip_path = find_input_zip()
            except ValueError as e:
                print(f"ERROR: {e}", file=sys.stderr)
                return 1
            if not zip_path:
                print(
                    "ERROR: no .zip file found under input/. Commit your ZIP to input/ "
                    "or use download-url / release-asset or pass --path explicitly.",
                    file=sys.stderr,
                )
                return 1
            print(f"Auto-selected source ZIP: {zip_path.name}", flush=True)

        target = dest_dir / zip_path.name
        if zip_path.resolve() != target.resolve():
            shutil.copy2(zip_path, target)
            zip_path = target
        print(f"Using ZIP: {zip_path}", flush=True)

    elif args.source == "download-url":
        if not args.url:
            print("ERROR: --url is required when source=download-url", file=sys.stderr)
            return 1
        name = args.url.rstrip("/").split("/")[-1] or "source.zip"
        if not name.lower().endswith(".zip"):
            name = "source.zip"
        zip_path = dest_dir / name
        download(args.url, zip_path)

    elif args.source == "release-asset":
        if not args.tag:
            print("ERROR: --tag is required when source=release-asset", file=sys.stderr)
            return 1
        repo = os.environ.get("GITHUB_REPOSITORY", "")
        if not repo:
            print("ERROR: GITHUB_REPOSITORY env var not set (must run inside GitHub Actions)", file=sys.stderr)
            return 1
        token = os.environ.get("GITHUB_TOKEN")
        try:
            name, asset_url = get_release_asset_url(repo, args.tag, token)
        except Exception as e:
            print(f"ERROR: {e}", file=sys.stderr)
            return 1
        if not name.lower().endswith(".zip"):
            name = "source.zip"
        zip_path = dest_dir / name
        download(asset_url, zip_path)

    if not zip_path or not zip_path.is_file():
        print("ERROR: ZIP acquisition failed", file=sys.stderr)
        return 1
    if not zipfile.is_zipfile(zip_path):
        print(f"ERROR: selected file is not a valid ZIP: {zip_path.name}", file=sys.stderr)
        return 1

    print(json.dumps({
        "source": args.source,
        "zip_path": str(zip_path),
        "size_bytes": zip_path.stat().st_size,
    }, indent=2))

    gh_output = os.environ.get("GITHUB_OUTPUT")
    if gh_output:
        with open(gh_output, "a", encoding="utf-8") as f:
            f.write(f"zip_path={zip_path}\n")
            f.write(f"zip_name={zip_path.name}\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
