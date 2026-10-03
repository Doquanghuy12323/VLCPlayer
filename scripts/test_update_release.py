import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from create_update_manifest import create_manifest
from publish_update_release import publish_release, validate_assets


class UpdateReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "app-release.apk"
        self.apk.write_bytes(b"signed APK fixture\x00\xff")
        self.badging = (
            "package: name='com.vlcplayer.app' versionCode='1790784054' "
            "versionName='2026.10.03.1234' platformBuildVersionName='14'\n"
            "sdkVersion:'21'\ntargetSdkVersion:'34'\n")

    def manifest(self, badging=None):
        return create_manifest(self.apk, self.badging if badging is None else badging,
                               1790784054, "2026.10.03.1234", "Cải thiện cập nhật")

    def test_manifest_uses_actual_apk_bytes_and_compatible_urls(self):
        manifest = self.manifest()
        self.assertEqual(manifest["sizeBytes"], len(self.apk.read_bytes()))
        self.assertEqual(manifest["sha256"], hashlib.sha256(self.apk.read_bytes()).hexdigest())
        self.assertEqual(manifest["minSdk"], 21)
        self.assertEqual(manifest["changelog"], "Cải thiện cập nhật")
        self.assertEqual(manifest["apkUrl"],
                         "https://github.com/Doquanghuy12323/VLCPlayer/releases/download/"
                         "v1790784054/app-release.apk")

    def test_rejects_wrong_package_version_name_code_or_sdk(self):
        for old, new in (("com.vlcplayer.app", "com.other.app"),
                         ("1790784054", "1790784055"),
                         ("2026.10.03.1234", "1.0"), ("sdkVersion:'21'", "sdkVersion:'23'")):
            with self.subTest(value=new), self.assertRaises(ValueError):
                self.manifest(self.badging.replace(old, new))

    def test_rejects_missing_and_non_numeric_metadata(self):
        for badging in ("", self.badging.replace("1790784054", "tomorrow"),
                        self.badging.replace("sdkVersion:'21'\n", "")):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                self.manifest(badging)

    def test_rejects_empty_apk(self):
        self.apk.write_bytes(b"")
        with self.assertRaises(ValueError):
            self.manifest()

    def test_rejects_assets_and_notes_beyond_android_reader_limits(self):
        with patch("create_update_manifest.MAX_APK_BYTES", 1):
            with self.assertRaisesRegex(ValueError, "size limit"):
                self.manifest()
        long_name = "v" * 129
        badging = self.badging.replace("2026.10.03.1234", long_name)
        with self.assertRaisesRegex(ValueError, "text limits"):
            create_manifest(self.apk, badging, 1790784054, long_name, "Notes")
        with self.assertRaisesRegex(ValueError, "text limits"):
            create_manifest(self.apk, self.badging, 1790784054, "2026.10.03.1234", "n" * 65537)
        # The character count is valid, but UTF-8 bytes exceed the network reader bound.
        with self.assertRaisesRegex(ValueError, "download limit"):
            create_manifest(self.apk, self.badging, 1790784054, "2026.10.03.1234", "🚀" * 40000)

    def release(self):
        manifest = self.manifest()
        pending = "untagged-c14f035e559ff4e5b72b"
        prefix = "https://github.com/Doquanghuy12323/VLCPlayer/releases/"
        return {"draft": True, "tag_name": "v1790784054",
                "html_url": prefix + "tag/" + pending, "assets": [
            {"name": "app-release.apk", "state": "uploaded", "size": manifest["sizeBytes"],
             "browser_download_url": prefix + "download/" + pending + "/app-release.apk"},
            {"name": "update.json", "state": "uploaded", "size": 1000,
             "browser_download_url": prefix + "download/" + pending + "/update.json"}]}

    def published_release(self):
        release = self.release()
        release.update(draft=False, published_at="2026-10-03T12:00:00Z",
                       html_url=self.manifest()["releaseUrl"])
        for asset in release["assets"]:
            asset["browser_download_url"] = (
                "https://github.com/Doquanghuy12323/VLCPlayer/releases/download/"
                "v1790784054/" + asset["name"])
        return release

    def test_accepts_complete_draft_only(self):
        validate_assets(self.release(), self.manifest())
        for alteration in (lambda release: release.update(draft=False),
                           lambda release: release["assets"].pop(),
                           lambda release: release["assets"][0].update(size=1),
                           lambda release: release["assets"][0].update(state="new"),
                           lambda release: release["assets"][0].update(browser_download_url="bad")):
            release = self.release()
            alteration(release)
            with self.assertRaises(ValueError):
                validate_assets(release, self.manifest())

    def test_published_release_requires_canonical_urls_and_uploaded_assets(self):
        validate_assets(self.published_release(), self.manifest(), published=True)
        for alteration in (lambda release: release.update(html_url=self.release()["html_url"]),
                           lambda release: release["assets"][0].update(
                               browser_download_url=self.release()["assets"][0]["browser_download_url"]),
                           lambda release: release["assets"][1].update(
                               browser_download_url=self.release()["assets"][1]["browser_download_url"]),
                           lambda release: release["assets"][1].update(state="new")):
            release = self.published_release()
            alteration(release)
            with self.assertRaises(ValueError):
                validate_assets(release, self.manifest(), published=True)

    def test_damaged_download_keeps_release_unpublished(self):
        manifest_path = Path(self.directory.name) / "update.json"
        manifest_path.write_text(json.dumps(self.manifest()), encoding="utf-8")
        notes_path = Path(self.directory.name) / "notes.md"
        notes_path.write_text("Release notes", encoding="utf-8")
        args = SimpleNamespace(apk=self.apk, manifest=manifest_path,
                               notes_file=notes_path, commit="a" * 40)
        release = self.release()
        release["target_commitish"] = args.commit
        release["assets"][1]["size"] = manifest_path.stat().st_size
        calls = []

        def fake_gh(*arguments):
            calls.append(arguments)
            if arguments[:2] == ("release", "view"):
                return '{"apiUrl": "https://api.github.com/repos/Doquanghuy12323/VLCPlayer/releases/123"}'
            if arguments[0] == "api":
                self.assertTrue(arguments[1].endswith("/releases/123"))
                return json.dumps(release)
            if arguments[:2] == ("release", "download"):
                downloaded = Path(arguments[arguments.index("--dir") + 1])
                (downloaded / "app-release.apk").write_bytes(b"corrupted APK")
                (downloaded / "update.json").write_bytes(manifest_path.read_bytes())
            return ""

        with patch("publish_update_release.gh", side_effect=fake_gh):
            with self.assertRaisesRegex(ValueError, "Downloaded release assets"):
                publish_release(args)
        self.assertFalse(any(call[:2] == ("release", "edit") for call in calls))

    def test_temporary_draft_urls_publish_only_after_verified_downloads(self):
        manifest_path = Path(self.directory.name) / "update.json"
        manifest_path.write_text(json.dumps(self.manifest()), encoding="utf-8")
        notes_path = Path(self.directory.name) / "notes.md"
        notes_path.write_text("Release notes", encoding="utf-8")
        args = SimpleNamespace(apk=self.apk, manifest=manifest_path,
                               notes_file=notes_path, commit="a" * 40)
        draft = self.release()
        published = self.published_release()
        for release in (draft, published):
            release["target_commitish"] = args.commit
            release["assets"][1]["size"] = manifest_path.stat().st_size
        downloaded_verified = False
        is_published = False

        def fake_gh(*arguments):
            nonlocal downloaded_verified, is_published
            if arguments[:2] == ("release", "view"):
                return '{"apiUrl": "https://api.github.com/repos/Doquanghuy12323/VLCPlayer/releases/123"}'
            if arguments[0] == "api":
                self.assertTrue(arguments[1].endswith("/releases/123"))
                return json.dumps(published if is_published else draft)
            if arguments[:2] == ("release", "download"):
                downloaded = Path(arguments[arguments.index("--dir") + 1])
                (downloaded / "app-release.apk").write_bytes(self.apk.read_bytes())
                (downloaded / "update.json").write_bytes(manifest_path.read_bytes())
                downloaded_verified = True
            if arguments[:2] == ("release", "edit"):
                self.assertTrue(downloaded_verified)
                is_published = True
            return ""

        with patch("publish_update_release.gh", side_effect=fake_gh), patch("builtins.print"):
            publish_release(args)
        self.assertTrue(is_published)


if __name__ == "__main__":
    unittest.main()
