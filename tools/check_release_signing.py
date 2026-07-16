#!/usr/bin/env python3
"""Classify release signing inputs without permitting unsigned publication."""

from __future__ import annotations

import argparse
import base64
import binascii
import os
import re
import sys
from collections.abc import Mapping


EXPECTED_CERT_VARIABLE = "RELEASE_CERT_SHA256"
REQUIRED_SIGNING_VARIABLES = (
    "RELEASE_KEYSTORE_BASE64",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
    EXPECTED_CERT_VARIABLE,
)
KNOWN_ANDROID_DEBUG_CERTIFICATES = {
    "b545a99be69d7a147d2ebbcd3614d11ce6fcb550660f181f2a20ce0dd835544b",
}


def normalize_certificate_digest(raw: str) -> str:
    if not re.fullmatch(r"[0-9A-Fa-f:\s]+", raw):
        raise ValueError("expected certificate SHA-256 contains forbidden characters or a wildcard")
    digest = re.sub(r"[^0-9A-Fa-f]", "", raw)
    if len(digest) != 64:
        raise ValueError("expected certificate SHA-256 must contain exactly 64 hex digits")
    if digest == "0" * 64:
        raise ValueError("all-zero expected certificate SHA-256 is forbidden")
    return digest.lower()


def validate_environment(environment: Mapping[str, str]) -> list[str]:
    missing = [name for name in REQUIRED_SIGNING_VARIABLES if not environment.get(name, "").strip()]
    errors: list[str] = []
    if missing:
        errors.append("missing required release signing inputs: " + ", ".join(missing))

    encoded_keystore = environment.get("RELEASE_KEYSTORE_BASE64", "").strip()
    if encoded_keystore:
        try:
            compact_keystore = re.sub(r"\s+", "", encoded_keystore)
            decoded_keystore = base64.b64decode(compact_keystore, validate=True)
            if not decoded_keystore:
                raise ValueError("decoded keystore is empty")
        except (binascii.Error, ValueError) as exc:
            errors.append(f"RELEASE_KEYSTORE_BASE64: invalid base64 keystore ({exc})")

    expected = environment.get(EXPECTED_CERT_VARIABLE, "").strip()
    if expected:
        try:
            normalized = normalize_certificate_digest(expected)
            if normalized in KNOWN_ANDROID_DEBUG_CERTIFICATES:
                errors.append(
                    f"{EXPECTED_CERT_VARIABLE}: known Android debug certificate is forbidden"
                )
        except ValueError as exc:
            errors.append(f"{EXPECTED_CERT_VARIABLE}: {exc}")
    return errors


def is_completely_unconfigured(environment: Mapping[str, str]) -> bool:
    return not any(environment.get(name, "").strip() for name in REQUIRED_SIGNING_VARIABLES)


def write_github_output(output_file: str, *, release_enabled: bool) -> None:
    if not output_file:
        return
    with open(output_file, "a", encoding="utf-8") as handle:
        handle.write(f"release_enabled={str(release_enabled).lower()}\n")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--allow-unconfigured-skip",
        action="store_true",
        help="successfully disable automatic CI publication when every signing input is absent",
    )
    parser.add_argument("--github-output", default=os.getenv("GITHUB_OUTPUT", ""))
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.allow_unconfigured_skip and is_completely_unconfigured(os.environ):
        write_github_output(args.github_output, release_enabled=False)
        print(
            "::notice title=Release skipped::All release signing inputs are absent; "
            "automatic CI will skip release build and publication."
        )
        return 0

    errors = validate_environment(os.environ)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        print(
            "Publishable releases never fall back to Android debug signing. "
            "Configure the complete keystore secret set and retry.",
            file=sys.stderr,
        )
        return 2

    write_github_output(args.github_output, release_enabled=True)
    expected = os.environ.get(EXPECTED_CERT_VARIABLE, "").strip()
    print("Release signing inputs are complete.")
    print(
        "Final APK certificates and Magisk trust bootstrap will use the configured "
        f"SHA-256 pin prefix {normalize_certificate_digest(expected)[:12]}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
