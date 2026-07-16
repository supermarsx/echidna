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
HELPER = REPO_ROOT / "magisk" / "common" / "telemetry-key-label.sh"
KEY_NAME = "preprocessor_telemetry_hmac.key"
KEY_BYTES = b"echidna-telemetry-label-fixture!"
assert len(KEY_BYTES) == 32


class TelemetryKeyLabelTest(unittest.TestCase):
    def test_exact_key_is_labeled_without_changing_inode_contract_or_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, effect_key = self.prepare_module(root)
            stale = effect_key.with_name(effect_key.name + ".tmp.42")
            stale.write_bytes(b"stale")

            result, context = self.run_helper(root, module)

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("u:object_r:echidna_telemetry_key_file:s0", context)
            self.assertEqual(KEY_BYTES, root_pin.read_bytes())
            self.assertEqual(KEY_BYTES, effect_key.read_bytes())
            self.assertFalse(stale.exists())

    def test_absent_key_is_allowed_only_before_first_provisioning(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, effect_key = self.prepare_module(root)
            effect_key.unlink()
            root_pin.unlink()

            optional, _ = self.run_helper(root, module, presence="optional")
            required, _ = self.run_helper(root, module, presence="required")

            self.assertEqual(0, optional.returncode, optional.stderr)
            self.assertNotEqual(0, required.returncode)
            self.assertIn("required derived effect key is missing", required.stderr)

    def test_one_sided_key_states_fail_closed_and_preserve_only_the_root_pin(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, effect_key = self.prepare_module(root)
            effect_key.unlink()

            root_only, _ = self.run_helper(root, module, presence="optional")

            self.assertNotEqual(0, root_only.returncode)
            self.assertIn("root pin exists without", root_only.stderr)
            self.assertEqual(KEY_BYTES, root_pin.read_bytes())
            self.assertFalse(effect_key.exists())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, effect_key = self.prepare_module(root)
            root_pin.unlink()

            effect_only, _ = self.run_helper(root, module, presence="optional")

            self.assertNotEqual(0, effect_only.returncode)
            self.assertIn("authoritative telemetry root pin is unsafe", effect_only.stderr)
            self.assertFalse(root_pin.exists())
            self.assertFalse(effect_key.exists())

    def test_symlink_is_unlinked_without_touching_its_target(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, effect_key = self.prepare_module(root)
            target = root / "outside-key"
            target.write_bytes(KEY_BYTES)
            effect_key.unlink()
            try:
                effect_key.symlink_to(target)
            except OSError as error:
                source = HELPER.read_text(encoding="utf-8")
                self.assertIn('[ -L "$effect_key" ] || [ ! -f "$effect_key" ]', source)
                self.assertIn(
                    'rm -f "$key_path" 2>/dev/null || true',
                    source,
                    f"host could not create a symlink fixture: {error}",
                )
                return

            result, _ = self.run_helper(root, module)

            self.assertNotEqual(0, result.returncode)
            self.assertFalse(effect_key.exists())
            self.assertFalse(effect_key.is_symlink())
            self.assertEqual(KEY_BYTES, target.read_bytes())
            self.assertEqual(KEY_BYTES, root_pin.read_bytes())

    def test_hash_or_owner_mode_drift_removes_only_derived_copy(self) -> None:
        scenarios = (
            ("hash", {}, b"different-derived-key-material!!"),
            ("mode", {"FAKE_EFFECT_STAT_BEFORE": "0:1005:640:1:20"}, None),
        )
        for name, overrides, replacement in scenarios:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                module, root_pin, effect_key = self.prepare_module(root)
                if replacement is not None:
                    self.assertEqual(32, len(replacement))
                    effect_key.write_bytes(replacement)

                result, _ = self.run_helper(root, module, overrides=overrides)

                self.assertNotEqual(0, result.returncode)
                self.assertFalse(effect_key.exists())
                self.assertEqual(KEY_BYTES, root_pin.read_bytes())

    def test_label_failure_wrong_context_and_inode_drift_roll_back_without_residue(self) -> None:
        scenarios = (
            ("chcon", {"FAKE_CHCON_FAIL": "1"}),
            (
                "context",
                {"FAKE_CHCON_RESULT_CONTEXT": "u:object_r:system_file:s0"},
            ),
            (
                "inode",
                {
                    "FAKE_EFFECT_DRIFT_AFTER_CALLS": "2",
                    "FAKE_EFFECT_STAT_AFTER": "0:1005:440:1:99",
                },
            ),
        )
        for name, overrides in scenarios:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                module, root_pin, effect_key = self.prepare_module(root)
                stale = effect_key.with_name(effect_key.name + ".tmp.77")
                stale.write_bytes(b"stale")

                result, _ = self.run_helper(root, module, overrides=overrides)

                self.assertNotEqual(0, result.returncode)
                self.assertFalse(effect_key.exists())
                self.assertFalse(stale.exists())
                self.assertEqual(KEY_BYTES, root_pin.read_bytes())
                self.assertIn("derived effect key removed", result.stderr)

    @staticmethod
    def prepare_module(root: Path) -> tuple[Path, Path, Path]:
        module = root / "module"
        root_pin = module / "trust" / "state" / KEY_NAME
        effect_key = module / "system" / "etc" / "echidna" / KEY_NAME
        root_pin.parent.mkdir(parents=True)
        effect_key.parent.mkdir(parents=True)
        root_pin.write_bytes(KEY_BYTES)
        effect_key.write_bytes(KEY_BYTES)
        return module, root_pin, effect_key

    def run_helper(
        self,
        root: Path,
        module: Path,
        *,
        presence: str = "required",
        overrides: dict[str, str] | None = None,
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        fake_bin = root / "fake-bin"
        fake_bin.mkdir(exist_ok=True)
        context = root / "context.txt"
        stat_count = root / "effect-stat-count.txt"
        self.write_fake_commands(fake_bin)
        env = os.environ.copy()
        env.update(
            {
                "PATH": shell_path(fake_bin) + os.pathsep + env.get("PATH", ""),
                "FAKE_CONTEXT_FILE": shell_path(context),
                "FAKE_EFFECT_STAT_COUNT": shell_path(stat_count),
                "FAKE_ROOT_STAT": "0:0:400:1:10",
                "FAKE_EFFECT_STAT_BEFORE": "0:1005:440:1:20",
                "FAKE_EFFECT_STAT_AFTER": "0:1005:440:1:20",
                "FAKE_EFFECT_DRIFT_AFTER_CALLS": "999",
            }
        )
        env.update(overrides or {})
        expected = hashlib.sha256(KEY_BYTES).hexdigest()
        result = subprocess.run(
            [
                bash_executable(),
                "-c",
                '. "$1"; echidna_prepare_effect_telemetry_key "$2" "$3" "$4"',
                "echidna-key-test",
                shell_path(HELPER),
                shell_path(module),
                expected,
                presence,
            ],
            cwd=REPO_ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        return result, context.read_text(encoding="utf-8").strip() if context.exists() else ""

    @staticmethod
    def write_fake_commands(fake_bin: Path) -> None:
        commands = {
            "stat": """#!/bin/sh
path="$3"
case "$path" in
  */trust/state/preprocessor_telemetry_hmac.key)
    echo "${FAKE_ROOT_STAT}"
    ;;
  */system/etc/echidna/preprocessor_telemetry_hmac.key)
    count=0
    [ ! -f "$FAKE_EFFECT_STAT_COUNT" ] || count="$(cat "$FAKE_EFFECT_STAT_COUNT")"
    count=$((count + 1))
    echo "$count" > "$FAKE_EFFECT_STAT_COUNT"
    if [ "$count" -gt "$FAKE_EFFECT_DRIFT_AFTER_CALLS" ]; then
      echo "$FAKE_EFFECT_STAT_AFTER"
    else
      echo "$FAKE_EFFECT_STAT_BEFORE"
    fi
    ;;
  *) exit 64 ;;
esac
""",
            "chcon": """#!/bin/sh
[ "${FAKE_CHCON_FAIL:-0}" != 1 ] || exit 1
[ "$1" = "u:object_r:echidna_telemetry_key_file:s0" ] || exit 64
printf '%s\n' "${FAKE_CHCON_RESULT_CONTEXT:-$1}" > "$FAKE_CONTEXT_FILE"
""",
            "ls": """#!/bin/sh
[ "$1" = -Zd ] || exit 64
[ -f "$FAKE_CONTEXT_FILE" ] || exit 1
printf '%s %s\n' "$(cat "$FAKE_CONTEXT_FILE")" "$2"
""",
        }
        for name, source in commands.items():
            path = fake_bin / name
            path.write_text(source, encoding="utf-8", newline="\n")
            path.chmod(0o755)


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
