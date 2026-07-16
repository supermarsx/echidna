#!/usr/bin/env python3
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CUSTOMIZE = REPO_ROOT / "magisk" / "customize.sh"
POST_FS = REPO_ROOT / "android" / "control-service" / "magisk" / "post-fs-data.sh"
SERVICE = REPO_ROOT / "android" / "control-service" / "magisk" / "service.sh"
SEPOLICY = REPO_ROOT / "magisk" / "sepolicy.rule"
PACKAGER = REPO_ROOT / "tools" / "build_magisk_module.sh"
ZYGISK_STATUS = REPO_ROOT / "magisk" / "common" / "zygisk-status.sh"
TRUST_BOOTSTRAP = REPO_ROOT / "magisk" / "common" / "trust-bootstrap.sh"
TELEMETRY_KEY_LABEL = REPO_ROOT / "magisk" / "common" / "telemetry-key-label.sh"


class MagiskContractsTest(unittest.TestCase):
    def test_runtime_scripts_do_not_use_invalid_zygisk_applet(self) -> None:
        for path in (CUSTOMIZE, POST_FS, SERVICE):
            with self.subTest(path=path):
                self.assertNotIn("magisk --zygisk", executable_lines(path))

    def test_runtime_policy_does_not_grant_zygote_self_transitions(self) -> None:
        forbidden = (
            "allow zygote zygote process dyntransition",
            "allow zygote zygote binder call",
            "allow zygote zygote binder transfer",
        )
        for path in (CUSTOMIZE, POST_FS, SERVICE, SEPOLICY):
            text = executable_lines(path)
            for rule in forbidden:
                with self.subTest(path=path, rule=rule):
                    self.assertNotIn(rule, text)

    def test_shared_region_directories_are_not_world_writable(self) -> None:
        post_fs = executable_lines(POST_FS)

        self.assertNotIn("chmod 0771", post_fs)
        self.assertIn('chmod 0755 "$TMP_DIR"', post_fs)
        self.assertIn('chmod 0755 "$PLUGIN_DIR"', post_fs)

    def test_packager_includes_shared_zygisk_status_helper(self) -> None:
        self.assertTrue(ZYGISK_STATUS.is_file())
        packager = PACKAGER.read_text(encoding="utf-8")
        self.assertIn('"${TEMPLATE_DIR}/common/zygisk-status.sh"', packager)
        self.assertIn('"${OUT_DIR}/common/zygisk-status.sh"', packager)

    def test_effect_key_policy_is_dedicated_and_read_only(self) -> None:
        policy = executable_lines(SEPOLICY)
        post_fs = executable_lines(POST_FS)

        self.assertIn("type echidna_telemetry_key_file", policy)
        self.assertIn("typeattribute echidna_telemetry_key_file file_type", policy)
        self.assertIn(
            "allow audioserver echidna_telemetry_key_file file { getattr open read }",
            policy,
        )
        self.assertIn(
            "allow hal_audio_server echidna_telemetry_key_file file { getattr open read }",
            policy,
        )
        for live_rule in (
            'magiskpolicy --live "type echidna_telemetry_key_file"',
            'magiskpolicy --live "typeattribute echidna_telemetry_key_file file_type"',
            (
                'magiskpolicy --live "allow audioserver echidna_telemetry_key_file '
                'file { getattr open read }"'
            ),
            (
                'magiskpolicy --live "allow hal_audio_server echidna_telemetry_key_file '
                'file { getattr open read }"'
            ),
        ):
            self.assertIn(live_rule, post_fs)
        for broad_target in (
            "system_file",
            "vendor_file",
            "system_configs_file",
            "vendor_configs_file",
        ):
            with self.subTest(target=broad_target):
                self.assertNotIn(f"hal_audio_server {broad_target}", policy)
                self.assertNotIn(f"audioserver {broad_target}", policy)
        key_allow_lines = [
            line
            for line in policy.splitlines()
            if line.startswith("allow ") and " echidna_telemetry_key_file " in line
        ]
        self.assertEqual(2, len(key_allow_lines))
        for line in key_allow_lines:
            self.assertNotRegex(line, r"\b(?:write|append|execute|map|create|unlink)\b")
        self.assertNotIn("permissive ", policy)

        self.assertLess(
            post_fs.rfind("\napply_sepolicy"),
            post_fs.rfind("\nif ! prepare_effect_telemetry_key"),
        )
        self.assertLess(
            post_fs.rfind("\nif ! prepare_effect_telemetry_key"),
            post_fs.rfind("\nactivate_preprocessor_registration"),
        )

    def test_packager_and_runtime_require_effect_key_label_helper(self) -> None:
        self.assertTrue(TELEMETRY_KEY_LABEL.is_file())
        packager = PACKAGER.read_text(encoding="utf-8")
        customize = CUSTOMIZE.read_text(encoding="utf-8")
        post_fs = POST_FS.read_text(encoding="utf-8")
        trust = TRUST_BOOTSTRAP.read_text(encoding="utf-8")
        for source in (packager, customize, post_fs, trust):
            self.assertIn("telemetry-key-label.sh", source)
        self.assertIn("echidna_prepare_effect_telemetry_key", post_fs)
        self.assertIn("echidna_prepare_effect_telemetry_key", trust)

    def test_packager_requires_release_pin_and_dex_trust_helper(self) -> None:
        self.assertTrue(TRUST_BOOTSTRAP.is_file())
        packager = PACKAGER.read_text(encoding="utf-8")
        for token in (
            "RELEASE_CERT_SHA256 is required",
            "ECHIDNA_TRUST_MODE:-production",
            "known Android debug certificate",
            "echidna-trust-helper.jar",
            "classes.dex",
            "release-cert-sha256",
            "trust-mode",
        ):
            with self.subTest(token=token):
                self.assertIn(token, packager)

    def test_packager_guards_recursive_output_cleanup(self) -> None:
        packager = PACKAGER.read_text(encoding="utf-8")

        self.assertIn('require_repo_output_path "staging directory" "${OUT_DIR}"', packager)
        self.assertIn('require_repo_output_path "zip path" "${ZIP_PATH}"', packager)
        self.assertIn('"${root}/out/"*|"${root}/build/"*', packager)

    def test_disabled_audioflinger_offsets_are_not_packaged_or_staged(self) -> None:
        for path in (PACKAGER, POST_FS, SERVICE):
            with self.subTest(path=path):
                text = path.read_text(encoding="utf-8")
                self.assertNotIn("ECHIDNA_AF_OFFSETS", text)
                self.assertNotIn("echidna_af_offsets.txt", text)

    def test_zygisk_status_helper_reads_magisk_settings(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake_bin = Path(tmp)
            magisk = fake_bin / "magisk"
            magisk.write_text(
                "#!/bin/sh\n"
                "[ \"$1\" = --sqlite ] || exit 64\n"
                "printf 'value=%s\\n' \"${FAKE_ZYGISK_VALUE:-0}\"\n",
                encoding="utf-8",
            )
            magisk.chmod(0o755)

            for value, expected in (("1", 0), ("0", 1)):
                env = os.environ.copy()
                env["PATH"] = str(fake_bin) + os.pathsep + env.get("PATH", "")
                env["FAKE_ZYGISK_VALUE"] = value
                result = subprocess.run(
                    [
                        bash_executable(),
                        "-c",
                        '. "magisk/common/zygisk-status.sh" && echidna_zygisk_enabled',
                    ],
                    check=False,
                    cwd=REPO_ROOT,
                    env=env,
                    capture_output=True,
                    text=True,
                )
                with self.subTest(value=value):
                    self.assertEqual(expected, result.returncode, result.stderr)


def executable_lines(path: Path) -> str:
    return "\n".join(
        line for line in path.read_text(encoding="utf-8").splitlines() if not line.lstrip().startswith("#")
    )


def bash_executable() -> str:
    if os.name == "nt":
        msys_bash = Path("C:/msys64/usr/bin/bash.exe")
        if msys_bash.is_file():
            return str(msys_bash)
    bash = shutil.which("bash")
    if bash is None:
        raise unittest.SkipTest("bash is required for the Magisk helper test")
    return bash


if __name__ == "__main__":
    unittest.main()
