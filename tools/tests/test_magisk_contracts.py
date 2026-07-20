#!/usr/bin/env python3
from __future__ import annotations

import os
import re
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
PRIVILEGED_CONTROLLER = (
    REPO_ROOT
    / "android"
    / "control-service"
    / "service"
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "echidna"
    / "control"
    / "service"
    / "PrivilegedController.kt"
)


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

    def test_effect_trust_policy_is_dedicated_and_read_only(self) -> None:
        policy = executable_lines(SEPOLICY)
        post_fs = executable_lines(POST_FS)

        trust_types = ("echidna_telemetry_key_file", "echidna_controller_spki_file")
        exact_allows = {
            f"allow {domain} {trust_type} file {{ getattr open read }}"
            for domain in ("audioserver", "hal_audio_server")
            for trust_type in trust_types
        }
        declarations = {
            declaration
            for trust_type in trust_types
            for declaration in (
                f"type {trust_type}",
                f"typeattribute {trust_type} file_type",
            )
        }
        for rule in declarations | exact_allows:
            with self.subTest(rule=rule):
                self.assertIn(rule, policy)
                self.assertIn(f'magiskpolicy --live "{rule}"', post_fs)
        for broad_target in (
            "system_file",
            "vendor_file",
            "system_configs_file",
            "vendor_configs_file",
        ):
            with self.subTest(target=broad_target):
                self.assertNotIn(f"hal_audio_server {broad_target}", policy)
                self.assertNotIn(f"audioserver {broad_target}", policy)
        trust_allow_lines = [
            line
            for line in policy.splitlines()
            if line.startswith("allow ")
            and any(f" {trust_type} " in line for trust_type in trust_types)
        ]
        self.assertEqual(exact_allows, set(trust_allow_lines))
        for line in trust_allow_lines:
            self.assertNotRegex(line, r"\b(?:write|append|execute|map|create|unlink)\b")
        self.assertNotIn("permissive ", policy)

        self.assertLess(
            post_fs.rfind("\napply_sepolicy"),
            post_fs.rfind("\nif ! prepare_effect_trust"),
        )
        self.assertLess(
            post_fs.rfind("\nif ! prepare_effect_trust"),
            post_fs.rfind("\nactivate_preprocessor_registration"),
        )

    def test_packager_and_runtime_require_combined_effect_trust_helper(self) -> None:
        self.assertTrue(TELEMETRY_KEY_LABEL.is_file())
        packager = PACKAGER.read_text(encoding="utf-8")
        customize = CUSTOMIZE.read_text(encoding="utf-8")
        post_fs = POST_FS.read_text(encoding="utf-8")
        trust = TRUST_BOOTSTRAP.read_text(encoding="utf-8")
        registration = (
            REPO_ROOT / "magisk" / "common" / "effect-registration.sh"
        ).read_text(encoding="utf-8")
        helper = TELEMETRY_KEY_LABEL.read_text(encoding="utf-8")
        for source in (packager, customize, post_fs, trust, registration):
            self.assertIn("telemetry-key-label.sh", source)
        for source in (post_fs, trust, registration, helper):
            self.assertIn("echidna_prepare_effect_trust", source)
        self.assertIn("controller_spki_sha256", trust)
        for token in (
            "trust/next-boot/preprocessor_controller_p256.spki",
            "system/etc/echidna/preprocessor_controller_p256.spki",
            "u:object_r:echidna_controller_spki_file:s0",
            "ECHIDNA_CONTROLLER_SPKI_BYTES=91",
            'ECHIDNA_CONTROLLER_SPKI_OWNER_MODE="0:0:444"',
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004",
            "chcon",
            "ls -Zd",
        ):
            with self.subTest(token=token):
                self.assertIn(token, helper)

    def test_any_staged_or_live_registration_requires_both_trust_pairs(self) -> None:
        post_fs = POST_FS.read_text(encoding="utf-8")
        for token in (
            'EFFECT_TRUST_PRESENCE=optional',
            '"$MODDIR/registration"',
            'libechidna_preproc.so',
            'audio_effects.xml',
            'EFFECT_TRUST_PRESENCE=required',
            'echidna_prepare_effect_trust "$MODDIR" "" "" "$EFFECT_TRUST_PRESENCE"',
        ):
            self.assertIn(token, post_fs)
        detect = post_fs.rfind("\nif effect_registration_present; then\n")
        cleanup = post_fs.rfind("\ndiscard_stale_preprocessor_activation\n")
        prepare = post_fs.rfind("\nif ! prepare_effect_trust; then\n")
        activate = post_fs.rfind("\nactivate_preprocessor_registration\n")
        self.assertGreaterEqual(detect, 0)
        self.assertLess(detect, cleanup)
        self.assertLess(cleanup, prepare)
        self.assertLess(prepare, activate)

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

    def test_installer_and_boot_script_do_not_demand_magisk_builtin_zygisk(self) -> None:
        # ReZygisk / Zygisk Next run with Magisk's built-in Zygisk switched off,
        # so "enable it in Magisk" is not advice either script may give.
        for path in (CUSTOMIZE, POST_FS):
            with self.subTest(path=path):
                text = executable_lines(path)
                self.assertNotIn("enable it in Magisk", text)
                self.assertIn("ECHIDNA_ZYGISK_IMPL", text)

    def test_installer_zygisk_reminder_is_conditional_on_the_implementation(self) -> None:
        # The reminder used to print unconditionally, which told ReZygisk /
        # Zygisk Next users to turn on the very toggle those projects require
        # to stay off. Every mention of the Magisk toggle must now sit behind
        # the implementation switch.
        customize = executable_lines(CUSTOMIZE)
        self.assertNotIn(
            "Reminder: enable Zygisk in the Magisk app if not already on.",
            customize,
        )
        switch = customize.index('case "${ECHIDNA_ZYGISK_IMPL:-}" in')
        for index, line in enumerate(customize.splitlines()):
            if "enable Zygisk in the Magisk app" in line:
                with self.subTest(line=index):
                    self.assertLess(switch, customize.index(line))
        standalone = [
            line
            for line in customize.splitlines()
            if "Leave Magisk's built-in Zygisk off" in line
        ]
        self.assertEqual(1, len(standalone))

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


