package com.echidna.control.service;

oneway interface IEchidnaPolicyListener {
    void onPolicyChanged(long generation);
}
