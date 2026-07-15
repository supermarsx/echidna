#!/usr/bin/env python3
"""Verify Echidna APK native payloads, signatures, and bundle provenance."""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Callable

from check_release_signing import normalize_certificate_digest


SHIM_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
COMPANION_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
ELF_IDENTITIES = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86": (1, 3),
    "x86_64": (2, 62),
}
KNOWN_ANDROID_DEBUG_CERTIFICATES = {
    # Certificate on the public 26.13 APKs. Subject checks catch debug keys from
    # other machines; retaining this digest prevents a renamed-subject bypass.
    "b545a99be69d7a147d2ebbcd3614d11ce6fcb550660f181f2a20ce0dd835544b",
}
DEBUG_SUBJECT_MARKER = "cn=android debug"
SHIM_JNI_EXPORTS = frozenset(
    {
        "Java_com_echidna_lsposed_core_NativeBridge_nativeInitialise",
        "Java_com_echidna_lsposed_core_NativeBridge_nativeIsEngineReady",
        "Java_com_echidna_lsposed_core_NativeBridge_nativeSetBypass",
        "Java_com_echidna_lsposed_core_NativeBridge_nativeSetProfile",
        "Java_com_echidna_lsposed_core_NativeBridge_nativeGetStatus",
        "Java_com_echidna_lsposed_core_NativeBridge_nativeProcessByteArray",
        "Java_com_echidna_lsposed_core_NativeBridge_nativeProcessShortArray",
        "Java_com_echidna_lsposed_core_NativeBridge_nativeProcessFloatArray",
        "Java_com_echidna_lsposed_core_NativeBridge_nativeProcessByteBuffer",
    }
)


class VerificationError(RuntimeError):
    pass


@dataclasses.dataclass(frozen=True)
class CertificateInfo:
    subject: str
    sha256: str


@dataclasses.dataclass(frozen=True)
class DynamicInfo:
    soname: str | None
    needed: frozenset[str]
    exports: frozenset[str] = frozenset()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_elf_identity(data: bytes, abi: str, label: str) -> None:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise VerificationError(f"{label} is not an ELF shared library")
    expected_class, expected_machine = ELF_IDENTITIES[abi]
    elf_class = data[4]
    byte_order = "little" if data[5] == 1 else "big" if data[5] == 2 else ""
    if not byte_order:
        raise VerificationError(f"{label} has an invalid ELF byte-order marker")
    machine = int.from_bytes(data[18:20], byte_order)
    if (elf_class, machine) != (expected_class, expected_machine):
        raise VerificationError(
            f"{label} has ELF class/machine {(elf_class, machine)}, "
            f"expected {(expected_class, expected_machine)} for {abi}"
        )


def find_readelf(explicit: str | None) -> str:
    if explicit:
        return explicit
    for candidate in ("llvm-readelf", "readelf"):
        resolved = shutil.which(candidate)
        if resolved:
            return resolved
    raise VerificationError("llvm-readelf/readelf was not found; install binutils or pass --readelf")


def find_nm(explicit: str | None) -> str:
    if explicit:
        return explicit
    for candidate in ("llvm-nm", "nm"):
        resolved = shutil.which(candidate)
        if resolved:
            return resolved
    raise VerificationError("llvm-nm/nm was not found; install binutils or pass --nm")


def read_dynamic_info(data: bytes, label: str, readelf: str, nm: str) -> DynamicInfo:
    with tempfile.TemporaryDirectory(prefix="echidna-elf-") as tmp:
        library = Path(tmp) / Path(label).name
        library.write_bytes(data)
        dynamic_result = subprocess.run(
            [readelf, "-d", str(library)],
            check=False,
            capture_output=True,
            text=True,
        )
        symbols_result = subprocess.run(
            [nm, "-D", "--defined-only", str(library)],
            check=False,
            capture_output=True,
            text=True,
        )
    if dynamic_result.returncode != 0:
        detail = (dynamic_result.stderr or dynamic_result.stdout).strip()
        raise VerificationError(f"readelf failed for {label}: {detail}")
    if symbols_result.returncode != 0:
        detail = (symbols_result.stderr or symbols_result.stdout).strip()
        raise VerificationError(f"nm failed for {label}: {detail}")

    soname_match = re.search(r"\(SONAME\).*?\[([^]]+)]", dynamic_result.stdout)
    needed = frozenset(re.findall(r"\(NEEDED\).*?\[([^]]+)]", dynamic_result.stdout))
    exports = frozenset(
        line.split()[-1]
        for line in symbols_result.stdout.splitlines()
        if line.split()
    )
    return DynamicInfo(soname_match.group(1) if soname_match else None, needed, exports)


