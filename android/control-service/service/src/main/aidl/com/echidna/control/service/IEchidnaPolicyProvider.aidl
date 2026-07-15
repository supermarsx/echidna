package com.echidna.control.service;

import com.echidna.control.service.IEchidnaPolicyListener;

/** Exported read-only policy surface for an explicitly addressed LSPosed target process. */
interface IEchidnaPolicyProvider {
    String getPolicySnapshot(String processName);
    void registerListener(String processName, IEchidnaPolicyListener listener);
    void unregisterListener(IEchidnaPolicyListener listener);
}
