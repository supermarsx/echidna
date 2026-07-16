#!/usr/bin/env python3
from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
APP_MANIFEST = REPO_ROOT / "android" / "app" / "app" / "src" / "main" / "AndroidManifest.xml"
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


if __name__ == "__main__":
    unittest.main()