class ZygiskStatusProbeTest(unittest.TestCase):
    """Drives magisk/common/zygisk-status.sh against fixture module trees.

    The fixtures prove only what they contain: that the probe recognizes the
    module identifiers ReZygisk and Zygisk Next document for themselves. No
    part of this suite runs against a real device or a real standalone Zygisk
    build.
    """

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.modules = self.root / "modules"
        self.modules.mkdir()
        self.fake_bin = self.root / "bin"
        self.fake_bin.mkdir()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_magisk(self, zygisk_value: str) -> None:
        magisk = self.fake_bin / "magisk"
        magisk.write_text(
            "#!/bin/sh\n"
            '[ "$1" = --sqlite ] || exit 64\n'
            "printf 'value=%s\\n' \"${FAKE_ZYGISK_VALUE:-0}\"\n",
            encoding="utf-8",
        )
        magisk.chmod(0o755)
        self.zygisk_value = zygisk_value

    def write_module(self, module_id: str, prop: str, disabled: bool = False) -> None:
        directory = self.modules / module_id
        directory.mkdir()
        (directory / "module.prop").write_text(prop, encoding="utf-8")
        if disabled:
            (directory / "disable").write_text("", encoding="utf-8")

    def probe(self) -> tuple[int, str]:
        env = os.environ.copy()
        if (self.fake_bin / "magisk").exists():
            env["PATH"] = str(self.fake_bin) + os.pathsep + env.get("PATH", "")
            env["FAKE_ZYGISK_VALUE"] = self.zygisk_value
        env["ECHIDNA_ZYGISK_MODULES_DIR"] = shell_path(self.modules)
        result = subprocess.run(
            [
                bash_executable(),
                "-c",
                '. "magisk/common/zygisk-status.sh"\n'
                "echidna_zygisk_enabled\n"
                "status=$?\n"
                'printf "%s\\n" "$ECHIDNA_ZYGISK_IMPL"\n'
                "exit $status\n",
            ],
            check=False,
            cwd=REPO_ROOT,
            env=env,
            capture_output=True,
            text=True,
        )
        return result.returncode, result.stdout.strip()

    def test_magisk_builtin_enabled_reports_builtin_implementation(self) -> None:
        self.write_magisk("1")
        self.assertEqual((0, "magisk-builtin"), self.probe())

    def test_rezygisk_module_is_detected_when_builtin_is_off(self) -> None:
        self.write_magisk("0")
        self.write_module(
            "rezygisk",
            "id=rezygisk\nname=ReZygisk\nversion=v1.0.0\n",
        )
        self.assertEqual((0, "standalone"), self.probe())

    def test_zygisk_next_module_is_detected_when_builtin_is_off(self) -> None:
        self.write_magisk("0")
        self.write_module(
            "zygisksu",
            "id=zygisksu\nname=Zygisk Next\nversion=1.2.3\n",
        )
        self.assertEqual((0, "standalone"), self.probe())

    def test_each_documented_identifier_form_is_recognized(self) -> None:
        forms = {
            "id": "id=rezygisk\nname=Something Else\ndescription=whatever\n",
            "name": "id=unrelated\nname=Zygisk Next\ndescription=whatever\n",
            "description": (
                "id=unrelated\nname=Something Else\n"
                "description=Standalone implementation of Zygisk.\n"
            ),
        }
        for form, prop in forms.items():
            with self.subTest(form=form):
                # Each form needs a pristine module tree; the final fixture is
                # released by the regular tearDown.
                self.tearDown()
                self.setUp()
                self.write_magisk("0")
                self.write_module("candidate", prop)
                self.assertEqual((0, "standalone"), self.probe())

    def test_disabled_standalone_module_is_not_counted(self) -> None:
        self.write_magisk("0")
        self.write_module(
            "rezygisk",
            "id=rezygisk\nname=ReZygisk\n",
            disabled=True,
        )
        self.assertEqual((1, ""), self.probe())

    def test_no_builtin_and_no_standalone_reports_not_enabled(self) -> None:
        self.write_magisk("0")
        self.write_module("echidna", "id=echidna\nname=Echidna\n")
        self.assertEqual((1, ""), self.probe())

    def test_missing_magisk_command_cannot_determine_state(self) -> None:
        self.assertEqual((2, ""), self.probe())

    def test_missing_magisk_command_still_detects_standalone_module(self) -> None:
        self.write_module("rezygisk", "id=rezygisk\nname=ReZygisk\n")
        self.assertEqual((0, "standalone"), self.probe())


