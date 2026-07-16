#!/usr/bin/env python3
from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
APP_MANIFEST = REPO_ROOT / "android" / "app" / "app" / "src" / "main" / "AndroidManifest.xml"
SHIM_ROOT = REPO_ROOT / "android" / "lsposed-shim" / "shim" / "src"
SERVICE_ROOT = REPO_ROOT / "android" / "control-service" / "service" / "src"
SHIM_MAIN_MANIFEST = SHIM_ROOT / "main" / "AndroidManifest.xml"
SHIM_DEBUG_MANIFEST = SHIM_ROOT / "debug" / "AndroidManifest.xml"
SERVICE_MAIN_MANIFEST = SERVICE_ROOT / "main" / "AndroidManifest.xml"
SERVICE_DEBUG_MANIFEST = SERVICE_ROOT / "debug" / "AndroidManifest.xml"
SESSION_PROOF_PROVIDER = (
    SERVICE_ROOT
    / "debug"
    / "kotlin"
    / "com"
    / "echidna"
    / "control"
    / "service"
    / "SessionProofEvidenceProvider.kt"
)
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class AndroidManifestContractsTest(unittest.TestCase):
    def test_headless_lsposed_visibility_is_exact_and_least_privilege(self) -> None:
        root = ET.parse(APP_MANIFEST).getroot()
        permissions = {
            node.attrib.get(f"{ANDROID_NS}name")
            for node in root.findall("uses-permission")
        }
        self.assertNotIn("android.permission.QUERY_ALL_PACKAGES", permissions)

        queries = root.findall("queries")
        self.assertEqual(1, len(queries))
        package_queries = [
            node.attrib.get(f"{ANDROID_NS}name")
            for node in queries[0].findall("package")
        ]
        self.assertEqual(["com.echidna.lsposed"], package_queries)
        self.assertEqual([], queries[0].findall("provider"))

        intent_queries = queries[0].findall("intent")
        self.assertEqual(1, len(intent_queries))
        actions = [
            node.attrib.get(f"{ANDROID_NS}name")
            for node in intent_queries[0].findall("action")
        ]
        categories = [
            node.attrib.get(f"{ANDROID_NS}name")
            for node in intent_queries[0].findall("category")
        ]
        self.assertEqual(["android.intent.action.MAIN"], actions)
        self.assertEqual(["android.intent.category.LAUNCHER"], categories)
        self.assertEqual([], intent_queries[0].findall("data"))

    def test_session_proof_surfaces_are_debug_only_and_dump_protected(self) -> None:
        shim_main = ET.parse(SHIM_MAIN_MANIFEST).getroot()
        shim_debug = ET.parse(SHIM_DEBUG_MANIFEST).getroot()
        service_main = ET.parse(SERVICE_MAIN_MANIFEST).getroot()
        service_debug = ET.parse(SERVICE_DEBUG_MANIFEST).getroot()

        self.assertNotIn(
            "android.permission.RECORD_AUDIO", self._permissions(shim_main)
        )
        self.assertEqual(
            {"android.permission.RECORD_AUDIO"}, self._permissions(shim_debug)
        )

        debug_providers = service_debug.findall("application/provider")
        self.assertEqual(1, len(debug_providers))
        provider = debug_providers[0]
        self.assertEqual(
            ".service.SessionProofEvidenceProvider",
            provider.attrib.get(f"{ANDROID_NS}name"),
        )
        self.assertEqual(
            "com.echidna.app.sessionproof",
            provider.attrib.get(f"{ANDROID_NS}authorities"),
        )
        self.assertEqual("true", provider.attrib.get(f"{ANDROID_NS}exported"))
        self.assertEqual(
            "android.permission.DUMP",
            provider.attrib.get(f"{ANDROID_NS}permission"),
        )

        self.assertEqual([], service_main.findall("application/provider"))
        self.assertTrue(SESSION_PROOF_PROVIDER.is_file())
        release_provider_sources = list((SERVICE_ROOT / "main").rglob("*SessionProof*"))
        release_provider_sources += list((SERVICE_ROOT / "release").rglob("*SessionProof*"))
        self.assertEqual([], release_provider_sources)

    def test_release_manifest_sources_have_no_broad_visibility_or_debug_audio(self) -> None:
        manifests = [APP_MANIFEST, SHIM_MAIN_MANIFEST, SERVICE_MAIN_MANIFEST]
        for manifest in manifests:
            with self.subTest(manifest=manifest.relative_to(REPO_ROOT)):
                root = ET.parse(manifest).getroot()
                permissions = self._permissions(root)
                self.assertNotIn("android.permission.QUERY_ALL_PACKAGES", permissions)
                self.assertNotIn("android.permission.RECORD_AUDIO", permissions)
                self.assertEqual([], root.findall("queries/provider"))

        shim_queries = ET.parse(SHIM_MAIN_MANIFEST).getroot().findall("queries")
        self.assertEqual(1, len(shim_queries))
        self.assertEqual(
            ["com.echidna.app"],
            [
                node.attrib.get(f"{ANDROID_NS}name")
                for node in shim_queries[0].findall("package")
            ],
        )
        self.assertEqual([], shim_queries[0].findall("intent"))

    @staticmethod
    def _permissions(root: ET.Element) -> set[str | None]:
        return {
            node.attrib.get(f"{ANDROID_NS}name")
            for node in root.findall("uses-permission")
        }


if __name__ == "__main__":
    unittest.main()
