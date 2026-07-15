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
}
