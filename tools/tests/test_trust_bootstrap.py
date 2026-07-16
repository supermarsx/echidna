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
BOOTSTRAP = REPO_ROOT / "magisk" / "common" / "trust-bootstrap.sh"
SERVICE = REPO_ROOT / "android" / "control-service" / "magisk" / "service.sh"
PACKAGER = REPO_ROOT / "tools" / "build_magisk_module.sh"
SCHEMA = REPO_ROOT / "native" / "effects" / "legacy" / "capability_schema.md"
TRUST_MAIN = (
    REPO_ROOT
    / "android"
    / "control-service"
    / "trust-helper"
    / "src"
    / "main"
    / "java"
    / "com"
    / "echidna"
    / "magisk"
    / "TrustBootstrapMain.java"
)
LABEL_HELPER = REPO_ROOT / "magisk" / "common" / "telemetry-key-label.sh"
TELEMETRY_KEY_BYTES = b"echidna-telemetry-label-fixture!"
TELEMETRY_SHA256 = hashlib.sha256(TELEMETRY_KEY_BYTES).hexdigest()
TELEMETRY_KEY_ID = TELEMETRY_SHA256[:16]
SPKI_PREFIX = bytes.fromhex(
    "3059301306072a8648ce3d020106082a8648ce3d03010703420004"
)
SPKI_BYTES = SPKI_PREFIX + bytes.fromhex(
    "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
    "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
)
SPKI_SHA256 = hashlib.sha256(SPKI_BYTES).hexdigest()


