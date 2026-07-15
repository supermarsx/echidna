package com.echidna.control.service;

oneway interface IEchidnaPolicyListener {
    void onPolicyChanged(long generation);
    void onCaptureOwnerRevoked(long generation, long handoffToken);
}