class ZygiskIdentifierContractTest(unittest.TestCase):
    """The shell probe and the app probe must recognize the same identifiers.

    Drift between them is the defect this suite exists to prevent: the app
    reported Zygisk as present while the installer told the user to enable it
    in Magisk, advice that conflicts with a standalone implementation.
    """

    def test_shell_and_kotlin_probes_share_the_identifier_set(self) -> None:
        kotlin = PRIVILEGED_CONTROLLER.read_text(encoding="utf-8")
        constants = dict(
            re.findall(r'private const val (\w+) = "([^"]*)"', kotlin)
        )
        resolved = re.sub(
            r"\$\{(\w+)\}",
            lambda match: constants.get(match.group(1), match.group(0)),
            kotlin,
        )
        kotlin_patterns = set(re.findall(r"grep -Eiq '([^']+)'", resolved))
        shell_patterns = set(
            re.findall(
                r"grep -Eiq '([^']+)'",
                ZYGISK_STATUS.read_text(encoding="utf-8"),
            )
        )
        self.assertEqual(
            {
                "^id=(zygisksu|rezygisk)",
                "^name=.*(Zygisk Next|ReZygisk)",
                "^description=.*Standalone implementation of Zygisk",
            },
            kotlin_patterns,
        )
        self.assertEqual(kotlin_patterns, shell_patterns)

    def test_both_probes_skip_modules_carrying_the_disable_marker(self) -> None:
        for path in (PRIVILEGED_CONTROLLER, ZYGISK_STATUS):
            with self.subTest(path=path):
                self.assertIn("/disable", path.read_text(encoding="utf-8"))


def shell_path(path: Path) -> str:
    resolved = path.resolve()
    if os.name == "nt":
        drive = resolved.drive.rstrip(":").lower()
        return f"/{drive}{resolved.as_posix()[2:]}"
    return resolved.as_posix()


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
