#!/usr/bin/env python3
"""Build a privacy-safe hook probe report for Echidna support issues.

The report combines static `analyze_audio_hal_dump.py` output with the optional
diagnostics export from the companion service. It emits a reviewable device
database entry and GitHub issue body, but it never enables offsets by itself.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
SCHEMA = "echidna.hook-probe-report.v1"
HASH_PREFIX_BYTES = 8

PACKAGE_PATTERN = re.compile(r"\b[a-zA-Z][\w]*(?:\.[a-zA-Z][\w-]*){1,}\b")
PATH_PATTERN = re.compile(r"/(?:data|sdcard|storage|mnt)/[^\s;:,]+")


def build_report(analysis: dict[str, Any], diagnostics: dict[str, Any] | None = None) -> dict[str, Any]:
    diagnostics = diagnostics or {}
    device = analysis.get("device") if isinstance(analysis.get("device"), dict) else {}
    profile = analysis.get("vendorProfile") if isinstance(analysis.get("vendorProfile"), dict) else {}
    candidates = [
        sanitize_candidate(candidate)
        for candidate in analysis.get("hookCandidates", [])
        if isinstance(candidate, dict)
    ][:12]
    libraries = [
        library_evidence(library)
        for library in analysis.get("libraries", [])
        if isinstance(library, dict)
    ][:24]
    runtime = runtime_evidence(diagnostics)
    validation_state = validation_state_for(candidates, runtime)
    database_entry = device_database_entry(profile, candidates, libraries, runtime, validation_state)
    report = {
        "schema": SCHEMA,
        "schemaVersion": SCHEMA_VERSION,
        "privacy": privacy_notice(),
        "deviceKey": hash_value(device_identity(device)),
        "device": sanitized_device(device),
        "vendorProfile": sanitize_profile(profile),
        "validationState": validation_state,
        "libraryEvidence": libraries,
        "hookCandidates": candidates,
        "runtimeEvidence": runtime,
        "deviceDatabaseEntry": database_entry,
    }
    report["evidenceHash"] = hash_value(
        {
            "deviceKey": report["deviceKey"],
            "vendorProfile": report["vendorProfile"],
            "libraries": libraries,
            "candidates": candidates,
            "runtime": runtime,
        }
    )
    report["githubIssue"] = github_issue(report)
    return report


def privacy_notice() -> dict[str, Any]:
    return {
        "rawPackageNames": False,
        "rawPresetIds": False,
        "rawDeviceNames": False,
        "rawFilesystemUserPaths": False,
        "identifierFormat": "sha256-prefix-16",
        "offsetPolicy": "review_required_exact_identity_match",
    }


def sanitized_device(device: dict[str, Any]) -> dict[str, Any]:
    abis = device.get("abis") if isinstance(device.get("abis"), list) else []
    return {
        "manufacturerHash": hash_if_present(device.get("manufacturer")),
        "brandHash": hash_if_present(device.get("brand")),
        "modelHash": hash_if_present(device.get("model")),
        "boardPlatformHash": hash_if_present(device.get("boardPlatform")),
        "apiLevel": str(device.get("apiLevel") or ""),
        "abis": [str(value) for value in abis],
    }


def device_identity(device: dict[str, Any]) -> dict[str, Any]:
    return {
        "manufacturer": device.get("manufacturer") or "",
        "brand": device.get("brand") or "",
        "model": device.get("model") or "",
        "boardPlatform": device.get("boardPlatform") or "",
        "apiLevel": device.get("apiLevel") or "",
        "abis": device.get("abis") or [],
    }


def sanitize_profile(profile: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": str(profile.get("id") or "unknown"),
        "name": str(profile.get("name") or "Unknown"),
        "confidence": str(profile.get("confidence") or "none"),
        "score": int(profile.get("score") or 0),
        "notes": [redact_text(str(note)) for note in profile.get("notes", [])[:4]],
    }


def library_evidence(library: dict[str, Any]) -> dict[str, Any]:
    symbols = [str(symbol) for symbol in library.get("matchedSymbols", [])]
    evidence = {
        "path": redact_path(str(library.get("path") or "")),
        "role": str(library.get("role") or "unknown"),
        "processScope": str(library.get("processScope") or "unknown"),
        "arch": str(library.get("arch") or "unknown"),
        "elfClass": str(library.get("elfClass") or "unknown"),
        "soname": str(library.get("soname") or ""),
        "needed": [str(item) for item in library.get("needed", [])],
        "sizeBytes": int(library.get("sizeBytes") or 0),
        "matchedSurfaces": [str(item) for item in library.get("matchedSurfaces", [])],
        "matchedSymbolCount": len(symbols),
        "strippedOrUnreadable": bool(library.get("strippedOrUnreadable")),
    }
    evidence["identityHash"] = hash_value(
        {
            "path": evidence["path"],
            "arch": evidence["arch"],
            "soname": evidence["soname"],
            "needed": evidence["needed"],
            "sizeBytes": evidence["sizeBytes"],
            "symbols": symbols,
        }
    )
    return evidence


def sanitize_candidate(candidate: dict[str, Any]) -> dict[str, Any]:
    symbols = [str(symbol) for symbol in candidate.get("symbols", [])[:8]]
    return {
        "surface": str(candidate.get("surface") or "unknown"),
        "library": redact_path(str(candidate.get("library") or "")),
        "role": str(candidate.get("role") or "unknown"),
        "processScope": str(candidate.get("processScope") or "unknown"),
        "confidence": str(candidate.get("confidence") or "exploratory"),
        "score": int(candidate.get("score") or 0),
        "risk": str(candidate.get("risk") or "unknown"),
        "symbols": symbols,
        "candidateHash": hash_value(
            {
                "surface": candidate.get("surface") or "",
                "library": candidate.get("library") or "",
                "symbols": symbols,
            }
        ),
    }


def runtime_evidence(diagnostics: dict[str, Any]) -> dict[str, Any]:
    telemetry = diagnostics.get("telemetry") if isinstance(diagnostics.get("telemetry"), dict) else {}
    whitelist = diagnostics.get("whitelist") if isinstance(diagnostics.get("whitelist"), dict) else {}
    status = diagnostics.get("status") if isinstance(diagnostics.get("status"), dict) else {}
    actions = diagnostics.get("actions") if isinstance(diagnostics.get("actions"), list) else []
    hooks = [
        sanitize_runtime_hook(hook)
        for hook in telemetry.get("hooks", [])
        if isinstance(hook, dict)
    ][:16]
    counts = whitelist.get("counts") if isinstance(whitelist.get("counts"), dict) else {}
    return {
        "diagnosticsSchema": str(diagnostics.get("schema") or ""),
        "totalCallbacks": int(telemetry.get("totalCallbacks") or 0),
        "xruns": int(telemetry.get("xruns") or 0),
        "hookTelemetry": hooks,
        "actionCodes": [str(item.get("code")) for item in actions if isinstance(item, dict)],
        "whitelistCounts": {
            "enabledWhitelist": int(counts.get("enabledWhitelist") or 0),
            "disabledWhitelist": int(counts.get("disabledWhitelist") or 0),
            "appBindings": int(counts.get("appBindings") or 0),
        },
        "module": {
            "magiskModuleInstalled": bool(status.get("magiskModuleInstalled")),
            "zygiskEnabled": bool(status.get("zygiskEnabled")),
            "selinuxState": str(status.get("selinuxState") or ""),
        },
    }


def sanitize_runtime_hook(hook: dict[str, Any]) -> dict[str, Any]:
    return {
        "name": str(hook.get("name") or ""),
        "library": redact_path(str(hook.get("library") or "")),
        "symbol": str(hook.get("symbol") or ""),
        "reason": redact_text(str(hook.get("reason") or "")),
        "attempts": int(hook.get("attempts") or 0),
        "successes": int(hook.get("successes") or 0),
        "failures": int(hook.get("failures") or 0),
        "successRate": float(hook.get("successRate") or 0.0),
    }


def validation_state_for(candidates: list[dict[str, Any]], runtime: dict[str, Any]) -> str:
    if "configure_whitelist" in runtime.get("actionCodes", []):
        return "blocked_no_whitelist"
    if any(hook.get("successes", 0) > 0 for hook in runtime.get("hookTelemetry", [])):
        if int(runtime.get("totalCallbacks") or 0) > 0:
            return "live_hook_and_callback_seen"
        return "live_hook_seen_no_callback"
    if candidates:
        return "static_candidates_need_live_probe"
    return "no_candidates"


def device_database_entry(
    profile: dict[str, Any],
    candidates: list[dict[str, Any]],
    libraries: list[dict[str, Any]],
    runtime: dict[str, Any],
    validation_state: str,
) -> dict[str, Any]:
    return {
        "profileId": str(profile.get("id") or "unknown"),
        "profileConfidence": str(profile.get("confidence") or "none"),
        "validationState": validation_state,
        "offsetProfiles": [],
        "candidateHashes": [candidate["candidateHash"] for candidate in candidates[:8]],
        "libraryIdentityHashes": [library["identityHash"] for library in libraries[:12]],
        "runtimeHookProof": [
            hook
            for hook in runtime.get("hookTelemetry", [])
            if int(hook.get("successes") or 0) > 0
        ],
        "policy": {
            "autoApplyOffsets": False,
            "requiresExactLibraryIdentity": True,
            "requiresLiveTelemetry": True,
        },
    }


def github_issue(report: dict[str, Any]) -> dict[str, str]:
    profile = report["vendorProfile"]
    title = (
        f"Hook probe evidence: {profile['id']} "
        f"({report['validationState']}, {report['deviceKey']})"
    )
    candidates = report.get("hookCandidates", [])[:8]
    hooks = report.get("runtimeEvidence", {}).get("hookTelemetry", [])[:8]
    actions = report.get("runtimeEvidence", {}).get("actionCodes", [])
    body_lines = [
        "## Summary",
        "",
        f"- schema: `{report['schema']}`",
        f"- evidence hash: `{report['evidenceHash']}`",
        f"- device key: `{report['deviceKey']}`",
        f"- vendor profile: `{profile['id']}` / `{profile['confidence']}`",
        f"- validation state: `{report['validationState']}`",
        "",
        "## Static Hook Candidates",
        "",
    ]
    if candidates:
        for candidate in candidates:
            body_lines.append(
                "- `{surface}` `{confidence}` `{risk}` `{library}`".format(**candidate)
            )
    else:
        body_lines.append("- none")
    body_lines.extend(["", "## Runtime Hook Evidence", ""])
    if hooks:
        for hook in hooks:
            body_lines.append(
                "- `{name}` attempts={attempts} successes={successes} symbol=`{symbol}`".format(
                    **hook
                )
            )
    else:
        body_lines.append("- none")
    body_lines.extend(["", "## Action Codes", ""])
    body_lines.append(", ".join(f"`{code}`" for code in actions) if actions else "- none")
    body_lines.extend(
        [
            "",
            "## Privacy",
            "",
            "This report omits raw package names, preset IDs, device names, and user paths.",
            "Offset profiles must stay review-gated and exact-library matched.",
        ]
    )
    return {"title": title, "body": "\n".join(body_lines)}


def hash_if_present(value: Any) -> str:
    text = str(value or "")
    return hash_value(text) if text else ""


def hash_value(value: Any) -> str:
    if isinstance(value, str):
        payload = value
    else:
        payload = json.dumps(value, sort_keys=True, separators=(",", ":"))
    digest = hashlib.sha256(payload.encode("utf-8")).digest()
    return "sha256:" + digest[:HASH_PREFIX_BYTES].hex()


def redact_text(value: str) -> str:
    return PACKAGE_PATTERN.sub("[redacted-id]", redact_path(value))


def redact_path(value: str) -> str:
    return PATH_PATTERN.sub("[redacted-path]", value)


def load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"expected a JSON object in {path}")
    return payload


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("analysis", type=Path, help="audio HAL analyzer JSON")
    parser.add_argument("--diagnostics", type=Path, help="optional diagnostics export JSON")
    parser.add_argument("--output", type=Path, help="write JSON report to this path")
    parser.add_argument("--compact", action="store_true", help="emit compact JSON")
    return parser


def main(argv: list[str]) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        analysis = load_json(args.analysis)
        diagnostics = load_json(args.diagnostics) if args.diagnostics else None
        report = build_report(analysis, diagnostics)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    payload = json.dumps(
        report,
        indent=None if args.compact else 2,
        sort_keys=True,
    )
    if args.output:
        args.output.write_text(payload + "\n", encoding="utf-8")
    else:
        print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
