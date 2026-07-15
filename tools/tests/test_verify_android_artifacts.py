#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = REPO_ROOT / "tools" / "verify_android_artifacts.py"
sys.path.insert(0, str(TOOL_PATH.parent))

spec = importlib.util.spec_from_file_location("verify_android_artifacts", TOOL_PATH)
assert spec is not None
verifier = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["verify_android_artifacts"] = verifier
spec.loader.exec_module(verifier)


RELEASE_DIGEST = "12" * 32
OTHER_RELEASE_DIGEST = "34" * 32
DEBUG_DIGEST = next(iter(verifier.KNOWN_ANDROID_DEBUG_CERTIFICATES))


def elf_for(abi: str) -> bytes:
    elf_class, machine = verifier.ELF_IDENTITIES[abi]
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    header[4] = elf_class
    header[5] = 1
    header[18:20] = machine.to_bytes(2, "little")
    return bytes(header)


def dynamic_info(_data: bytes, entry: str) -> object:
    name = Path(entry).name
    needed = frozenset({"libech_dsp.so"}) if name == "libechidna_shim_jni.so" else frozenset()
    if name == "libechidna_shim_jni.so":
        exports = verifier.SHIM_JNI_EXPORTS
    elif name == "libech_dsp.so":
        exports = frozenset({"ech_dsp_api_get_version"})
    else:
        exports = frozenset()
    return verifier.DynamicInfo(name, needed, exports)


def write_companion(path: Path, *, omit_abi: str | None = None) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for abi in verifier.COMPANION_ABIS:
            if abi != omit_abi:
                archive.writestr(f"lib/{abi}/libechidna_control_jni.so", elf_for(abi))


def write_shim(
    path: Path,
    *,
    omit_abi: str | None = None,
    include_full_zygisk: bool = False,
) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for abi in verifier.SHIM_ABIS:
            if abi != omit_abi:
                archive.writestr(f"lib/{abi}/libechidna_shim_jni.so", elf_for(abi))
                archive.writestr(f"lib/{abi}/libech_dsp.so", elf_for(abi))
        if include_full_zygisk:
            archive.writestr("lib/arm64-v8a/libechidna.so", elf_for("arm64-v8a"))


class AndroidArtifactVerifierTest(unittest.TestCase):
    def test_companion_requires_control_jni_for_every_abi(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "companion.apk"
            write_companion(apk, omit_abi="x86")
            with self.assertRaisesRegex(verifier.VerificationError, "lib/x86/"):
                verifier.verify_companion_apk(apk, dynamic_info)

    def test_shim_requires_dedicated_jni_and_dsp_for_every_supported_abi(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "shim.apk"
            write_shim(apk, omit_abi="armeabi-v7a")
            with self.assertRaisesRegex(verifier.VerificationError, "armeabi-v7a"):
                verifier.verify_shim_apk(apk, dynamic_info)

    def test_full_zygisk_module_is_forbidden_in_shim(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "shim.apk"
            write_shim(apk, include_full_zygisk=True)
            with self.assertRaisesRegex(verifier.VerificationError, "full Zygisk module"):
                verifier.verify_shim_apk(apk, dynamic_info)

    def test_companion_rejects_unexpected_native_library(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "companion.apk"
            write_companion(apk)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("lib/arm64-v8a/libstale_engine.so", elf_for("arm64-v8a"))

            with self.assertRaisesRegex(verifier.VerificationError, "unexpected"):
                verifier.verify_companion_apk(apk, dynamic_info)

    def test_shim_rejects_unexpected_native_library(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "shim.apk"
            write_shim(apk)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("lib/arm64-v8a/libstale_engine.so", elf_for("arm64-v8a"))

            with self.assertRaisesRegex(verifier.VerificationError, "unexpected"):
                verifier.verify_shim_apk(apk, dynamic_info)

    def test_shim_jni_requires_complete_exports_without_zygisk_entry(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "shim.apk"
            write_shim(apk)

            def bad_symbols(data: bytes, entry: str) -> object:
                info = dynamic_info(data, entry)
                if Path(entry).name != "libechidna_shim_jni.so":
                    return info
                return verifier.DynamicInfo(
                    info.soname,
                    info.needed,
                    frozenset({"zygisk_module_entry"}),
                )

            with self.assertRaisesRegex(verifier.VerificationError, "missing required exports"):
                verifier.verify_shim_apk(apk, bad_symbols)

    def test_bundle_members_must_match_standalone_apks(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            companion = root / "echidna-companion-26.14.apk"
            shim = root / "echidna-lsposed-shim-26.14.apk"
            bundle = root / "echidna-apks-26.14.zip"
            write_companion(companion)
            write_shim(shim)
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.write(companion, f"apks/{companion.name}")
                archive.writestr(f"apks/{shim.name}", b"not-the-shim")

            with self.assertRaisesRegex(verifier.VerificationError, "does not match"):
                verifier.verify_apks_bundle(bundle, companion, shim)

    def test_debug_certificate_is_rejected_for_each_apk(self) -> None:
        release = verifier.CertificateInfo("CN=Echidna Release", RELEASE_DIGEST)
        debug_subject = verifier.CertificateInfo("C=US, O=Android, CN=Android Debug", RELEASE_DIGEST)
        debug_digest = verifier.CertificateInfo("CN=Renamed", DEBUG_DIGEST)

        for companion, shim in ((debug_subject, release), (release, debug_digest)):
            with self.subTest(companion=companion, shim=shim):
                with self.assertRaisesRegex(verifier.VerificationError, "debug certificate"):
                    verifier.validate_release_certificates(companion, shim, None)

    def test_expected_certificate_and_cross_apk_signer_are_enforced(self) -> None:
        release = verifier.CertificateInfo("CN=Echidna Release", RELEASE_DIGEST)
        other = verifier.CertificateInfo("CN=Other Release", OTHER_RELEASE_DIGEST)

        with self.assertRaisesRegex(verifier.VerificationError, "not signed by the same"):
            verifier.validate_release_certificates(release, other, None)
        with self.assertRaisesRegex(verifier.VerificationError, "does not match configured"):
            verifier.validate_release_certificates(release, release, OTHER_RELEASE_DIGEST)
        verifier.validate_release_certificates(release, release, RELEASE_DIGEST)

    def test_ci_and_release_transport_the_dedicated_shim_runtime(self) -> None:
        ci = (REPO_ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        release = (REPO_ROOT / ".github" / "workflows" / "release.yml").read_text(
            encoding="utf-8"
        )
        native_builder = (REPO_ROOT / "tools" / "build_native_ndk.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("libechidna_shim_jni.so", native_builder)
        self.assertIn("python3 tools/verify_android_artifacts.py", ci)
        self.assertIn("name: android-runtime-libs", release)
        self.assertIn("needs: [prepare, native]", release)
        self.assertIn("--require-release-signing", release)
        # Publish downloads only final packages, so the raw cross-job .so
        # transport artifact cannot leak into GitHub release assets.
        publish = release[release.index("  publish:") :]
        self.assertNotIn("name: android-runtime-libs", publish)
        for artifact in ("native-libs", "magisk", "android-apks"):
            self.assertIn(f"name: {artifact}", publish)


if __name__ == "__main__":
    unittest.main()
