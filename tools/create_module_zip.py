#!/usr/bin/env python3
"""Create a deterministic Magisk ZIP with explicit Unix installation modes."""

from __future__ import annotations

import argparse
import stat
import zipfile
from pathlib import Path, PurePosixPath


FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def normalized_members(values: list[str]) -> set[str]:
    result: set[str] = set()
    for value in values:
        member = PurePosixPath(value).as_posix().lstrip("/")
        if not member or member.startswith("../") or "/../" in member:
            raise ValueError(f"unsafe module member: {value}")
        result.add(member)
    return result


def create_zip(source: Path, output: Path, executables: set[str], read_only: set[str]) -> None:
    source = source.resolve()
    output = output.resolve()
    if not source.is_dir():
        raise ValueError(f"module staging directory is missing: {source}")
    candidates = sorted(source.rglob("*"))
    symlinks = [path for path in candidates if path.is_symlink()]
    if symlinks:
        raise ValueError(
            "module staging must not contain symlinks: "
            + ", ".join(path.relative_to(source).as_posix() for path in symlinks)
        )
    files = [path for path in candidates if path.is_file()]
    members = {path.relative_to(source).as_posix() for path in files}
    unknown = sorted((executables | read_only) - members)
    if unknown:
        raise ValueError("mode manifest names missing module members: " + ", ".join(unknown))
    if executables & read_only:
        raise ValueError("module members cannot be both executable and read-only")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    with zipfile.ZipFile(
        output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, strict_timestamps=True
    ) as archive:
        for path in files:
            member = path.relative_to(source).as_posix()
            info = zipfile.ZipInfo(member, FIXED_TIME)
            info.create_system = 3
            mode = 0o755 if member in executables else 0o444 if member in read_only else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            info._compresslevel = 9
            archive.writestr(info, path.read_bytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--executable", action="append", default=[])
    parser.add_argument("--read-only", action="append", default=[])
    args = parser.parse_args()
    create_zip(
        args.source,
        args.output,
        normalized_members(args.executable),
        normalized_members(args.read_only),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
