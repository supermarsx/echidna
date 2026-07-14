#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = REPO_ROOT / "tools" / "analyze_audio_hal_dump.py"

spec = importlib.util.spec_from_file_location("analyze_audio_hal_dump", TOOL_PATH)
assert spec is not None
analyzer = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["analyze_audio_hal_dump"] = analyzer
spec.loader.exec_module(analyzer)


def elf_blob(machine: int, payload: bytes) -> bytes:
    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4] = 2
    header[5] = 1
    header[16:18] = (3).to_bytes(2, "little")
    header[18:20] = machine.to_bytes(2, "little")
    return bytes(header) + b"\0" + payload


def write(path: Path, data: bytes | str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(data, bytes):
        path.write_bytes(data)
    else:
        path.write_text(data, encoding="utf-8")


class AudioHalDumpAnalyzerTest(unittest.TestCase):
    def test_samsung_exynos_dump_reports_hal_and_tinyalsa_candidates(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(
                root / "vendor" / "build.prop",
                "\n".join(
                    [
                        "ro.product.manufacturer=samsung",
                        "ro.product.model=SM-G973F",
                        "ro.board.platform=exynos9820",
                        "ro.product.cpu.abilist=arm64-v8a,armeabi-v7a",
                    ]
                ),
            )
            write(
                root / "vendor" / "lib64" / "hw" / "audio.primary.exynos9820.so",
                elf_blob(
                    183,
                    b"audio_stream_in_read\0IStreamIn\0readFrames\0SamsungPrimaryHal",
                ),
            )
            write(
                root / "vendor" / "lib64" / "libtinyalsa.so",
                elf_blob(183, b"pcm_read\0pcm_readi\0pcm_mmap_read"),
            )
            write(
                root / "vendor" / "etc" / "audio_policy_configuration.xml",
                "<module name=\"primary\"><mixPort name=\"primary input\" /></module>",
            )

            result = analyzer.analyze_root(root)

        self.assertEqual("samsung_exynos", result["vendorProfile"]["id"])
        self.assertEqual("high", result["vendorProfile"]["confidence"])
        surfaces = {item["surface"] for item in result["hookCandidates"]}
        self.assertIn("audio_hal_stream_in", surfaces)
        self.assertIn("tinyalsa_pcm", surfaces)
        self.assertTrue(result["kernelSignals"])
        self.assertIn("arm64-v8a", result["device"]["abis"])

    def test_samsung_qualcomm_profile_beats_generic_qualcomm(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(
                root / "vendor" / "build.prop",
                "\n".join(
                    [
                        "ro.product.manufacturer=samsung",
                        "ro.product.model=SM-S918B",
                        "ro.board.platform=kalama",
                    ]
                ),
            )
            write(
                root / "vendor" / "lib64" / "hw" / "audio.primary.kalama.so",
                elf_blob(183, b"open_input_stream\0audio_stream_in_read\0qcom"),
            )
            write(
                root / "system" / "lib64" / "libaudioclient.so",
                elf_blob(183, b"_ZN7android11AudioRecord4readEPvmb\0AudioRecord read"),
            )

            result = analyzer.analyze_root(root)

        self.assertEqual("samsung_qualcomm", result["vendorProfile"]["id"])
        profile_ids = [item["id"] for item in result["profileMatches"]]
        self.assertIn("qualcomm", profile_ids)
        scopes = {item["processScope"] for item in result["hookCandidates"]}
        self.assertIn("app_process", scopes)
        self.assertIn("audioserver_or_vendor_service", scopes)

    def test_empty_dump_warns_without_throwing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = analyzer.analyze_root(Path(tmp))

        self.assertEqual("unknown", result["vendorProfile"]["id"])
        self.assertTrue(result["warnings"])
        self.assertEqual([], result["libraries"])
        self.assertEqual([], result["hookCandidates"])

    def test_cli_writes_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "dump"
            out = Path(tmp) / "analysis.json"
            write(root / "system" / "build.prop", "ro.hardware=ranchu")
            write(
                root / "system" / "lib64" / "libOpenSLES.so",
                elf_blob(62, b"SLAndroidSimpleBufferQueueItf_Enqueue"),
            )

            completed = subprocess.run(
                [
                    sys.executable,
                    str(TOOL_PATH),
                    str(root),
                    "--output",
                    str(out),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual("", completed.stderr)
            self.assertEqual(0, completed.returncode)
            payload = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual("android_emulator", payload["vendorProfile"]["id"])
        self.assertEqual("opensl", payload["hookCandidates"][0]["surface"])


if __name__ == "__main__":
    unittest.main()
