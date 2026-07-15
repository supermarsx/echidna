#!/usr/bin/env python3
from __future__ import annotations

import base64
import importlib.util
import sys
import unittest
from pathlib import Path


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

    def test_malformed_base64_is_rejected_and_wrapped_base64_is_accepted(self) -> None:
        malformed = {**FULL_ENVIRONMENT, "RELEASE_KEYSTORE_BASE64": "not!base64"}
        encoded = FULL_ENVIRONMENT["RELEASE_KEYSTORE_BASE64"]
        wrapped = {**FULL_ENVIRONMENT, "RELEASE_KEYSTORE_BASE64": encoded[:8] + "\n" + encoded[8:]}

        self.assertIn("invalid base64 keystore", checker.validate_environment(malformed)[0])
        self.assertEqual([], checker.validate_environment(wrapped))

    def test_optional_certificate_pin_is_normalized_and_validated(self) -> None:
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
        self.assertIn("exactly 64 hex digits", checker.validate_environment(invalid)[0])

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


if __name__ == "__main__":
    unittest.main()
