#!/usr/bin/env python3
"""Resolve and validate Echidna release tags.

The release workflow uses YY.N tags, for example 26.1 and 26.2 in 2026. This
helper keeps the validation and auto-increment rules testable outside GitHub
Actions.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Iterable, TextIO

TAG_PATTERN = re.compile(r"^(?P<yy>\d{2})\.(?P<sequence>[1-9]\d*)$")
AUTO_RELEASE_BRANCH_REF = "refs/heads/main"


@dataclass(frozen=True)
class Resolution:
    tag: str
    source: str
    should_create_tag: bool


def parse_release_date(raw: str | None) -> date:
    if not raw:
        return datetime.now(timezone.utc).date()
    try:
        return date.fromisoformat(raw)
    except ValueError as exc:
        raise ValueError(f"invalid --today value {raw!r}; expected YYYY-MM-DD") from exc


def validate_release_tag(tag: str, *, label: str = "release tag") -> str:
    cleaned = tag.strip()
    if not TAG_PATTERN.fullmatch(cleaned):
        raise ValueError(
            f"{label} {tag!r} is invalid; expected YY.N, for example 26.1. "
            "Use a two-digit year and a positive, non-zero sequence without "
            "leading zeroes."
        )
    return cleaned


def read_tags(tags_file: str | None, extra_tags: Iterable[str]) -> set[str]:
    tags: set[str] = {tag.strip() for tag in extra_tags if tag.strip()}
    if tags_file:
        for line in Path(tags_file).read_text(encoding="utf-8").splitlines():
            tag = line.strip()
            if tag:
                tags.add(tag)
    return tags


def current_yy(today: date) -> str:
    return f"{today.year % 100:02d}"


def compute_next_tag(existing_tags: Iterable[str], today: date) -> str:
    yy = current_yy(today)
    max_sequence = 0
    for tag in existing_tags:
        match = TAG_PATTERN.fullmatch(tag)
        if match and match.group("yy") == yy:
            max_sequence = max(max_sequence, int(match.group("sequence")))
    return f"{yy}.{max_sequence + 1}"


def resolve_tag(
    *,
    event_name: str,
    ref: str,
    manual_tag: str,
    existing_tags: set[str],
    today: date,
) -> Resolution:
    if event_name == "push":
        prefix = "refs/tags/"
        if not ref.startswith(prefix):
            raise ValueError(f"push release workflow expected a tag ref, got {ref!r}")
        tag = validate_release_tag(ref.removeprefix(prefix), label="pushed tag")
        return Resolution(tag=tag, source="push", should_create_tag=False)

    if event_name not in {"workflow_dispatch", "workflow_call"}:
        raise ValueError(f"unsupported release event {event_name!r}")

    if manual_tag.strip():
        tag = validate_release_tag(manual_tag, label="manual release tag")
        return Resolution(
            tag=tag,
            source="manual",
            should_create_tag=tag not in existing_tags,
        )

    if event_name == "workflow_call" and ref != AUTO_RELEASE_BRANCH_REF:
        raise ValueError(
            "CI auto-release workflow_call expected "
            f"{AUTO_RELEASE_BRANCH_REF}, got {ref!r}"
        )

    tag = compute_next_tag(existing_tags, today)
    source = "ci" if event_name == "workflow_call" else "auto"
    return Resolution(tag=tag, source=source, should_create_tag=tag not in existing_tags)


def write_github_outputs(output_file: str | None, resolution: Resolution) -> None:
    if not output_file:
        return
    with Path(output_file).open("a", encoding="utf-8") as handle:
        handle.write(f"tag={resolution.tag}\n")
        handle.write(f"source={resolution.source}\n")
        handle.write(
            f"should_create_tag={str(resolution.should_create_tag).lower()}\n"
        )
        handle.write(f"checkout_ref=refs/tags/{resolution.tag}\n")


def print_resolution(stream: TextIO, resolution: Resolution) -> None:
    print(f"tag={resolution.tag}", file=stream)
    print(f"source={resolution.source}", file=stream)
    print(f"should_create_tag={str(resolution.should_create_tag).lower()}", file=stream)
    print(f"checkout_ref=refs/tags/{resolution.tag}", file=stream)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-name", default=os.getenv("GITHUB_EVENT_NAME", ""))
    parser.add_argument("--ref", default=os.getenv("GITHUB_REF", ""))
    parser.add_argument("--manual-tag", default=os.getenv("RELEASE_TAG_INPUT", ""))
    parser.add_argument("--tags-file", default=None)
    parser.add_argument("--tag", action="append", default=[], dest="extra_tags")
    parser.add_argument("--today", default=os.getenv("RELEASE_DATE", ""))
    parser.add_argument("--github-output", default=os.getenv("GITHUB_OUTPUT", ""))
    return parser


def main(argv: list[str]) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        existing_tags = read_tags(args.tags_file, args.extra_tags)
        resolution = resolve_tag(
            event_name=args.event_name,
            ref=args.ref,
            manual_tag=args.manual_tag,
            existing_tags=existing_tags,
            today=parse_release_date(args.today),
        )
        write_github_outputs(args.github_output, resolution)
        print_resolution(sys.stdout, resolution)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