def verify_native_entry(
    archive: zipfile.ZipFile,
    entry: str,
    abi: str,
    expected_soname: str,
    required_needed: frozenset[str],
    required_exports: frozenset[str],
    forbidden_exports: frozenset[str],
    dynamic_reader: Callable[[bytes, str], DynamicInfo],
) -> None:
    try:
        data = archive.read(entry)
    except KeyError as exc:
        raise VerificationError(f"missing native APK entry: {entry}") from exc
    verify_elf_identity(data, abi, entry)
    dynamic = dynamic_reader(data, entry)
    if dynamic.soname != expected_soname:
        raise VerificationError(
            f"{entry} SONAME is {dynamic.soname!r}, expected {expected_soname!r}"
        )
    missing_needed = required_needed - dynamic.needed
    if missing_needed:
        raise VerificationError(
            f"{entry} is missing DT_NEEDED dependencies: {', '.join(sorted(missing_needed))}"
        )
    missing_exports = required_exports - dynamic.exports
    if missing_exports:
        raise VerificationError(
            f"{entry} is missing required exports: {', '.join(sorted(missing_exports))}"
        )
    present_forbidden = forbidden_exports & dynamic.exports
    if present_forbidden:
        raise VerificationError(
            f"{entry} contains forbidden exports: {', '.join(sorted(present_forbidden))}"
        )


def verify_companion_apk(
    path: Path,
    dynamic_reader: Callable[[bytes, str], DynamicInfo],
) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        forbidden = sorted(
            name
            for name in names
            if name.startswith("lib/")
            and Path(name).name in {"libechidna.so", "libech_dsp.so", "libechidna_shim_jni.so"}
        )
        if forbidden:
            raise VerificationError(
                "companion APK must not embed the Zygisk/shim engine payload: "
                + ", ".join(forbidden)
            )
        expected_native_entries = {
            f"lib/{abi}/libechidna_control_jni.so" for abi in COMPANION_ABIS
        }
        native_entries = {
            name for name in names if name.startswith("lib/") and name.endswith(".so")
        }
        if native_entries != expected_native_entries:
            missing = sorted(expected_native_entries - native_entries)
            unexpected = sorted(native_entries - expected_native_entries)
            raise VerificationError(
                "companion APK native payload does not match the exact contract; "
                f"missing={missing}, unexpected={unexpected}"
            )
        for abi in COMPANION_ABIS:
            verify_native_entry(
                archive,
                f"lib/{abi}/libechidna_control_jni.so",
                abi,
                "libechidna_control_jni.so",
                frozenset(),
                frozenset(),
                frozenset(),
                dynamic_reader,
            )


def verify_shim_apk(
    path: Path,
    dynamic_reader: Callable[[bytes, str], DynamicInfo],
) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        forbidden = sorted(
            name for name in names if name.startswith("lib/") and Path(name).name == "libechidna.so"
        )
        if forbidden:
            raise VerificationError(
                "LSPosed APK must never package the full Zygisk module: " + ", ".join(forbidden)
            )
        expected_native_entries = {
            f"lib/{abi}/{library}"
            for abi in SHIM_ABIS
            for library in ("libechidna_shim_jni.so", "libech_dsp.so")
        }
        native_entries = {
            name for name in names if name.startswith("lib/") and name.endswith(".so")
        }
        if native_entries != expected_native_entries:
            missing = sorted(expected_native_entries - native_entries)
            unexpected = sorted(native_entries - expected_native_entries)
            raise VerificationError(
                "LSPosed APK native payload does not match the exact contract; "
                f"missing={missing}, unexpected={unexpected}"
            )
        for abi in SHIM_ABIS:
            verify_native_entry(
                archive,
                f"lib/{abi}/libechidna_shim_jni.so",
                abi,
                "libechidna_shim_jni.so",
                frozenset({"libech_dsp.so"}),
                SHIM_JNI_EXPORTS,
                frozenset({"zygisk_module_entry"}),
                dynamic_reader,
            )
            verify_native_entry(
                archive,
                f"lib/{abi}/libech_dsp.so",
                abi,
                "libech_dsp.so",
                frozenset(),
                frozenset({"ech_dsp_api_get_version"}),
                frozenset(),
                dynamic_reader,
            )


