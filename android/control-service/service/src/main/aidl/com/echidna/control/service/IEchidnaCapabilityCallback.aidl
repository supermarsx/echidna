package com.echidna.control.service;

/** One-shot result for short-lived legacy preprocessor capability issuance. */
oneway interface IEchidnaCapabilityCallback {
    void onCapabilityResult(int status, long generation, in byte[] envelope, String diagnostic);
}
