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

    def test_archive_members_match_exact_reviewed_layout(self) -> None:
        for missing in (
            "LICENSE.md",
            "META-INF/com/google/android/update-binary",
            "common/zygisk-status.sh",
            "zygisk/arm64-v8a.so",
        ):
            with self.subTest(missing=missing), self.archive(omissions={missing}) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "missing required"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)
        with self.archive({"system/bin/unreviewed": b"payload"}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "exact reviewed layout"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_duplicate_archive_member_names(self) -> None:
        with self.archive(duplicate_names=["module.prop"]) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "duplicate entries"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_non_regular_archive_members(self) -> None:
        for file_type in (stat.S_IFLNK, stat.S_IFDIR, stat.S_IFCHR):
            with (
                self.subTest(file_type=file_type),
                self.archive(file_types={"module.prop": file_type}) as path,
            ):
                with self.assertRaisesRegex(verifier.VerificationError, "Unix regular file"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_executable_native_payload_modes(self) -> None:
        for entry in (
            "zygisk/arm64-v8a.so",
            "libs/arm64-v8a/libech_dsp.so",
            "preproc/arm64-v8a/libechidna_preproc.so",
        ):
            with self.subTest(entry=entry), self.archive(modes={entry: 0o755}) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "expected 0644"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_special_bits_for_every_mode_class(self) -> None:
        for entry, mode in (
            ("customize.sh", 0o4755),
            ("module.prop", 0o2644),
            ("common/echidna-trust-helper.jar", 0o1444),
        ):
            with self.subTest(entry=entry), self.archive(modes={entry: mode}) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "has mode"):
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

    def test_rejects_any_generated_controller_spki_material(self) -> None:
        for name in (
            "trust/next-boot/preprocessor_controller_p256.spki",
            "system/etc/echidna/preprocessor_controller_p256.spki",
        ):
            with self.subTest(name=name), self.archive({name: b"fixture-spki"}) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "controller SPKI material"):
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

    def test_rejects_broad_audio_hal_spki_policy(self) -> None:
        broad = self.sepolicy() + (
            b"\nallow audioserver vendor_configs_file file { getattr open read }\n"
        )
        with self.archive({"sepolicy.rule": broad}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "broad system/vendor"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_extra_controller_spki_allow(self) -> None:
        for rule in (
            "allow appdomain echidna_controller_spki_file file { getattr open read map }",
            "allow audioserver echidna_controller_spki_file file { getattr open read write }",
            (
                "allow hal_audio_server echidna_controller_spki_file "
                "file { getattr open read execute }"
            ),
        ):
            broad = self.sepolicy() + ("\n" + rule + "\n").encode("utf-8")
            with self.subTest(rule=rule), self.archive({"sepolicy.rule": broad}) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "non-reviewed allow"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_extra_effect_trust_typeattributes(self) -> None:
        for trust_type in (
            "echidna_telemetry_key_file",
            "echidna_controller_spki_file",
        ):
            for attribute in ("system_file_type", "vendor_file_type", "data_file_type"):
                policy = self.sepolicy() + (
                    f"typeattribute {trust_type} {attribute}\n".encode("utf-8")
                )
                with (
                    self.subTest(trust_type=trust_type, attribute=attribute),
                    self.archive({"sepolicy.rule": policy}) as path,
                ):
                    with self.assertRaisesRegex(verifier.VerificationError, "exactly the file_type"):
                        verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_inherited_file_type_access_widening(self) -> None:
        for rule in (
            "allow appdomain file_type file { getattr open read }",
            "allow untrusted_app file_type file { getattr open read map }",
            "allow audioserver file_type file { getattr open read }",
        ):
            policy = self.sepolicy() + (rule + "\n").encode("utf-8")
            with self.subTest(rule=rule), self.archive({"sepolicy.rule": policy}) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "reviewed module policy"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_duplicate_or_reordered_policy_lines(self) -> None:
        first = verifier.EXPECTED_SEPOLICY_LINES[0]
        duplicate = self.sepolicy() + (first + "\n").encode("utf-8")
        with self.archive({"sepolicy.rule": duplicate}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "duplicate non-comment"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

        reordered_lines = list(verifier.EXPECTED_SEPOLICY_LINES)
        reordered_lines[:2] = reversed(reordered_lines[:2])
        reordered = ("\n".join(reordered_lines) + "\n").encode("utf-8")
        with self.archive({"sepolicy.rule": reordered}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "same_members=True"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_permissive_policy(self) -> None:
        permissive = self.sepolicy() + b"\npermissive hal_audio_default\n"
        with self.archive({"sepolicy.rule": permissive}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "permissive"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_type_declared_only_by_live_fallback(self) -> None:
        allows_only = (
            b"allow audioserver echidna_telemetry_key_file file { getattr open read }\n"
            b"allow hal_audio_server echidna_telemetry_key_file file { getattr open read }\n"
        )
        with self.archive({"sepolicy.rule": allows_only}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "declare the dedicated"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_spki_type_declared_only_by_live_fallback(self) -> None:
        telemetry_only = (
            b"type echidna_telemetry_key_file\n"
            b"typeattribute echidna_telemetry_key_file file_type\n"
            b"allow audioserver echidna_telemetry_key_file file { getattr open read }\n"
            b"allow hal_audio_server echidna_telemetry_key_file file { getattr open read }\n"
            b"allow audioserver echidna_controller_spki_file file { getattr open read }\n"
            b"allow hal_audio_server echidna_controller_spki_file file { getattr open read }\n"
        )
        with self.archive({"sepolicy.rule": telemetry_only}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "controller SPKI file type"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_missing_exact_key_label_lifecycle(self) -> None:
        with self.archive(
            {"common/telemetry-key-label.sh": b"#!/system/bin/sh\nchcon system_file\n"}
        ) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "trust label contract"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_missing_spki_live_policy_fallback(self) -> None:
        post_fs = self.post_fs().replace(
            b'magiskpolicy --live "allow audioserver echidna_controller_spki_file '
            b'file { getattr open read }"\n',
            b"",
        )
        with self.archive({"post-fs-data.sh": post_fs}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "live policy fallback"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_effect_trust_after_registration_success(self) -> None:
        registration = self.registration().replace(
            b"echidna_prepare_effect_trust\n"
            b'write_status "staged-next-boot"\n',
            b'write_status "staged-next-boot"\n'
            b"echidna_prepare_effect_trust\n",
        )
        with self.archive({"common/effect-registration.sh": registration}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "before reporting success"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_command_lookup_or_comment_as_trust_invocation(self) -> None:
        for decoy in (
            "command -v echidna_prepare_effect_trust",
            "# echidna_prepare_effect_trust",
        ):
            registration = self.registration().replace(
                b"echidna_prepare_effect_trust\n",
                (decoy + "\n").encode("utf-8"),
                1,
            )
            with self.subTest(script="registration", decoy=decoy), self.archive(
                {"common/effect-registration.sh": registration}
            ) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "before reporting success"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)

            trust = self.trust_bootstrap().replace(
                b"echidna_prepare_effect_trust\n",
                (decoy + "\n").encode("utf-8"),
                1,
            )
            with self.subTest(script="bootstrap", decoy=decoy), self.archive(
                {"common/trust-bootstrap.sh": trust}
            ) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "before reporting success"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_registration_without_combined_helper(self) -> None:
        registration = self.registration().replace(b"telemetry-key-label.sh\n", b"")
        with self.archive({"common/effect-registration.sh": registration}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "combined effect-trust helper"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_effect_trust_after_bootstrap_success(self) -> None:
        trust = self.trust_bootstrap().replace(
            b"echidna_prepare_effect_trust\n"
            b'write_status "$pin_status"\n',
            b'write_status "$pin_status"\n'
            b"echidna_prepare_effect_trust\n",
        )
        with self.archive({"common/trust-bootstrap.sh": trust}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "before reporting success"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_bootstrap_without_controller_spki_digest(self) -> None:
        trust = self.trust_bootstrap().replace(b"controller_spki_sha256\n", b"")
        with self.archive({"common/trust-bootstrap.sh": trust}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "provisioning contract"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_effect_trust_after_registration_exposure(self) -> None:
        post_fs = self.post_fs().replace(
            b"if ! prepare_effect_trust; then\nexit 1\nfi\n"
            b"activate_preprocessor_registration\n",
            b"activate_preprocessor_registration\n"
            b"if ! prepare_effect_trust; then\nexit 1\nfi\n",
        )
        with self.archive({"post-fs-data.sh": post_fs}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "before registration exposure"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_optional_trust_when_registration_state_exists(self) -> None:
        post_fs = self.post_fs().replace(
            b"if effect_registration_present; then\n"
            b"EFFECT_TRUST_PRESENCE=required\n"
            b"fi\n",
            b"",
        )
        with self.archive({"post-fs-data.sh": post_fs}) as path:
            with self.assertRaisesRegex(verifier.VerificationError, "staged or live registration"):
                verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_rejects_missing_aeli_export(self) -> None:
        with self.archive() as path:
            def missing_aeli(data: bytes, label: str) -> verifier.DynamicInfo:
                dynamic = self.dynamic_reader(data, label)
                if label.startswith("preproc/"):
                    return verifier.DynamicInfo(dynamic.soname, dynamic.needed, frozenset())
                return dynamic

            with self.assertRaisesRegex(verifier.VerificationError, "AELI"):
                verifier.verify_magisk_zip(path, missing_aeli)

    def test_rejects_invalid_or_wrong_abi_engine_and_dsp_payloads(self) -> None:
        for entry in (
            "zygisk/arm64-v8a.so",
            "libs/arm64-v8a/libech_dsp.so",
        ):
            with self.subTest(entry=entry, defect="not-elf"), self.archive(
                {entry: b"not-an-elf"}
            ) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "not an ELF"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)
            with self.subTest(entry=entry, defect="wrong-abi"), self.archive(
                {entry: self.elf("x86_64")}
            ) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "expected .* for arm64"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader)

    def test_all_native_families_require_soname_dependencies_and_exports(self) -> None:
        entries = (
            "zygisk/arm64-v8a.so",
            "libs/arm64-v8a/libech_dsp.so",
            "preproc/arm64-v8a/libechidna_preproc.so",
        )
        for entry in entries:
            with self.subTest(entry=entry, contract="soname"), self.archive() as path:
                def wrong_soname(data: bytes, label: str) -> verifier.DynamicInfo:
                    dynamic = self.dynamic_reader(data, label)
                    if label == entry:
                        return verifier.DynamicInfo("wrong.so", dynamic.needed, dynamic.exports)
                    return dynamic

                with self.assertRaisesRegex(verifier.VerificationError, "SONAME"):
                    verifier.verify_magisk_zip(path, wrong_soname)
            with self.subTest(entry=entry, contract="needed"), self.archive() as path:
                def wrong_needed(data: bytes, label: str) -> verifier.DynamicInfo:
                    dynamic = self.dynamic_reader(data, label)
                    if label == entry:
                        return verifier.DynamicInfo(
                            dynamic.soname, dynamic.needed | {"libunexpected.so"}, dynamic.exports
                        )
                    return dynamic

                with self.assertRaisesRegex(verifier.VerificationError, "DT_NEEDED"):
                    verifier.verify_magisk_zip(path, wrong_needed)
            with self.subTest(entry=entry, contract="exports"), self.archive() as path:
                def missing_export(data: bytes, label: str) -> verifier.DynamicInfo:
                    dynamic = self.dynamic_reader(data, label)
                    if label == entry:
                        return verifier.DynamicInfo(dynamic.soname, dynamic.needed, frozenset())
                    return dynamic

                with self.assertRaisesRegex(verifier.VerificationError, "missing required exports"):
                    verifier.verify_magisk_zip(path, missing_export)

    def test_build_root_provenance_binds_every_native_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            build_root = Path(temporary) / "build"
            self.write_build_root(build_root)
            with self.archive() as path:
                verifier.verify_magisk_zip(path, self.dynamic_reader, build_root)
            for abi in verifier.ABIS:
                for entry, _, _, _, _ in verifier.native_contracts(abi):
                    with self.subTest(entry=entry), self.archive(
                        {entry: self.elf(abi) + b"tampered"}
                    ) as path:
                        with self.assertRaisesRegex(
                            verifier.VerificationError, "does not match exact build output"
                        ):
                            verifier.verify_magisk_zip(path, self.dynamic_reader, build_root)

            with self.archive({"common/native-payload-manifest.json": b"{}"}) as path:
                with self.assertRaisesRegex(verifier.VerificationError, "exact reviewed layout"):
                    verifier.verify_magisk_zip(path, self.dynamic_reader, build_root)

    def test_release_gate_uses_external_build_root_provenance(self) -> None:
        workflow = (REPO_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("--zip out/echidna-magisk.zip", workflow)
        self.assertIn("--build-root build", workflow)

    def dynamic_reader(self, data: bytes, label: str) -> verifier.DynamicInfo:
        if label.startswith("zygisk/"):
            return verifier.DynamicInfo(
                verifier.ZYGISK_SONAME,
                verifier.ALLOWED_ZYGISK_NEEDED,
                frozenset({"zygisk_module_entry"}),
            )
        if label.startswith("libs/"):
            return verifier.DynamicInfo(
                verifier.DSP_SONAME,
                verifier.ALLOWED_DSP_NEEDED,
                frozenset({"ech_dsp_api_get_version"}),
            )
        return verifier.DynamicInfo(
            verifier.PREPROCESSOR_SONAME,
            verifier.ALLOWED_PREPROCESSOR_NEEDED,
            frozenset({"AELI"}),
        )

    def archive(
        self,
        overrides: dict[str, bytes] | None = None,
        *,
        omissions: set[str] | None = None,
        modes: dict[str, int] | None = None,
        file_types: dict[str, int] | None = None,
        duplicate_names: list[str] | None = None,
    ) -> "ArchiveContext":
        entries: dict[str, tuple[bytes, int]] = {
            "LICENSE.md": (b"fixture license\n", 0o644),
            "META-INF/com/google/android/update-binary": (b"#!/sbin/sh\n", 0o755),
            "META-INF/com/google/android/updater-script": (b"#MAGISK\n", 0o644),
            "module.prop": (b"id=echidna\n", 0o644),
            "customize.sh": (b"#!/system/bin/sh\n$MODDIR/system/vendor\n", 0o755),
            "post-fs-data.sh": (self.post_fs(), 0o755),
            "service.sh": (
                b"#!/system/bin/sh\n"
                b"cleanup_preprocessor_activation\n"
                b"stage_preprocessor_registration\n"
                b"staged-next-boot\n",
                0o755,
            ),
            "sepolicy.rule": (self.sepolicy(), 0o644),
            "common/zygisk-status.sh": (b"#!/system/bin/sh\n", 0o644),
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
        for name, mode in (modes or {}).items():
            data = entries[name][0]
            entries[name] = (data, mode)
        for name in omissions or set():
            entries.pop(name)
        return ArchiveContext(entries, file_types or {}, duplicate_names or [])

    @staticmethod
    def post_fs() -> bytes:
        return (
            b"#!/system/bin/sh\n"
            b"telemetry-key-label.sh\n"
            b"EFFECT_TRUST_PRESENCE=optional\n"
            b'"$MODDIR/registration"\n'
            b'echidna_prepare_effect_trust "$MODDIR" "" "" "$EFFECT_TRUST_PRESENCE"\n'
            b"magiskpolicy --live \"type echidna_telemetry_key_file\"\n"
            b"magiskpolicy --live \"typeattribute echidna_telemetry_key_file file_type\"\n"
            b"magiskpolicy --live \"type echidna_controller_spki_file\"\n"
            b"magiskpolicy --live \"typeattribute echidna_controller_spki_file file_type\"\n"
            b"magiskpolicy --live \"allow audioserver echidna_telemetry_key_file "
            b"file { getattr open read }\"\n"
            b"magiskpolicy --live \"allow hal_audio_server echidna_telemetry_key_file "
            b"file { getattr open read }\"\n"
            b"magiskpolicy --live \"allow audioserver echidna_controller_spki_file "
            b"file { getattr open read }\"\n"
            b"magiskpolicy --live \"allow hal_audio_server echidna_controller_spki_file "
            b"file { getattr open read }\"\n"
            b"if effect_registration_present; then\n"
            b"EFFECT_TRUST_PRESENCE=required\n"
            b"fi\n"
            b"discard_stale_preprocessor_activation\n"
            b"marker=\"$(manual_disable_marker)\"\n"
            b"apply_sepolicy\n"
            b"if ! prepare_effect_trust; then\nexit 1\nfi\n"
            b"activate_preprocessor_registration\n"
            b"arm_boot_watchdog\n"
        )

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
            + "telemetry-key-label.sh\n"
            + "echidna_prepare_effect_trust\n"
            + 'write_status "staged-next-boot"\n'
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
            "controller_spki_sha256\n"
            "silent rotation is refused\n"
            "telemetry-key-label.sh\n"
            "echidna_prepare_effect_trust\n"
            'write_status "$pin_status"\n'
            "failed transaction cleanup\n"
            "echidna_prepare_effect_trust\n"
        ).encode("utf-8")

    @staticmethod
    def key_label() -> bytes:
        return (
            "#!/system/bin/sh\n"
            "u:object_r:echidna_telemetry_key_file:s0\n"
            "u:object_r:echidna_controller_spki_file:s0\n"
            "trust/next-boot/preprocessor_controller_p256.spki\n"
            "system/etc/echidna/preprocessor_controller_p256.spki\n"
            "ECHIDNA_CONTROLLER_SPKI_BYTES=91\n"
            'ECHIDNA_CONTROLLER_SPKI_OWNER_MODE="0:0:444"\n'
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004\n"
            "echidna_prepare_effect_trust\n"
            "chcon\n"
            "ls -Zd\n"
            "root pin\n"
            "derived effect key removed\n"
            "controller SPKI root pin\n"
            "derived controller SPKI\n"
        ).encode("utf-8")

    @staticmethod
    def sepolicy() -> bytes:
        return ("\n".join(verifier.EXPECTED_SEPOLICY_LINES) + "\n").encode("utf-8")

    def write_build_root(self, root: Path) -> None:
        for abi in verifier.ABIS:
            library_dir = root / abi / "lib"
            library_dir.mkdir(parents=True)
            for name in (
                verifier.ZYGISK_SONAME,
                verifier.DSP_SONAME,
                verifier.PREPROCESSOR_SONAME,
            ):
                (library_dir / name).write_bytes(self.elf(abi))

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
    def __init__(
        self,
        entries: dict[str, tuple[bytes, int]],
        file_types: dict[str, int],
        duplicate_names: list[str],
    ) -> None:
        self.entries = entries
        self.file_types = file_types
        self.duplicate_names = duplicate_names
        self.temporary: tempfile.TemporaryDirectory[str] | None = None
        self.path: Path | None = None

    def __enter__(self) -> Path:
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "module.zip"
        with zipfile.ZipFile(self.path, "w") as archive:
            for name, (data, mode) in self.entries.items():
                info = zipfile.ZipInfo(name)
                info.create_system = 3
                info.external_attr = (self.file_types.get(name, stat.S_IFREG) | mode) << 16
                archive.writestr(info, data)
            for name in self.duplicate_names:
                data, mode = self.entries[name]
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
