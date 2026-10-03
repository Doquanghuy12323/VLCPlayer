#!/usr/bin/env python3
"""Create updater metadata from the signed APK, never from build assumptions."""

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path


REPOSITORY = "Doquanghuy12323/VLCPlayer"
PACKAGE_NAME = "com.vlcplayer.app"
APK_NAME = "app-release.apk"
MAX_APK_BYTES = 1024 * 1024 * 1024
MAX_METADATA_BYTES = 128 * 1024


def manifest_json(manifest):
    return json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"


def apk_metadata(badging):
    package_line = next((line for line in badging.splitlines()
                         if line.startswith("package: ")), "")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    sdk = re.search(r"^sdkVersion:'(\d+)'$", badging, re.MULTILINE)
    if not all(fields.get(key) for key in ("name", "versionCode", "versionName")) or not sdk:
        raise ValueError("APK is missing its package, version or minimum Android SDK")
    try:
        version_code = int(fields["versionCode"])
        min_sdk = int(sdk.group(1))
    except ValueError as error:
        raise ValueError("APK version code and minimum SDK must be integers") from error
    if not 0 < version_code <= 2_147_483_647 or min_sdk < 1:
        raise ValueError("APK version code or minimum SDK is out of range")
    return fields["name"], version_code, fields["versionName"], min_sdk


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def create_manifest(apk, badging, expected_code, expected_name, changelog,
                    expected_package=PACKAGE_NAME, expected_min_sdk=21):
    apk = Path(apk)
    if apk.name != APK_NAME or not apk.is_file() or apk.stat().st_size <= 0:
        raise ValueError("A nonempty app-release.apk is required")
    if apk.stat().st_size > MAX_APK_BYTES:
        raise ValueError("APK exceeds the updater's size limit")
    package, code, name, min_sdk = apk_metadata(badging)
    if (package, code, name, min_sdk) != (
            expected_package, expected_code, expected_name, expected_min_sdk):
        raise ValueError("Signed APK metadata does not match the expected release")
    if not changelog.strip():
        raise ValueError("Release notes must not be empty")
    if not name.strip() or len(name) > 128 or len(changelog.strip()) > 65536:
        raise ValueError("Version name or release notes exceed the updater's text limits")
    release_url = f"https://github.com/{REPOSITORY}/releases"
    manifest = {
        "schemaVersion": 1,
        "packageName": package,
        "versionCode": code,
        "versionName": name,
        "minSdk": min_sdk,
        "apkName": APK_NAME,
        "apkUrl": f"{release_url}/download/v{code}/{APK_NAME}",
        "sizeBytes": apk.stat().st_size,
        "sha256": sha256_file(apk),
        "changelog": changelog.strip(),
        "releaseUrl": f"{release_url}/tag/v{code}",
    }
    if len(manifest_json(manifest).encode("utf-8")) > MAX_METADATA_BYTES:
        raise ValueError("Metadata exceeds the updater's download limit")
    return manifest


def release_notes(commit, supplied_notes=""):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("The release must identify an exact Git commit")
    if supplied_notes.strip():
        notes = supplied_notes.strip()
    else:
        previous = subprocess.run(
            ["git", "describe", "--tags", "--match", "v[0-9]*", "--abbrev=0", commit],
            text=True, capture_output=True, check=False)
        revisions = f"{previous.stdout.strip()}..{commit}" if previous.returncode == 0 else commit
        subjects = subprocess.run(
            ["git", "log", "--format=%s", "--max-count=12", revisions],
            text=True, capture_output=True, check=True).stdout.splitlines()
        notes = "### Thay đổi\n\n" + ("\n".join(f"- {subject}" for subject in subjects)
                 if subjects else "- Cập nhật bản phát hành và bản dựng ứng dụng.")
    return f"{notes}\n\nBuild from commit {commit}\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--notes-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    subprocess.run([args.apksigner, "verify", "--verbose", str(args.apk)],
                   text=True, capture_output=True, check=True)
    badging = subprocess.run([args.aapt, "dump", "badging", str(args.apk)],
                            text=True, capture_output=True, check=True).stdout
    notes = release_notes(args.commit, os.environ.get("RELEASE_NOTES", ""))
    manifest = create_manifest(args.apk, badging, args.version_code, args.version_name, notes)
    args.notes_file.write_text(notes, encoding="utf-8")
    args.output.write_text(manifest_json(manifest), encoding="utf-8")
    print(f"Verified {manifest['packageName']} {manifest['versionName']} "
          f"({manifest['sizeBytes']} bytes); updater metadata created")


if __name__ == "__main__":
    main()
