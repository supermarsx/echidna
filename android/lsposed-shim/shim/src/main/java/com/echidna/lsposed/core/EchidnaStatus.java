package com.echidna.lsposed.core;

/**
 * Mirrors the status codes exposed by {@code echidna_get_status()} on the native side.
 */
public enum EchidnaStatus {
    DISABLED(0),
    WAITING_FOR_ATTACH(1),
    HOOKED(2),
    ERROR(3);

    private final int code;

    EchidnaStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static EchidnaStatus fromNativeCode(int value) {
        for (EchidnaStatus status : values()) {
            if (status.code == value) {
                return status;
            }
        }
        return ERROR;
    }
}
