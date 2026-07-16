#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import io
import stat
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools"))
TOOL_PATH = REPO_ROOT / "tools" / "verify_magisk_module.py"
spec = importlib.util.spec_from_file_location("verify_magisk_module", TOOL_PATH)
assert spec and spec.loader
verifier = importlib.util.module_from_spec(spec)
sys.modules["verify_magisk_module"] = verifier
spec.loader.exec_module(verifier)


class VerifyMagiskModuleTest(unittest.TestCase):
    def test_accepts_three_abi_default_off_registration(self) -> None:
        with self.archive() as path:
            verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_pre_generated_registry(self) -> None:
        with self.archive({"system/vendor/etc/audio_effects.xml": b"<xml/>"}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "pre-generated"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_pre_generated_inert_registry(self) -> None:
        with self.archive(
            {"registration/next-boot/config/vendor/etc/audio_effects.xml": b"<xml/>"}
        ) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "pre-generated"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_any_generated_telemetry_proof_key_material(self) -> None:
        for name in (
            "trust/state/preprocessor_telemetry_hmac.key",
            "trust/state/preprocessor_telemetry_hmac.key.meta",
            "system/etc/echidna/preprocessor_telemetry_hmac.key",
        ):
            with self.subTest(name=name), self.archive({name: b"fixture-secret"}) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "proof-key material"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_accepts_explicit_development_package_without_generated_secret(self) -> None:
        with self.archive({"common/trust-mode": b"development\n"}) as path:
            verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_auto_apply_content(self) -> None:
        with self.archive(
            {"common/effect-registration.sh": self.registration() + b"\n<preprocess>"}
        ) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "auto-apply"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_broad_audio_hal_key_policy(self) -> None:
        broad = self.sepolicy() + (
            b"\nallow hal_audio_server system_file file { getattr open read }\n"
        )
        with self.archive({"sepolicy.rule": broad}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "broad system/vendor"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_type_declared_only_by_live_fallback(self) -> None:
        allows_only = (
            b"allow audioserver echidna_telemetry_key_file file { getattr open read }\n"
            b"allow hal_audio_server echidna_telemetry_key_file file { getattr open read }\n"
        )
        with self.archive({"sepolicy.rule": allows_only}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "declare the dedicated"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_missing_exact_key_label_lifecycle(self) -> None:
        with self.archive(
            {"common/telemetry-key-label.sh": b"#!/system/bin/sh\nchcon system_file\n"}
        ) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "label contract"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_missing_aeli_export(self) -> None:
        with self.archive() as path:
            def missing_aeli(data: bytes, label: str) -> verifier.DynamicInfo:
                return verifier.DynamicInfo(
                    verifier.PREPROCESSOR_SONAME,
                    verifier.ALLOWED_PREPROCESSOR_NEEDED,
                    frozenset(),
                )

            with self.assertRaisesRegex(verifier.VerificationError, "AELI"):
                verifier.verify_magisk_zip(path, missing_aeli)

    def dynamic_reader(self, data: bytes, label: str) -> verifier.DynamicInfo:
        return verifier.DynamicInfo(
            verifier.PREPROCESSOR_SONAME,
            verifier.ALLOWED_PREPROCESSOR_NEEDED,
            frozenset({"AELI"}),
        )

    def archive(self, overrides: dict[str, bytes] | None = None) -> "ArchiveContext":
        entries: dict[str, tuple[bytes, int]] = {
            "module.prop": (b"id=echidna\n", 0o644),
            "customize.sh": (b"#!/system/bin/sh\n$MODDIR/system/vendor\n", 0o755),
            "post-fs-data.sh": (
                b"#!/system/bin/sh\n"
                b"magiskpolicy --live \"type echidna_telemetry_key_file\"\n"
                b"magiskpolicy --live \"typeattribute echidna_telemetry_key_file file_type\"\n"
                b"magiskpolicy --live \"allow audioserver echidna_telemetry_key_file "
                b"file { getattr open read }\"\n"
                b"magiskpolicy --live \"allow hal_audio_server echidna_telemetry_key_file "
                b"file { getattr open read }\"\n"
                b"discard_stale_preprocessor_activation\n"
                b"marker=\"$(manual_disable_marker)\"\n"
                b"apply_sepolicy\n"
                b"if ! prepare_effect_telemetry_key; then\nexit 1\nfi\n"
                b"activate_preprocessor_registration\n"
                b"arm_boot_watchdog\n",
                0o755,
            ),
            "service.sh": (
                b"#!/system/bin/sh\n"
                b"cleanup_preprocessor_activation\n"
                b"stage_preprocessor_registration\n"
                b"staged-next-boot\n",
                0o755,
            ),
            "sepolicy.rule": (self.sepolicy(), 0o644),
            "common/trust-bootstrap.sh": (self.trust_bootstrap(), 0o755),
            "common/telemetry-key-label.sh": (self.key_label(), 0o644),
            "common/effect-registration.sh": (self.registration(), 0o755),
            "common/effect-activation.sh": (self.activation(), 0o755),
            "common/echidna-trust-helper.jar": (self.helper_jar(), 0o444),
            "common/release-cert-sha256": (b"ab" * 32 + b"\n", 0o444),
            "common/trust-mode": (b"production\n", 0o444),
        }
        for abi in verifier.ABIS:
            entries[f"zygisk/{abi}.so"] = (self.elf(abi), 0o644)
            entries[f"libs/{abi}/libech_dsp.so"] = (self.elf(abi), 0o644)
            entries[f"preproc/{abi}/libechidna_preproc.so"] = (self.elf(abi), 0o644)
        for name, data in (overrides or {}).items():
            mode = entries.get(name, (b"", 0o644))[1]
            entries[name] = (data, mode)
        return ArchiveContext(entries)

    @staticmethod
    def registration() -> bytes:
        return (
            "#!/system/bin/sh\n"
            + verifier.TYPE_UUID + "\n"
            + verifier.IMPLEMENTATION_UUID + "\n"
            + "auto_apply=false\n"
            + "$MODDIR/system/vendor\n"
            + "Stable-AIDL-only\n"
            + "staged-next-boot\n"
            + "registration/next-boot\n"
            + "state-v2\n"
            + "lshal list -ip\n"
        ).encode("utf-8")

    @staticmethod
    def activation() -> bytes:
        return (
            "#!/system/bin/sh\n"
            "cleanup_transient_configs\n"
            "current_fingerprint=fixture\n"
            "fingerprint/source/config/library/key\n"
            "cp \"$inert_config\" \"$config_temporary\"\n"
            "approved-for-magisk-mount\n"
        ).encode("utf-8")

    @staticmethod
    def trust_bootstrap() -> bytes:
        return (
            "#!/system/bin/sh\n"
            "TELEMETRY_KEY=preprocessor_telemetry_hmac.key\n"
            "TRUST_STATE=trust/state\n"
            "--telemetry-root\n"
            "--telemetry-metadata\n"
            "--telemetry-effect\n"
            "telemetry_key_sha256\n"
            "telemetry_key_id\n"
            "silent rotation is refused\n"
            "telemetry-key-label.sh\n"
            "echidna_prepare_effect_telemetry_key\n"
        ).encode("utf-8")

    @staticmethod
    def key_label() -> bytes:
        return (
            "#!/system/bin/sh\n"
            "u:object_r:echidna_telemetry_key_file:s0\n"
            "echidna_prepare_effect_telemetry_key\n"
            "chcon\n"
            "ls -Zd\n"
            "root pin\n"
            "derived effect key removed\n"
        ).encode("utf-8")

    @staticmethod
    def sepolicy() -> bytes:
        return (
            "type echidna_telemetry_key_file\n"
            "typeattribute echidna_telemetry_key_file file_type\n"
            "allow audioserver echidna_telemetry_key_file file { getattr open read }\n"
            "allow hal_audio_server echidna_telemetry_key_file file { getattr open read }\n"
        ).encode("utf-8")

    @staticmethod
    def helper_jar() -> bytes:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("classes.dex", b"dex")
        return output.getvalue()

    @staticmethod
    def elf(abi: str) -> bytes:
        elf_class, machine = verifier.__dict__["verify_elf_identity"].__globals__["ELF_IDENTITIES"][abi]
        result = bytearray(64)
        result[:4] = b"\x7fELF"
        result[4] = elf_class
        result[5] = 1
        result[18:20] = machine.to_bytes(2, "little")
        return bytes(result)


class ArchiveContext:
    def __init__(self, entries: dict[str, tuple[bytes, int]]) -> None:
        self.entries = entries
        self.temporary: tempfile.TemporaryDirectory[str] | None = None
        self.path: Path | None = None

    def __enter__(self) -> Path:
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "module.zip"
        with zipfile.ZipFile(self.path, "w") as archive:
            for name, (data, mode) in self.entries.items():
                info = zipfile.ZipInfo(name)
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | mode) << 16
                archive.writestr(info, data)
        return self.path

    def __exit__(self, *args: object) -> None:
        assert self.temporary
        self.temporary.cleanup()


if __name__ == "__main__":
    unittest.main()
