"""scripts/android_release_manifest.py 单元测试（纯 Python 标准库 unittest）。"""

from __future__ import annotations

import contextlib
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import android_release_manifest as manifest_mod  # noqa: E402


class TestComputeVersionCode(unittest.TestCase):
    def test_valid_semver_and_boundaries(self) -> None:
        cases = [
            ("0.0.1", 1),
            ("0.6.0", 600),
            ("0.6.1", 601),
            ("v0.6.1", 601),
            ("1.2.3", 10203),
            ("v1.2.3", 10203),
            ("0.6.1-rc.1", 601),
            ("v0.6.1-beta.2", 601),
            ("0.6.1-0.alpha-1", 601),
            ("0.99.99", 9999),
            ("200000.0.0", 2_000_000_000),
            ("200000.99.99", 2_000_009_999),
        ]
        for version, expected in cases:
            with self.subTest(version=version):
                self.assertEqual(manifest_mod.compute_version_code(version), expected)

    def test_monotonic_over_historical_run_number(self) -> None:
        code_061 = manifest_mod.compute_version_code("0.6.1")
        code_062 = manifest_mod.compute_version_code("0.6.2")
        code_070 = manifest_mod.compute_version_code("0.7.0")
        code_100 = manifest_mod.compute_version_code("1.0.0")
        self.assertGreater(code_061, 3)  # 严格大于历史 v0.6.0 run_number=3
        self.assertLess(code_061, code_062)
        self.assertLess(code_062, code_070)
        self.assertLess(code_070, code_100)

    def test_out_of_range_and_zero_rejected(self) -> None:
        invalid_versions = [
            "0.0.0",
            "v0.0.0",
            "0.0.0-alpha.1",
            "200001.0.0",
            "0.100.0",
            "0.0.100",
            "1.100.1",
            "1.0.100",
        ]
        for version in invalid_versions:
            with self.subTest(version=version):
                with self.assertRaises(ValueError):
                    manifest_mod.compute_version_code(version)

    def test_malformed_versions_rejected(self) -> None:
        malformed: list[object] = [
            "",
            "   ",
            "0.6",
            "1.2.3.4",
            "V0.6.1",
            "vv0.6.1",
            "01.2.3",
            "0.06.1",
            "0.6.01",
            "0.6.1+build.1",
            "0.6.1-",
            "0.6.1-01",
            "dev-abc1234",
            " 0.6.1",
            "0.6.1 ",
            "0.6.1\n",
            "-1.0.0",
            None,
            601,
        ]
        for version in malformed:
            with self.subTest(version=version):
                with self.assertRaises(ValueError):
                    manifest_mod.compute_version_code(version)  # type: ignore[arg-type]


