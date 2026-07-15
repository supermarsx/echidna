package com.echidna.control.service;

/** Small invalidations only; clients fetch their UID-scoped document separately. */
oneway interface IEchidnaPolicyListener {
    void onPolicyChanged(long generation);
    void onCaptureOwnerRevoked(long generation, long handoffToken);
}
