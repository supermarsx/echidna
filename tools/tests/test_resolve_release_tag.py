#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import sys
import unittest
from datetime import date
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = REPO_ROOT / "tools" / "resolve_release_tag.py"

spec = importlib.util.spec_from_file_location("resolve_release_tag", TOOL_PATH)
assert spec is not None
resolver = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["resolve_release_tag"] = resolver
spec.loader.exec_module(resolver)


class ReleaseTagResolverTest(unittest.TestCase):
    def test_main_push_computes_next_current_year_tag(self) -> None:
        result = resolver.resolve_tag(
            event_name="push",
            ref="refs/heads/main",
            manual_tag="",
            existing_tags={"25.99", "26.1", "26.13", "invalid"},
            today=date(2026, 7, 15),
        )

        self.assertEqual("26.14", result.tag)
        self.assertEqual("ci", result.source)
        self.assertTrue(result.should_create_tag)

    def test_tag_push_uses_existing_valid_tag_without_creation(self) -> None:
        result = resolver.resolve_tag(
            event_name="push",
            ref="refs/tags/26.14",
            manual_tag="",
            existing_tags={"26.14"},
            today=date(2026, 7, 15),
        )

        self.assertEqual("26.14", result.tag)
        self.assertEqual("push", result.source)
        self.assertFalse(result.should_create_tag)

    def test_invalid_manual_or_non_main_ci_release_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "expected YY.N"):
            resolver.resolve_tag(
                event_name="workflow_dispatch",
                ref="refs/heads/main",
                manual_tag="v26.14",
                existing_tags=set(),
                today=date(2026, 7, 15),
            )
        with self.assertRaisesRegex(ValueError, "refs/heads/main"):
            resolver.resolve_tag(
                event_name="workflow_call",
                ref="refs/heads/feature",
                manual_tag="",
                existing_tags=set(),
                today=date(2026, 7, 15),
            )


if __name__ == "__main__":
    unittest.main()
