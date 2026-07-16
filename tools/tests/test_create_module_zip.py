#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import stat
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = REPO_ROOT / "tools" / "create_module_zip.py"
spec = importlib.util.spec_from_file_location("create_module_zip", TOOL_PATH)
assert spec and spec.loader
builder = importlib.util.module_from_spec(spec)
sys.modules["create_module_zip"] = builder
spec.loader.exec_module(builder)


class SyntheticRelativePath:
    def __init__(self, member: str) -> None:
        self.member = member

    def as_posix(self) -> str:
        return self.member


class WindowsOrderedSyntheticPath:
    """Path double whose native comparison uses Windows-style separators."""

    def __init__(self, member: str) -> None:
        self.member = member

    def relative_to(self, _source: object) -> SyntheticRelativePath:
        return SyntheticRelativePath(self.member)

    def __lt__(self, other: "WindowsOrderedSyntheticPath") -> bool:
        return self.member.replace("/", "\\") < other.member.replace("/", "\\")


class CreateModuleZipTest(unittest.TestCase):
    def test_member_plan_uses_posix_member_names_not_host_path_order(self) -> None:
        paths = [
            WindowsOrderedSyntheticPath("a/z.txt"),
            WindowsOrderedSyntheticPath("a0.txt"),
        ]

        host_order = [path.member for path in sorted(paths)]
        member_plan = builder.canonical_member_plan(object(), paths)

        self.assertEqual(["a0.txt", "a/z.txt"], host_order)
        self.assertEqual(["a/z.txt", "a0.txt"], [member for member, _path in member_plan])

    def test_repeated_archives_are_byte_identical_and_canonically_ordered(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "module"
            (source / "a").mkdir(parents=True)
            (source / "B.txt").write_bytes(b"ordinary\n")
            (source / "a" / "z.txt").write_bytes(b"executable\n")
            (source / "a0.txt").write_bytes(b"read only\n")
            first = root / "first.zip"
            second = root / "second.zip"

            for output in (first, second):
                builder.create_zip(
                    source,
                    output,
                    executables={"a/z.txt"},
                    read_only={"a0.txt"},
                )

            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(["B.txt", "a/z.txt", "a0.txt"], archive.namelist())
                expected_modes = {
                    "B.txt": 0o644,
                    "a/z.txt": 0o755,
                    "a0.txt": 0o444,
                }
                for info in archive.infolist():
                    with self.subTest(member=info.filename):
                        self.assertEqual(builder.FIXED_TIME, info.date_time)
                        self.assertEqual(3, info.create_system)
                        self.assertEqual(zipfile.ZIP_DEFLATED, info.compress_type)
                        self.assertEqual(
                            stat.S_IFREG | expected_modes[info.filename],
                            info.external_attr >> 16,
                        )


if __name__ == "__main__":
    unittest.main()