def parse_apksigner_output(output: str, label: str) -> CertificateInfo:
    signer_count = re.search(r"Number of signers:\s*(\d+)", output)
    subjects = re.findall(r"Signer #\d+ certificate DN:\s*(.+)", output)
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f: ]+)", output)
    if signer_count is None or int(signer_count.group(1)) != 1:
        raise VerificationError(f"{label} must have exactly one current APK signer")
    if len(subjects) != 1 or len(digests) != 1:
        raise VerificationError(f"unable to parse {label} APK signer certificate")
    return CertificateInfo(
        subject=subjects[0].strip(),
        sha256=normalize_certificate_digest(digests[0]),
    )


def read_apk_certificate(path: Path, apksigner: str) -> CertificateInfo:
    result = subprocess.run(
        [apksigner, "verify", "--verbose", "--print-certs", str(path)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise VerificationError(f"APK signature verification failed for {path}: {detail}")
    return parse_apksigner_output(result.stdout + "\n" + result.stderr, str(path))


def validate_release_certificates(
    companion: CertificateInfo,
    shim: CertificateInfo,
    expected_sha256: str | None,
) -> None:
    for label, certificate in (("companion", companion), ("LSPosed shim", shim)):
        if (
            DEBUG_SUBJECT_MARKER in certificate.subject.lower()
            or certificate.sha256 in KNOWN_ANDROID_DEBUG_CERTIFICATES
        ):
            raise VerificationError(
                f"{label} APK uses a forbidden Android debug certificate: {certificate.subject}"
            )
    if companion.sha256 != shim.sha256:
        raise VerificationError(
            "companion and LSPosed APKs are not signed by the same release certificate"
        )
    if expected_sha256:
        expected = normalize_certificate_digest(expected_sha256)
        for label, certificate in (("companion", companion), ("LSPosed shim", shim)):
            if certificate.sha256 != expected:
                raise VerificationError(
                    f"{label} APK signer {certificate.sha256} does not match configured "
                    f"release certificate {expected}"
                )


def verify_apks_bundle(bundle: Path, companion: Path, shim: Path) -> None:
    expected = {companion.name: companion, shim.name: shim}
    with zipfile.ZipFile(bundle) as archive:
        apk_entries = [name for name in archive.namelist() if name.lower().endswith(".apk")]
        for name, source in expected.items():
            matches = [entry for entry in apk_entries if Path(entry).name == name]
            if len(matches) != 1:
                raise VerificationError(
                    f"APK bundle must contain exactly one {name}; found {len(matches)}"
                )
            bundled_digest = hashlib.sha256(archive.read(matches[0])).hexdigest()
            source_digest = sha256_file(source)
            if bundled_digest != source_digest:
                raise VerificationError(
                    f"APK bundle member {name} does not match the standalone artifact"
                )
        unexpected = sorted(
            entry for entry in apk_entries if Path(entry).name not in expected
        )
        if unexpected:
            raise VerificationError("APK bundle contains unexpected APKs: " + ", ".join(unexpected))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--companion", required=True, type=Path)
    parser.add_argument("--shim", required=True, type=Path)
    parser.add_argument("--apks-bundle", type=Path)
    parser.add_argument("--readelf")
    parser.add_argument("--nm")
    parser.add_argument("--apksigner")
    parser.add_argument("--require-release-signing", action="store_true")
    parser.add_argument(
        "--expected-cert-sha256",
        default=os.getenv("RELEASE_CERT_SHA256", ""),
    )
    return parser


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    try:
        for path in (args.companion, args.shim):
            if not path.is_file():
                raise VerificationError(f"APK not found: {path}")
        readelf = find_readelf(args.readelf)
        nm = find_nm(args.nm)
        dynamic_reader = lambda data, label: read_dynamic_info(data, label, readelf, nm)
        verify_companion_apk(args.companion, dynamic_reader)
        verify_shim_apk(args.shim, dynamic_reader)
        if args.apks_bundle:
            if not args.apks_bundle.is_file():
                raise VerificationError(f"APK bundle not found: {args.apks_bundle}")
            verify_apks_bundle(args.apks_bundle, args.companion, args.shim)

        if args.require_release_signing and not args.apksigner:
            raise VerificationError("--apksigner is required with --require-release-signing")
        if args.apksigner:
            companion_certificate = read_apk_certificate(args.companion, args.apksigner)
            shim_certificate = read_apk_certificate(args.shim, args.apksigner)
            if args.require_release_signing:
                validate_release_certificates(
                    companion_certificate,
                    shim_certificate,
                    args.expected_cert_sha256 or None,
                )
            print(
                "APK signer SHA-256: "
                f"companion={companion_certificate.sha256} shim={shim_certificate.sha256}"
            )

        print("Android artifact contract verified.")
        return 0
    except (OSError, subprocess.SubprocessError, zipfile.BadZipFile, VerificationError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
