package com.echidna.lsposed.core;

/**
 * Convenience facade consumed by LSPosed UI integration to surface module state and toggles.
 */
public final class EchidnaControls {

    private EchidnaControls() {
    }

    public static EchidnaStatus getNativeStatus() {
        return ModuleState.getInstance().getNativeStatus();
    }

    public static String getActiveProfile() {
        return ModuleState.getInstance().getActiveProfile();
    }

    public static boolean isBypassEnabled() {
        return ModuleState.getInstance().isBypassActive();
    }

    public static void setBypassEnabled(boolean enabled) {
        ModuleState.getInstance().setBypassOverride(enabled);
    }

    public static boolean refreshConfiguration() {
        return ModuleState.getInstance().refreshConfiguration();
    }
}
