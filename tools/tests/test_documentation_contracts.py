#!/usr/bin/env python3
from __future__ import annotations

import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]

STATUS_FILES = (
    "readme.md",
    "todo.md",
    "docs/architecture.md",
    "docs/build-install.md",
    "docs/comparison.md",
    "docs/design-rationale.md",
    "docs/developer_readme.md",
    "docs/index.md",
    "docs/limitations.md",
    "docs/magisk_release.md",
    "docs/signing.md",
    "docs/vendor-hal-analysis.md",
    "docs/verification.md",
    "docs/why-hard.md",
)


def read(relative_path: str) -> str:
    return (REPO_ROOT / relative_path).read_text(encoding="utf-8")


class DocumentationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.status = {path: read(path) for path in STATUS_FILES}
        cls.corpus = "\n".join(cls.status.values())

    def test_native_output_and_delivery_inventories_are_distinct(self) -> None:
        build_script = read("tools/build_native_ndk.sh")
        for library in (
            "libechidna.so",
            "libech_dsp.so",
            "libechidna_shim_jni.so",
        ):
            self.assertIn(library, build_script)

        native_cmake = read("native/CMakeLists.txt")
        legacy_cmake = read("native/effects/legacy/CMakeLists.txt")
        self.assertIn("add_subdirectory(effects/legacy)", native_cmake)
        self.assertIn("OUTPUT_NAME echidna_preproc", legacy_cmake)

        for path in (
            "docs/architecture.md",
            "docs/build-install.md",
            "docs/developer_readme.md",
            "docs/limitations.md",
            "docs/verification.md",
            "todo.md",
        ):
            with self.subTest(path=path):
                text = self.status[path].lower()
                self.assertRegex(text, r"\b12\b")
                self.assertRegex(text, r"(?:release|deliver|transport|support)")
                self.assertIn("preproc", text)

        for path in (
            "docs/architecture.md",
            "docs/build-install.md",
            "docs/developer_readme.md",
        ):
            self.assertIn("libechidna_shim_jni.so", self.status[path].lower())

        stale_patterns = (
            r"six\s+per-abi\s+native",
            r"all\s+(?:the\s+)?six\s+per-abi",
            r"all\s+\*\*6\*\*\s+\.so",
            r"six\s+real\s+android\s+\.so",
            r"signed\s+apk,\s*6\s+per-abi",
            r"native (?:build|superbuild) produces (?:exactly )?nine outputs",
            r"only the nine engine/dsp/shim",
            r"nine supported delivery artifacts",
        )
        for pattern in stale_patterns:
            with self.subTest(pattern=pattern):
                self.assertIsNone(re.search(pattern, self.corpus, re.IGNORECASE))

    def test_removed_magisk_contracts_do_not_return(self) -> None:
        for token in ("ECHIDNA_AF_OFFSETS", "echidna_af_offsets.txt"):
            with self.subTest(token=token):
                self.assertNotIn(token, self.corpus)

        stale_policy_patterns = (
            r"zygote\s+dyntransition",
            r"binder_call",
            r"appl(?:y|ies).{0,30}selinux\s+relaxations",
        )
        for pattern in stale_policy_patterns:
            with self.subTest(pattern=pattern):
                self.assertIsNone(re.search(pattern, self.corpus, re.IGNORECASE))

        scripts = read("android/control-service/magisk/post-fs-data.sh") + read(
            "android/control-service/magisk/service.sh"
        )
        self.assertNotIn("ECHIDNA_AF_OFFSETS", scripts)
        self.assertNotIn("echidna_af_offsets", scripts)

    def test_hosted_release_is_documented_as_fail_closed(self) -> None:
        signing = self.status["docs/signing.md"]
        build_install = self.status["docs/build-install.md"]
        workflow = read(".github/workflows/release.yml")

        self.assertIn("Hosted release workflow fails closed", signing)
        self.assertIn("Direct local Gradle fallback", signing)
        self.assertIn("cannot be upgraded in place", signing)
        self.assertIn("direct local Gradle build", build_install)
        self.assertIn("hosted release workflow", build_install)

        self.assertIn("tools/check_release_signing.py", workflow)
        self.assertIn("tools/verify_android_artifacts.py", workflow)
        self.assertIn("tools/verify_magisk_module.py", workflow)
        self.assertIn("RELEASE_CERT_SHA256", workflow)
        self.assertNotIn("debug-signing fallback", workflow)

    def test_legacy_effect_registration_stays_default_off(self) -> None:
        packager = read("tools/build_magisk_module.sh")
        registration = read("magisk/common/effect-registration.sh")
        merger = read(
            "android/control-service/trust-helper/src/main/java/com/echidna/magisk/"
            "EffectConfigMerger.java"
        )
        workflow = read(".github/workflows/release.yml")

        for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
            self.assertIn(abi, packager)
        self.assertIn("libechidna_preproc.so", packager)
        self.assertIn("tools/verify_magisk_module.py", workflow)
        self.assertIn("Stable-AIDL-only", registration)
        self.assertIn("auto_apply=false", registration)
        self.assertIn("$MODDIR/system/vendor", registration)
        self.assertIn("auto-apply refused", merger)
        self.assertNotIn("killall audioserver", registration)
        self.assertNotIn("setprop ctl.restart", registration)
        for path in (
            "readme.md",
            "docs/architecture.md",
            "docs/build-install.md",
            "docs/developer_readme.md",
            "docs/limitations.md",
            "docs/magisk_release.md",
            "docs/verification.md",
            "todo.md",
        ):
            with self.subTest(path=path):
                text = self.status[path].lower()
                self.assertRegex(text, r"default-off|not session-attached|not attached")

    def test_capture_route_status_matches_native_contract(self) -> None:
        contract = read("native/zygisk/src/hooks/capture_route_reachability.h")
        for route in ("kAAudioRoute", "kOpenSlRoute", "kTinyAlsaRoute"):
            with self.subTest(route=route):
                self.assertIn(route, contract)
        for token in (
            "kDeveloperContractOnly",
            "ECHIDNA_AR_SR,ECHIDNA_AR_CH,ECHIDNA_AR_FORMAT",
            "ECHIDNA_LIBC_SR,ECHIDNA_LIBC_CH,ECHIDNA_LIBC_FORMAT",
            "unsupported_injection_boundary",
        ):
            self.assertIn(token, contract)

        for path in (
            "docs/architecture.md",
            "docs/developer_readme.md",
            "docs/limitations.md",
            "docs/verification.md",
            "todo.md",
        ):
            with self.subTest(path=path):
                text = self.status[path]
                self.assertIn("unsupported_injection_boundary", text)
                self.assertRegex(text, r"(?i)developer contract|developer-contract")
                self.assertIn("AudioFlinger", text)
                self.assertIn("Audio HAL", text)

        forbidden_claims = (
            r"AudioFlinger\s+client\s+intercepts",
            r"AudioFlinger/HAL\s+record\s+path",
            r"Audio\s+HAL\s*\|\s*(?:Operational|Supported)",
        )
        for pattern in forbidden_claims:
            with self.subTest(pattern=pattern):
                self.assertIsNone(re.search(pattern, self.corpus, re.IGNORECASE))

    def test_policy_v2_uses_authenticated_transport_specific_delivery(self) -> None:
        readme = self.status["readme.md"]
        architecture = self.status["docs/architecture.md"]
        developer = self.status["docs/developer_readme.md"]
        publisher = read(
            "android/control-service/service/src/main/kotlin/com/echidna/control/service/"
            "ProfileSyncBridge.kt"
        )
        provider = read(
            "android/control-service/service/src/main/kotlin/com/echidna/control/service/"
            "PolicySnapshotService.kt"
        )
        shim_receiver = read(
            "android/lsposed-shim/shim/src/main/java/com/echidna/lsposed/core/"
            "ProfileSyncReceiver.java"
        )

        self.assertIn("ProfileSyncClientRole.LSPOSED -> closeSocket(socket)", publisher)
        self.assertIn("Binder.getCallingUid()", provider)
        self.assertIn("PolicySnapshotService", shim_receiver)
        self.assertIn("UID-scoped", readme)
        self.assertIn("process-scoped", readme)
        for text in (architecture, developer):
            self.assertIn("schemaVersion", text)
            self.assertIn("generation", text)
            self.assertIn("defaultProfileId", text)
            self.assertIn("captureOwners", text)
            self.assertIn("PolicySnapshotService", text)

        active_single_holder_patterns = (
            r"profile-sync\s+socket\s+is\s+single-holder",
            r"the\s+profile-sync\s+socket\s+is\s+single-holder",
            r"shared-memory\s+`?ProfileSyncBridge",
            r"lsposed.{0,80}(?:same|identical).{0,40}(?:af_unix|socket)",
            r"every connecting (?:zygisk or )?lsposed reader",
        )
        for pattern in active_single_holder_patterns:
            with self.subTest(pattern=pattern):
                self.assertIsNone(re.search(pattern, self.corpus, re.IGNORECASE))

    def test_historical_interception_evidence_is_qualified(self) -> None:
        for path in (
            "readme.md",
            "docs/architecture.md",
            "docs/build-install.md",
            "docs/developer_readme.md",
            "docs/verification.md",
        ):
            with self.subTest(path=path):
                text = self.status[path].lower()
                self.assertIn("audiorecord", text)
                self.assertRegex(text, r"historical|predates|pre-redesign")

    def test_performance_claim_boundary_is_explicit(self) -> None:
        performance = read("docs/performance-testing.md")
        mkdocs = read("mkdocs.yml")
        self.assertIn("Host results measure processing cost only", performance)
        self.assertIn("They are not Android callback latency", performance)
        self.assertIn("audio_pipeline_results.json", performance)
        self.assertIn("Performance Testing: performance-testing.md", mkdocs)
        verification = self.status["docs/verification.md"]
        self.assertIn("40/40 functional checks", verification)
        self.assertIn("592 scenarios", verification)
        self.assertIn("host processing-cost results only", verification)

    def test_historical_selinux_signal_is_not_attributed_to_current_head(self) -> None:
        for path in ("docs/limitations.md", "docs/verification.md", "todo.md"):
            with self.subTest(path=path):
                text = self.status[path]
                self.assertIn("v0.0.0", text)
                self.assertRegex(text, r"(?i)(?:not current HEAD|not evidence|not proof)")
                self.assertIn("telemetry", text.lower())

    def test_telemetry_origin_proof_status_matches_active_contract(self) -> None:
        native = read("native/effects/legacy/telemetry_protocol.cpp") + read(
            "native/effects/legacy/effect_context.cpp"
        )
        shim = read(
            "android/lsposed-shim/shim/src/main/java/com/echidna/lsposed/hooks/"
            "LegacyPreprocessorSessionManager.java"
        )
        control = read(
            "android/control-service/service/src/main/kotlin/com/echidna/control/service/"
            "LegacyPreprocessorTelemetryProof.kt"
        )
        self.assertIn("ECHIDNA_PREPROCESSOR_TELEMETRY_PROOF_V2", native)
        self.assertIn("pollTelemetryProof", shim)
        self.assertIn('Mac.getInstance("HmacSHA256")', control)

        for path in (
            "docs/limitations.md",
            "docs/magisk_release.md",
            "docs/signing.md",
            "docs/verification.md",
        ):
            with self.subTest(path=path):
                text = self.status[path]
                self.assertIn("ECHT v2", text)
                self.assertIn("HMAC", text)

        stale_patterns = (
            r"no native (?:telemetry )?(?:producer|consumer|protocol)",
            r"native (?:hmac )?consumption.{0,40}(?:future|still required)",
        )
        for pattern in stale_patterns:
            with self.subTest(pattern=pattern):
                self.assertIsNone(re.search(pattern, self.corpus, re.IGNORECASE))

    def test_policy_and_diagnostics_language_stays_fail_closed(self) -> None:
        forbidden = (
            r"panic.{0,40}(?:master[- ]off|turns? off.{0,20}master)",
            r"lsposed.{0,100}(?:zero(?:es|s|ed)? (?:the )?buffer|return(?:s|ed)? 0)",
            r"java fallback active",
        )
        for pattern in forbidden:
            with self.subTest(pattern=pattern):
                self.assertIsNone(re.search(pattern, self.corpus, re.IGNORECASE | re.DOTALL))


if __name__ == "__main__":
    unittest.main()
