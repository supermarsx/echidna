#!/usr/bin/env python3
"""Fail-closed source, AOSP contract, and OEM product gate for Echidna AIDL effects."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


TYPE_UUID = "c83e3db3-d4f5-5f2c-a095-8775c1edfc6d"
IMPLEMENTATION_UUID = "3e66a36e-dee9-5d81-a0d6-49fc3b863530"
LIBRARY_FILE = "libechidna_preproc_aidl.so"
EFFECT_NAME = "echidna_preprocessor"
MODULES = {
    34: "libechidna_preproc_aidl_v1",
    35: "libechidna_preproc_aidl_v2",
}


@dataclass
class Checks:
    passed: list[str] = field(default_factory=list)
    failed: list[str] = field(default_factory=list)

    def require(self, condition: bool, message: str) -> None:
        (self.passed if condition else self.failed).append(message)

    def require_file(self, path: Path, label: str) -> str:
        exists = path.is_file()
        self.require(exists, f"{label}: {path}")
        return path.read_text(encoding="utf-8") if exists else ""


def _load_json(path: Path, checks: Checks, label: str) -> dict[str, Any]:
    text = checks.require_file(path, label)
    if not text:
        return {}
    try:
        value = json.loads(text)
    except (json.JSONDecodeError, OSError) as error:
        checks.require(False, f"{label} is valid JSON: {error}")
        return {}
    checks.require(isinstance(value, dict), f"{label} root is an object")
    return value if isinstance(value, dict) else {}


def _contains_all(text: str, tokens: list[str], checks: Checks, label: str) -> None:
    for token in tokens:
        checks.require(token in text, f"{label} contains {token!r}")


def check_source(repo: Path, api: int, checks: Checks) -> None:
    integration = repo / "native/effects/aidl/integration"
    manifest = _load_json(integration / "manifest.json", checks, "integration manifest")
    effect = manifest.get("effect", {}) if isinstance(manifest, dict) else {}
    checks.require(effect.get("type_uuid") == TYPE_UUID, "manifest type UUID is pinned")
    checks.require(
        effect.get("implementation_uuid") == IMPLEMENTATION_UUID,
        "manifest implementation UUID is pinned",
    )
    checks.require(effect.get("library_file") == LIBRARY_FILE, "manifest library is pinned")
    checks.require(
        effect.get("exports") == ["createEffect", "queryEffect", "destroyEffect"],
        "manifest declares the factory ABI exports",
    )

    runtime_h = checks.require_file(
        repo / "native/effects/aidl/legacy_runtime.h", "shared AIDL runtime header"
    )
    runtime_cpp = checks.require_file(
        repo / "native/effects/aidl/legacy_runtime.cpp", "shared AIDL runtime"
    )
    _contains_all(
        runtime_h,
        [
            TYPE_UUID,
            IMPLEMENTATION_UUID,
            "LegacyRuntime",
            "SetValueV1",
            "SetPacketV2",
            "start_requested_",
            "legacy_enabled_",
        ],
        checks,
        "shared runtime header",
    )
    _contains_all(
        runtime_cpp,
        [
            "Flags::Type::PRE_PROC",
            "Flags::Insert::FIRST",
            "context_.ApplyCapability",
            "EFFECT_CMD_GET_PARAM",
            "context_.Process",
            "status == -EPERM ? 0 : status",
            "ActivateIfRequested",
        ],
        checks,
        "shared runtime",
    )
    checks.require(
        "common.input.frameCount != common.output.frameCount" not in runtime_cpp
        and "common.output.frameCount <= 0" in runtime_cpp,
        "shared runtime accepts independent valid input/output FMQ depths",
    )

    legacy_header = checks.require_file(
        repo / "native/effects/legacy/effect_library.h", "legacy descriptor header"
    )
    _contains_all(
        legacy_header,
        ["0xc83e3db3U", "0x3e66a36eU", "0xdee9U", "0x5d81U"],
        checks,
        "legacy descriptor header",
    )

    android_bp = checks.require_file(repo / "Android.bp", "Soong build")
    expected_source = f"native/effects/aidl/api{api}/echidna_effect.cpp"
    _contains_all(
        android_bp,
        [
            "aidlaudioeffectservice_defaults",
            ":effectCommonFile",
            "libcrypto",
            "-DECHIDNA_HAS_BORINGSSL=1",
            MODULES[api],
            expected_source,
            'relative_install_path: "soundfx"',
        ],
        checks,
        "Soong build",
    )
    checks.require("cc_binary" not in android_bp, "integration does not add a second factory")

    adapter = checks.require_file(repo / expected_source, f"API {api} adapter")
    expected_adapter_tokens = (
        ["SetValueV1", "GetValueV1"] if api == 34 else ["SetPacketV2", "GetPacketV2"]
    )
    _contains_all(
        adapter,
        [
            'extern "C" binder_exception_t createEffect',
            'extern "C" binder_exception_t queryEffect',
            "effectProcessImpl",
            "commandImpl",
            "EffectImpl::commandImpl",
            *expected_adapter_tokens,
        ],
        checks,
        f"API {api} adapter",
    )
    if api == 35:
        checks.require("REQUIRES(mImplMutex)" in adapter, "API 35 adapter uses V2 locking contract")
        checks.require("reopen(" not in adapter, "API 35 inherits the AOSP reopen implementation")

    xml_path = integration / f"audio_effects_config.api{api}.xml"
    check_config(xml_path, api, checks, "reference factory config")
    if api == 34:
        patch = checks.require_file(
            integration / "android14-effect-type-map.patch", "Android 14 type-map patch"
        )
        _contains_all(patch, [EFFECT_NAME, TYPE_UUID, "EffectConfig.cpp"], checks, "type-map patch")


def _normalize_hardware_root(root: Path) -> Path:
    direct = root / "audio/aidl/default/EffectImpl.cpp"
    return root if direct.is_file() else root / "hardware/interfaces"


def _normalize_frameworks_root(root: Path) -> Path:
    direct = root / "media/libaudiohal/impl/EffectHalAidl.cpp"
    return root if direct.is_file() else root / "frameworks/av"


def check_aosp(hardware_root: Path, frameworks_root: Path, api: int, checks: Checks) -> None:
    hardware = _normalize_hardware_root(hardware_root)
    frameworks = _normalize_frameworks_root(frameworks_root)
    aidl_bp = checks.require_file(hardware / "audio/aidl/Android.bp", "AOSP effect AIDL build")
    default_bp = checks.require_file(
        hardware / "audio/aidl/default/Android.bp", "AOSP effect implementation build"
    )
    impl_h = checks.require_file(
        hardware / "audio/aidl/default/include/effect-impl/EffectImpl.h",
        "AOSP EffectImpl contract",
    )
    impl_cpp = checks.require_file(
        hardware / "audio/aidl/default/EffectImpl.cpp", "AOSP EffectImpl implementation"
    )
    conversion = checks.require_file(
        frameworks
        / "media/libaudiohal/impl/effectsAidlConversion/AidlConversionVendorExtension.cpp",
        "framework vendor-effect conversion",
    )

    _contains_all(
        default_bp,
        ["aidlaudioeffectservice_defaults", "effectCommonFile", "EffectImpl.cpp"],
        checks,
        "AOSP effect implementation build",
    )
    if api == 34:
        _contains_all(aidl_bp, ["android.hardware.audio.effect-V1"], checks, "AOSP AIDL build")
        checks.require("reopen(OpenEffectReturn" not in impl_h, "API 34 EffectImpl has no reopen")
        _contains_all(
            conversion,
            [
                "legacy2aidl_EffectParameterReader_ParameterExtension",
                "legacy2aidl_EffectParameterReader_Param_VendorExtension",
                "aidl2legacy_ParameterExtension_EffectParameterWriter",
            ],
            checks,
            "API 34 vendor conversion",
        )
    else:
        _contains_all(aidl_bp, ["android.hardware.audio.effect-V2"], checks, "AOSP AIDL build")
        _contains_all(
            impl_h + impl_cpp,
            ["reopen(OpenEffectReturn", "dupeFmqWithReopen", "mDataMqNotEmptyEf"],
            checks,
            "API 35 reopen contract",
        )
        _contains_all(
            conversion,
            [
                "legacy2aidl_EffectParameterReader_Parameter",
                "legacy2aidl_EffectParameterReader_VendorExtension",
                "aidl2legacy_Parameter_EffectParameterWriter",
            ],
            checks,
            "API 35 vendor conversion",
        )


def _nodes(root: ET.Element, tag: str) -> list[ET.Element]:
    return [node for node in root.iter() if node.tag.rsplit("}", 1)[-1] == tag]


def check_config(path: Path, api: int, checks: Checks, label: str) -> None:
    text = checks.require_file(path, label)
    if not text:
        return
    try:
        root = ET.fromstring(text)
    except ET.ParseError as error:
        checks.require(False, f"{label} parses as XML: {error}")
        return
    libraries = [
        node
        for node in _nodes(root, "library")
        if node.attrib.get("name") == EFFECT_NAME
    ]
    effects = [
        node for node in _nodes(root, "effect") if node.attrib.get("name") == EFFECT_NAME
    ]
    checks.require(
        len(libraries) == 1 and libraries[0].attrib.get("path") == LIBRARY_FILE,
        f"{label} registers one Echidna soundfx library",
    )
    checks.require(
        len(effects) == 1
        and effects[0].attrib.get("library") == EFFECT_NAME
        and effects[0].attrib.get("uuid") == IMPLEMENTATION_UUID,
        f"{label} registers one Echidna implementation UUID",
    )
    if effects:
        if api == 35:
            checks.require(
                effects[0].attrib.get("type") == TYPE_UUID,
                f"{label} pins the API 35 custom type UUID",
            )
        else:
            checks.require(
                "type" not in effects[0].attrib,
                f"{label} does not use the unsupported API 34 type attribute",
            )
    applies = [
        node for node in _nodes(root, "apply") if node.attrib.get("effect") == EFFECT_NAME
    ]
    checks.require(not applies, f"{label} does not auto-attach Echidna to every capture")


def check_product(
    config: Path,
    packages: Path,
    evidence_path: Path,
    factory_source: Path | None,
    api: int,
    checks: Checks,
) -> None:
    check_config(config, api, checks, "OEM active factory config")
    package_text = checks.require_file(packages, "OEM product package declaration")
    selected = MODULES[api]
    other = MODULES[35 if api == 34 else 34]
    checks.require(selected in package_text, f"OEM selects {selected}")
    checks.require(other not in package_text, f"OEM does not select {other}")

    if api == 34:
        checks.require(factory_source is not None, "API 34 supplies patched EffectConfig.cpp")
        if factory_source is not None:
            factory_text = checks.require_file(factory_source, "OEM API 34 EffectConfig.cpp")
            _contains_all(
                factory_text,
                [EFFECT_NAME, TYPE_UUID, "findUuid"],
                checks,
                "OEM API 34 type mapping",
            )

    evidence = _load_json(evidence_path, checks, "device evidence")
    exact_true = [
        "factory_is_oem_instance",
        "library_loaded_from_soundfx",
        "selinux_enforcing",
        "factory_reads_controller_spki",
        "factory_reads_telemetry_key",
        "effect_vts_passed",
        "create_open_close_passed",
        "fmq_process_passed",
        "reset_passed",
        "telemetry_v2_hmac_verified",
    ]
    checks.require(evidence.get("api") == api, "device evidence API matches the build")
    checks.require(
        evidence.get("factory_instance_count") == 1,
        "device has exactly one Stable AIDL effect factory instance",
    )
    for key in exact_true:
        checks.require(evidence.get(key) is True, f"device evidence proves {key}")
    checks.require(
        isinstance(evidence.get("unauthorized_max_abs_diff"), (int, float))
        and evidence["unauthorized_max_abs_diff"] <= 1e-7,
        "unauthorized capture is bit-near identity",
    )
    checks.require(
        isinstance(evidence.get("authorized_mutated_samples"), int)
        and evidence["authorized_mutated_samples"] > 0,
        "authorized DSP changes at least one sample",
    )
    if api == 35:
        checks.require(evidence.get("reopen_passed") is True, "API 35 FMQ reopen is proven")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", type=int, choices=(34, 35), required=True)
    parser.add_argument(
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    parser.add_argument("--hardware-interfaces-root", type=Path)
    parser.add_argument("--frameworks-av-root", type=Path)
    parser.add_argument("--product-config", type=Path)
    parser.add_argument("--product-packages", type=Path)
    parser.add_argument("--device-evidence", type=Path)
    parser.add_argument("--factory-source", type=Path)
    parser.add_argument(
        "--require-product-gate",
        action="store_true",
        help="require OEM config, package, VTS/device, SELinux, DSP, and telemetry evidence",
    )
    return parser.parse_args(argv)


def run(args: argparse.Namespace) -> Checks:
    checks = Checks()
    repo = args.repo_root.resolve()
    check_source(repo, args.api, checks)

    aosp_values = (args.hardware_interfaces_root, args.frameworks_av_root)
    checks.require(
        all(value is None for value in aosp_values)
        or all(value is not None for value in aosp_values),
        "AOSP hardware/interfaces and frameworks/av roots are supplied together",
    )
    if all(value is not None for value in aosp_values):
        check_aosp(
            args.hardware_interfaces_root.resolve(),
            args.frameworks_av_root.resolve(),
            args.api,
            checks,
        )

    product_values = (
        args.product_config,
        args.product_packages,
        args.device_evidence,
    )
    if args.require_product_gate:
        checks.require(
            all(value is not None for value in product_values),
            "product gate supplies config, package declaration, and device evidence",
        )
    if all(value is not None for value in product_values):
        check_product(
            args.product_config.resolve(),
            args.product_packages.resolve(),
            args.device_evidence.resolve(),
            args.factory_source.resolve() if args.factory_source else None,
            args.api,
            checks,
        )
    return checks


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    checks = run(args)
    for message in checks.passed:
        print(f"PASS: {message}")
    for message in checks.failed:
        print(f"FAIL: {message}", file=sys.stderr)
    print(
        f"stable AIDL effect gate: {len(checks.passed)} passed, "
        f"{len(checks.failed)} failed"
    )
    return 1 if checks.failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
