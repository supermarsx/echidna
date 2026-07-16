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
CONTROLLER_SPKI_FILE = "preprocessor_controller_p256.spki"
CONTROLLER_SPKI_PENDING = f"trust/next-boot/{CONTROLLER_SPKI_FILE}"
CONTROLLER_SPKI_ACTIVE = f"system/etc/echidna/{CONTROLLER_SPKI_FILE}"
ALLOWED_PREPROCESSOR_NEEDED = frozenset({"libc.so", "libdl.so", "libm.so"})
KNOWN_DEBUG_CERT = "b545a99be69d7a147d2ebbcd3614d11ce6fcb550660f181f2a20ce0dd835544b"
EXPECTED_SEPOLICY_LINES = frozenset(
    {
        "type echidna_config_file",
        "typeattribute echidna_config_file file_type",
        "typeattribute echidna_config_file data_file_type",
        "type echidna_telemetry_file",
        "typeattribute echidna_telemetry_file file_type",
        "typeattribute echidna_telemetry_file data_file_type",
        "type echidna_telemetry_key_file",
        "typeattribute echidna_telemetry_key_file file_type",
        "type echidna_controller_spki_file",
        "typeattribute echidna_controller_spki_file file_type",
        "allow appdomain shell_data_file dir search",
        "allow appdomain echidna_config_file dir { search getattr open read }",
        "allow appdomain echidna_config_file file { getattr open read map }",
        "allow untrusted_app echidna_config_file dir { search getattr open read }",
        "allow untrusted_app echidna_config_file file { getattr open read map }",
        "dontaudit appdomain echidna_config_file file write",
        "dontaudit untrusted_app echidna_config_file file write",
        "allow appdomain echidna_telemetry_file dir { search getattr open read }",
        "allow appdomain echidna_telemetry_file file { getattr open read write append map }",
        "allow untrusted_app echidna_telemetry_file dir { search getattr open read }",
        "allow untrusted_app echidna_telemetry_file file { getattr open read write append map }",
        "allow audioserver echidna_telemetry_key_file file { getattr open read }",
        "allow hal_audio_server echidna_telemetry_key_file file { getattr open read }",
        "allow audioserver echidna_controller_spki_file file { getattr open read }",
        "allow hal_audio_server echidna_controller_spki_file file { getattr open read }",
    }
)


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
            "common/telemetry-key-label.sh",
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
        generated_spki = sorted(name for name in names if Path(name).name == CONTROLLER_SPKI_FILE)
        if generated_spki:
            raise VerificationError(
                "release ZIP must not ship generated controller SPKI material: "
                + ", ".join(generated_spki)
            )
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
        verify_mode(
            archive.getinfo("common/telemetry-key-label.sh"),
            0o644,
            "common/telemetry-key-label.sh",
        )
        for abi in ABIS:
            verify_preprocessor(archive, abi, dynamic_reader)

        registration = read_required(archive, "common/effect-registration.sh").decode("utf-8")
        activation = read_required(archive, "common/effect-activation.sh").decode("utf-8")
        post_fs = read_required(archive, "post-fs-data.sh").decode("utf-8")
        service = read_required(archive, "service.sh").decode("utf-8")
        customize = read_required(archive, "customize.sh").decode("utf-8")
        trust = read_required(archive, "common/trust-bootstrap.sh").decode("utf-8")
        key_label = read_required(archive, "common/telemetry-key-label.sh").decode("utf-8")
        sepolicy = read_required(archive, "sepolicy.rule").decode("utf-8")
        combined = "\n".join(
            (registration, activation, post_fs, service, customize, trust, key_label, sepolicy)
        )
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
            "controller_spki_sha256",
            "silent rotation is refused",
        ):
            if token not in trust:
                raise VerificationError(
                    f"telemetry proof-key provisioning contract is missing {token!r}"
                )

        exact_key_allows = {
            "allow audioserver echidna_telemetry_key_file file { getattr open read }",
            "allow hal_audio_server echidna_telemetry_key_file file { getattr open read }",
        }
        exact_spki_allows = {
            "allow audioserver echidna_controller_spki_file file { getattr open read }",
            "allow hal_audio_server echidna_controller_spki_file file { getattr open read }",
        }
        exact_key_declarations = {
            "type echidna_telemetry_key_file",
            "typeattribute echidna_telemetry_key_file file_type",
        }
        exact_spki_declarations = {
            "type echidna_controller_spki_file",
            "typeattribute echidna_controller_spki_file file_type",
        }
        policy_lines = {
            line.strip()
            for line in sepolicy.splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
        if not exact_key_allows.issubset(policy_lines):
            raise VerificationError(
                "telemetry key policy must grant exact read-only access to audio effect hosts"
            )
        if not exact_key_declarations.issubset(policy_lines):
            raise VerificationError(
                "sepolicy.rule must declare the dedicated telemetry key file type"
            )
        if not exact_spki_allows.issubset(policy_lines):
            raise VerificationError(
                "controller SPKI policy must grant exact read-only access to audio effect hosts"
            )
        if not exact_spki_declarations.issubset(policy_lines):
            raise VerificationError(
                "sepolicy.rule must declare the dedicated controller SPKI file type"
            )
        for trust_type in ("echidna_telemetry_key_file", "echidna_controller_spki_file"):
            actual_attributes = {
                line
                for line in policy_lines
                if line.startswith(f"typeattribute {trust_type} ")
            }
            expected_attributes = {f"typeattribute {trust_type} file_type"}
            if actual_attributes != expected_attributes:
                raise VerificationError(
                    f"{trust_type} must have exactly the file_type attribute; found "
                    + ", ".join(sorted(actual_attributes))
                )
        for line in policy_lines:
            if line.startswith("permissive "):
                raise VerificationError(
                    "effect trust policy must not contain permissive domains"
                )
            if not line.startswith("allow "):
                continue
            if " echidna_telemetry_key_file " in line and line not in exact_key_allows:
                raise VerificationError(
                    "telemetry key policy contains a non-reviewed allow: " + line
                )
            if " echidna_controller_spki_file " in line and line not in exact_spki_allows:
                raise VerificationError(
                    "controller SPKI policy contains a non-reviewed allow: " + line
                )
            if re.search(
                r"^allow\s+(?:audioserver|hal_audio(?:_[A-Za-z0-9_]+)?)\s+"
                r"(?:system_file|vendor_file|system_configs_file|vendor_configs_file)\b",
                line,
            ):
                raise VerificationError(
                    "audio effect hosts must not receive broad system/vendor file access"
                )
        if policy_lines != EXPECTED_SEPOLICY_LINES:
            unexpected = sorted(policy_lines - EXPECTED_SEPOLICY_LINES)
            missing = sorted(EXPECTED_SEPOLICY_LINES - policy_lines)
            raise VerificationError(
                "sepolicy.rule must match the reviewed module policy exactly; "
                f"unexpected={unexpected}, missing={missing}"
            )
        for token in (
            "u:object_r:echidna_telemetry_key_file:s0",
            "u:object_r:echidna_controller_spki_file:s0",
            CONTROLLER_SPKI_PENDING,
            CONTROLLER_SPKI_ACTIVE,
            "ECHIDNA_CONTROLLER_SPKI_BYTES=91",
            "ECHIDNA_CONTROLLER_SPKI_OWNER_MODE=\"0:0:444\"",
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004",
            "echidna_prepare_effect_trust",
            "chcon",
            "ls -Zd",
            "root pin",
            "derived effect key removed",
            "controller SPKI root pin",
            "derived controller SPKI",
        ):
            if token not in key_label:
                raise VerificationError(
                    f"effect trust label contract is missing {token!r}"
                )
        for script_name, source in (
            ("post-fs-data.sh", post_fs),
            ("common/trust-bootstrap.sh", trust),
            ("common/effect-registration.sh", registration),
        ):
            if "telemetry-key-label.sh" not in source:
                raise VerificationError(
                    f"{script_name} must source the combined effect-trust helper"
                )
        for live_rule in (
            exact_key_allows
            | exact_key_declarations
            | exact_spki_allows
            | exact_spki_declarations
        ):
            if f'magiskpolicy --live "{live_rule}"' not in post_fs:
                raise VerificationError(
                    f"post-fs live policy fallback is missing {live_rule!r}"
                )

        cleanup = activation.find("cleanup_transient_configs")
        fingerprint = activation.find("current_fingerprint=")
        copy = activation.find('cp "$inert_config"')
        if cleanup < 0 or fingerprint < 0 or copy < 0 or not cleanup < fingerprint < copy:
            raise VerificationError(
                "effect activation must remove stale backing, then verify fingerprint, then copy"
            )
        discard_call = post_fs.rfind("\ndiscard_stale_preprocessor_activation\n")
        trust_presence_call = post_fs.rfind("\nif effect_registration_present; then\n")
        marker_call = post_fs.rfind('\nmarker="$(manual_disable_marker')
        policy_call = post_fs.rfind("\napply_sepolicy\n")
        trust_label_call = post_fs.rfind("\nif ! prepare_effect_trust; then\n")
        activate_call = post_fs.rfind("\nactivate_preprocessor_registration\n")
        watchdog_call = post_fs.rfind("\narm_boot_watchdog\n")
        cleanup_call = service.rfind("\ncleanup_preprocessor_activation\n")
        staging_call = service.rfind("\nstage_preprocessor_registration\n")
        if discard_call < 0 or marker_call < 0 or discard_call > marker_call:
            raise VerificationError("post-fs-data must discard stale registration before markers")
        for token in (
            "EFFECT_TRUST_PRESENCE=optional",
            "EFFECT_TRUST_PRESENCE=required",
            '"$MODDIR/registration"',
            'echidna_prepare_effect_trust "$MODDIR" "" "" "$EFFECT_TRUST_PRESENCE"',
        ):
            if token not in post_fs:
                raise VerificationError(
                    "post-fs-data staged or live registration trust contract is missing "
                    f"{token!r}"
                )
        if (
            trust_presence_call < 0
            or discard_call < 0
            or trust_label_call < 0
            or not trust_presence_call < discard_call < trust_label_call
        ):
            raise VerificationError(
                "post-fs-data must make both trust pairs required for any staged or live "
                "registration before cleanup and exposure"
            )
        if (
            policy_call < 0
            or trust_label_call < 0
            or activate_call < 0
            or not policy_call < trust_label_call < activate_call
        ):
            raise VerificationError(
                "post-fs-data must load policy and label both trust inputs before "
                "registration exposure"
            )
        if activate_call < 0 or watchdog_call < 0 or activate_call > watchdog_call:
            raise VerificationError("post-fs-data must validate registration before later boot work")
        if cleanup_call < 0 or staging_call < 0 or cleanup_call > staging_call:
            raise VerificationError("late service must clean activation backing before restaging")

        registration_trust = registration.rfind("echidna_prepare_effect_trust")
        registration_success = registration.find('write_status "staged-next-boot"')
        if (
            registration_trust < 0
            or registration_success < 0
            or registration_trust > registration_success
        ):
            raise VerificationError(
                "effect registration must verify both trust inputs before reporting success"
            )
        bootstrap_trust = trust.rfind("echidna_prepare_effect_trust")
        bootstrap_success = trust.find('write_status "$pin_status"')
        if bootstrap_trust < 0 or bootstrap_success < 0 or bootstrap_trust > bootstrap_success:
            raise VerificationError(
                "trust bootstrap must label both trust inputs before reporting success"
            )

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
