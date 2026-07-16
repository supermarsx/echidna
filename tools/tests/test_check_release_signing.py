#!/usr/bin/env python3
from __future__ import annotations

import base64
import importlib.util
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = REPO_ROOT / "tools" / "check_release_signing.py"

spec = importlib.util.spec_from_file_location("check_release_signing", TOOL_PATH)
assert spec is not None
checker = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["check_release_signing"] = checker
spec.loader.exec_module(checker)


FULL_ENVIRONMENT = {
    "RELEASE_KEYSTORE_BASE64": base64.b64encode(b"fake-keystore").decode("ascii"),
    "RELEASE_STORE_PASSWORD": "store-password",
    "RELEASE_KEY_ALIAS": "release",
    "RELEASE_KEY_PASSWORD": "key-password",
    "RELEASE_CERT_SHA256": "aa" * 32,
}


class ReleaseSigningConfigurationTest(unittest.TestCase):
    def test_missing_configuration_fails_closed(self) -> None:
        errors = checker.validate_environment({})

        self.assertEqual(1, len(errors))
        for name in checker.REQUIRED_SIGNING_VARIABLES:
            self.assertIn(name, errors[0])

    def test_partial_configuration_names_only_missing_inputs(self) -> None:
        environment = {
            "RELEASE_KEYSTORE_BASE64": FULL_ENVIRONMENT["RELEASE_KEYSTORE_BASE64"],
            "RELEASE_KEY_ALIAS": "release",
        }

        errors = checker.validate_environment(environment)

        self.assertEqual(1, len(errors))
        self.assertIn("RELEASE_STORE_PASSWORD", errors[0])
        self.assertIn("RELEASE_KEY_PASSWORD", errors[0])
        self.assertNotIn("RELEASE_KEY_ALIAS,", errors[0])

    def test_complete_configuration_is_accepted(self) -> None:
        self.assertEqual([], checker.validate_environment(FULL_ENVIRONMENT))

    def test_only_completely_absent_configuration_can_skip(self) -> None:
        self.assertTrue(checker.is_completely_unconfigured({}))
        self.assertTrue(
            checker.is_completely_unconfigured(
                {name: "  " for name in checker.REQUIRED_SIGNING_VARIABLES}
            )
        )
        self.assertFalse(
            checker.is_completely_unconfigured({"RELEASE_KEY_ALIAS": "release"})
        )

    def test_automatic_ci_can_skip_absent_but_not_partial_signing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "github-output"
            with mock.patch.dict(os.environ, {}, clear=True), mock.patch(
                "sys.stdout", new_callable=io.StringIO
            ) as stdout:
                result = checker.main(
                    ["--allow-unconfigured-skip", "--github-output", str(output)]
                )
            self.assertEqual(0, result)
            self.assertIn("::notice title=Release skipped::", stdout.getvalue())
            self.assertEqual("release_enabled=false\n", output.read_text(encoding="utf-8"))

            partial = {"RELEASE_KEY_ALIAS": "release"}
            with mock.patch.dict(os.environ, partial, clear=True), mock.patch(
                "sys.stderr", new_callable=io.StringIO
            ) as stderr:
                result = checker.main(["--allow-unconfigured-skip"])
            self.assertEqual(2, result)
            self.assertIn("missing required release signing inputs", stderr.getvalue())

    def test_manual_or_tag_mode_rejects_absent_signing(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch(
            "sys.stderr", new_callable=io.StringIO
        ) as stderr:
            result = checker.main([])
        self.assertEqual(2, result)
        self.assertIn("missing required release signing inputs", stderr.getvalue())

    def test_complete_signing_enables_release(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "github-output"
            with mock.patch.dict(os.environ, FULL_ENVIRONMENT, clear=True):
                result = checker.main(
                    ["--allow-unconfigured-skip", "--github-output", str(output)]
                )
            self.assertEqual(0, result)
            self.assertEqual("release_enabled=true\n", output.read_text(encoding="utf-8"))

    def test_malformed_base64_is_rejected_and_wrapped_base64_is_accepted(self) -> None:
        malformed = {**FULL_ENVIRONMENT, "RELEASE_KEYSTORE_BASE64": "not!base64"}
        encoded = FULL_ENVIRONMENT["RELEASE_KEYSTORE_BASE64"]
        wrapped = {**FULL_ENVIRONMENT, "RELEASE_KEYSTORE_BASE64": encoded[:8] + "\n" + encoded[8:]}

        self.assertIn("invalid base64 keystore", checker.validate_environment(malformed)[0])
        self.assertEqual([], checker.validate_environment(wrapped))

    def test_required_certificate_pin_is_normalized_and_validated(self) -> None:
        environment = {
            **FULL_ENVIRONMENT,
            "RELEASE_CERT_SHA256": "AA:" * 31 + "AA",
        }
        self.assertEqual([], checker.validate_environment(environment))
        self.assertEqual(
            "aa" * 32,
            checker.normalize_certificate_digest(environment["RELEASE_CERT_SHA256"]),
        )

        invalid = {**FULL_ENVIRONMENT, "RELEASE_CERT_SHA256": "not-a-digest"}
        self.assertIn("forbidden characters", checker.validate_environment(invalid)[0])
        wildcard = {**FULL_ENVIRONMENT, "RELEASE_CERT_SHA256": "aa" * 32 + "*"}
        self.assertIn("wildcard", checker.validate_environment(wildcard)[0])
        debug = {
            **FULL_ENVIRONMENT,
            "RELEASE_CERT_SHA256": next(iter(checker.KNOWN_ANDROID_DEBUG_CERTIFICATES)),
        }
        self.assertIn("debug certificate", checker.validate_environment(debug)[0])

    def test_release_workflow_fails_closed_before_tag_creation(self) -> None:
        workflow = (REPO_ROOT / ".github" / "workflows" / "release.yml").read_text(
            encoding="utf-8"
        )

        preflight = workflow.index("python3 tools/check_release_signing.py")
        key_validation = workflow.index("Validate release keystore credentials")
        tag_creation = workflow.index("Create tag if needed and reject duplicate releases")
        self.assertLess(preflight, tag_creation)
        self.assertLess(key_validation, tag_creation)
        self.assertIn("keytool -importkeystore", workflow)
        self.assertIn("trap cleanup_preflight EXIT", workflow)
        self.assertIn("echo \"::add-mask::${RELEASE_STORE_PASSWORD}\"", workflow)
        self.assertIn("echo \"::add-mask::${RELEASE_KEY_PASSWORD}\"", workflow)
        cleanup = workflow.index("Remove temporary release keystore")
        upload = workflow.index("Upload Android artifacts")
        self.assertLess(cleanup, upload)
        self.assertIn("if: ${{ always() }}", workflow[cleanup:upload])
        self.assertIn('chmod 0600 "$RUNNER_TEMP/release.jks"', workflow)
        self.assertNotIn("set -x", workflow)
        self.assertNotIn('cat "$RUNNER_TEMP/release.jks"', workflow)
        self.assertNotIn("Building release with debug-signing fallback", workflow)
        self.assertIn("RELEASE_STORE_FILE: ${{ runner.temp }}/release.jks", workflow)
        ci_workflow = (REPO_ROOT / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("allow_unsigned_skip:", workflow)
        self.assertIn("default: false", workflow)
        self.assertIn(
            "ALLOW_UNSIGNED_SKIP: ${{ inputs.allow_unsigned_skip || false }}", workflow
        )
        self.assertIn("allow_unsigned_skip: true", ci_workflow)
        self.assertIn("signing_args+=(--allow-unconfigured-skip)", workflow)
        self.assertGreaterEqual(
            workflow.count("needs.prepare.outputs.release_enabled == 'true'"), 3
        )


if __name__ == "__main__":
    unittest.main()
