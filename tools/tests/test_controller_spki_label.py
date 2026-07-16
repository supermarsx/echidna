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
SPKI_NAME = "preprocessor_controller_p256.spki"
SPKI_PREFIX = bytes.fromhex(
    "3059301306072a8648ce3d020106082a8648ce3d03010703420004"
)
# secp256r1 generator point, encoded as canonical uncompressed SubjectPublicKeyInfo.
SPKI_BYTES = SPKI_PREFIX + bytes.fromhex(
    "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
    "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
)
assert len(SPKI_BYTES) == 91


class ControllerSpkiLabelTest(unittest.TestCase):
    def test_exact_pair_is_labeled_without_changing_pin_or_derived_copy(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, derived = self.prepare_module(root)
            stale = derived.with_name(derived.name + ".tmp.42")
            stale.write_bytes(b"stale")

            result, context = self.run_helper(root, module)

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("u:object_r:echidna_controller_spki_file:s0", context)
            self.assertEqual(SPKI_BYTES, root_pin.read_bytes())
            self.assertEqual(SPKI_BYTES, derived.read_bytes())
            self.assertFalse(stale.exists())

    def test_pair_presence_is_exact_for_optional_and_required_modes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, derived = self.prepare_module(root)
            root_pin.unlink()
            derived.unlink()

            optional, _ = self.run_helper(root, module, presence="optional")
            required, _ = self.run_helper(root, module, presence="required")

            self.assertEqual(0, optional.returncode, optional.stderr)
            self.assertNotEqual(0, required.returncode)
            self.assertIn("required derived controller SPKI is missing", required.stderr)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, derived = self.prepare_module(root)
            derived.unlink()

            result, _ = self.run_helper(root, module, presence="optional")

            self.assertNotEqual(0, result.returncode)
            self.assertEqual(SPKI_BYTES, root_pin.read_bytes())
            self.assertFalse(derived.exists())
            self.assertIn("root pin exists without", result.stderr)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, derived = self.prepare_module(root)
            root_pin.unlink()

            result, _ = self.run_helper(root, module, presence="optional")

            self.assertNotEqual(0, result.returncode)
            self.assertFalse(root_pin.exists())
            self.assertFalse(derived.exists())
            self.assertIn("authoritative controller SPKI root pin is unsafe", result.stderr)

    def test_invalid_der_size_hash_owner_or_mode_removes_only_derived(self) -> None:
        scenarios: tuple[tuple[str, bytes | None, dict[str, str], str | None], ...] = (
            ("der", b"\x00" + SPKI_BYTES[1:], {}, None),
            ("size", SPKI_BYTES[:-1], {}, None),
            ("hash", SPKI_PREFIX + b"\x01" * 64, {}, None),
            ("root-owner", None, {"FAKE_ROOT_STAT_BEFORE": "1:0:444:1:10"}, None),
            ("derived-mode", None, {"FAKE_EFFECT_STAT_BEFORE": "0:0:644:1:20"}, None),
            ("expected-hash", None, {}, "0" * 64),
        )
        for name, replacement, overrides, expected in scenarios:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                module, root_pin, derived = self.prepare_module(root)
                if replacement is not None:
                    derived.write_bytes(replacement)

                result, _ = self.run_helper(
                    root, module, overrides=overrides, expected=expected
                )

                self.assertNotEqual(0, result.returncode)
                self.assertEqual(SPKI_BYTES, root_pin.read_bytes())
                self.assertFalse(derived.exists())

    def test_symlink_context_and_root_or_derived_inode_drift_roll_back(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, derived = self.prepare_module(root)
            target = root / "outside.spki"
            target.write_bytes(SPKI_BYTES)
            derived.unlink()
            try:
                derived.symlink_to(target)
            except OSError as error:
                source = HELPER.read_text(encoding="utf-8")
                self.assertIn('[ -L "$effect_spki" ] || [ ! -f "$effect_spki" ]', source)
                self.assertIn(
                    'rm -f "$spki_path" 2>/dev/null || true',
                    source,
                    f"host could not create a symlink fixture: {error}",
                )
            else:
                result, _ = self.run_helper(root, module)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(derived.exists())
                self.assertEqual(SPKI_BYTES, target.read_bytes())
                self.assertEqual(SPKI_BYTES, root_pin.read_bytes())

        scenarios = (
            ("chcon", {"FAKE_CHCON_FAIL": "1"}),
            ("context", {"FAKE_CHCON_RESULT_CONTEXT": "u:object_r:system_file:s0"}),
            (
                "derived-inode",
                {
                    "FAKE_EFFECT_DRIFT_AFTER_CALLS": "2",
                    "FAKE_EFFECT_STAT_AFTER": "0:0:444:1:99",
                },
            ),
            (
                "root-inode",
                {
                    "FAKE_ROOT_DRIFT_AFTER_CALLS": "2",
                    "FAKE_ROOT_STAT_AFTER": "0:0:444:1:99",
                },
            ),
        )
        for name, overrides in scenarios:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                module, root_pin, derived = self.prepare_module(root)
                stale = derived.with_name(derived.name + ".tmp.77")
                stale.write_bytes(b"stale")

                result, _ = self.run_helper(root, module, overrides=overrides)

                self.assertNotEqual(0, result.returncode)
                self.assertEqual(SPKI_BYTES, root_pin.read_bytes())
                self.assertFalse(derived.exists())
                self.assertFalse(stale.exists())
                self.assertIn("derived controller SPKI removed", result.stderr)

    def test_combined_optional_lifecycle_validates_each_pair_independently(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, derived = self.prepare_module(root)

            result, _ = self.run_helper(root, module, combined=True, presence="optional")

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(SPKI_BYTES, root_pin.read_bytes())
            self.assertEqual(SPKI_BYTES, derived.read_bytes())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module, root_pin, derived = self.prepare_module(root)
            derived.unlink()

            result, _ = self.run_helper(root, module, combined=True, presence="optional")

            self.assertNotEqual(0, result.returncode)
            self.assertEqual(SPKI_BYTES, root_pin.read_bytes())
            self.assertFalse(derived.exists())

    @staticmethod
    def prepare_module(root: Path) -> tuple[Path, Path, Path]:
        module = root / "module"
        root_pin = module / "trust" / "next-boot" / SPKI_NAME
        derived = module / "system" / "etc" / "echidna" / SPKI_NAME
        root_pin.parent.mkdir(parents=True)
        derived.parent.mkdir(parents=True)
        root_pin.write_bytes(SPKI_BYTES)
        derived.write_bytes(SPKI_BYTES)
        return module, root_pin, derived

    def run_helper(
        self,
        root: Path,
        module: Path,
        *,
        presence: str = "required",
        overrides: dict[str, str] | None = None,
        expected: str | None = None,
        combined: bool = False,
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        fake_bin = root / "fake-bin"
        fake_bin.mkdir(exist_ok=True)
        context = root / "context.txt"
        self.write_fake_commands(fake_bin)
        env = os.environ.copy()
        env.update(
            {
                "PATH": shell_path(fake_bin) + os.pathsep + env.get("PATH", ""),
                "FAKE_CONTEXT_FILE": shell_path(context),
                "FAKE_ROOT_STAT_COUNT": shell_path(root / "root-stat-count.txt"),
                "FAKE_EFFECT_STAT_COUNT": shell_path(root / "effect-stat-count.txt"),
                "FAKE_ROOT_STAT_BEFORE": "0:0:444:1:10",
                "FAKE_ROOT_STAT_AFTER": "0:0:444:1:10",
                "FAKE_ROOT_DRIFT_AFTER_CALLS": "999",
                "FAKE_EFFECT_STAT_BEFORE": "0:0:444:1:20",
                "FAKE_EFFECT_STAT_AFTER": "0:0:444:1:20",
                "FAKE_EFFECT_DRIFT_AFTER_CALLS": "999",
            }
        )
        env.update(overrides or {})
        digest = expected if expected is not None else hashlib.sha256(SPKI_BYTES).hexdigest()
        if combined:
            invocation = 'echidna_prepare_effect_trust "$2" "" "$3" "$4"'
        else:
            invocation = 'echidna_prepare_effect_controller_spki "$2" "$3" "$4"'
        result = subprocess.run(
            [
                bash_executable(),
                "-c",
                f'. "$1"; {invocation}',
                "echidna-spki-test",
                shell_path(HELPER),
                shell_path(module),
                digest,
                presence,
            ],
            cwd=REPO_ROOT,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        value = context.read_text(encoding="utf-8").strip() if context.exists() else ""
        return result, value

    @staticmethod
    def write_fake_commands(fake_bin: Path) -> None:
        commands = {
            "stat": """#!/bin/sh
path="$3"
case "$path" in
  */trust/next-boot/preprocessor_controller_p256.spki)
    count=0
    [ ! -f "$FAKE_ROOT_STAT_COUNT" ] || count="$(cat "$FAKE_ROOT_STAT_COUNT")"
    count=$((count + 1))
    echo "$count" > "$FAKE_ROOT_STAT_COUNT"
    if [ "$count" -gt "$FAKE_ROOT_DRIFT_AFTER_CALLS" ]; then
      echo "$FAKE_ROOT_STAT_AFTER"
    else
      echo "$FAKE_ROOT_STAT_BEFORE"
    fi
    ;;
  */system/etc/echidna/preprocessor_controller_p256.spki)
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
[ "$1" = "u:object_r:echidna_controller_spki_file:s0" ] || exit 64
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