class TrustBootstrapTest(unittest.TestCase):
    def test_late_service_invokes_nonfatal_bootstrap(self) -> None:
        service = SERVICE.read_text(encoding="utf-8")
        self.assertIn('TRUST_BOOTSTRAP="$MODDIR/common/trust-bootstrap.sh"', service)
        self.assertIn('if ! "$TRUST_BOOTSTRAP" "$MODDIR"; then', service)
        self.assertLess(service.index("clear_boot_watchdog"), service.index("bootstrap_preprocessor_trust"))

    def test_success_records_transactional_next_boot_trust_pairs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = self.prepare_module(root, "production")
            arguments = root / "arguments.txt"
            app_process = root / "app_process"
            app_process.write_bytes(
                (
                    "#!/bin/sh\n"
                    f"printf '%s\\n' \"$*\" > '{shell_path(arguments)}'\n"
                    "echo ECHIDNA_TRUST_V2\n"
                    "echo status=pinned-next-boot\n"
                    "echo telemetry_status=generated-next-boot\n"
                    f"echo spki_sha256={SPKI_SHA256}\n"
                    f"echo telemetry_key_sha256={TELEMETRY_SHA256}\n"
                    f"echo telemetry_key_id={TELEMETRY_KEY_ID}\n"
                    "echo raw_key=telemetry-fixture-secret-must-not-leak\n"
                    "echo reboot_required=true\n"
                ).encode("utf-8")
            )
            app_process.chmod(0o755)
            runtime = root / "runtime"
            result = self.run_bootstrap(module, runtime, app_process)
            self.assertEqual(0, result.returncode, result.stderr)
            status = (runtime / "trust" / "status.txt").read_text(encoding="utf-8")
            self.assertIn("state=pinned-next-boot", status)
            self.assertIn("reboot_required=true", status)
            self.assertIn("telemetry_state=generated-next-boot", status)
            self.assertIn("telemetry_key_sha256=" + TELEMETRY_SHA256, status)
            self.assertNotIn("telemetry-fixture-secret", status + result.stdout + result.stderr)
            self.assertEqual(
                SPKI_BYTES,
                (module / "system" / "etc" / "echidna"
                 / "preprocessor_controller_p256.spki").read_bytes(),
            )
            self.assertIn("reboot is required", result.stdout)
            invocation = arguments.read_text(encoding="utf-8")
            self.assertIn("--telemetry-root " + shell_path(
                module / "trust" / "state" / "preprocessor_telemetry_hmac.key"
            ), invocation)
            self.assertIn("--telemetry-metadata " + shell_path(
                module / "trust" / "state" / "preprocessor_telemetry_hmac.key.meta"
            ), invocation)
            self.assertIn("--telemetry-effect " + shell_path(
                module / "system" / "etc" / "echidna" / "preprocessor_telemetry_hmac.key"
            ), invocation)

    def test_production_and_development_modes_accept_precise_ready_status(self) -> None:
        for mode in ("production", "development"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                module = self.prepare_module(root, mode)
                app_process = root / "app_process"
                app_process.write_bytes(
                    (
                        "#!/bin/sh\n"
                        "echo ECHIDNA_TRUST_V2\n"
                        "echo status=active-match\n"
                        "echo telemetry_status=ready\n"
                        f"echo spki_sha256={SPKI_SHA256}\n"
                        f"echo telemetry_key_sha256={TELEMETRY_SHA256}\n"
                        f"echo telemetry_key_id={TELEMETRY_KEY_ID}\n"
                        "echo reboot_required=false\n"
                    ).encode("utf-8")
                )
                app_process.chmod(0o755)
                result = self.run_bootstrap(module, root / "runtime", app_process)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                if mode == "development":
                    self.assertIn("NON-PRODUCTION", result.stdout)

    def test_inconsistent_key_metadata_fails_closed_without_secret_log(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = self.prepare_module(root, "production")
            app_process = root / "app_process"
            app_process.write_bytes(
                (
                    "#!/bin/sh\n"
                    "echo ECHIDNA_TRUST_V2\n"
                    "echo status=active-match\n"
                    "echo telemetry_status=ready\n"
                    f"echo spki_sha256={SPKI_SHA256}\n"
                    f"echo telemetry_key_sha256={'ef' * 32}\n"
                    f"echo telemetry_key_id={'ab' * 8}\n"
                    "echo raw_key=do-not-log-this-secret\n"
                    "echo reboot_required=false\n"
                ).encode("utf-8")
            )
            app_process.chmod(0o755)
            runtime = root / "runtime"
            result = self.run_bootstrap(module, runtime, app_process)
            self.assertNotEqual(0, result.returncode)
            combined = result.stdout + result.stderr + (
                runtime / "trust" / "status.txt"
            ).read_text(encoding="utf-8")
            self.assertIn("inconsistent telemetry proof-key metadata", combined)
            self.assertNotIn("do-not-log-this-secret", combined)

    def test_helper_failure_is_precise_and_non_destructive(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = self.prepare_module(root, "production")
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
            self.assertIn("reinstall/reprovision", result.stdout)
            self.assertIn("silent rotation is refused", result.stdout)

    def test_failed_java_transaction_removes_only_one_sided_derived_spki(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = self.prepare_module(root, "production")
            pending = (
                module / "trust" / "next-boot" / "preprocessor_controller_p256.spki"
            )
            derived = (
                module / "system" / "etc" / "echidna" / "preprocessor_controller_p256.spki"
            )
            pending.unlink()
            app_process = root / "app_process"
            app_process.write_text("#!/bin/sh\nexit 2\n", encoding="utf-8")
            app_process.chmod(0o755)

            result = self.run_bootstrap(module, root / "runtime", app_process)

            self.assertNotEqual(0, result.returncode)
            self.assertFalse(pending.exists())
            self.assertFalse(derived.exists())
            telemetry_root = (
                module / "trust" / "state" / "preprocessor_telemetry_hmac.key"
            )
            self.assertEqual(TELEMETRY_KEY_BYTES, telemetry_root.read_bytes())

    def test_packager_and_schema_share_exact_destination_contract(self) -> None:
        packager = PACKAGER.read_text(encoding="utf-8")
        schema = SCHEMA.read_text(encoding="utf-8")
        bootstrap = BOOTSTRAP.read_text(encoding="utf-8")
        self.assertIn("RELEASE_CERT_SHA256 is required", packager)
        self.assertIn("forbidden characters or a wildcard", packager)
        self.assertIn("production module refuses the known Android debug certificate", packager)
        self.assertIn("echidna-trust-helper.jar", packager)
        self.assertIn("trust/next-boot", bootstrap)
        self.assertIn("trust/state", bootstrap)
        self.assertIn("preprocessor_telemetry_hmac.key", bootstrap)
        self.assertIn("--telemetry-root", bootstrap)
        self.assertIn("--telemetry-effect", bootstrap)
        self.assertIn("telemetry-key-label.sh", bootstrap)
        self.assertIn("echidna_prepare_effect_trust", bootstrap)
        fixed = "/system/etc/echidna/preprocessor_controller_p256.spki"
        self.assertIn(fixed, schema)
        self.assertIn("/system/etc/echidna/$KEY_NAME", bootstrap)
        self.assertNotIn("killall audioserver", bootstrap)
        self.assertNotIn("setprop ctl.restart", bootstrap)

    def test_android_helper_enforces_atomic_owner_mode_and_authority_contract(self) -> None:
        source = TRUST_MAIN.read_text(encoding="utf-8")
        for token in (
            'TELEMETRY_KEY_SUFFIX =\n            "/files/echidna/preprocessor_telemetry_hmac.key"',
            "private static final int AID_AUDIO = 1005",
            "byte[] contents = index == 0 ? generatedRootPin : metadata",
            "writeAtomicOwned(path, contents, 0, 0, 0400)",
            "appPath, rootPin, companionUid, companionUid, 0600",
            "options.telemetryEffect, rootPin, 0, AID_AUDIO, 0440",
            "O_NOFOLLOW",
            "O_EXCL",
            "Os.fsync(output)",
            "Os.rename(temporary, path)",
            "fsyncDirectory(new File(path).getParent())",
            "silent rotation refused",
            "controller SPKI root/derived transaction is incomplete",
            "FirstProvisionTransaction.create(",
            "new String[] {pendingPath, activePath}",
            "new String[] {options.telemetryRoot, options.telemetryMetadata}",
            'requireMatchingPinnedKey(activePath, spki, "active derived copy")',
            "deleteNewTrustFile(path)",
            'path + ".tmp." + Os.getpid()',
        ):
            self.assertIn(token, source)
        self.assertIn("Derived copies are never authoritative", source)
        self.assertNotIn("new String(rootPin", source)
        self.assertNotIn("Arrays.toString(rootPin", source)

    def test_android_helper_prepares_main_looper_before_system_context(self) -> None:
        source = TRUST_MAIN.read_text(encoding="utf-8")
        start = source.index("private static Context systemContext()")
        end = source.index("private static String requireDataDir", start)
        system_context = source[start:end]
        self.assertIn("import android.os.Looper;", source)
        self.assertIn("if (Looper.myLooper() == null)", system_context)
        self.assertLess(
            system_context.index("Looper.prepareMainLooper();"),
            system_context.index("systemMain.invoke(null)"),
        )

    @staticmethod
    def prepare_module(root: Path, mode: str) -> Path:
        module = root / "module"
        common = module / "common"
        common.mkdir(parents=True)
        (common / "echidna-trust-helper.jar").write_bytes(b"dex-container")
        (common / "release-cert-sha256").write_text("ab" * 32 + "\n", encoding="ascii")
        (common / "trust-mode").write_text(mode + "\n", encoding="ascii")
        shutil.copyfile(LABEL_HELPER, common / "telemetry-key-label.sh")
        root_pin = module / "trust" / "state" / "preprocessor_telemetry_hmac.key"
        effect_key = (
            module / "system" / "etc" / "echidna" / "preprocessor_telemetry_hmac.key"
        )
        root_pin.parent.mkdir(parents=True)
        effect_key.parent.mkdir(parents=True)
        root_pin.write_bytes(TELEMETRY_KEY_BYTES)
        effect_key.write_bytes(TELEMETRY_KEY_BYTES)
        spki_root = module / "trust" / "next-boot" / "preprocessor_controller_p256.spki"
        spki_effect = (
            module / "system" / "etc" / "echidna" / "preprocessor_controller_p256.spki"
        )
        spki_root.parent.mkdir(parents=True, exist_ok=True)
        spki_root.write_bytes(SPKI_BYTES)
        spki_effect.write_bytes(SPKI_BYTES)
        return module

    def run_bootstrap(
        self, module: Path, runtime: Path, app_process: Path
    ) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        fake_bin = module.parent / "fake-bin"
        fake_bin.mkdir(exist_ok=True)
        context_file = module.parent / "telemetry-key-context.txt"
        fake_commands = {
            "stat": """#!/bin/sh
case "$3" in
  */trust/state/preprocessor_telemetry_hmac.key) echo 0:0:400:1:10 ;;
  */system/etc/echidna/preprocessor_telemetry_hmac.key) echo 0:1005:440:1:20 ;;
  */trust/next-boot/preprocessor_controller_p256.spki) echo 0:0:444:1:30 ;;
  */system/etc/echidna/preprocessor_controller_p256.spki) echo 0:0:444:1:40 ;;
  *) exit 64 ;;
esac
""",
            "chcon": """#!/bin/sh
case "$1" in
  u:object_r:echidna_telemetry_key_file:s0|u:object_r:echidna_controller_spki_file:s0) ;;
  *) exit 64 ;;
esac
printf '%s\n' "$1" > "$FAKE_CONTEXT_FILE"
""",
            "ls": """#!/bin/sh
[ "$1" = -Zd ] || exit 64
printf '%s %s\n' "$(cat "$FAKE_CONTEXT_FILE")" "$2"
""",
        }
        for name, source in fake_commands.items():
            command = fake_bin / name
            command.write_text(source, encoding="utf-8", newline="\n")
            command.chmod(0o755)
        env["ECHIDNA_RUNTIME_DIR"] = shell_path(runtime)
        env["ECHIDNA_APP_PROCESS"] = shell_path(app_process)
        env["FAKE_CONTEXT_FILE"] = shell_path(context_file)
        env["PATH"] = shell_path(fake_bin) + os.pathsep + env.get("PATH", "")
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
