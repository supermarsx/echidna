package com.echidna.control.service;

import com.echidna.control.service.IEchidnaPolicyListener;

interface IEchidnaPolicyProvider {
    String getPolicySnapshot(String processName);
    void registerListener(String processName, IEchidnaPolicyListener listener);
    void unregisterListener(IEchidnaPolicyListener listener);
}
