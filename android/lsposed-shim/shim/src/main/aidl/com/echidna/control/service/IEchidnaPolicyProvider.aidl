package com.echidna.control.service;

import com.echidna.control.service.IEchidnaPolicyListener;
import com.echidna.control.service.IEchidnaCapabilityCallback;

interface IEchidnaPolicyProvider {
    String getPolicySnapshot(String processName);
    void registerListener(String processName, IEchidnaPolicyListener listener);
    void unregisterListener(IEchidnaPolicyListener listener);
    // Append-only v2 surface. Callers must feature-detect before requesting a capability.
    long getApiVersion();
    oneway void requestLegacyPreprocessorCapability(int audioSessionId, String processName,
            long generation, in byte[] nonce, IEchidnaCapabilityCallback callback);
    // Append-only v3 surface. Payload is the fixed 48-byte ECHT v1 value, not effect_param_t.
    oneway void reportLegacyPreprocessorTelemetry(int audioSessionId, String processName,
            long generation, in byte[] snapshot);
    // Append-only v4 surface. Nonce is the exact 16-byte value in the active signed capability.
    oneway void reportLegacyPreprocessorTelemetryV4(int audioSessionId, String processName,
            long generation, in byte[] capabilityNonce, in byte[] snapshot);
    // Append-only v5 surface. Proof is the exact fixed 112-byte ECHT v2 value.
    oneway void reportLegacyPreprocessorTelemetryProofV5(int audioSessionId, String processName,
            long generation, in byte[] proof);
    boolean registerCaptureOwnerClient(String processName, long clientApiVersion,
            IEchidnaPolicyListener listener);
    oneway void reportCaptureOwnerInactive(
            String processName, long generation, long handoffToken);
    // Append-only v7 surface. These calls are synchronous only long enough to capture and validate
    // Binder UID/PID; expensive signing/HMAC work remains offloaded by the service.
    boolean requestLegacyPreprocessorCapabilityV7(int audioSessionId, String processName,
            long generation, in byte[] nonce, IEchidnaCapabilityCallback callback);
    boolean reportLegacyPreprocessorTelemetryV7(int audioSessionId, String processName,
            long generation, in byte[] capabilityNonce, in byte[] snapshot);
    boolean reportLegacyPreprocessorTelemetryProofV7(int audioSessionId, String processName,
            long generation, in byte[] proof);
    boolean reportCaptureOwnerInactiveV7(
            String processName, long generation, long handoffToken);
}
