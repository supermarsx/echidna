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
