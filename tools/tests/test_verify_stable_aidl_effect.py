from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "tools/verify_stable_aidl_effect.py"
SPEC = importlib.util.spec_from_file_location("verify_stable_aidl_effect", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class StableAidlEffectGateTest(unittest.TestCase):
    def source_args(self, api: int) -> argparse.Namespace:
        return argparse.Namespace(
            api=api,
            repo_root=REPO,
            hardware_interfaces_root=None,
            frameworks_av_root=None,
            product_config=None,
            product_packages=None,
            device_evidence=None,
            factory_source=None,
            require_product_gate=False,
        )

    def test_source_contracts_cover_both_platform_versions(self) -> None:
        for api in (34, 35):
            with self.subTest(api=api):
                checks = MODULE.run(self.source_args(api))
                self.assertEqual([], checks.failed)

    def test_product_gate_fails_closed_without_evidence(self) -> None:
        args = self.source_args(35)
        args.require_product_gate = True
        checks = MODULE.run(args)
        self.assertTrue(
            any("product gate supplies" in failure for failure in checks.failed)
        )

    def test_api35_product_evidence_must_prove_reopen_and_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "audio_effects_config.xml"
            config.write_text(
                (REPO / "native/effects/aidl/integration/"
                 "audio_effects_config.api35.xml").read_text(encoding="utf-8"),
                encoding="utf-8",
            )
            packages = root / "device.mk"
            packages.write_text(
                "PRODUCT_PACKAGES += libechidna_preproc_aidl_v2\n",
                encoding="utf-8",
            )
            evidence = root / "evidence.json"
            payload = {
                "api": 35,
                "factory_instance_count": 1,
                "factory_is_oem_instance": True,
                "library_loaded_from_soundfx": True,
                "selinux_enforcing": True,
                "factory_reads_controller_spki": True,
                "factory_reads_telemetry_key": True,
                "effect_vts_passed": True,
                "create_open_close_passed": True,
                "fmq_process_passed": True,
                "reset_passed": True,
                "reopen_passed": False,
                "unauthorized_max_abs_diff": 0.0,
                "authorized_mutated_samples": 0,
                "telemetry_v2_hmac_verified": True,
            }
            evidence.write_text(json.dumps(payload), encoding="utf-8")
            checks = MODULE.Checks()
            MODULE.check_product(config, packages, evidence, None, 35, checks)
            self.assertTrue(any("reopen" in item for item in checks.failed))
            self.assertTrue(any("changes at least one sample" in item for item in checks.failed))

            payload["reopen_passed"] = True
            payload["authorized_mutated_samples"] = 128
            evidence.write_text(json.dumps(payload), encoding="utf-8")
            checks = MODULE.Checks()
            MODULE.check_product(config, packages, evidence, None, 35, checks)
            self.assertEqual([], checks.failed)


if __name__ == "__main__":
    unittest.main()
