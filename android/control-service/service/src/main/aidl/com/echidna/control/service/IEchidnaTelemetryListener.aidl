package com.echidna.control.service;

oneway interface IEchidnaTelemetryListener {
    void onTelemetry(String telemetryJson);
}
