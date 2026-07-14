#!/usr/bin/env python3
"""Render-check Mermaid fences in Markdown documentation."""

from __future__ import annotations

import argparse
import dataclasses
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


DEFAULT_MERMAID_VERSION = "11.16.0"
FENCE_RE = re.compile(r"(?ms)^```mermaid[^\n]*\r?\n(.*?)\r?\n```")


@dataclasses.dataclass(frozen=True)
class MermaidBlock:
    source: Path
    index: int
    fence_line: int
    text: str


def iter_markdown_files(root: Path) -> list[Path]:
    files = sorted((root / "docs").rglob("*.md"))
    for extra in ("readme.md",):
        path = root / extra
        if path.exists():
            files.append(path)
    return files


def extract_blocks(path: Path) -> list[MermaidBlock]:
    text = path.read_text(encoding="utf-8")
    blocks: list[MermaidBlock] = []
    for index, match in enumerate(FENCE_RE.finditer(text), start=1):
        fence_line = text.count("\n", 0, match.start()) + 1
        blocks.append(
            MermaidBlock(
                source=path,
                index=index,
                fence_line=fence_line,
                text=match.group(1),
            )
        )
    return blocks


def mermaid_command(version: str) -> list[str]:
    npx = shutil.which("npx") or shutil.which("npx.cmd")
    if npx is None:
        raise FileNotFoundError("npx was not found; install Node.js before checking Mermaid.")
    return [npx, "-y", f"@mermaid-js/mermaid-cli@{version}"]


def render_block(
    block: MermaidBlock,
    workdir: Path,
    command_prefix: list[str],
) -> subprocess.CompletedProcess[str]:
    base = f"{block.source.stem}-{block.index}"
    input_path = workdir / f"{base}.mmd"
    output_path = workdir / f"{base}.svg"
    input_path.write_text(block.text, encoding="utf-8")
    command = [
        *command_prefix,
        "-i",
        str(input_path),
        "-o",
        str(output_path),
        "--quiet",
    ]
    return subprocess.run(
        command,
        cwd=workdir,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        default=Path(__file__).resolve().parents[1],
        type=Path,
        help="Repository root. Defaults to the parent of tools/.",
    )
    parser.add_argument(
        "--version",
        default=DEFAULT_MERMAID_VERSION,
        help="Mermaid CLI version to use.",
    )
    args = parser.parse_args()

    root = args.root.resolve()
    blocks = [
        block
        for markdown_file in iter_markdown_files(root)
        for block in extract_blocks(markdown_file)
    ]
    if not blocks:
        print("No Mermaid diagrams found.")
        return 0

    failures = 0
    command_prefix = mermaid_command(args.version)
    with tempfile.TemporaryDirectory(prefix="echidna-mermaid-") as tmp:
        workdir = Path(tmp)
        for block in blocks:
            relative = block.source.relative_to(root)
            result = render_block(block, workdir, command_prefix)
            if result.returncode == 0:
                print(f"OK {relative}:{block.fence_line} block {block.index}")
                continue

            failures += 1
            print(
                f"FAIL {relative}:{block.fence_line} block {block.index}",
                file=sys.stderr,
            )
            if result.stdout:
                print(result.stdout.strip(), file=sys.stderr)
            if result.stderr:
                print(result.stderr.strip(), file=sys.stderr)

    if failures:
        print(f"{failures} Mermaid diagram(s) failed.", file=sys.stderr)
        return 1

    print(f"Rendered {len(blocks)} Mermaid diagram(s) with Mermaid CLI {args.version}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
