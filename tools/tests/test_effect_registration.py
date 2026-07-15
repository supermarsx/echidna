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
FACTORY_PID = 4242


class EffectRegistrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.module = self.root / "module"
        self.android = self.root / "android"
        self.runtime = self.root / "runtime"
        self.proc = self.root / "proc"
        self.bin = self.root / "bin"
        (self.module / "common").mkdir(parents=True)
        (self.module / "trust" / "next-boot").mkdir(parents=True)
        for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
            (self.module / "preproc" / abi).mkdir(parents=True)
            (self.module / "preproc" / abi / "libechidna_preproc.so").write_bytes(b"elf")
        self.bin.mkdir()
        (self.module / "common" / "echidna-trust-helper.jar").write_bytes(b"dex")
        (self.module / "trust" / "next-boot" / "preprocessor_controller_p256.spki").write_bytes(
            b"spki"
        )
        self.evidence = self.root / "factory.txt"
        self.arguments = self.root / "arguments.txt"
        self.app_process = self.root / "app_process"
        self.write_process(FACTORY_PID, "arm64-v8a")
        self.write_evidence("7.0", FACTORY_PID)
        self.write_app_process(0)
        self.write_getprop()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_api34_registered_generic_vendor_host_stages_only_inert_config(self) -> None:
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        arguments = self.arguments.read_text(encoding="utf-8")
        self.assertIn("--source /vendor/etc/audio_effects.xml", arguments)
        self.assertIn(
            "--inert-config "
            + shell_path(
                self.module
                / "registration"
                / "next-boot"
                / "config"
                / "vendor"
                / "etc"
                / "audio_effects.xml"
            ),
            arguments,
        )
        self.assertIn(
            "--transient-config "
            + shell_path(self.module / "system" / "vendor" / "etc" / "audio_effects.xml"),
            arguments,
        )
        self.assertIn(
            "--metadata "
            + shell_path(self.module / "registration" / "next-boot" / "state-v2"),
            arguments,
        )
        self.assertIn(
            "--library-output "
            + shell_path(
                self.module
                / "system"
                / "vendor"
                / "lib64"
                / "soundfx"
                / "libechidna_preproc.so"
            ),
            arguments,
        )
        self.assertFalse((self.module / "system" / "vendor" / "etc").exists())
        status = (self.runtime / "effect-registration" / "status.txt").read_text(
            encoding="utf-8"
        )
        self.assertIn("state=staged-next-boot", status)
        self.assertIn("auto_apply=false", status)

    def test_vendor_cohosted_factory_versions_with_one_registered_pid_are_supported(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio@7.1::IDevicesFactory/default 4242\n"
            "android.hardware.audio.effect@6.0::IEffectsFactory/default 4242\n"
            "android.hardware.audio.effect@7.0::IEffectsFactory/default 4242\n",
            encoding="utf-8",
        )
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_api30_vendor_sku_precedes_vendor_default(self) -> None:
        self.write_evidence("6.0", FACTORY_PID)
        self.write_config("vendor/etc/audio/sku_demo/audio_effects.xml")
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration(extra={"FAKE_SDK": "30", "FAKE_SKU": "demo"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        arguments = self.arguments.read_text(encoding="utf-8")
        self.assertIn("--source /vendor/etc/audio/sku_demo/audio_effects.xml", arguments)
        self.assertIn(
            "/registration/next-boot/config/vendor/etc/audio/sku_demo/audio_effects.xml",
            arguments,
        )

    def test_api26_system_conf_and_32_bit_registered_host_are_supported(self) -> None:
        self.write_evidence("2.0", FACTORY_PID)
        self.write_process(FACTORY_PID, "armeabi-v7a")
        self.write_config("system/etc/audio_effects.conf")
        result = self.run_registration(extra={"FAKE_SDK": "26", "FAKE_ABI": "armeabi-v7a"})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        arguments = self.arguments.read_text(encoding="utf-8")
        self.assertIn("--format conf", arguments)
        self.assertIn("--abi armeabi-v7a", arguments)
        self.assertIn("/system/lib/soundfx/libechidna_preproc.so", arguments)

    def test_stable_aidl_only_fails_closed_without_calling_helper(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect.IFactory/default: "
            "[android.hardware.audio.effect.IFactory]\n",
            encoding="utf-8",
        )
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertIn("Stable-AIDL-only", result.stdout)

    def test_hidl_and_aidl_ambiguity_fails_closed(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect@7.0::IEffectsFactory/default 4242\n"
            "android.hardware.audio.effect.IFactory/default: "
            "[android.hardware.audio.effect.IFactory]\n",
            encoding="utf-8",
        )
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertIn("ambiguous", result.stdout)

    def test_manifest_or_lshal_row_without_registered_pid_is_not_evidence(self) -> None:
        self.evidence.write_text(
            "android.hardware.audio.effect@7.0::IEffectsFactory/default N/A\n",
            encoding="utf-8",
        )
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertIn("no registered", result.stdout)

    def test_multiple_registered_factory_pids_fail_closed_as_ambiguous(self) -> None:
        self.write_process(4343, "arm64-v8a")
        self.evidence.write_text(
            "android.hardware.audio.effect@6.0::IEffectsFactory/default 4242\n"
            "android.hardware.audio.effect@7.0::IEffectsFactory/default 4343\n",
            encoding="utf-8",
        )
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertIn("ambiguous", result.stdout)

    def test_unverified_registered_pid_maps_fail_closed(self) -> None:
        (self.proc / str(FACTORY_PID) / "maps").write_text("unrelated\n", encoding="utf-8")
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertIn("could not be proven", result.stdout)

    def test_odm_config_refuses_lower_priority_vendor_fallthrough(self) -> None:
        self.write_config("odm/etc/audio_effects.xml")
        self.write_config("vendor/etc/audio_effects.xml")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertIn("unsupported partition: /odm/etc/audio_effects.xml", result.stdout)

    def test_drift_marks_restage_without_disabling_module(self) -> None:
        self.write_config("vendor/etc/audio_effects.xml")
        self.write_app_process(3)
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((self.module / "registration" / "restage-required").is_file())
        self.assertFalse((self.module / "disable").exists())
        status = (self.runtime / "effect-registration" / "status.txt").read_text(
            encoding="utf-8"
        )
        self.assertIn("state=restage-required", status)

    def test_legacy_v1_state_requires_restage_without_invoking_helper(self) -> None:
        self.write_config("vendor/etc/audio_effects.xml")
        (self.module / "registration").mkdir()
        (self.module / "registration" / "state-v1").write_text("version=1\n", encoding="utf-8")
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.arguments.exists())
        self.assertTrue((self.module / "registration" / "restage-required").is_file())

    def test_helper_failure_does_not_create_transient_config(self) -> None:
        self.write_config("vendor/etc/audio_effects.xml")
        self.write_app_process(2)
        result = self.run_registration()
        self.assertNotEqual(0, result.returncode)
        transient = self.module / "system" / "vendor" / "etc" / "audio_effects.xml"
        self.assertFalse(transient.exists())

    def write_config(self, relative: str) -> None:
        path = self.android / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("fixture", encoding="utf-8")

    def write_evidence(self, version: str, pid: int) -> None:
        self.evidence.write_text(
            f"android.hardware.audio.effect@{version}::IEffectsFactory/default {pid}\n",
            encoding="utf-8",
        )

    def write_process(self, pid: int, abi: str) -> None:
        identities = {
            "arm64-v8a": (2, 183),
            "armeabi-v7a": (1, 40),
            "x86_64": (2, 62),
        }
        elf_class, machine = identities[abi]
        process = self.proc / str(pid)
        process.mkdir(parents=True, exist_ok=True)
        executable = bytearray(64)
        executable[:4] = b"\x7fELF"
        executable[4] = elf_class
        executable[5] = 1
        executable[18:20] = machine.to_bytes(2, "little")
        (process / "exe").write_bytes(executable)
        resolved_executable = (process / "exe").resolve().as_posix()
        (process / "maps").write_text(
            f"70000000-70001000 r-xp 00000000 00:00 1 {resolved_executable}\n",
            encoding="utf-8",
        )
        fields_after_comm = ["S"] + ["0"] * 18 + ["123456"]
        (process / "stat").write_text(
            f"{pid} (generic.vendor.audio-service) {' '.join(fields_after_comm)}\n",
            encoding="utf-8",
        )

    def write_app_process(self, result: int) -> None:
        if result == 0:
            response = (
                "echo ECHIDNA_EFFECT_REGISTRATION_V2\n"
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

    def run_registration(
        self, extra: dict[str, str] | None = None
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": str(self.bin) + os.pathsep + environment.get("PATH", ""),
                "ECHIDNA_RUNTIME_DIR": shell_path(self.runtime),
                "ECHIDNA_ANDROID_ROOT": shell_path(self.android),
                "ECHIDNA_PROC_ROOT": shell_path(self.proc),
                "ECHIDNA_TEST_ALLOW_PROC_EXE": "1",
                "ECHIDNA_APP_PROCESS": shell_path(self.app_process),
                "ECHIDNA_EFFECT_FACTORY_EVIDENCE": shell_path(self.evidence),
                "ECHIDNA_BUILD_FINGERPRINT": "vendor/device/build:fixture",
            }
        )
        if extra:
            environment.update(extra)
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
