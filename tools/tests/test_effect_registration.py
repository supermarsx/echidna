#!/usr/bin/env python3
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
REGISTRATION = REPO_ROOT / "magisk" / "common" / "effect-registration.sh"


class EffectRegistrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.module = self.root / "module"
        self.android = self.root / "android"
        self.runtime = self.root / "runtime"
        self.bin = self.root / "bin"
        (self.module / "common").mkdir(parents=True)
        (self.module / "trust" / "next-boot").mkdir(parents=True)
        (self.module / "preproc" / "arm64-v8a").mkdir(parents=True)
        (self.module / "preproc" / "armeabi-v7a").mkdir(parents=True)
        self.bin.mkdir()
        (self.module / "common" / "echidna-trust-helper.jar").write_bytes(b"dex")
        (self.module / "trust" / "next-boot" / "preprocessor_controller_p256.spki").write_bytes(
            b"spki"
        )
        for abi in ("arm64-v8a", "armeabi-v7a"):
            (self.module / "preproc" / abi / "libechidna_preproc.so").write_bytes(b"elf")
        self.evidence = self.root / "factory.txt"
        self.arguments = self.root / "arguments.txt"
        self.app_process = self.root / "app_process"
        self.write_app_process(0)
        self.write_getprop()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_api34_hidl_vendor_xml_stages_exact_default_off_paths(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect@7.0::IEffectsFactory/default\n", encoding="utf-8"
        )
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertEqual(0, result.returncode, result.stderr)
        arguments = self.arguments.read_text(encoding="utf-8")
        self.assertIn("--source /vendor/etc/audio_effects.xml", arguments)
        self.assertIn("--config-output " + shell_path(
            self.module / "system" / "vendor" / "etc" / "audio_effects.xml"
        ), arguments)
        self.assertIn("--library-output " + shell_path(
            self.module / "system" / "vendor" / "lib64" / "soundfx" / "libechidna_preproc.so"
        ), arguments)
        self.assertIn("--active-key " + shell_path(
            self.module / "system" / "etc" / "echidna" / "preprocessor_controller_p256.spki"
        ), arguments)
        status = (self.runtime / "effect-registration" / "status.txt").read_text(encoding="utf-8")
        self.assertIn("state=staged-next-boot", status)
        self.assertIn("auto_apply=false", status)

    def test_api30_vendor_sku_precedes_vendor_default(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect@6.0::IEffectsFactory/default\n", encoding="utf-8"
        )
        self.write_config("vendor/etc/audio/sku_demo/audio_effects.xml")
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration(extra={"FAKE_SDK": "30", "FAKE_SKU": "demo"})
        self.assertEqual(0, result.returncode, result.stderr)
        arguments = self.arguments.read_text(encoding="utf-8")
        self.assertIn("--source /vendor/etc/audio/sku_demo/audio_effects.xml", arguments)
        self.assertIn("/system/vendor/etc/audio/sku_demo/audio_effects.xml", arguments)

    def test_api26_system_conf_and_32_bit_host_are_supported(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect@2.0::IEffectsFactory/default\n", encoding="utf-8"
        )
        self.write_config("system/etc/audio_effects.conf")
        result = self.run_registration(
            extra={
                "FAKE_SDK": "26",
                "FAKE_ABI": "armeabi-v7a",
                "ECHIDNA_EFFECT_HOST_BITS": "32",
            }
        )
        self.assertEqual(0, result.returncode, result.stderr)
        arguments = self.arguments.read_text(encoding="utf-8")
        self.assertIn("--format conf", arguments)
        self.assertIn("--abi armeabi-v7a", arguments)
        self.assertIn("/system/lib/soundfx/libechidna_preproc.so", arguments)

    def test_stable_aidl_only_fails_closed_without_calling_helper(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect.IFactory/default\n", encoding="utf-8"
        )
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertIn("Stable-AIDL-only", result.stdout)

    def test_hidl_and_aidl_ambiguity_fails_closed(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect@7.0::IEffectsFactory/default\n"
            "android.hardware.audio.effect.IFactory/default\n",
            encoding="utf-8",
        )
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertIn("ambiguous", result.stdout)

    def test_odm_config_refuses_lower_priority_vendor_fallthrough(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect@7.0::IEffectsFactory/default\n", encoding="utf-8"
        )
        self.write_config("odm/etc/audio_effects.xml")
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertIn("unsupported partition: /odm/etc/audio_effects.xml", result.stdout)

    def test_drift_exit_disables_module_and_preserves_outputs(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect@7.0::IEffectsFactory/default\n", encoding="utf-8"
        )
        self.write_config("vendor/etc/audio_effects.xml")
        preserved = self.module / "system" / "vendor" / "etc" / "audio_effects.xml"
        preserved.parent.mkdir(parents=True)
        preserved.write_text("preserve", encoding="utf-8")
        self.write_app_process(3)
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((self.module / "disable").is_file())
        self.assertEqual("preserve", preserved.read_text(encoding="utf-8"))
        status = (self.runtime / "effect-registration" / "status.txt").read_text(encoding="utf-8")
        self.assertIn("state=disabled-drift", status)

    def test_helper_failure_does_not_touch_overlay_outputs(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect@5.0::IEffectsFactory/default\n", encoding="utf-8"
        )
        self.write_config("vendor/etc/audio_effects.xml")
        self.write_app_process(2)
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.module / "system").exists())

    def write_config(self, relative: str) -> None:
        path = self.android / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("fixture", encoding="utf-8")

    def write_app_process(self, result: int) -> None:
        if result == 0:
            response = (
                "echo ECHIDNA_EFFECT_REGISTRATION_V1\n"
                "echo status=staged-next-boot\n"
                "echo reboot_required=true\n"
            )
        elif result == 3:
            response = "echo ECHIDNA_EFFECT_REGISTRATION_DRIFT=fingerprint-changed >&2\nexit 3\n"
        else:
            response = "echo ECHIDNA_EFFECT_REGISTRATION_ERROR=write-failed >&2\nexit 2\n"
        self.app_process.write_text(
            "#!/bin/sh\n"
            f"printf '%s\\n' \"$*\" > '{shell_path(self.arguments)}'\n"
            + response,
            encoding="utf-8",
        )
        self.app_process.chmod(0o755)

    def write_getprop(self) -> None:
        getprop = self.bin / "getprop"
        getprop.write_text(
            "#!/bin/sh\n"
            "case \"$1\" in\n"
            "  ro.build.version.sdk) echo \"${FAKE_SDK:-34}\" ;;\n"
            "  ro.boot.product.vendor.sku) echo \"${FAKE_SKU:-}\" ;;\n"
            "  ro.product.cpu.abilist) echo \"${FAKE_ABI:-arm64-v8a},armeabi-v7a\" ;;\n"
            "  ro.product.cpu.abi) echo \"${FAKE_ABI:-arm64-v8a}\" ;;\n"
            "  ro.build.fingerprint) echo vendor/device/build:fixture ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        getprop.chmod(0o755)

    def run_registration(self, extra: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": str(self.bin) + os.pathsep + environment.get("PATH", ""),
                "ECHIDNA_RUNTIME_DIR": shell_path(self.runtime),
                "ECHIDNA_ANDROID_ROOT": shell_path(self.android),
                "ECHIDNA_APP_PROCESS": shell_path(self.app_process),
                "ECHIDNA_EFFECT_FACTORY_EVIDENCE": shell_path(self.evidence),
                "ECHIDNA_EFFECT_HOST_BITS": "64",
                "ECHIDNA_EFFECT_ABI": "arm64-v8a",
                "ECHIDNA_BUILD_FINGERPRINT": "vendor/device/build:fixture",
            }
        )
        if extra:
            environment.update(extra)
            if extra.get("FAKE_ABI") == "armeabi-v7a":
                environment["ECHIDNA_EFFECT_ABI"] = "armeabi-v7a"
        return subprocess.run(
            [bash_executable(), shell_path(REGISTRATION), shell_path(self.module)],
            cwd=REPO_ROOT,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )


def bash_executable() -> str:
    if os.name == "nt":
        msys = Path("C:/msys64/usr/bin/bash.exe")
        if msys.is_file():
            return str(msys)
    bash = shutil.which("bash")
    if bash is None:
        raise unittest.SkipTest("bash is required")
    return bash


def shell_path(path: Path) -> str:
    resolved = path.resolve()
    if os.name == "nt":
        drive = resolved.drive.rstrip(":").lower()
        return f"/{drive}{resolved.as_posix()[2:]}"
    return resolved.as_posix()


if __name__ == "__main__":
    unittest.main()
