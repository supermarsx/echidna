#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
ACTIVATION = REPO_ROOT / "magisk" / "common" / "effect-activation.sh"
FINGERPRINT = "vendor/device/build:fixture"
TYPE_UUID = "c83e3db3-d4f5-5f2c-a095-8775c1edfc6d"
IMPLEMENTATION_UUID = "3e66a36e-dee9-5d81-a0d6-49fc3b863530"


class EffectActivationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.module = self.root / "module"
        self.android = self.root / "android"
        self.runtime = self.root / "runtime"
        self.bin = self.root / "bin"
        self.source = self.android / "vendor" / "etc" / "audio_effects.xml"
        self.inert = (
            self.module
            / "registration"
            / "next-boot"
            / "config"
            / "vendor"
            / "etc"
            / "audio_effects.xml"
        )
        self.transient = self.module / "system" / "vendor" / "etc" / "audio_effects.xml"
        self.library = (
            self.module
            / "system"
            / "vendor"
            / "lib64"
            / "soundfx"
            / "libechidna_preproc.so"
        )
        self.key = (
            self.module / "system" / "etc" / "echidna" / "preprocessor_controller_p256.spki"
        )
        self.metadata = self.module / "registration" / "next-boot" / "state-v2"
        self.bin.mkdir(parents=True)
        for path in (self.source, self.inert, self.library, self.key):
            path.parent.mkdir(parents=True, exist_ok=True)
        self.original = {
            self.source: b"<audio_effects_conf><libraries/><effects/></audio_effects_conf>\n",
            self.inert: b"<audio_effects_conf><libraries/><effects echidna='true'/></audio_effects_conf>\n",
            self.library: b"verified-preprocessor-library",
            self.key: b"verified-controller-spki",
        }
        for path, contents in self.original.items():
            path.write_bytes(contents)
        self.inert.chmod(0o644)
        self.library.chmod(0o644)
        self.key.chmod(0o444)
        self.write_metadata()
        self.write_getprop()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_early_validation_success_is_idempotent(self) -> None:
        first = self.run_activation()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self.assertEqual(self.inert.read_bytes(), self.transient.read_bytes())
        second = self.run_activation()
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertEqual(self.inert.read_bytes(), self.transient.read_bytes())
        status = (self.runtime / "effect-registration" / "activation-status.txt").read_text(
            encoding="utf-8"
        )
        self.assertIn("state=approved-for-magisk-mount", status)

    def test_first_boot_after_ota_never_leaves_mountable_config(self) -> None:
        self.write_stale_transient()
        result = self.run_activation(extra={"ECHIDNA_BUILD_FINGERPRINT": "new/ota/fingerprint"})
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.transient.exists())
        self.assertNoTemporaryConfig()
        status = (self.runtime / "effect-registration" / "activation-status.txt").read_text(
            encoding="utf-8"
        )
        self.assertIn("state=not-activated", status)
        self.assertIn("fingerprint changed", status)

    def test_stock_source_drift_removes_crash_leftover_before_failure(self) -> None:
        self.source.write_bytes(b"post-ota-stock-config")
        self.write_stale_transient()
        result = self.run_activation()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.transient.exists())
        self.assertNoTemporaryConfig()

    def test_each_staged_artifact_hash_failure_leaves_no_transient(self) -> None:
        for path in (self.inert, self.library, self.key):
            with self.subTest(path=path.name):
                path.chmod(0o644)
                path.write_bytes(b"tampered")
                self.write_stale_transient()
                result = self.run_activation()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.transient.exists())
                self.assertNoTemporaryConfig()
                path.write_bytes(self.original[path])
                if path == self.key:
                    path.chmod(0o444)

    def test_path_contract_failure_removes_stale_transient(self) -> None:
        metadata = self.metadata.read_text(encoding="ascii")
        metadata = metadata.replace(
            "transient_config=" + shell_path(self.transient),
            "transient_config=" + shell_path(self.module / "system" / "etc" / "wrong.xml"),
        )
        self.metadata.chmod(0o644)
        self.metadata.write_bytes(metadata.replace("\r\n", "\n").encode("ascii"))
        self.metadata.chmod(0o444)
        self.write_stale_transient()
        result = self.run_activation()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.transient.exists())
        self.assertNoTemporaryConfig()

    def test_restage_marker_removes_crash_leftover_before_refusal(self) -> None:
        marker = self.module / "registration" / "restage-required"
        marker.write_bytes(b"explicit restage required\n")
        self.write_stale_transient()
        result = self.run_activation()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.transient.exists())
        self.assertNoTemporaryConfig()

    def test_interrupted_copy_rolls_back_partial_temporary_and_transient(self) -> None:
        fake_cp = self.bin / "cp"
        fake_cp.write_bytes(
            b"#!/bin/sh\n"
            b"printf 'partial-copy' > \"$2\"\n"
            b"exit 1\n"
        )
        fake_cp.chmod(0o755)
        result = self.run_activation()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.transient.exists())
        self.assertNoTemporaryConfig()

    def test_cleanup_is_idempotent_and_preserves_inert_state(self) -> None:
        self.assertEqual(0, self.run_activation().returncode)
        first = self.run_activation(action="cleanup")
        second = self.run_activation(action="cleanup")
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertFalse(self.transient.exists())
        self.assertEqual(self.original[self.inert], self.inert.read_bytes())
        self.assertTrue(self.metadata.is_file())

    def test_missing_metadata_cannot_expose_stale_config(self) -> None:
        self.metadata.chmod(0o644)
        self.metadata.unlink()
        self.write_stale_transient()
        result = self.run_activation()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.transient.exists())
        self.assertNoTemporaryConfig()

    def write_metadata(self) -> None:
        metadata = (
            "version=2\n"
            "source_path=/vendor/etc/audio_effects.xml\n"
            f"inert_config={shell_path(self.inert)}\n"
            f"transient_config={shell_path(self.transient)}\n"
            f"library_output={shell_path(self.library)}\n"
            f"active_key={shell_path(self.key)}\n"
            "partition=vendor\n"
            "bits=64\n"
            "abi=arm64-v8a\n"
            "format=xml\n"
            f"source_sha256={digest(self.original[self.source])}\n"
            f"overlay_sha256={digest(self.original[self.inert])}\n"
            f"library_sha256={digest(self.original[self.library])}\n"
            f"key_sha256={digest(self.original[self.key])}\n"
            f"fingerprint={FINGERPRINT}\n"
            f"type_uuid={TYPE_UUID}\n"
            f"implementation_uuid={IMPLEMENTATION_UUID}\n"
            "auto_apply=false\n"
        )
        self.metadata.write_bytes(metadata.encode("ascii"))
        self.metadata.chmod(0o444)

    def write_getprop(self) -> None:
        getprop = self.bin / "getprop"
        getprop.write_bytes(
            b"#!/bin/sh\n"
            b"[ \"$1\" = ro.build.fingerprint ] && "
            b"echo \"${ECHIDNA_BUILD_FINGERPRINT:-vendor/device/build:fixture}\"\n"
        )
        getprop.chmod(0o755)

    def write_stale_transient(self) -> None:
        self.transient.parent.mkdir(parents=True, exist_ok=True)
        self.transient.write_bytes(b"stale-previous-boot-config")

    def assertNoTemporaryConfig(self) -> None:
        self.assertEqual([], list(self.transient.parent.glob("audio_effects.xml.tmp.*")))

    def run_activation(
        self,
        action: str = "activate",
        extra: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": str(self.bin) + os.pathsep + environment.get("PATH", ""),
                "ECHIDNA_RUNTIME_DIR": shell_path(self.runtime),
                "ECHIDNA_ANDROID_ROOT": shell_path(self.android),
                "ECHIDNA_TEST_ALLOW_NONROOT": "1",
                "ECHIDNA_BUILD_FINGERPRINT": FINGERPRINT,
            }
        )
        if extra:
            environment.update(extra)
        return subprocess.run(
            [bash_executable(), shell_path(ACTIVATION), shell_path(self.module), action],
            cwd=REPO_ROOT,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )


def digest(contents: bytes) -> str:
    return hashlib.sha256(contents).hexdigest()


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
