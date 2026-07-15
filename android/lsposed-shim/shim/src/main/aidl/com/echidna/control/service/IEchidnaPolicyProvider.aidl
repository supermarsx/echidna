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
}
