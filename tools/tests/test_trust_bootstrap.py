#!/usr/bin/env python3
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
BOOTSTRAP = REPO_ROOT / "magisk" / "common" / "trust-bootstrap.sh"
SERVICE = REPO_ROOT / "android" / "control-service" / "magisk" / "service.sh"
PACKAGER = REPO_ROOT / "tools" / "build_magisk_module.sh"
SCHEMA = REPO_ROOT / "native" / "effects" / "legacy" / "capability_schema.md"


class TrustBootstrapTest(unittest.TestCase):
    def test_late_service_invokes_nonfatal_bootstrap(self) -> None:
        service = SERVICE.read_text(encoding="utf-8")
        self.assertIn('TRUST_BOOTSTRAP="$MODDIR/common/trust-bootstrap.sh"', service)
        self.assertIn('if ! "$TRUST_BOOTSTRAP" "$MODDIR"; then', service)
        self.assertLess(service.index("clear_boot_watchdog"), service.index("bootstrap_preprocessor_trust"))

    def test_success_records_next_boot_without_hot_replacing_active_key(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = root / "module"
            common = module / "common"
            common.mkdir(parents=True)
            (common / "echidna-trust-helper.jar").write_bytes(b"dex-container")
            (common / "release-cert-sha256").write_text("ab" * 32 + "\n", encoding="ascii")
            (common / "trust-mode").write_text("production\n", encoding="ascii")
            app_process = root / "app_process"
            app_process.write_text(
                "#!/bin/sh\n"
                "echo ECHIDNA_TRUST_V1\n"
                "echo status=pinned-next-boot\n"
                "echo reboot_required=true\n",
                encoding="utf-8",
            )
            app_process.chmod(0o755)
            runtime = root / "runtime"
            result = self.run_bootstrap(module, runtime, app_process)
            self.assertEqual(0, result.returncode, result.stderr)
            status = (runtime / "trust" / "status.txt").read_text(encoding="utf-8")
            self.assertIn("state=pinned-next-boot", status)
            self.assertIn("reboot_required=true", status)
            self.assertFalse((module / "system" / "etc" / "echidna").exists())
            self.assertIn("reboot is required", result.stdout)

    def test_helper_failure_is_precise_and_non_destructive(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = root / "module"
            common = module / "common"
            common.mkdir(parents=True)
            (common / "echidna-trust-helper.jar").write_bytes(b"dex-container")
            (common / "release-cert-sha256").write_text("ab" * 32 + "\n", encoding="ascii")
            (common / "trust-mode").write_text("production\n", encoding="ascii")
            app_process = root / "app_process"
            app_process.write_text(
                "#!/bin/sh\n"
                "echo 'ECHIDNA_TRUST_ERROR=signer mismatch' >&2\n"
                "exit 2\n",
                encoding="utf-8",
            )
            app_process.chmod(0o755)
            runtime = root / "runtime"
            result = self.run_bootstrap(module, runtime, app_process)
            self.assertNotEqual(0, result.returncode)
            status = (runtime / "trust" / "status.txt").read_text(encoding="utf-8")
            self.assertIn("state=failed", status)
            self.assertIn("Identity bypass remains active", result.stdout)
            self.assertIn("disable the module", result.stdout)

    def test_packager_and_schema_share_exact_destination_contract(self) -> None:
        packager = PACKAGER.read_text(encoding="utf-8")
        schema = SCHEMA.read_text(encoding="utf-8")
        bootstrap = BOOTSTRAP.read_text(encoding="utf-8")
        self.assertIn("RELEASE_CERT_SHA256 is required", packager)
        self.assertIn("forbidden characters or a wildcard", packager)
        self.assertIn("production module refuses the known Android debug certificate", packager)
        self.assertIn("echidna-trust-helper.jar", packager)
        self.assertIn("trust/next-boot", bootstrap)
        fixed = "/system/etc/echidna/preprocessor_controller_p256.spki"
        self.assertIn(fixed, schema)
        self.assertIn("/system/etc/echidna/$KEY_NAME", bootstrap)
        self.assertNotIn("killall audioserver", bootstrap)
        self.assertNotIn("setprop ctl.restart", bootstrap)

    def run_bootstrap(
        self, module: Path, runtime: Path, app_process: Path
    ) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["ECHIDNA_RUNTIME_DIR"] = shell_path(runtime)
        env["ECHIDNA_APP_PROCESS"] = shell_path(app_process)
        return subprocess.run(
            [bash_executable(), shell_path(BOOTSTRAP), shell_path(module)],
            cwd=REPO_ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )


def bash_executable() -> str:
    if os.name == "nt":
        msys_bash = Path("C:/msys64/usr/bin/bash.exe")
        if msys_bash.is_file():
            return str(msys_bash)
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
