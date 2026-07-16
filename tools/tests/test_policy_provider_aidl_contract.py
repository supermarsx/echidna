import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SERVICE_AIDL = REPO_ROOT / (
    "android/control-service/service/src/main/aidl/com/echidna/control/service/"
    "IEchidnaPolicyProvider.aidl"
)
SHIM_AIDL = REPO_ROOT / (
    "android/lsposed-shim/shim/src/main/aidl/com/echidna/control/service/"
    "IEchidnaPolicyProvider.aidl"
)

EXPECTED_METHODS = [
    "String getPolicySnapshot(String processName)",
    "void registerListener(String processName, IEchidnaPolicyListener listener)",
    "void unregisterListener(IEchidnaPolicyListener listener)",
    "long getApiVersion()",
    (
        "oneway void requestLegacyPreprocessorCapability(int audioSessionId, "
        "String processName, long generation, in byte[] nonce, "
        "IEchidnaCapabilityCallback callback)"
    ),
    (
        "oneway void reportLegacyPreprocessorTelemetry(int audioSessionId, "
        "String processName, long generation, in byte[] snapshot)"
    ),
    (
        "oneway void reportLegacyPreprocessorTelemetryV4(int audioSessionId, "
        "String processName, long generation, in byte[] capabilityNonce, "
        "in byte[] snapshot)"
    ),
    (
        "oneway void reportLegacyPreprocessorTelemetryProofV5(int audioSessionId, "
        "String processName, long generation, in byte[] proof)"
    ),
    (
        "boolean registerCaptureOwnerClient(String processName, long clientApiVersion, "
        "IEchidnaPolicyListener listener)"
    ),
    (
        "oneway void reportCaptureOwnerInactive(String processName, long generation, "
        "long handoffToken)"
    ),
    (
        "boolean requestLegacyPreprocessorCapabilityV7(int audioSessionId, "
        "String processName, long generation, in byte[] nonce, "
        "IEchidnaCapabilityCallback callback)"
    ),
    (
        "boolean reportLegacyPreprocessorTelemetryV7(int audioSessionId, "
        "String processName, long generation, in byte[] capabilityNonce, "
        "in byte[] snapshot)"
    ),
    (
        "boolean reportLegacyPreprocessorTelemetryProofV7(int audioSessionId, "
        "String processName, long generation, in byte[] proof)"
    ),
    (
        "boolean reportCaptureOwnerInactiveV7(String processName, long generation, "
        "long handoffToken)"
    ),
]


def parse_methods(path: Path) -> list[str]:
    source = path.read_text(encoding="utf-8")
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    source = re.sub(r"//[^\r\n]*", "", source)
    match = re.search(
        r"\binterface\s+IEchidnaPolicyProvider\s*\{(?P<body>.*?)\}",
        source,
        flags=re.DOTALL,
    )
    if match is None:
        raise AssertionError(f"IEchidnaPolicyProvider interface missing from {path}")
    methods = []
    for declaration in match.group("body").split(";"):
        if not declaration.strip():
            continue
        normalized = " ".join(declaration.split())
        normalized = re.sub(r"\(\s+", "(", normalized)
        normalized = re.sub(r"\s+\)", ")", normalized)
        methods.append(normalized)
    return methods


class PolicyProviderAidlContractTest(unittest.TestCase):
    def test_copies_freeze_append_only_transaction_order_through_v7(self) -> None:
        service_methods = parse_methods(SERVICE_AIDL)
        shim_methods = parse_methods(SHIM_AIDL)

        self.assertEqual(
            service_methods,
            shim_methods,
            "service and shim AIDL method order/signatures diverged",
        )
        self.assertEqual(
            EXPECTED_METHODS,
            service_methods,
            "AIDL declaration index is the Binder transaction offset from "
            "IBinder.FIRST_CALL_TRANSACTION",
        )

    def test_pid_bound_legacy_calls_stay_oneway_and_v7_boundaries_are_sync(self) -> None:
        methods_by_name = {
            re.search(r"\b([A-Za-z0-9_]+)\s*\(", method).group(1): method
            for method in parse_methods(SERVICE_AIDL)
        }
        legacy_oneway = {
            "requestLegacyPreprocessorCapability",
            "reportLegacyPreprocessorTelemetryV4",
            "reportLegacyPreprocessorTelemetryProofV5",
            "reportCaptureOwnerInactive",
        }
        synchronous_v7 = {
            "requestLegacyPreprocessorCapabilityV7",
            "reportLegacyPreprocessorTelemetryV7",
            "reportLegacyPreprocessorTelemetryProofV7",
            "reportCaptureOwnerInactiveV7",
        }

        for name in legacy_oneway:
            self.assertTrue(methods_by_name[name].startswith("oneway "), name)
        for name in synchronous_v7:
            self.assertFalse(methods_by_name[name].startswith("oneway "), name)
            self.assertTrue(methods_by_name[name].startswith("boolean "), name)


if __name__ == "__main__":
    unittest.main()
