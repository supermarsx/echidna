#!/usr/bin/env python3
"""Build and verify a compact comparison of two Echidna audio benchmark reports."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import subprocess
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any, NoReturn


REPO_ROOT = Path(__file__).resolve().parents[2]
BENCHMARK_SOURCE = REPO_ROOT / "tools/perf/audio_pipeline_benchmark.cpp"
REPORT_SCHEMA = REPO_ROOT / "tools/perf/audio_pipeline_result.schema.json"
DSP_API_HEADER = REPO_ROOT / "native/dsp/include/echidna/dsp/api.h"
PRODUCTION_SOURCE_ROOTS = (
    REPO_ROOT / "native/dsp/CMakeLists.txt",
    REPO_ROOT / "native/dsp/include",
    REPO_ROOT / "native/dsp/src",
    REPO_ROOT / "native/include/echidna_api.h",
    REPO_ROOT / "native/zygisk/src/audio/pcm_buffer_processor.cpp",
    REPO_ROOT / "native/zygisk/src/audio/pcm_buffer_processor.h",
    REPO_ROOT / "tools/perf/CMakeLists.txt",
    BENCHMARK_SOURCE,
    REPORT_SCHEMA,
    REPO_ROOT / "tools/perf/run_audio_pipeline_benchmark.ps1",
    REPO_ROOT / "tools/perf/run_audio_pipeline_benchmark.sh",
    REPO_ROOT / "tools/perf/validate_audio_pipeline_report.py",
)
COMPARISON_SCHEMA_VERSION = "1.0.0"
EXPECTED_SCENARIOS = 592
EXPECTED_VALIDATIONS = 40
EXPECTED_LOADS = {0: 296, 8: 296}
EXPECTED_GROUPS = {
    (profile, path, load)
    for profile in ("memcpy_baseline", "neutral_dsp", "voice_fx_ll")
    for path in ("float32", "pcm16_bridge")
    for load in (0, 8)
} | {
    ("autotune_hq", path, load)
    for path in ("float32", "pcm16_bridge")
    for load in (0, 8)
}
THRESHOLDS = {
    "functional_failures_max": 0,
    "strict_deadline_misses_max": 0,
    "unloaded_safety_failures_max": 0,
    "group_average_latency_ratio": 1.35,
    "group_average_latency_min_absolute_us": 5.0,
    "group_worst_p99_ratio": 1.50,
    "group_worst_p99_min_absolute_us": 20.0,
    "group_minimum_throughput_ratio": 0.75,
    "audio_metric_max_absolute_delta": 1.0e-9,
}
ALLOWED_CONTRACT_CHANGES = {
    "public_api_version": {
        "baseline_version": 0x00010100,
        "current_version": 0x00010200,
        "change_reference": "e7c2f1f597554f7ec97e3af2799a0f24ce11d1f8",
        "rationale": (
            "DSP ABI 1.2 adds independently owned per-stream engine handles; "
            "runtime and header versions must advance together."
        ),
    },
}


class ComparisonError(ValueError):
    """Raised when reports are incomparable or compact evidence is invalid."""


def fail(message: str) -> NoReturn:
    raise ComparisonError(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as handle:
            value = json.load(handle, parse_constant=lambda token: fail(f"invalid {token}"))
    except (OSError, json.JSONDecodeError) as error:
        raise ComparisonError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        fail(f"{path}: root must be an object")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as error:
        raise ComparisonError(f"cannot hash {path}: {error}") from error
    return digest.hexdigest()


def canonical_source_bytes(path: Path) -> bytes:
    try:
        contents = path.read_bytes()
    except OSError as error:
        raise ComparisonError(f"cannot hash {path}: {error}") from error
    try:
        contents.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ComparisonError(f"cannot hash {path}: source is not UTF-8: {error}") from error
    return contents.replace(b"\r\n", b"\n")


def source_sha256(path: Path) -> str:
    return hashlib.sha256(canonical_source_bytes(path)).hexdigest()


def production_source_files() -> list[Path]:
    files: set[Path] = set()
    for root in PRODUCTION_SOURCE_ROOTS:
        if root.is_file():
            files.add(root)
        elif root.is_dir():
            files.update(path for path in root.rglob("*") if path.is_file())
        else:
            fail(f"production source path is missing: {root}")
    return sorted(files, key=lambda path: path.relative_to(REPO_ROOT).as_posix())


def production_source_bundle_sha256(files: list[Path]) -> str:
    digest = hashlib.sha256()
    for path in files:
        relative = path.relative_to(REPO_ROOT).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        contents = canonical_source_bytes(path)
        digest.update(len(contents).to_bytes(8, "big"))
        digest.update(contents)
    return digest.hexdigest()


def dsp_api_version_from_source() -> int:
    try:
        source = DSP_API_HEADER.read_text(encoding="utf-8")
    except OSError as error:
        raise ComparisonError(f"cannot read {DSP_API_HEADER}: {error}") from error
    components: dict[str, int] = {}
    for name, value in re.findall(
        r"^#define\s+ECH_DSP_API_VERSION_(MAJOR|MINOR|PATCH)\s+(\d+)U?\s*$",
        source,
        flags=re.MULTILINE,
    ):
        components[name] = int(value)
    if set(components) != {"MAJOR", "MINOR", "PATCH"}:
        fail("cannot resolve the DSP API version from its public header")
    if any(value < 0 or value > 255 for value in components.values()):
        fail("DSP API version components exceed the packed version contract")
    return (
        (components["MAJOR"] << 16)
        | (components["MINOR"] << 8)
        | components["PATCH"]
    )


def contract_change_record(check_id: str) -> dict[str, Any]:
    allowed = ALLOWED_CONTRACT_CHANGES.get(check_id)
    if allowed is None:
        fail(f"{check_id}: contract change is not allowlisted")
    source_version = dsp_api_version_from_source()
    if allowed["current_version"] != source_version:
        fail(f"{check_id}: allowlist is stale for the source-declared API version")
    return {
        "baseline_version": allowed["baseline_version"],
        "current_version": allowed["current_version"],
        "source_header": str(DSP_API_HEADER.relative_to(REPO_ROOT)).replace("\\", "/"),
        "source_declared_version": source_version,
        "change_reference": allowed["change_reference"],
        "rationale": allowed["rationale"],
    }


def finite_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        fail(f"{label}: expected number")
    number = float(value)
    if not math.isfinite(number):
        fail(f"{label}: expected finite number")
    return number


def parse_timestamp(value: Any, label: str) -> str:
    if not isinstance(value, str):
        fail(f"{label}: expected string timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ComparisonError(f"{label}: invalid timestamp: {error}") from error
    if parsed.tzinfo is None:
        fail(f"{label}: timestamp must include a timezone")
    return value


def require_dict(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(f"{label}: expected object")
    return value


def require_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        fail(f"{label}: expected array")
    return value


def validate_report(report: dict[str, Any], label: str) -> None:
    if report.get("schema_version") != "1.0.0":
        fail(f"{label}: unsupported report schema")
    parse_timestamp(report.get("generated_utc"), f"{label}.generated_utc")
    methodology = require_dict(report.get("methodology"), f"{label}.methodology")
    environment = require_dict(report.get("environment"), f"{label}.environment")
    validations = require_list(report.get("validations"), f"{label}.validations")
    performance = require_list(report.get("performance"), f"{label}.performance")
    if methodology.get("mode") != "full":
        fail(f"{label}: report is not a full matrix")
    if methodology.get("warmups_per_scenario") != 200:
        fail(f"{label}: expected 200 warmups")
    if methodology.get("measured_iterations_per_scenario") != 1200:
        fail(f"{label}: expected 1200 measured iterations")
    if methodology.get("percentile_method") != "nearest-rank ceil(p*n), no interpolation":
        fail(f"{label}: percentile method changed")
    if len(validations) != EXPECTED_VALIDATIONS:
        fail(f"{label}: expected {EXPECTED_VALIDATIONS} validations")
    if len(performance) != EXPECTED_SCENARIOS:
        fail(f"{label}: expected {EXPECTED_SCENARIOS} scenarios")
    if not isinstance(environment.get("compiler"), str):
        fail(f"{label}: compiler is missing")

    load_counts: dict[int, int] = defaultdict(int)
    groups: set[tuple[str, str, int]] = set()
    scenario_ids: set[str] = set()
    for index, scenario_value in enumerate(performance):
        scenario = require_dict(scenario_value, f"{label}.performance[{index}]")
        scenario_id = scenario.get("scenario_id")
        profile = scenario.get("profile")
        path = scenario.get("path")
        load = scenario.get("background_load_threads")
        if not isinstance(scenario_id, str) or scenario_id in scenario_ids:
            fail(f"{label}: missing or duplicate scenario id")
        if not isinstance(profile, str) or not isinstance(path, str) or not isinstance(load, int):
            fail(f"{label}.{scenario_id}: invalid group identity")
        scenario_ids.add(scenario_id)
        groups.add((profile, path, load))
        load_counts[load] += 1
        latency = require_dict(scenario.get("latency_us"), f"{label}.{scenario_id}.latency")
        for percentile in ("p50", "p95", "p99"):
            finite_number(latency.get(percentile), f"{label}.{scenario_id}.{percentile}")
        finite_number(scenario.get("frames_per_second"), f"{label}.{scenario_id}.fps")
        finite_number(scenario.get("deadline_us"), f"{label}.{scenario_id}.deadline")
        if scenario.get("setup_ok") is not True or scenario.get("processing_ok") is not True:
            fail(f"{label}.{scenario_id}: setup or processing failed")
    if dict(load_counts) != EXPECTED_LOADS:
        fail(f"{label}: unexpected load matrix {dict(load_counts)}")
    if groups != EXPECTED_GROUPS:
        fail(f"{label}: unexpected profile/path/load groups")


def comparable(baseline: dict[str, Any], current: dict[str, Any]) -> None:
    validate_report(baseline, "baseline")
    validate_report(current, "current")
    ignored_methodology = {"observable_checksum"}
    baseline_methodology = {
        key: value
        for key, value in baseline["methodology"].items()
        if key not in ignored_methodology
    }
    current_methodology = {
        key: value
        for key, value in current["methodology"].items()
        if key not in ignored_methodology
    }
    if baseline_methodology != current_methodology:
        fail("benchmark methodology differs from baseline")
    if baseline["environment"] != current["environment"]:
        fail("benchmark environment differs from baseline")
    baseline_ids = {item["scenario_id"] for item in baseline["performance"]}
    current_ids = {item["scenario_id"] for item in current["performance"]}
    if baseline_ids != current_ids:
        fail("scenario inventories differ")
    baseline_checks = {item["id"] for item in baseline["validations"]}
    current_checks = {item["id"] for item in current["validations"]}
    if baseline_checks != current_checks:
        fail("functional validation inventories differ")


def rounded(value: float) -> float:
    return round(value, 9)


def ratio(current: float, baseline: float) -> float:
    if baseline == 0.0:
        return 1.0 if current == 0.0 else math.inf
    return current / baseline


def aggregate_scenarios(scenarios: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(scenarios)
    if count == 0:
        fail("cannot aggregate an empty scenario group")
    averages = {
        percentile: sum(float(item["latency_us"][percentile]) for item in scenarios) / count
        for percentile in ("p50", "p95", "p99")
    }
    return {
        "scenario_count": count,
        "average_p50_us": rounded(averages["p50"]),
        "average_p95_us": rounded(averages["p95"]),
        "average_p99_us": rounded(averages["p99"]),
        "worst_p99_us": rounded(max(float(item["latency_us"]["p99"]) for item in scenarios)),
        "maximum_deadline_utilization": rounded(
            max(float(item["latency_us"]["p99"]) / float(item["deadline_us"])
                for item in scenarios)
        ),
        "minimum_throughput_frames_per_second": rounded(
            min(float(item["frames_per_second"]) for item in scenarios)
        ),
        "strict_deadline_misses": sum(not item["strict_deadline_met"] for item in scenarios),
        "safety_gate_failures": sum(
            item["safety_gate_applies"] and not item["safety_gate_passed"]
            for item in scenarios
        ),
    }


def audio_checks(
    baseline: dict[str, Any], current: dict[str, Any]
) -> tuple[list[dict[str, Any]], dict[str, float], int, int]:
    baseline_by_id = {item["id"]: item for item in baseline["validations"]}
    current_by_id = {item["id"]: item for item in current["validations"]}
    checks: list[dict[str, Any]] = []
    maximum_deltas: dict[str, float] = {}
    failures = 0
    contract_changes = 0
    for check_id in sorted(baseline_by_id):
        old = baseline_by_id[check_id]
        new = current_by_id[check_id]
        if old.get("effect") != new.get("effect") or old.get("fixture") != new.get("fixture"):
            fail(f"validation metadata changed for {check_id}")
        old_metrics = require_dict(old.get("metrics"), f"baseline validation {check_id}")
        new_metrics = require_dict(new.get("metrics"), f"current validation {check_id}")
        if set(old_metrics) != set(new_metrics):
            fail(f"validation metric inventory changed for {check_id}")
        deltas = {
            metric: rounded(
                finite_number(new_metrics[metric], f"{check_id}.{metric}.current")
                - finite_number(old_metrics[metric], f"{check_id}.{metric}.baseline")
            )
            for metric in sorted(old_metrics)
        }
        maximum = max((abs(value) for value in deltas.values()), default=0.0)
        maximum_deltas[check_id] = maximum
        allowed_contract_change = check_id in ALLOWED_CONTRACT_CHANGES
        contract_change = None
        if allowed_contract_change:
            contract_change = contract_change_record(check_id)
            expected_baseline = {
                "header_version": contract_change["baseline_version"],
                "runtime_version": contract_change["baseline_version"],
            }
            expected_current = {
                "header_version": contract_change["current_version"],
                "runtime_version": contract_change["current_version"],
            }
            if old_metrics != expected_baseline or new_metrics != expected_current:
                fail(f"{check_id}: metrics do not match the allowlisted contract change")
            contract_changes += 1
        passed = old.get("passed") is True and new.get("passed") is True
        if (
            not passed
            or not allowed_contract_change
            and maximum > THRESHOLDS["audio_metric_max_absolute_delta"]
        ):
            failures += 1
        result = {
            "id": check_id,
            "effect": old["effect"],
            "fixture": old["fixture"],
            "baseline_passed": old.get("passed") is True,
            "current_passed": new.get("passed") is True,
            "baseline_metrics": old_metrics,
            "current_metrics": new_metrics,
            "metric_deltas": deltas,
            "maximum_metric_absolute_delta": maximum,
            "metric_delta_policy": (
                "allowlisted_contract_change"
                if allowed_contract_change
                else "exact_audio_metrics"
            ),
        }
        if contract_change is not None:
            result["contract_change"] = contract_change
        checks.append(result)
    return checks, maximum_deltas, failures, contract_changes


def validation_ids_for_group(profile: str, path: str, check_ids: set[str]) -> list[str]:
    if profile == "memcpy_baseline":
        prefixes = ("benchmark_output_dependency",)
    elif profile == "neutral_dsp":
        prefixes = ("neutral_", "stereo_channel_isolation")
        if path == "pcm16_bridge":
            prefixes += ("pcm16_",)
    elif profile == "voice_fx_ll":
        prefixes = (
            "eq_",
            "compressor_",
            "gate_",
            "pitch_",
            "formant_",
            "reverb_",
            "mix_",
            "full_chain_",
            "stereo_channel_isolation",
        )
        if path == "pcm16_bridge":
            prefixes += ("pcm16_",)
    else:
        prefixes = ("autotune_", "pitch_")
        if path == "pcm16_bridge":
            prefixes += ("pcm16_",)
    return sorted(check_id for check_id in check_ids if check_id.startswith(prefixes))


def material_reasons(
    baseline: dict[str, Any], current: dict[str, Any]
) -> tuple[list[str], dict[str, Any]]:
    reasons: list[str] = []
    comparison: dict[str, Any] = {}
    for percentile in ("p50", "p95", "p99"):
        key = f"average_{percentile}_us"
        absolute = float(current[key]) - float(baseline[key])
        relative = ratio(float(current[key]), float(baseline[key]))
        comparison[f"average_{percentile}_ratio"] = rounded(relative)
        comparison[f"average_{percentile}_absolute_delta_us"] = rounded(absolute)
        if (
            relative > THRESHOLDS["group_average_latency_ratio"]
            and absolute > THRESHOLDS["group_average_latency_min_absolute_us"]
        ):
            reasons.append(f"average_{percentile}_latency")
    worst_absolute = float(current["worst_p99_us"]) - float(baseline["worst_p99_us"])
    worst_ratio = ratio(float(current["worst_p99_us"]), float(baseline["worst_p99_us"]))
    comparison["worst_p99_ratio"] = rounded(worst_ratio)
    comparison["worst_p99_absolute_delta_us"] = rounded(worst_absolute)
    if (
        worst_ratio > THRESHOLDS["group_worst_p99_ratio"]
        and worst_absolute > THRESHOLDS["group_worst_p99_min_absolute_us"]
    ):
        reasons.append("worst_p99_latency")
    throughput_ratio = ratio(
        float(current["minimum_throughput_frames_per_second"]),
        float(baseline["minimum_throughput_frames_per_second"]),
    )
    comparison["minimum_throughput_ratio"] = rounded(throughput_ratio)
    if throughput_ratio < THRESHOLDS["group_minimum_throughput_ratio"]:
        reasons.append("minimum_throughput")
    if current["strict_deadline_misses"] > THRESHOLDS["strict_deadline_misses_max"]:
        reasons.append("strict_deadline_misses")
    if current["safety_gate_failures"] > THRESHOLDS["unloaded_safety_failures_max"]:
        reasons.append("safety_gate_failures")
    return reasons, comparison


def report_summary(report: dict[str, Any]) -> dict[str, int]:
    return {
        "functional_checks": len(report["validations"]),
        "functional_failures": sum(not item["passed"] for item in report["validations"]),
        "scenarios": len(report["performance"]),
        "strict_deadline_misses": sum(
            not item["strict_deadline_met"] for item in report["performance"]
        ),
        "unloaded_safety_failures": sum(
            item["safety_gate_applies"] and not item["safety_gate_passed"]
            for item in report["performance"]
        ),
    }


def git_revision() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise ComparisonError(f"cannot resolve git revision: {error}") from error


def build_comparison(
    baseline_path: Path,
    current_path: Path,
    ambient_conditions: str,
    revision: str | None = None,
) -> dict[str, Any]:
    baseline = load_json(baseline_path)
    current = load_json(current_path)
    comparable(baseline, current)
    checks, check_deltas, audio_failures, contract_changes = audio_checks(baseline, current)
    source_files = production_source_files()

    baseline_groups: dict[tuple[str, str, int], list[dict[str, Any]]] = defaultdict(list)
    current_groups: dict[tuple[str, str, int], list[dict[str, Any]]] = defaultdict(list)
    for item in baseline["performance"]:
        key = (item["profile"], item["path"], item["background_load_threads"])
        baseline_groups[key].append(item)
    for item in current["performance"]:
        key = (item["profile"], item["path"], item["background_load_threads"])
        current_groups[key].append(item)

    groups: list[dict[str, Any]] = []
    material_count = 0
    all_check_ids = set(check_deltas)
    for profile, path, load in sorted(EXPECTED_GROUPS):
        old = aggregate_scenarios(baseline_groups[(profile, path, load)])
        new = aggregate_scenarios(current_groups[(profile, path, load)])
        reasons, comparison = material_reasons(old, new)
        selected_checks = validation_ids_for_group(profile, path, all_check_ids)
        audio_delta = max((check_deltas[item] for item in selected_checks), default=0.0)
        if audio_delta > THRESHOLDS["audio_metric_max_absolute_delta"]:
            reasons.append("audio_metric_delta")
        reasons = sorted(set(reasons))
        material_count += bool(reasons)
        groups.append(
            {
                "profile": profile,
                "route": path,
                "background_load_threads": load,
                "audio_validation_ids": selected_checks,
                "maximum_audio_metric_absolute_delta": audio_delta,
                "baseline": old,
                "current": new,
                "comparison": comparison,
                "material_regression": bool(reasons),
                "reasons": reasons,
            }
        )

    baseline_summary = report_summary(baseline)
    current_summary = report_summary(current)
    global_failure = (
        current_summary["functional_failures"] > THRESHOLDS["functional_failures_max"]
        or current_summary["strict_deadline_misses"]
        > THRESHOLDS["strict_deadline_misses_max"]
        or current_summary["unloaded_safety_failures"]
        > THRESHOLDS["unloaded_safety_failures_max"]
        or audio_failures > 0
    )
    passed = not global_failure and material_count == 0
    return {
        "schema_version": COMPARISON_SCHEMA_VERSION,
        "comparison_id": "post-handoff-8ee3f5a",
        "generated_utc": datetime.now().astimezone().isoformat(timespec="seconds"),
        "scope": (
            "host processing cost only; not Android callback, HAL, acoustic, "
            "or end-to-end latency"
        ),
        "baseline": {
            "label": "final-methodology-20260715",
            "generated_utc": baseline["generated_utc"],
            "report_sha256": sha256(baseline_path),
            "tracked_raw_report": False,
        },
        "current": {
            "label": "post-handoff-8ee3f5a",
            "generated_utc": current["generated_utc"],
            "report_sha256": sha256(current_path),
            "git_revision": revision or git_revision(),
        },
        "harness": {
            "benchmark_source": str(BENCHMARK_SOURCE.relative_to(REPO_ROOT)).replace("\\", "/"),
            "benchmark_source_sha256": source_sha256(BENCHMARK_SOURCE),
            "report_schema": str(REPORT_SCHEMA.relative_to(REPO_ROOT)).replace("\\", "/"),
            "report_schema_sha256": source_sha256(REPORT_SCHEMA),
            "production_source_file_count": len(source_files),
            "production_source_bundle_sha256": production_source_bundle_sha256(source_files),
            "environment": current["environment"],
            "methodology": {
                key: value
                for key, value in current["methodology"].items()
                if key != "observable_checksum"
            },
            "background_load_threads": sorted(EXPECTED_LOADS),
            "ambient_conditions": ambient_conditions,
        },
        "thresholds": THRESHOLDS,
        "summary": {"baseline": baseline_summary, "current": current_summary},
        "groups": groups,
        "audio_checks": checks,
        "result": {
            "passed": passed,
            "material_regression_count": material_count,
            "audio_comparison_failure_count": audio_failures,
            "allowlisted_contract_change_count": contract_changes,
        },
    }


def require_hex(value: Any, label: str, length: int) -> str:
    if not isinstance(value, str) or len(value) != length:
        fail(f"{label}: expected {length}-digit hexadecimal value")
    try:
        int(value, 16)
    except ValueError as error:
        raise ComparisonError(f"{label}: expected hexadecimal value") from error
    return value.lower()


def require_sha256(value: Any, label: str) -> str:
    return require_hex(value, label, 64)


def verify_comparison(artifact: dict[str, Any]) -> None:
    expected_top = {
        "schema_version",
        "comparison_id",
        "generated_utc",
        "scope",
        "baseline",
        "current",
        "harness",
        "thresholds",
        "summary",
        "groups",
        "audio_checks",
        "result",
    }
    if set(artifact) != expected_top:
        fail("comparison root keys are missing or unexpected")
    if artifact["schema_version"] != COMPARISON_SCHEMA_VERSION:
        fail("unsupported comparison schema")
    if artifact["comparison_id"] != "post-handoff-8ee3f5a":
        fail("unexpected comparison id")
    parse_timestamp(artifact["generated_utc"], "generated_utc")
    if artifact["thresholds"] != THRESHOLDS:
        fail("comparison thresholds are stale")

    baseline = require_dict(artifact["baseline"], "baseline")
    current = require_dict(artifact["current"], "current")
    require_sha256(baseline.get("report_sha256"), "baseline.report_sha256")
    require_sha256(current.get("report_sha256"), "current.report_sha256")
    if baseline.get("tracked_raw_report") is not False:
        fail("baseline traceability status must remain explicit")
    parse_timestamp(baseline.get("generated_utc"), "baseline.generated_utc")
    parse_timestamp(current.get("generated_utc"), "current.generated_utc")
    require_hex(current.get("git_revision"), "current.git_revision", 40)

    harness = require_dict(artifact["harness"], "harness")
    if harness.get("benchmark_source") != "tools/perf/audio_pipeline_benchmark.cpp":
        fail("benchmark source path changed")
    if harness.get("report_schema") != "tools/perf/audio_pipeline_result.schema.json":
        fail("report schema path changed")
    if require_sha256(
        harness.get("benchmark_source_sha256"), "harness.benchmark_source_sha256"
    ) != source_sha256(BENCHMARK_SOURCE):
        fail("comparison is stale for the current benchmark source")
    if require_sha256(
        harness.get("report_schema_sha256"), "harness.report_schema_sha256"
    ) != source_sha256(REPORT_SCHEMA):
        fail("comparison is stale for the current report schema")
    source_files = production_source_files()
    if harness.get("production_source_file_count") != len(source_files):
        fail("comparison production source inventory is stale")
    if require_sha256(
        harness.get("production_source_bundle_sha256"),
        "harness.production_source_bundle_sha256",
    ) != production_source_bundle_sha256(source_files):
        fail("comparison is stale for the current production source bundle")
    if harness.get("background_load_threads") != [0, 8]:
        fail("comparison load inventory changed")
    if not isinstance(harness.get("ambient_conditions"), str) or not harness["ambient_conditions"]:
        fail("ambient timing conditions are missing")

    summary = require_dict(artifact["summary"], "summary")
    current_summary = require_dict(summary.get("current"), "summary.current")
    if current_summary.get("functional_checks") != EXPECTED_VALIDATIONS:
        fail("functional check count changed")
    if current_summary.get("scenarios") != EXPECTED_SCENARIOS:
        fail("scenario count changed")

    checks = require_list(artifact["audio_checks"], "audio_checks")
    if len(checks) != EXPECTED_VALIDATIONS:
        fail("audio check inventory changed")
    audio_failures = 0
    contract_changes = 0
    check_ids: set[str] = set()
    for check in checks:
        item = require_dict(check, "audio check")
        check_id = item.get("id")
        if not isinstance(check_id, str) or check_id in check_ids:
            fail("audio check ids are missing or duplicated")
        check_ids.add(check_id)
        allowed_contract_change = check_id in ALLOWED_CONTRACT_CHANGES
        expected_check_keys = {
            "id",
            "effect",
            "fixture",
            "baseline_passed",
            "current_passed",
            "baseline_metrics",
            "current_metrics",
            "metric_deltas",
            "maximum_metric_absolute_delta",
            "metric_delta_policy",
        }
        if allowed_contract_change:
            expected_check_keys.add("contract_change")
        if set(item) != expected_check_keys:
            fail(f"{check_id}: audio check keys are missing or unexpected")
        old_metrics = require_dict(item.get("baseline_metrics"), f"{check_id}.baseline_metrics")
        new_metrics = require_dict(item.get("current_metrics"), f"{check_id}.current_metrics")
        deltas = require_dict(item.get("metric_deltas"), f"{check_id}.deltas")
        if set(old_metrics) != set(new_metrics) or set(old_metrics) != set(deltas):
            fail(f"{check_id}: metric inventories differ")
        expected_deltas: dict[str, float] = {}
        for metric in old_metrics:
            baseline_value = finite_number(old_metrics[metric], f"{check_id}.{metric}.baseline")
            current_value = finite_number(new_metrics[metric], f"{check_id}.{metric}.current")
            finite_number(deltas[metric], f"{check_id}.{metric}.delta")
            expected_deltas[metric] = rounded(current_value - baseline_value)
        if deltas != expected_deltas:
            fail(f"{check_id}: metric deltas are inconsistent")
        maximum = max((abs(float(value)) for value in deltas.values()), default=0.0)
        if item.get("maximum_metric_absolute_delta") != maximum:
            fail(f"{check_id}: maximum audio delta is inconsistent")
        if allowed_contract_change:
            expected_change = contract_change_record(check_id)
            if item.get("metric_delta_policy") != "allowlisted_contract_change":
                fail(f"{check_id}: contract change policy is missing")
            if item.get("contract_change") != expected_change:
                fail(f"{check_id}: contract change rationale or reference is stale")
            expected_baseline = {
                "header_version": expected_change["baseline_version"],
                "runtime_version": expected_change["baseline_version"],
            }
            expected_current = {
                "header_version": expected_change["source_declared_version"],
                "runtime_version": expected_change["source_declared_version"],
            }
            if old_metrics != expected_baseline or new_metrics != expected_current:
                fail(f"{check_id}: contract values do not match the source declaration")
            contract_changes += 1
        elif item.get("metric_delta_policy") != "exact_audio_metrics":
            fail(f"{check_id}: exact audio metric policy is missing")
        if (
            item.get("baseline_passed") is not True
            or item.get("current_passed") is not True
            or not allowed_contract_change
            and maximum > THRESHOLDS["audio_metric_max_absolute_delta"]
        ):
            audio_failures += 1

    groups = require_list(artifact["groups"], "groups")
    if len(groups) != len(EXPECTED_GROUPS):
        fail("profile/route/load summary count changed")
    group_keys: set[tuple[str, str, int]] = set()
    material_count = 0
    for group in groups:
        item = require_dict(group, "group")
        key = (item.get("profile"), item.get("route"), item.get("background_load_threads"))
        if key not in EXPECTED_GROUPS or key in group_keys:
            fail("profile/route/load group is missing or duplicated")
        group_keys.add(key)
        old = require_dict(item.get("baseline"), f"{key}.baseline")
        new = require_dict(item.get("current"), f"{key}.current")
        reasons, comparison = material_reasons(old, new)
        selected = require_list(item.get("audio_validation_ids"), f"{key}.audio_validation_ids")
        if any(check_id not in check_ids for check_id in selected):
            fail(f"{key}: group references an unknown audio validation")
        group_audio_delta = max(
            (
                next(
                    check["maximum_metric_absolute_delta"]
                    for check in checks
                    if check["id"] == check_id
                )
                for check_id in selected
            ),
            default=0.0,
        )
        if item.get("maximum_audio_metric_absolute_delta") != group_audio_delta:
            fail(f"{key}: group audio delta is inconsistent")
        if group_audio_delta > THRESHOLDS["audio_metric_max_absolute_delta"]:
            reasons.append("audio_metric_delta")
        reasons = sorted(set(reasons))
        if item.get("comparison") != comparison:
            fail(f"{key}: comparison ratios are inconsistent")
        if item.get("reasons") != reasons or item.get("material_regression") != bool(reasons):
            fail(f"{key}: material regression result is inconsistent")
        material_count += bool(reasons)
    if group_keys != EXPECTED_GROUPS:
        fail("profile/route/load inventory is incomplete")

    result = require_dict(artifact["result"], "result")
    global_failure = (
        current_summary.get("functional_failures")
        > THRESHOLDS["functional_failures_max"]
        or current_summary.get("strict_deadline_misses")
        > THRESHOLDS["strict_deadline_misses_max"]
        or current_summary.get("unloaded_safety_failures")
        > THRESHOLDS["unloaded_safety_failures_max"]
        or audio_failures > 0
    )
    expected_pass = not global_failure and material_count == 0
    if result != {
        "passed": expected_pass,
        "material_regression_count": material_count,
        "audio_comparison_failure_count": audio_failures,
        "allowlisted_contract_change_count": contract_changes,
    }:
        fail("overall comparison result is inconsistent")


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except OSError as error:
        raise ComparisonError(f"cannot write {path}: {error}") from error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    generate = subparsers.add_parser("generate", help="compare two raw reports")
    generate.add_argument("--baseline", type=Path, required=True)
    generate.add_argument("--current", type=Path, required=True)
    generate.add_argument("--output", type=Path, required=True)
    generate.add_argument("--ambient", required=True)
    generate.add_argument("--git-revision")
    verify = subparsers.add_parser("verify", help="verify compact tracked evidence")
    verify.add_argument("artifact", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "generate":
            artifact = build_comparison(
                args.baseline,
                args.current,
                args.ambient,
                args.git_revision,
            )
            verify_comparison(artifact)
            write_json(args.output, artifact)
            print(f"Wrote verified audio comparison to {args.output}")
        else:
            verify_comparison(load_json(args.artifact))
            print(f"Verified audio comparison {args.artifact}")
    except ComparisonError as error:
        print(f"audio comparison failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
