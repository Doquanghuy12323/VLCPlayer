#!/usr/bin/env python3
"""Publish a release only after both updater assets can be downloaded intact."""

import argparse
import json
import re
import subprocess
import tempfile
from pathlib import Path

from create_update_manifest import APK_NAME, REPOSITORY, sha256_file


def gh(*arguments):
    return subprocess.run(["gh", *arguments], text=True, capture_output=True, check=True).stdout


def validate_assets(release, manifest, published=False, metadata_size=None):
    tag = f"v{manifest['versionCode']}"
    if release.get("tag_name") != tag:
        raise ValueError("Release tag does not match the signed APK")
    if published:
        if release.get("draft") or not release.get("published_at"):
            raise ValueError("Release publication was not confirmed")
        if release.get("html_url") != manifest["releaseUrl"]:
            raise ValueError("Published release URL differs from updater metadata")
        asset_tag = tag
    else:
        if not release.get("draft"):
            raise ValueError("Asset verification requires an unpublished draft release")
        # GitHub uses a temporary tag in draft URLs until the tag is published.
        # Match that exact pending tag on the draft and both assets, in this repo.
        match = re.fullmatch(
            rf"https://github\.com/{re.escape(REPOSITORY)}/releases/tag/"
            r"(v[0-9]+|untagged-[0-9a-f]+)", release.get("html_url", ""))
        if not match or (match.group(1).startswith("v") and match.group(1) != tag):
            raise ValueError("Draft release URL does not identify this repository and release")
        asset_tag = match.group(1)
    assets = release.get("assets", [])
    if len(assets) != 2 or {asset.get("name") for asset in assets} != {APK_NAME, "update.json"}:
        raise ValueError("The release must contain exactly app-release.apk and update.json")
    for asset in assets:
        if asset.get("state") != "uploaded" or asset.get("size", 0) <= 0:
            raise ValueError("A release asset has not finished uploading")
        expected_url = (f"https://github.com/{REPOSITORY}/releases/download/"
                        f"{asset_tag}/{asset['name']}")
        if asset.get("browser_download_url") != expected_url:
            raise ValueError("Uploaded asset URL differs from its release")
        if asset["name"] == APK_NAME:
            if asset["size"] != manifest["sizeBytes"]:
                raise ValueError("Uploaded APK size differs from the signed build")
            if published and expected_url != manifest["apkUrl"]:
                raise ValueError("Published APK URL differs from updater metadata")
            if asset.get("digest") and asset["digest"] != f"sha256:{manifest['sha256']}":
                raise ValueError("Uploaded APK digest differs from the signed build")
        elif metadata_size is not None and asset["size"] != metadata_size:
            raise ValueError("Uploaded metadata size differs from the verified local file")


def publish_release(args):
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if args.apk.stat().st_size != manifest["sizeBytes"] or sha256_file(args.apk) != manifest["sha256"]:
        raise ValueError("APK changed after updater metadata was generated")
    tag = f"v{manifest['versionCode']}"
    gh("release", "create", tag, "--repo", REPOSITORY, "--draft", "--target", args.commit,
       "--title", f"VLC Player {manifest['versionName']}", "--notes-file", str(args.notes_file))
    gh("release", "upload", tag, str(args.apk), str(args.manifest), "--repo", REPOSITORY)
    # The REST tag endpoint does not resolve pending tags on draft releases.
    # gh release view resolves drafts via GraphQL; inspect its stable numeric ID.
    api_url = json.loads(gh("release", "view", tag, "--repo", REPOSITORY,
                           "--json", "apiUrl"))["apiUrl"]
    api_prefix = f"https://api.github.com/repos/{REPOSITORY}/releases/"
    if (not isinstance(api_url, str) or not api_url.startswith(api_prefix)
            or not re.fullmatch(r"[1-9][0-9]*", api_url[len(api_prefix):])):
        raise ValueError("Could not identify the new draft release")
    release_api = api_url[len("https://api.github.com/"):]
    release = json.loads(gh("api", release_api))
    validate_assets(release, manifest, metadata_size=args.manifest.stat().st_size)
    if release.get("target_commitish") != args.commit:
        raise ValueError("Draft release targets an unexpected commit")
    with tempfile.TemporaryDirectory(prefix="vlcplayer-release-") as downloaded:
        gh("release", "download", tag, "--repo", REPOSITORY,
           "--pattern", APK_NAME, "--pattern", "update.json", "--dir", downloaded)
        downloaded_apk = Path(downloaded) / APK_NAME
        downloaded_manifest = Path(downloaded) / "update.json"
        if (downloaded_apk.stat().st_size != manifest["sizeBytes"]
                or sha256_file(downloaded_apk) != manifest["sha256"]
                or downloaded_manifest.read_bytes() != args.manifest.read_bytes()):
            raise ValueError("Downloaded release assets differ from the verified local build")
    gh("release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--latest")
    published = json.loads(gh("api", release_api))
    validate_assets(published, manifest, published=True,
                    metadata_size=args.manifest.stat().st_size)
    if published.get("target_commitish") != args.commit:
        raise ValueError("Published release targets an unexpected commit")
    print(f"Published {manifest['releaseUrl']} after verifying both downloaded assets")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--notes-file", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    publish_release(parser.parse_args())


if __name__ == "__main__":
    main()
