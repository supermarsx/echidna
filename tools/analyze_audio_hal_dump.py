#!/usr/bin/env python3
"""Analyze Android firmware or device dumps for Echidna audio hook coverage.

The analyzer is intentionally read-only. It scans extracted Android partitions
or adb-collected snapshots, classifies common SoC/vendor audio stacks, and emits
JSON that can guide Echidna hook validation without pretending static evidence is
live hook proof.
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

SCHEMA_VERSION = 1
DEFAULT_MAX_FILE_BYTES = 16 * 1024 * 1024

PROPERTY_FILES = {
    "build.prop",
    "default.prop",
    "prop.default",
    "vendor.default.prop",
}

TEXT_SIGNAL_SUFFIXES = {
    ".prop",
    ".xml",
    ".conf",
    ".rc",
    ".txt",
    ".json",
}

AUDIO_FILE_PATTERNS = [
    re.compile(r"(^|/)libOpenSLES\.so$", re.IGNORECASE),
    re.compile(r"(^|/)libaaudio\.so$", re.IGNORECASE),
    re.compile(r"(^|/)libaudioclient\.so$", re.IGNORECASE),
    re.compile(r"(^|/)libaudioflinger\.so$", re.IGNORECASE),
    re.compile(r"(^|/)libtinyalsa\.so$", re.IGNORECASE),
    re.compile(r"(^|/)libaudio[^/]*\.so$", re.IGNORECASE),
    re.compile(r"(^|/)audio\.[^/]*\.so$", re.IGNORECASE),
    re.compile(r"(^|/)android\.hardware\.audio[^/]*\.so$", re.IGNORECASE),
    re.compile(r"(^|/)vendor\.[^/]*audio[^/]*\.so$", re.IGNORECASE),
    re.compile(r"(^|/)audio[^/]*service[^/]*$", re.IGNORECASE),
]

KERNEL_SIGNAL_PATTERNS = [
    re.compile(r"(^|/)proc/asound/(cards|pcm)$", re.IGNORECASE),
    re.compile(r"(^|/)sys/kernel/debug/asoc/.*", re.IGNORECASE),
    re.compile(r"(^|/)proc/config\.gz$", re.IGNORECASE),
    re.compile(r"(^|/)kernel.*config.*$", re.IGNORECASE),
    re.compile(r"(^|/)audio_policy.*\.xml$", re.IGNORECASE),
    re.compile(r"(^|/)audio_platform_info.*\.xml$", re.IGNORECASE),
    re.compile(r"(^|/)mixer_paths.*\.xml$", re.IGNORECASE),
]

SURFACE_PATTERNS: dict[str, list[re.Pattern[str]]] = {
    "aaudio": [
        re.compile(r"AAudioStream_read"),
        re.compile(r"AAudioStream_setDataCallback"),
        re.compile(r"AAudioStreamBuilder_openStream"),
    ],
    "opensl": [
        re.compile(r"SLAndroidSimpleBufferQueueItf_Enqueue"),
        re.compile(r"SLAndroidSimpleBufferQueueItf_RegisterCallback"),
        re.compile(r"SLBufferQueueItf_Enqueue"),
        re.compile(r"SLBufferQueueItf_RegisterCallback"),
    ],
    "audiorecord_native": [
        re.compile(r"AudioRecord.*read", re.IGNORECASE),
        re.compile(r"_ZN7android11AudioRecord4read"),
    ],
    "audioflinger_record": [
        re.compile(r"AudioFlinger.*RecordThread", re.IGNORECASE),
        re.compile(r"_ZN7android12AudioFlinger11RecordThread10threadLoopEv"),
        re.compile(r"_ZN7android12AudioFlinger10RecordThread4read"),
        re.compile(r"_ZN7android12AudioFlinger10RecordThread13processVolume"),
    ],
    "tinyalsa_pcm": [
        re.compile(r"\bpcm_read\b"),
        re.compile(r"\bpcm_readi\b"),
        re.compile(r"\bpcm_mmap_read\b"),
        re.compile(r"\bpcm_get_htimestamp\b"),
    ],
    "audio_hal_stream_in": [
        re.compile(r"audio_stream_in_read"),
        re.compile(r"\bin_read\b"),
        re.compile(r"\badev_in_read\b"),
        re.compile(r"open_input_stream"),
        re.compile(r"IStreamIn", re.IGNORECASE),
        re.compile(r"readFrames", re.IGNORECASE),
    ],
    "audio_aidl_service": [
        re.compile(r"aidl.*android.*hardware.*audio", re.IGNORECASE),
        re.compile(r"IStreamIn", re.IGNORECASE),
        re.compile(r"IModule", re.IGNORECASE),
        re.compile(r"IStreamCommon", re.IGNORECASE),
    ],
    "audio_hidl_service": [
        re.compile(r"android\.hardware\.audio@"),
        re.compile(r"IDevicesFactory", re.IGNORECASE),
        re.compile(r"IPrimaryDevice", re.IGNORECASE),
        re.compile(r"IStreamIn", re.IGNORECASE),
    ],
}

KNOWN_HOOK_SYMBOLS = {
    "AAudioStream_read",
    "AAudioStream_setDataCallback",
    "AAudioStreamBuilder_openStream",
    "SLAndroidSimpleBufferQueueItf_Enqueue",
    "SLAndroidSimpleBufferQueueItf_RegisterCallback",
    "SLBufferQueueItf_Enqueue",
    "SLBufferQueueItf_RegisterCallback",
    "_ZN7android11AudioRecord4readEPvmb",
    "_ZN7android11AudioRecord4readEPvjb",
    "_ZN7android12AudioFlinger11RecordThread10threadLoopEv",
    "_ZN7android12AudioFlinger10RecordThread4readEPvjj",
    "_ZN7android12AudioFlinger10RecordThread13processVolumeEPKvj",
    "audio_stream_in_read",
    "pcm_read",
    "pcm_readi",
    "pcm_mmap_read",
    "in_read",
    "adev_in_read",
    "open_input_stream",
    "readFrames",
    "IStreamIn",
    "IDevicesFactory",
    "IPrimaryDevice",
    "IModule",
    "IStreamCommon",
}


@dataclass(frozen=True)
class VendorProfileRule:
    profile_id: str
    name: str
    property_patterns: Sequence[tuple[str, str]]
    path_patterns: Sequence[str]
    notes: Sequence[str]


@dataclass
class ProfileMatch:
    profile_id: str
    name: str
    score: int
    confidence: str
    evidence: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


@dataclass
class LibraryScan:
    path: str
    partition: str
    role: str
    process_scope: str
    arch: str
    elf_class: str
    size_bytes: int
    matched_symbols: list[str]
    matched_surfaces: list[str]
    soname: str | None
    needed: list[str]
    stripped_or_unreadable: bool


VENDOR_PROFILE_RULES = [
    VendorProfileRule(
        profile_id="samsung_exynos",
        name="Samsung Exynos",
        property_patterns=(
            ("ro.product.manufacturer", r"samsung"),
            ("ro.board.platform", r"(exynos|s5e|universal|erd|erd\d+)"),
        ),
        path_patterns=(r"samsung", r"exynos", r"s5e", r"audio\.primary\.exynos"),
        notes=(
            "Samsung Exynos HALs often need device-specific audioserver/HAL validation.",
            "Prefer collecting audioserver maps and audio policy dumps before changing hooks.",
        ),
    ),
    VendorProfileRule(
        profile_id="samsung_qualcomm",
        name="Samsung Qualcomm",
        property_patterns=(
            ("ro.product.manufacturer", r"samsung"),
            ("ro.board.platform", r"(qcom|msm|sm\d{3,5}|kona|lahaina|taro|kalama|pineapple)"),
        ),
        path_patterns=(r"samsung", r"qcom", r"audio\.primary\.(sm|kona|lahaina|taro|kalama)"),
        notes=(
            "Samsung Qualcomm combines Samsung policy files with Qualcomm primary HALs.",
            "Validate both libaudioclient app mappings and audioserver HAL libraries.",
        ),
    ),
    VendorProfileRule(
        profile_id="qualcomm",
        name="Qualcomm",
        property_patterns=(
            ("ro.board.platform", r"(qcom|msm|sm\d{3,5}|kona|lahaina|taro|kalama|pineapple)"),
            ("ro.hardware", r"(qcom|msm|sm\d{3,5})"),
        ),
        path_patterns=(r"qcom", r"qti", r"audio\.primary\.(msm|sm|kona|lahaina|taro|kalama)"),
        notes=(
            "Qualcomm stacks usually expose tinyalsa plus primary HAL candidates.",
            "Use live hook telemetry to distinguish app-process and audioserver coverage.",
        ),
    ),
    VendorProfileRule(
        profile_id="mediatek",
        name="MediaTek",
        property_patterns=(
            ("ro.board.platform", r"(mt\d{4}|mtk|mediatek)"),
            ("ro.hardware", r"(mt\d{4}|mtk|mediatek)"),
        ),
        path_patterns=(r"mediatek", r"mtk", r"audio\.primary\.mt"),
        notes=(
            "MediaTek HALs commonly require symbol discovery from vendor binaries.",
            "Capture audio policy and mixer paths because routing names vary by build.",
        ),
    ),
    VendorProfileRule(
        profile_id="google_tensor",
        name="Google Tensor",
        property_patterns=(
            ("ro.product.manufacturer", r"google"),
            ("ro.board.platform", r"(gs101|gs201|zuma|gs\d+)"),
        ),
        path_patterns=(r"google", r"tensor", r"gs101", r"gs201", r"zuma"),
        notes=(
            "Tensor devices are useful arm64 baselines for app-process hook validation.",
            "Vendor HAL proof still needs audioserver maps and live telemetry.",
        ),
    ),
    VendorProfileRule(
        profile_id="android_emulator",
        name="Android Emulator",
        property_patterns=(
            ("ro.hardware", r"(ranchu|goldfish)"),
            ("ro.kernel.qemu", r"1"),
        ),
        path_patterns=(r"ranchu", r"goldfish", r"emulator"),
        notes=(
            "Emulator findings are good smoke signals, not vendor HAL proof.",
            "Do not infer Samsung or physical-device coverage from this profile.",
        ),
    ),
]


def normalize_rel_path(path: Path) -> str:
    return path.as_posix().lstrip("./")


def rel_path(root: Path, path: Path) -> str:
    return normalize_rel_path(path.relative_to(root))


def partition_for(path: str) -> str:
    first = path.split("/", 1)[0]
    return first if first in {"system", "system_ext", "vendor", "odm", "product"} else "unknown"


def path_matches(path: str, patterns: Iterable[re.Pattern[str]]) -> bool:
    normalized = "/" + path.replace("\\", "/")
    return any(pattern.search(normalized) for pattern in patterns)


def is_audio_file(path: str) -> bool:
    return path_matches(path, AUDIO_FILE_PATTERNS)


def is_kernel_signal_file(path: str) -> bool:
    return path_matches(path, KERNEL_SIGNAL_PATTERNS)


def read_limited(path: Path, max_bytes: int) -> bytes:
    size = path.stat().st_size
    if size <= max_bytes:
        return path.read_bytes()

    half = max_bytes // 2
    with path.open("rb") as handle:
        head = handle.read(half)
        handle.seek(max(size - half, 0))
        tail = handle.read(half)
    return head + b"\n__ECHIDNA_TRUNCATED__\n" + tail


def read_text_signal(path: Path, max_bytes: int) -> str:
    if path.name.endswith(".gz"):
        with gzip.open(path, "rb") as handle:
            return handle.read(max_bytes).decode("utf-8", "ignore")
    return read_limited(path, max_bytes).decode("utf-8", "ignore")


def extract_ascii_strings(data: bytes, min_len: int = 4) -> list[str]:
    strings: list[str] = []
    current = bytearray()
    for byte in data:
        if 32 <= byte <= 126:
            current.append(byte)
            continue
        if len(current) >= min_len:
            strings.append(current.decode("ascii", "ignore"))
        current.clear()
    if len(current) >= min_len:
        strings.append(current.decode("ascii", "ignore"))
    return strings


def parse_elf_arch(data: bytes) -> tuple[str, str]:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return "not_elf_or_unknown", "unknown"

    elf_class = {1: "ELF32", 2: "ELF64"}.get(data[4], "unknown")
    byte_order = "little" if data[5] == 1 else "big"
    machine = int.from_bytes(data[18:20], byte_order, signed=False)
    arch = {
        3: "x86",
        40: "arm",
        62: "x86_64",
        183: "aarch64",
    }.get(machine, f"machine_{machine}")
    return arch, elf_class


def find_readelf() -> str | None:
    override = os.getenv("ECHIDNA_READELF")
    if override:
        return override
    for candidate in ("llvm-readelf", "readelf"):
        found = shutil.which(candidate)
        if found:
            return found
    return None


def run_readelf(path: Path) -> str:
    tool = find_readelf()
    if not tool:
        return ""
    chunks: list[str] = []
    for args in (("--dyn-syms", "--wide"), ("-d", "--wide")):
        try:
            result = subprocess.run(
                [tool, *args, str(path)],
                check=False,
                capture_output=True,
                text=True,
                timeout=8,
            )
        except (OSError, subprocess.TimeoutExpired):
            continue
        if result.stdout:
            chunks.append(result.stdout)
    return "\n".join(chunks)


def parse_soname(readelf_text: str) -> str | None:
    match = re.search(r"\(SONAME\).*Shared library: \[(?P<name>[^\]]+)\]", readelf_text)
    return match.group("name") if match else None


def parse_needed(readelf_text: str) -> list[str]:
    return sorted(
        {
            match.group("name")
            for match in re.finditer(
                r"\(NEEDED\).*Shared library: \[(?P<name>[^\]]+)\]",
                readelf_text,
            )
        }
    )


def match_symbols(text: str) -> list[str]:
    matches = {symbol for symbol in KNOWN_HOOK_SYMBOLS if symbol in text}
    for surface_patterns in SURFACE_PATTERNS.values():
        for pattern in surface_patterns:
            for match in pattern.finditer(text):
                value = match.group(0).strip()
                if value:
                    matches.add(value)
    return sorted(matches)


def match_surfaces(text: str) -> list[str]:
    surfaces: list[str] = []
    for surface, patterns in SURFACE_PATTERNS.items():
        if any(pattern.search(text) for pattern in patterns):
            surfaces.append(surface)
    return surfaces


def classify_library(path: str, surfaces: Sequence[str]) -> tuple[str, str]:
    lower = path.lower()
    if lower.endswith("libopensles.so") or "opensl" in surfaces:
        return "opensl", "app_process"
    if lower.endswith("libaaudio.so") or "aaudio" in surfaces:
        return "aaudio", "app_process"
    if lower.endswith("libaudioclient.so") or "audiorecord_native" in surfaces:
        return "audiorecord_or_client", "app_process"
    if lower.endswith("libaudioflinger.so") or "audioflinger_record" in surfaces:
        return "audioflinger", "audioserver"
    if lower.endswith("libtinyalsa.so") or "tinyalsa_pcm" in surfaces:
        return "tinyalsa", "app_or_audio_service"
    if "android.hardware.audio" in lower or "audio_aidl_service" in surfaces:
        return "audio_service_binder", "audioserver_or_vendor_service"
    if "audio.primary" in lower or "audio_hal_stream_in" in surfaces:
        return "audio_hal", "audioserver_or_vendor_service"
    if "audio" in lower:
        return "audio_related", "unknown"
    return "unknown", "unknown"


def parse_properties(root: Path, max_bytes: int) -> dict[str, str]:
    properties: dict[str, str] = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.name not in PROPERTY_FILES:
            continue
        for line in read_text_signal(path, max_bytes).splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key, value = stripped.split("=", 1)
            properties.setdefault(key.strip(), value.strip())
    return properties


def profile_score(
    rule: VendorProfileRule,
    properties: dict[str, str],
    audio_paths: Sequence[str],
) -> ProfileMatch:
    score = 0
    evidence: list[str] = []
    for key, pattern in rule.property_patterns:
        value = properties.get(key, "")
        if value and re.search(pattern, value, re.IGNORECASE):
            score += 3
            evidence.append(f"{key}={value}")
    for path in audio_paths:
        for pattern in rule.path_patterns:
            if re.search(pattern, path, re.IGNORECASE):
                score += 1
                evidence.append(path)
                break
    confidence = "none"
    if score >= 7:
        confidence = "high"
    elif score >= 4:
        confidence = "medium"
    elif score > 0:
        confidence = "low"
    return ProfileMatch(
        profile_id=rule.profile_id,
        name=rule.name,
        score=score,
        confidence=confidence,
        evidence=sorted(set(evidence))[:12],
        notes=list(rule.notes),
    )


def select_vendor_profile(
    properties: dict[str, str],
    audio_paths: Sequence[str],
) -> tuple[ProfileMatch, list[ProfileMatch]]:
    matches = [
        profile_score(rule, properties, audio_paths)
        for rule in VENDOR_PROFILE_RULES
    ]
    matches.sort(key=lambda item: item.score, reverse=True)
    best = matches[0] if matches and matches[0].score > 0 else ProfileMatch(
        profile_id="unknown",
        name="Unknown",
        score=0,
        confidence="none",
        evidence=[],
        notes=[
            "No known vendor profile matched. Treat all HAL findings as exploratory.",
        ],
    )
    return best, [match for match in matches if match.score > 0]


def scan_library(root: Path, path: Path, max_bytes: int) -> LibraryScan:
    relative = rel_path(root, path)
    data = read_limited(path, max_bytes)
    arch, elf_class = parse_elf_arch(data)
    extracted = "\n".join(extract_ascii_strings(data))
    readelf_text = run_readelf(path)
    combined_text = "\n".join((relative, extracted, readelf_text))
    matched_symbols = match_symbols(combined_text)
    matched_surfaces = match_surfaces(combined_text)
    role, process_scope = classify_library(relative, matched_surfaces)
    return LibraryScan(
        path=relative,
        partition=partition_for(relative),
        role=role,
        process_scope=process_scope,
        arch=arch,
        elf_class=elf_class,
        size_bytes=path.stat().st_size,
        matched_symbols=matched_symbols,
        matched_surfaces=matched_surfaces,
        soname=parse_soname(readelf_text),
        needed=parse_needed(readelf_text),
        stripped_or_unreadable=not matched_symbols and arch != "not_elf_or_unknown",
    )


def build_hook_candidates(libraries: Sequence[LibraryScan]) -> list[dict[str, object]]:
    candidates: list[dict[str, object]] = []
    for library in libraries:
        surfaces = library.matched_surfaces or [library.role]
        for surface in surfaces:
            if surface in {"unknown", "audio_related"}:
                continue
            score = 40
            score += min(len(library.matched_symbols), 6) * 8
            if library.arch in {"aarch64", "x86_64"}:
                score += 10
            if library.process_scope == "app_process":
                score += 10
            if library.process_scope in {"audioserver", "audioserver_or_vendor_service"}:
                score -= 5
            score = max(0, min(score, 95))
            risk = "low" if library.process_scope == "app_process" else "device_gated"
            candidates.append(
                {
                    "surface": surface,
                    "library": library.path,
                    "role": library.role,
                    "processScope": library.process_scope,
                    "confidence": confidence_from_score(score),
                    "score": score,
                    "risk": risk,
                    "symbols": library.matched_symbols[:12],
                    "notes": candidate_notes(library, surface),
                }
            )
    candidates.sort(
        key=lambda item: (
            int(item["score"]),
            str(item["surface"]),
            str(item["library"]),
        ),
        reverse=True,
    )
    return candidates


def confidence_from_score(score: int) -> str:
    if score >= 75:
        return "high"
    if score >= 55:
        return "medium"
    if score >= 35:
        return "low"
    return "exploratory"


def candidate_notes(library: LibraryScan, surface: str) -> list[str]:
    notes: list[str] = []
    if library.process_scope == "app_process":
        notes.append("Can be validated from a scoped target app if the library maps there.")
    elif library.process_scope == "audioserver":
        notes.append("Requires audioserver evidence; app-process Zygisk alone is not proof.")
    elif library.process_scope == "audioserver_or_vendor_service":
        notes.append("Requires audioserver or vendor-service maps plus live telemetry.")
    if surface in {"audio_hal_stream_in", "audio_aidl_service", "audio_hidl_service"}:
        notes.append("Treat as vendor-HAL evidence until a rooted device proves callback hits.")
    if library.stripped_or_unreadable:
        notes.append("No known hook symbols were visible; offsets may need device discovery.")
    return notes


def collect_kernel_signals(root: Path, max_bytes: int) -> list[dict[str, object]]:
    signals: list[dict[str, object]] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        relative = rel_path(root, path)
        if not is_kernel_signal_file(relative):
            continue
        text = read_text_signal(path, max_bytes)
        lower = text.lower()
        markers = []
        for marker in (
            "capture",
            "input",
            "primary",
            "voice",
            "compress",
            "deep_buffer",
            "fast",
            "mmap",
            "pcm",
            "snd_soc",
            "tinyalsa",
        ):
            if marker in lower:
                markers.append(marker)
        signals.append(
            {
                "path": relative,
                "partition": partition_for(relative),
                "markers": markers,
                "lineCount": text.count("\n") + (1 if text else 0),
            }
        )
    return signals


def device_summary(properties: dict[str, str]) -> dict[str, object]:
    abi_raw = properties.get("ro.product.cpu.abilist", "")
    return {
        "manufacturer": first_property(
            properties,
            "ro.product.manufacturer",
            "ro.product.vendor.manufacturer",
        ),
        "brand": first_property(properties, "ro.product.brand", "ro.product.vendor.brand"),
        "model": first_property(properties, "ro.product.model", "ro.product.vendor.model"),
        "boardPlatform": first_property(
            properties,
            "ro.board.platform",
            "ro.hardware",
            "ro.product.board",
        ),
        "apiLevel": first_property(properties, "ro.build.version.sdk"),
        "abis": [value for value in abi_raw.split(",") if value],
    }


def first_property(properties: dict[str, str], *keys: str) -> str:
    for key in keys:
        value = properties.get(key)
        if value:
            return value
    return ""


def recommendation_list(
    profile: ProfileMatch,
    libraries: Sequence[LibraryScan],
    candidates: Sequence[dict[str, object]],
    kernel_signals: Sequence[dict[str, object]],
) -> list[str]:
    surfaces = {str(candidate["surface"]) for candidate in candidates}
    recommendations = [
        "Validate static candidates on a rooted device with Echidna hook telemetry.",
        "Collect /proc/<pid>/maps for target apps, audioserver, and vendor audio services.",
        "Run dumpsys media.audio_flinger and dumpsys media.audio_policy on the same build.",
    ]
    if profile.profile_id.startswith("samsung"):
        recommendations.append(
            "For Samsung, keep Exynos and Snapdragon results separate; do not merge offsets."
        )
    if "audio_hal_stream_in" in surfaces or "audio_aidl_service" in surfaces:
        recommendations.append(
            "HAL candidates need audioserver/vendor-service proof before release claims."
        )
    if "opensl" not in surfaces:
        recommendations.append("No OpenSL symbols found; test OpenSL apps before claiming coverage.")
    if "tinyalsa_pcm" not in surfaces:
        recommendations.append("No tinyalsa read symbols found; HAL fallback may be unavailable.")
    if not any(library.arch in {"aarch64", "arm"} for library in libraries):
        recommendations.append("No ARM ELF audio libraries found; this is not physical ARM proof.")
    if not kernel_signals:
        recommendations.append(
            "No audio policy, mixer path, or kernel/asound signals were present in the dump."
        )
    return recommendations


def warning_list(
    libraries: Sequence[LibraryScan],
    candidates: Sequence[dict[str, object]],
) -> list[str]:
    warnings: list[str] = []
    if not libraries:
        warnings.append("No audio-related libraries were found in the scanned root.")
    if not candidates:
        warnings.append("No hook candidates were found; dump may be incomplete or stripped.")
    if any(candidate["risk"] == "device_gated" for candidate in candidates):
        warnings.append(
            "Device-gated HAL candidates are static evidence only; they are not live hook proof."
        )
    if any(library.stripped_or_unreadable for library in libraries):
        warnings.append("Some ELF files exposed no known symbols; offset discovery may be required.")
    return warnings


def analyze_root(root: Path, max_file_bytes: int = DEFAULT_MAX_FILE_BYTES) -> dict[str, object]:
    root = root.resolve()
    if not root.exists() or not root.is_dir():
        raise ValueError(f"root must be an existing directory: {root}")

    properties = parse_properties(root, max_file_bytes)
    audio_paths = [
        rel_path(root, path)
        for path in sorted(root.rglob("*"))
        if path.is_file() and is_audio_file(rel_path(root, path))
    ]
    profile, profile_matches = select_vendor_profile(properties, audio_paths)
    libraries = [
        scan_library(root, root / relative, max_file_bytes)
        for relative in audio_paths
    ]
    candidates = build_hook_candidates(libraries)
    kernel_signals = collect_kernel_signals(root, max_file_bytes)

    return {
        "schemaVersion": SCHEMA_VERSION,
        "root": str(root),
        "device": device_summary(properties),
        "vendorProfile": profile_to_json(profile),
        "profileMatches": [profile_to_json(match) for match in profile_matches],
        "propertiesFound": sorted(properties.keys()),
        "libraries": [library_to_json(library) for library in libraries],
        "hookCandidates": candidates,
        "kernelSignals": kernel_signals,
        "recommendations": recommendation_list(
            profile,
            libraries,
            candidates,
            kernel_signals,
        ),
        "warnings": warning_list(libraries, candidates),
    }


def profile_to_json(profile: ProfileMatch) -> dict[str, object]:
    return {
        "id": profile.profile_id,
        "name": profile.name,
        "score": profile.score,
        "confidence": profile.confidence,
        "evidence": profile.evidence,
        "notes": profile.notes,
    }


def library_to_json(library: LibraryScan) -> dict[str, object]:
    return {
        "path": library.path,
        "partition": library.partition,
        "role": library.role,
        "processScope": library.process_scope,
        "arch": library.arch,
        "elfClass": library.elf_class,
        "sizeBytes": library.size_bytes,
        "matchedSymbols": library.matched_symbols,
        "matchedSurfaces": library.matched_surfaces,
        "soname": library.soname,
        "needed": library.needed,
        "strippedOrUnreadable": library.stripped_or_unreadable,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "root",
        type=Path,
        help="Extracted Android root, firmware dump, or adb-collected snapshot.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Write JSON analysis to this path instead of stdout.",
    )
    parser.add_argument(
        "--max-file-bytes",
        type=int,
        default=DEFAULT_MAX_FILE_BYTES,
        help="Maximum bytes to sample from any one file (default: 16 MiB).",
    )
    parser.add_argument(
        "--compact",
        action="store_true",
        help="Emit compact JSON.",
    )
    return parser


def main(argv: list[str]) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        result = analyze_root(args.root, max_file_bytes=args.max_file_bytes)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    indent = None if args.compact else 2
    payload = json.dumps(result, indent=indent, sort_keys=True)
    if args.output:
        args.output.write_text(payload + "\n", encoding="utf-8")
    else:
        print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