class TestBuildAndroidLatestManifest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp_dir = Path(self._tmp.name)
        self.apk_payload = b"PK\x03\x04fake-android-apk-content-\x00\xff" * 64
        self.apk_path = self.tmp_dir / "FlameContractor-0.6.1-android-universal-release.apk"
        self.apk_path.write_bytes(self.apk_payload)
        self.expected_sha256 = hashlib.sha256(self.apk_payload).hexdigest()
        self.expected_size = len(self.apk_payload)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_build_manifest_with_base_url_and_object_key(self) -> None:
        manifest = manifest_mod.build_android_latest_manifest(
            apk_path=self.apk_path,
            version_name="0.6.1",
            version_code=601,
            base_url="https://dl.20190415.cloud/",
            apk_object_key="/android/releases/FlameContractor-0.6.1-android-universal-release.apk",
            release_page_url="https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1",
            changelog="  修复续火通知\r\n新增自动检查更新  \r\n",
            min_version_code=0,
            published_at="2026-11-26T00:00:00Z",
        )

        self.assertEqual(
            manifest,
            {
                "schemaVersion": 1,
                "platform": "android",
                "versionName": "0.6.1",
                "versionCode": 601,
                "minVersionCode": 0,
                "publishedAt": "2026-11-26T00:00:00Z",
                "fileSize": self.expected_size,
                "sha256": self.expected_sha256,
                "downloadUrl": "https://dl.20190415.cloud/android/releases/FlameContractor-0.6.1-android-universal-release.apk",
                "releasePageUrl": "https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1",
                "changelog": "修复续火通知\n新增自动检查更新",
            },
        )
        self.assertEqual(len(str(manifest["sha256"])), 64)
        self.assertEqual(str(manifest["sha256"]), str(manifest["sha256"]).lower())

    def test_defaults_auto_compute_version_code_and_utc_timestamp(self) -> None:
        manifest = manifest_mod.build_android_latest_manifest(
            apk_path=self.apk_path,
            version_name="v0.6.1",
            base_url="https://github.com/oinzc/flame-contractor/releases/download/v0.6.1",
            apk_object_key="FlameContractor-0.6.1-android-universal-release.apk",
        )
        self.assertEqual(manifest["versionName"], "0.6.1")
        self.assertEqual(manifest["versionCode"], 601)
        self.assertEqual(manifest["minVersionCode"], 0)
        self.assertEqual(manifest["releasePageUrl"], "")
        self.assertEqual(manifest["changelog"], "")
        self.assertEqual(
            manifest["downloadUrl"],
            "https://github.com/oinzc/flame-contractor/releases/download/v0.6.1/"
            "FlameContractor-0.6.1-android-universal-release.apk",
        )
        self.assertRegex(
            str(manifest["publishedAt"]),
            re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"),
        )

    def test_direct_download_url_mode_and_mutual_exclusion(self) -> None:
        direct_url = (
            "https://github.com/oinzc/flame-contractor/releases/download/v0.6.1/"
            "FlameContractor-0.6.1-android-universal-release.apk"
        )
        manifest = manifest_mod.build_android_latest_manifest(
            apk_path=self.apk_path,
            version_name="0.6.1",
            version_code=601,
            download_url=direct_url,
        )
        self.assertEqual(manifest["downloadUrl"], direct_url)

        with self.assertRaises(ValueError):
            manifest_mod.build_android_latest_manifest(
                apk_path=self.apk_path,
                version_name="0.6.1",
                version_code=601,
                base_url="https://dl.20190415.cloud",
                apk_object_key="android/releases/app.apk",
                download_url=direct_url,
            )

    def test_streaming_hash_for_multichunk_file(self) -> None:
        large_apk = self.tmp_dir / "large.apk"
        chunk_a = b"A" * (manifest_mod.HASH_CHUNK_SIZE + 12345)
        chunk_b = b"B" * (manifest_mod.HASH_CHUNK_SIZE + 6789)
        large_apk.write_bytes(chunk_a + chunk_b)

        expected_sha = hashlib.sha256(chunk_a + chunk_b).hexdigest()
        sha, size = manifest_mod.compute_file_sha256_and_size(large_apk)
        self.assertEqual(sha, expected_sha)
        self.assertEqual(size, len(chunk_a) + len(chunk_b))

    def test_min_version_code_validation(self) -> None:
        # 边界合法：minVersionCode == versionCode
        manifest = manifest_mod.build_android_latest_manifest(
            apk_path=self.apk_path,
            version_name="0.6.1",
            version_code=601,
            base_url="https://dl.20190415.cloud",
            apk_object_key="android/releases/app.apk",
            min_version_code=601,
        )
        self.assertEqual(manifest["minVersionCode"], 601)

        for bad_min in (-1, 602, True):
            with self.subTest(bad_min=bad_min):
                with self.assertRaises(ValueError):
                    manifest_mod.build_android_latest_manifest(
                        apk_path=self.apk_path,
                        version_name="0.6.1",
                        version_code=601,
                        base_url="https://dl.20190415.cloud",
                        apk_object_key="android/releases/app.apk",
                        min_version_code=bad_min,  # type: ignore[arg-type]
                    )

    def test_invalid_urls_and_object_keys_rejected(self) -> None:
        bad_base_urls = [
            "",
            "http://dl.20190415.cloud",
            "ftp://dl.20190415.cloud",
            "HTTPS://dl.20190415.cloud",
            "https://",
            "https:///only-path",
            "https://dl.20190415.cloud ",
            " https://dl.20190415.cloud",
            "https://dl.20190415.cloud\n",
            "https://dl.20190415.cloud\r/x",
            "https://dl.20190415.cloud/with space",
            "https://user:pass@dl.20190415.cloud",
            "https://dl.20190415.cloud?a=1",
            "https://dl.20190415.cloud#frag",
        ]
        for bad_url in bad_base_urls:
            with self.subTest(bad_base_url=bad_url):
                with self.assertRaises(ValueError):
                    manifest_mod.build_android_latest_manifest(
                        apk_path=self.apk_path,
                        version_name="0.6.1",
                        version_code=601,
                        base_url=bad_url,
                        apk_object_key="android/releases/app.apk",
                    )

        bad_keys = [
            "",
            "/",
            "android/releases/",
            "android/../secret.apk",
            "android/./app.apk",
            "android//app.apk",
            "android\\app.apk",
            "android/app with space.apk",
            "android/app\n.apk",
            "android/app.apk?token=1",
            "https://evil.example/app.apk",
        ]
        for bad_key in bad_keys:
            with self.subTest(bad_key=bad_key):
                with self.assertRaises(ValueError):
                    manifest_mod.build_android_latest_manifest(
                        apk_path=self.apk_path,
                        version_name="0.6.1",
                        version_code=601,
                        base_url="https://dl.20190415.cloud",
                        apk_object_key=bad_key,
                    )

        for bad_release_page in (
            "http://github.com/oinzc/flame-contractor/releases/tag/v0.6.1",
            "https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1\n",
            " ",
        ):
            with self.subTest(bad_release_page=bad_release_page):
                with self.assertRaises(ValueError):
                    manifest_mod.build_android_latest_manifest(
                        apk_path=self.apk_path,
                        version_name="0.6.1",
                        version_code=601,
                        base_url="https://dl.20190415.cloud",
                        apk_object_key="android/releases/app.apk",
                        release_page_url=bad_release_page,
                    )

    def test_missing_empty_or_directory_apk_rejected(self) -> None:
        with self.assertRaises(FileNotFoundError):
            manifest_mod.build_android_latest_manifest(
                apk_path=self.tmp_dir / "nonexistent.apk",
                version_name="0.6.1",
                version_code=601,
                base_url="https://dl.20190415.cloud",
                apk_object_key="android/releases/app.apk",
            )

        empty_apk = self.tmp_dir / "empty.apk"
        empty_apk.write_bytes(b"")
        with self.assertRaises(ValueError):
            manifest_mod.build_android_latest_manifest(
                apk_path=empty_apk,
                version_name="0.6.1",
                version_code=601,
                base_url="https://dl.20190415.cloud",
                apk_object_key="android/releases/app.apk",
            )

        with self.assertRaises(ValueError):
            manifest_mod.build_android_latest_manifest(
                apk_path=self.tmp_dir,
                version_name="0.6.1",
                version_code=601,
                base_url="https://dl.20190415.cloud",
                apk_object_key="android/releases/app.apk",
            )

    def test_invalid_published_at_and_version_code_rejected(self) -> None:
        for bad_ts in ("2026-11-26", "2026-11-26T00:00:00+08:00", "2026-02-30T00:00:00Z"):
            with self.subTest(bad_ts=bad_ts):
                with self.assertRaises(ValueError):
                    manifest_mod.build_android_latest_manifest(
                        apk_path=self.apk_path,
                        version_name="0.6.1",
                        version_code=601,
                        base_url="https://dl.20190415.cloud",
                        apk_object_key="android/releases/app.apk",
                        published_at=bad_ts,
                    )

        for bad_code in (0, -5, True, 2_100_000_001):
            with self.subTest(bad_code=bad_code):
                with self.assertRaises(ValueError):
                    manifest_mod.build_android_latest_manifest(
                        apk_path=self.apk_path,
                        version_name="0.6.1",
                        version_code=bad_code,  # type: ignore[arg-type]
                        base_url="https://dl.20190415.cloud",
                        apk_object_key="android/releases/app.apk",
                    )


