#!/usr/bin/env python3
"""Verify the release Magisk ZIP, including default-off legacy effect payloads."""

from __future__ import annotations

import argparse
import io
import re
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import Callable

from verify_android_artifacts import (
    DynamicInfo,
    VerificationError,
    find_nm,
    find_readelf,
    read_dynamic_info,
    verify_elf_identity,
)


ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
PREPROCESSOR_SONAME = "libechidna_preproc.so"
TYPE_UUID = "c83e3db3-d4f5-5f2c-a095-8775c1edfc6d"
IMPLEMENTATION_UUID = "3e66a36e-dee9-5d81-a0d6-49fc3b863530"
TELEMETRY_KEY_FILE = "preprocessor_telemetry_hmac.key"
ALLOWED_PREPROCESSOR_NEEDED = frozenset({"libc.so", "libdl.so", "libm.so"})
KNOWN_DEBUG_CERT = "b545a99be69d7a147d2ebbcd3614d11ce6fcb550660f181f2a20ce0dd835544b"


def verify_mode(info: zipfile.ZipInfo, expected: int, label: str) -> None:
    mode = (info.external_attr >> 16) & 0o777
    if mode != expected:
        raise VerificationError(f"{label} has mode {mode:04o}, expected {expected:04o}")


def read_required(archive: zipfile.ZipFile, name: str) -> bytes:
    matches = [info for info in archive.infolist() if info.filename == name]
    if len(matches) != 1:
        raise VerificationError(f"Magisk ZIP must contain exactly one {name}; found {len(matches)}")
    return archive.read(matches[0])


def verify_preprocessor(
    archive: zipfile.ZipFile,
    abi: str,
    dynamic_reader: Callable[[bytes, str], DynamicInfo],
) -> None:
    entry = f"preproc/{abi}/{PREPROCESSOR_SONAME}"
    data = read_required(archive, entry)
    verify_elf_identity(data, abi, entry)
    dynamic = dynamic_reader(data, entry)
    if dynamic.soname != PREPROCESSOR_SONAME:
        raise VerificationError(
            f"{entry} SONAME is {dynamic.soname!r}, expected {PREPROCESSOR_SONAME!r}"
        )
    if dynamic.needed != ALLOWED_PREPROCESSOR_NEEDED:
        raise VerificationError(
            f"{entry} DT_NEEDED is {sorted(dynamic.needed)}, expected "
            f"{sorted(ALLOWED_PREPROCESSOR_NEEDED)}"
        )
    if "AELI" not in dynamic.exports:
        raise VerificationError(f"{entry} does not export the legacy AELI descriptor")
    verify_mode(archive.getinfo(entry), 0o644, entry)


