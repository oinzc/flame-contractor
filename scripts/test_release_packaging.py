"""Unit tests for scripts/release_packaging.py."""

from __future__ import annotations

import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import release_packaging as rp  # noqa: E402


class TestWindowsPortableZip(unittest.TestCase):
    def test_builds_deterministic_zip_with_expected_layout(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rel_dir = root / "target" / "release"
            rel_dir.mkdir(parents=True)
            exe_path = rel_dir / "flame-contractor-desktop.exe"
            exe_path.write_bytes(b"MZ-MAIN-EXE")
            dll_path = rel_dir / "WebView2Loader.dll"
            dll_path.write_bytes(b"WV2-DLL")

            backend_dir = root / "resources" / "backend"
            (backend_dir / "_internal").mkdir(parents=True)
            (backend_dir / "flame-backend.exe").write_bytes(b"MZ-BACKEND")
            (backend_dir / "_internal" / "lib.dll").write_bytes(b"INTERNAL-DLL")

            out_dir = root / "dist"
            zip_path = rp.build_windows_portable_zip(
                exe_path=exe_path,
                backend_dir=backend_dir,
                version="0.9.2",
                out_dir=out_dir,
            )

            self.assertEqual(
                zip_path.name, "FlameContractor-0.9.2-windows-x64-portable.zip"
            )
            self.assertTrue(zip_path.is_file())

            with zipfile.ZipFile(zip_path, "r") as zf:
                names = zf.namelist()
                self.assertEqual(
                    names,
                    [
                        "FlameContractor-0.9.2-portable/Flame Contractor.exe",
                        "FlameContractor-0.9.2-portable/WebView2Loader.dll",
                        "FlameContractor-0.9.2-portable/backend/_internal/lib.dll",
                        "FlameContractor-0.9.2-portable/backend/flame-backend.exe",
                        "FlameContractor-0.9.2-portable/README-portable.txt",
                    ],
                )
                self.assertEqual(
                    zf.read("FlameContractor-0.9.2-portable/Flame Contractor.exe"),
                    b"MZ-MAIN-EXE",
                )
                self.assertEqual(
                    zf.read("FlameContractor-0.9.2-portable/backend/_internal/lib.dll"),
                    b"INTERNAL-DLL",
                )
                readme = zf.read(
                    "FlameContractor-0.9.2-portable/README-portable.txt"
                ).decode("utf-8")
                self.assertIn("Flame Contractor.exe", readme)
                self.assertIn("data/", readme)

    def test_rejects_invalid_version_or_missing_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            exe_path = root / "flame-contractor-desktop.exe"
            exe_path.write_bytes(b"MZ")
            backend_dir = root / "backend"
            backend_dir.mkdir()

            with self.assertRaises(ValueError):
                rp.build_windows_portable_zip(exe_path, backend_dir, "../bad", root)

            with self.assertRaises(FileNotFoundError):
                rp.build_windows_portable_zip(exe_path, backend_dir, "0.9.2", root)


class TestSha256Sums(unittest.TestCase):
    def test_writes_sorted_gnu_format_utf8_lf(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            b_file = root / "z-package.zip"
            a_file = root / "a-installer.exe"
            b_file.write_bytes(b"portable-zip-bytes")
            a_file.write_bytes(b"installer-exe-bytes")

            out_file = root / "SHA256SUMS-windows.txt"
            rp.write_sha256sums(out_file, [b_file, a_file])

            raw = out_file.read_bytes()
            self.assertNotIn(b"\r\n", raw)
            self.assertFalse(raw.startswith(b"\xef\xbb\xbf"))

            lines = raw.decode("utf-8").splitlines()
            expected_a = hashlib.sha256(b"installer-exe-bytes").hexdigest()
            expected_b = hashlib.sha256(b"portable-zip-bytes").hexdigest()
            self.assertEqual(
                lines,
                [
                    f"{expected_a}  a-installer.exe",
                    f"{expected_b}  z-package.zip",
                ],
            )

    def test_rejects_duplicate_basenames_and_missing_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d1 = root / "d1"
            d2 = root / "d2"
            d1.mkdir()
            d2.mkdir()
            f1 = d1 / "same.exe"
            f2 = d2 / "same.exe"
            f1.write_bytes(b"1")
            f2.write_bytes(b"2")

            with self.assertRaises(ValueError):
                rp.write_sha256sums(root / "SUMS.txt", [f1, f2])

            with self.assertRaises(FileNotFoundError):
                rp.write_sha256sums(root / "SUMS.txt", [root / "missing.bin"])


class TestCliEntrypoint(unittest.TestCase):
    def test_cli_subcommands(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            exe_path = root / "flame-contractor-desktop.exe"
            exe_path.write_bytes(b"EXE")
            backend_dir = root / "backend"
            backend_dir.mkdir()
            (backend_dir / "flame-backend.exe").write_bytes(b"BE")
            out_dir = root / "out"

            res1 = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "release_packaging.py"),
                    "windows-portable",
                    "--exe",
                    str(exe_path),
                    "--backend-dir",
                    str(backend_dir),
                    "--version",
                    "1.0.0",
                    "--out-dir",
                    str(out_dir),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(res1.returncode, 0, res1.stderr)
            zip_path = out_dir / "FlameContractor-1.0.0-windows-x64-portable.zip"
            self.assertTrue(zip_path.is_file())

            sums_path = out_dir / "SHA256SUMS-windows.txt"
            res2 = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "release_packaging.py"),
                    "sha256sums",
                    "--output",
                    str(sums_path),
                    "--files",
                    str(zip_path),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(res2.returncode, 0, res2.stderr)
            self.assertTrue(sums_path.is_file())
            self.assertIn(zip_path.name, sums_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