class TestCli(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp_dir = Path(self._tmp.name)
        self.apk_payload = b"PK\x03\x04cli-test-apk-bytes-1234567890"
        self.apk_path = self.tmp_dir / "FlameContractor-0.6.1-android-universal-release.apk"
        self.apk_path.write_bytes(self.apk_payload)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _run_main(self, argv: list[str]) -> tuple[int, str, str]:
        stdout_buf = io.StringIO()
        stderr_buf = io.StringIO()
        with contextlib.redirect_stdout(stdout_buf), contextlib.redirect_stderr(stderr_buf):
            exit_code = manifest_mod.main(argv)
        return exit_code, stdout_buf.getvalue(), stderr_buf.getvalue()

    def test_cli_compute_version_code_success_and_failure(self) -> None:
        code, out, err = self._run_main(["--compute-version-code", "0.6.1"])
        self.assertEqual(code, 0)
        self.assertEqual(out, "601\n")
        self.assertEqual(err, "")

        code, out, err = self._run_main(["--compute-version-code", "v1.2.3-rc.1"])
        self.assertEqual(code, 0)
        self.assertEqual(out, "10203\n")
        self.assertEqual(err, "")

        code, out, err = self._run_main(["--compute-version-code", "0.0.0"])
        self.assertEqual(code, 1)
        self.assertEqual(out, "")
        self.assertIn("ERROR:", err)

    def test_cli_build_manifest_inline_changelog_writes_utf8_lf_json(self) -> None:
        output_path = self.tmp_dir / "nested" / "latest.json"
        code, out, err = self._run_main(
            [
                "--build-manifest",
                "--apk",
                str(self.apk_path),
                "--version-name",
                "0.6.1",
                "--version-code",
                "601",
                "--base-url",
                "https://dl.20190415.cloud",
                "--apk-object-key",
                "android/releases/FlameContractor-0.6.1-android-universal-release.apk",
                "--release-page-url",
                "https://github.com/oinzc/flame-contractor/releases/tag/v0.6.1",
                "--changelog",
                "新增应用内自动检查更新",
                "--min-version-code",
                "600",
                "--published-at",
                "2026-11-26T00:00:00Z",
                "--output",
                str(output_path),
            ]
        )
        self.assertEqual(code, 0, msg=err)
        self.assertIn("MANIFEST_OK:", out)
        self.assertTrue(output_path.is_file())

        raw_bytes = output_path.read_bytes()
        self.assertNotIn(b"\r\n", raw_bytes)
        raw_text = raw_bytes.decode("utf-8")
        self.assertIn("新增应用内自动检查更新", raw_text)  # ensure_ascii=False

        parsed = json.loads(raw_text)
        self.assertEqual(parsed["schemaVersion"], 1)
        self.assertEqual(parsed["platform"], "android")
        self.assertEqual(parsed["versionName"], "0.6.1")
        self.assertEqual(parsed["versionCode"], 601)
        self.assertEqual(parsed["minVersionCode"], 600)
        self.assertEqual(parsed["fileSize"], len(self.apk_payload))
        self.assertEqual(parsed["sha256"], hashlib.sha256(self.apk_payload).hexdigest())
        self.assertEqual(
            parsed["downloadUrl"],
            "https://dl.20190415.cloud/android/releases/FlameContractor-0.6.1-android-universal-release.apk",
        )

    def test_cli_build_manifest_changelog_file_and_conflict(self) -> None:
        notes_file = self.tmp_dir / "notes.md"
        notes_file.write_bytes("- 修复小米后台弹出检测\r\n- 接入 R2 极速更新\r\n".encode("utf-8"))
        output_path = self.tmp_dir / "latest.json"

        code, _, err = self._run_main(
            [
                "--build-manifest",
                "--apk",
                str(self.apk_path),
                "--version-name",
                "0.6.1",
                "--version-code",
                "601",
                "--base-url",
                "https://dl.20190415.cloud",
                "--apk-object-key",
                "android/releases/FlameContractor-0.6.1-android-universal-release.apk",
                "--changelog-file",
                str(notes_file),
                "--output",
                str(output_path),
            ]
        )
        self.assertEqual(code, 0, msg=err)
        parsed = json.loads(output_path.read_text(encoding="utf-8"))
        self.assertEqual(parsed["changelog"], "- 修复小米后台弹出检测\n- 接入 R2 极速更新")

        # 同时传入 --changelog 与 --changelog-file 报错
        code, _, err = self._run_main(
            [
                "--build-manifest",
                "--apk",
                str(self.apk_path),
                "--version-name",
                "0.6.1",
                "--version-code",
                "601",
                "--base-url",
                "https://dl.20190415.cloud",
                "--apk-object-key",
                "android/releases/FlameContractor-0.6.1-android-universal-release.apk",
                "--changelog",
                "inline",
                "--changelog-file",
                str(notes_file),
            ]
        )
        self.assertEqual(code, 1)
        self.assertIn("ERROR:", err)

    def test_cli_rejects_http_url_without_writing_output(self) -> None:
        output_path = self.tmp_dir / "should_not_exist.json"
        code, _, err = self._run_main(
            [
                "--build-manifest",
                "--apk",
                str(self.apk_path),
                "--version-name",
                "0.6.1",
                "--version-code",
                "601",
                "--base-url",
                "http://dl.20190415.cloud",
                "--apk-object-key",
                "android/releases/FlameContractor-0.6.1-android-universal-release.apk",
                "--output",
                str(output_path),
            ]
        )
        self.assertEqual(code, 1)
        self.assertIn("ERROR:", err)
        self.assertFalse(output_path.exists())

    def test_subprocess_entrypoint(self) -> None:
        script_path = SCRIPTS_DIR / "android_release_manifest.py"
        proc = subprocess.run(
            [sys.executable, str(script_path), "--compute-version-code", "1.2.3"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertEqual(proc.stdout.strip(), "10203")

    def test_extract_changelog_section_matches_semver_and_stops_at_next_version(self) -> None:
        sample_md = (
            "# 更新日志\n\n"
            "## [v0.9.0] - 2026-11-28\n\n"
            "### 新增\n"
            "- **极简模式默认开启**：打开即可一键续火。\n\n"
            "### 修复与改进\n"
            "- **切换更顺滑**：修复闪烁问题。\n\n"
            "## [v0.8.0] - 2026-11-28\n\n"
            "### 新增\n"
            "- 旧版本条目。\n"
        )
        extracted = manifest_mod.extract_changelog_section(sample_md, "0.9.0")
        self.assertIn("极简模式默认开启", extracted)
        self.assertIn("切换更顺滑", extracted)
        self.assertNotIn("旧版本条目", extracted)

        self.assertEqual(manifest_mod.extract_changelog_section(sample_md, "v0.8.0"), "### 新增\n- 旧版本条目。")
        self.assertEqual(manifest_mod.extract_changelog_section(sample_md, "9.9.9"), "")

    def test_format_user_facing_changelog_cleans_markdown_and_filters_ci_noise(self) -> None:
        raw_md = (
            "### 新增\n"
            "- **闹钟式选时**：使用 `24小时` 滚轮替代手输。\n"
            "  - 支持 [查看详情](https://example.com) 快捷预设。\n\n"
            "### 修复与改进\n"
            "- **切换闪烁修复**：切页过渡更稳定。\n"
        )
        cleaned = manifest_mod.format_user_facing_changelog(raw_md)
        expected = (
            "【新增功能】\n"
            "• 闹钟式选时：使用 24小时 滚轮替代手输。\n"
            "  · 支持 查看详情 快捷预设。\n\n"
            "【修复与优化】\n"
            "• 切换闪烁修复：切页过渡更稳定。"
        )
        self.assertEqual(cleaned, expected)

        github_auto_noise = (
            "## What's Changed\n"
            "* chore(ci): bump actions/setup-java from 4 to 6 by @dependabot[bot] in https://github.com/o/r/pull/28\n"
            "* feat(app): 支持好友单独定时 by @oinzc in https://github.com/o/r/pull/29\n\n"
            "## New Contributors\n"
            "* @dependabot[bot] made their first contribution in https://github.com/o/r/pull/28\n\n"
            "**Full Changelog**: https://github.com/o/r/compare/v0.8.0...v0.9.0\n"
        )
        cleaned_auto = manifest_mod.format_user_facing_changelog(github_auto_noise)
        self.assertEqual(cleaned_auto, "• feat(app): 支持好友单独定时")

        only_noise = (
            "## What's Changed\n"
            "* chore(ci): bump gradle/actions from 4 to 6 by @dependabot[bot] in https://github.com/o/r/pull/26\n"
            "## New Contributors\n"
            "* @dependabot[bot] made their first contribution in https://github.com/o/r/pull/26\n"
            "**Full Changelog**: https://github.com/o/r/compare/v0.7.2...v0.8.0\n"
        )
        self.assertEqual(manifest_mod.format_user_facing_changelog(only_noise), "")

    def test_cli_extract_changelog_and_format_file(self) -> None:
        md_path = self.tmp_dir / "CHANGELOG.md"
        out_path = self.tmp_dir / "notes.txt"
        md_path.write_text(
            "## [v0.9.0] - 2026-11-28\n\n### 新增\n- **功能**：更顺滑。\n",
            encoding="utf-8",
        )
        code, _, err = self._run_main(
            [
                "--extract-changelog",
                str(md_path),
                "--version-name",
                "0.9.0",
                "--user-facing",
                "--output",
                str(out_path),
            ]
        )
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(out_path.read_text(encoding="utf-8"), "【新增功能】\n• 功能：更顺滑。\n")

        fmt_out = self.tmp_dir / "formatted.txt"
        raw_path = self.tmp_dir / "raw.md"
        raw_path.write_text("### 修复\n- **问题**：已修复 `bug`。\n", encoding="utf-8")
        code2, _, err2 = self._run_main(
            [
                "--format-changelog-file",
                str(raw_path),
                "--output",
                str(fmt_out),
            ]
        )
        self.assertEqual(code2, 0, msg=err2)
        self.assertEqual(fmt_out.read_text(encoding="utf-8"), "【修复与优化】\n• 问题：已修复 bug。\n")

    def test_extract_recent_changelog_entries_filters_dedups_and_sorts(self) -> None:
        sample_md = (
            "# 更新日志\n\n"
            "## [Unreleased]\n\n"
            "### 新增\n"
            "- 尚未发布的改动。\n\n"
            "## [v0.9.1] - 2026-11-29\n\n"
            "### 新增\n"
            "- 高于上限版本的条目。\n\n"
            "## [v0.9.0] - 2026-11-28\n\n"
            "### 新增\n"
            "- **极简模式默认开启**：更直观。\n\n"
            "## [v0.8.0] - 2026-11-28\n\n"
            "## What's Changed\n"
            "* chore(ci): bump actions/setup-java from 4 to 6 by @dependabot[bot] in https://github.com/o/r/pull/28\n\n"
            "## 0.7.2 - 2026-11-27\n\n"
            "### 新增\n"
            "- **跳过今日已亮火花**：默认关闭。\n\n"
            "## [v0.7.2] - 2026-11-20\n\n"
            "### 修复\n"
            "- 重复版本号不应覆盖先出现条目。\n\n"
            "## v0.7.0\n\n"
            "### 新增\n"
            "- 初版极简模式。\n"
        )
        entries = manifest_mod.extract_recent_changelog_entries(
            sample_md,
            up_to_version="0.9.0",
            max_entries=2,
        )
        self.assertEqual([e["versionName"] for e in entries], ["0.9.0", "0.7.2"])
        self.assertEqual([e["versionCode"] for e in entries], [900, 702])
        self.assertEqual([e["publishedAt"] for e in entries], ["2026-11-28", "2026-11-27"])
        self.assertEqual(entries[0]["changelog"], "【新增功能】\n• 极简模式默认开启：更直观。")
        self.assertIn("跳过今日已亮火花", str(entries[1]["changelog"]))
        self.assertNotIn("重复版本号", str(entries[1]["changelog"]))

        all_entries = manifest_mod.extract_recent_changelog_entries(
            sample_md,
            up_to_version="0.9.0",
            max_entries=10,
        )
        self.assertEqual([e["versionName"] for e in all_entries], ["0.9.0", "0.7.2", "0.7.0"])
        self.assertEqual(all_entries[-1]["publishedAt"], "")

    def test_build_manifest_with_recent_releases_and_backward_compatibility(self) -> None:
        base_manifest = manifest_mod.build_android_latest_manifest(
            apk_path=self.apk_path,
            version_name="0.9.0",
            base_url="https://dl.20190415.cloud",
            apk_object_key="android/releases/FlameContractor-0.9.0-android-universal-release.apk",
            changelog="• 最新版本说明",
        )
        self.assertNotIn("recentReleases", base_manifest)

        with_recent = manifest_mod.build_android_latest_manifest(
            apk_path=self.apk_path,
            version_name="0.9.0",
            base_url="https://dl.20190415.cloud",
            apk_object_key="android/releases/FlameContractor-0.9.0-android-universal-release.apk",
            changelog="• 最新版本说明",
            recent_releases=[
                {
                    "versionName": "v0.7.0",
                    "versionCode": 700,
                    "publishedAt": "2026-11-26",
                    "changelog": "• 0.7.0 说明",
                },
                {
                    "versionName": "0.8.0",
                    "versionCode": 800,
                    "publishedAt": "2026-11-28T01:00:00Z",
                    "changelog": "• 0.8.0 说明",
                },
                {
                    "versionName": "0.8.0",
                    "versionCode": 800,
                    "publishedAt": "2026-11-28",
                    "changelog": "• 重复项应被去重",
                },
                {
                    "versionName": "0.6.2",
                    "versionCode": 602,
                    "publishedAt": "",
                    "changelog": "   ",
                },
            ],
        )
        recent = with_recent["recentReleases"]
        assert isinstance(recent, list)
        self.assertEqual([item["versionCode"] for item in recent], [800, 700])
        self.assertEqual(recent[0]["versionName"], "0.8.0")
        self.assertEqual(recent[1]["versionName"], "0.7.0")

    def test_cli_build_manifest_with_repo_changelog_md(self) -> None:
        repo_changelog = SCRIPTS_DIR.parent / "CHANGELOG.md"
        self.assertTrue(repo_changelog.is_file())
        output_path = self.tmp_dir / "latest_with_history.json"
        code, _, err = self._run_main(
            [
                "--build-manifest",
                "--apk",
                str(self.apk_path),
                "--version-name",
                "0.9.0",
                "--base-url",
                "https://dl.20190415.cloud",
                "--apk-object-key",
                "android/releases/FlameContractor-0.9.0-android-universal-release.apk",
                "--changelog",
                "• 0.9.0 更新",
                "--changelog-md",
                str(repo_changelog),
                "--output",
                str(output_path),
            ]
        )
        self.assertEqual(code, 0, msg=err)
        raw_bytes = output_path.read_bytes()
        self.assertLess(len(raw_bytes), 256 * 1024)
        parsed = json.loads(raw_bytes.decode("utf-8"))
        versions = [item["versionName"] for item in parsed["recentReleases"]]
        for expected in ("0.9.0", "0.8.0", "0.7.2", "0.7.1", "0.7.0"):
            self.assertIn(expected, versions)


if __name__ == "__main__":
    unittest.main()