def verify_magisk_zip(
    path: Path,
    dynamic_reader: Callable[[bytes, str], DynamicInfo],
) -> None:
    with zipfile.ZipFile(path) as archive:
        names = [info.filename for info in archive.infolist()]
        required = {
            "module.prop",
            "customize.sh",
            "post-fs-data.sh",
            "service.sh",
            "sepolicy.rule",
            "common/trust-bootstrap.sh",
            "common/effect-registration.sh",
            "common/effect-activation.sh",
            "common/echidna-trust-helper.jar",
            "common/release-cert-sha256",
            "common/trust-mode",
        }
        for abi in ABIS:
            required.update(
                {
                    f"zygisk/{abi}.so",
                    f"libs/{abi}/libech_dsp.so",
                    f"preproc/{abi}/{PREPROCESSOR_SONAME}",
                }
            )
        missing = sorted(required - set(names))
        if missing:
            raise VerificationError("Magisk ZIP is missing required entries: " + ", ".join(missing))
        duplicates = sorted({name for name in names if names.count(name) > 1})
        if duplicates:
            raise VerificationError("Magisk ZIP contains duplicate entries: " + ", ".join(duplicates))

        unexpected_preprocessors = sorted(
            name for name in names
            if Path(name).name == PREPROCESSOR_SONAME
            and name not in {f"preproc/{abi}/{PREPROCESSOR_SONAME}" for abi in ABIS}
        )
        if unexpected_preprocessors:
            raise VerificationError(
                "preprocessor must remain in inert per-ABI staging: "
                + ", ".join(unexpected_preprocessors)
            )
        active_registry = sorted(
            name for name in names
            if (name.startswith("system/")
                or name.startswith("registration/next-boot/config/"))
            and Path(name).name in {"audio_effects.xml", "audio_effects.conf"}
        )
        if active_registry:
            raise VerificationError(
                "release ZIP must not ship a pre-generated effect registry: "
                + ", ".join(active_registry)
            )
        if "system/etc/echidna/preprocessor_controller_p256.spki" in names:
            raise VerificationError("release ZIP must not ship an active controller SPKI")
        telemetry_secrets = sorted(
            name
            for name in names
            if Path(name).name in {TELEMETRY_KEY_FILE, TELEMETRY_KEY_FILE + ".meta"}
        )
        if telemetry_secrets:
            raise VerificationError(
                "release ZIP must not ship generated telemetry proof-key material: "
                + ", ".join(telemetry_secrets)
            )

        for script in (
            "customize.sh",
            "post-fs-data.sh",
            "service.sh",
            "common/trust-bootstrap.sh",
            "common/effect-registration.sh",
            "common/effect-activation.sh",
        ):
            verify_mode(archive.getinfo(script), 0o755, script)
        for abi in ABIS:
            verify_preprocessor(archive, abi, dynamic_reader)

        registration = read_required(archive, "common/effect-registration.sh").decode("utf-8")
        activation = read_required(archive, "common/effect-activation.sh").decode("utf-8")
        post_fs = read_required(archive, "post-fs-data.sh").decode("utf-8")
        service = read_required(archive, "service.sh").decode("utf-8")
        customize = read_required(archive, "customize.sh").decode("utf-8")
        trust = read_required(archive, "common/trust-bootstrap.sh").decode("utf-8")
        combined = "\n".join((registration, activation, post_fs, service, customize, trust))
        for token in (
            TYPE_UUID,
            IMPLEMENTATION_UUID,
            "auto_apply=false",
            "$MODDIR/system/vendor",
            "Stable-AIDL-only",
            "staged-next-boot",
            "registration/next-boot",
            "state-v2",
            "lshal list -ip",
            "approved-for-magisk-mount",
            "cleanup_transient_configs",
            "fingerprint/source/config/library/key",
        ):
            if token not in combined:
                raise VerificationError(f"Magisk registration contract is missing {token!r}")
        for forbidden in (
            "killall audioserver",
            "setprop ctl.restart",
            "<preprocess",
            "pre_processing {",
            "/cmdline",
            "ECHIDNA_EFFECT_HOST_BITS",
        ):
            if forbidden in combined:
                raise VerificationError(f"Magisk registration contains forbidden hot/auto-apply token {forbidden!r}")

        for token in (
            TELEMETRY_KEY_FILE,
            "trust/state",
            "--telemetry-root",
            "--telemetry-metadata",
            "--telemetry-effect",
            "telemetry_key_sha256",
            "telemetry_key_id",
            "silent rotation is refused",
        ):
            if token not in trust:
                raise VerificationError(
                    f"telemetry proof-key provisioning contract is missing {token!r}"
                )

        cleanup = activation.find("cleanup_transient_configs")
        fingerprint = activation.find("current_fingerprint=")
        copy = activation.find('cp "$inert_config"')
        if cleanup < 0 or fingerprint < 0 or copy < 0 or not cleanup < fingerprint < copy:
            raise VerificationError(
                "effect activation must remove stale backing, then verify fingerprint, then copy"
            )
        discard_call = post_fs.rfind("\ndiscard_stale_preprocessor_activation\n")
        marker_call = post_fs.rfind('\nmarker="$(manual_disable_marker')
        activate_call = post_fs.rfind("\nactivate_preprocessor_registration\n")
        watchdog_call = post_fs.rfind("\narm_boot_watchdog\n")
        cleanup_call = service.rfind("\ncleanup_preprocessor_activation\n")
        staging_call = service.rfind("\nstage_preprocessor_registration\n")
        if discard_call < 0 or marker_call < 0 or discard_call > marker_call:
            raise VerificationError("post-fs-data must discard stale registration before markers")
        if activate_call < 0 or watchdog_call < 0 or activate_call > watchdog_call:
            raise VerificationError("post-fs-data must validate registration before later boot work")
        if cleanup_call < 0 or staging_call < 0 or cleanup_call > staging_call:
            raise VerificationError("late service must clean activation backing before restaging")

        helper = read_required(archive, "common/echidna-trust-helper.jar")
        with zipfile.ZipFile(io.BytesIO(helper)) as helper_archive:
            if helper_archive.namelist().count("classes.dex") != 1:
                raise VerificationError("app_process helper must contain exactly one classes.dex")

        cert_pin = read_required(archive, "common/release-cert-sha256").decode("ascii").strip()
        if not re.fullmatch(r"[0-9a-f]{64}", cert_pin) or cert_pin == "0" * 64:
            raise VerificationError("release certificate pin is not one normalized nonzero SHA-256")
        mode = read_required(archive, "common/trust-mode").decode("ascii").strip()
        if mode not in {"production", "development"}:
            raise VerificationError("trust mode is invalid")
        if mode == "production" and cert_pin == KNOWN_DEBUG_CERT:
            raise VerificationError("production module contains the known Android debug pin")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zip", required=True, type=Path)
    parser.add_argument("--readelf")
    parser.add_argument("--nm")
    return parser


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    try:
        if not args.zip.is_file():
            raise VerificationError(f"Magisk ZIP not found: {args.zip}")
        readelf = find_readelf(args.readelf)
        nm = find_nm(args.nm)
        dynamic_reader = lambda data, label: read_dynamic_info(data, label, readelf, nm)
        verify_magisk_zip(args.zip, dynamic_reader)
        print("Magisk module contract verified (3 ABIs, default-off legacy effect registration).")
        return 0
    except (OSError, subprocess.SubprocessError, zipfile.BadZipFile, VerificationError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
