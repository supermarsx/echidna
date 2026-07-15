package com.echidna.lsposed;

import com.echidna.lsposed.core.ModuleState;
import com.echidna.lsposed.hooks.AudioRecordHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point registered through {@code assets/xposed_init}. Installs AudioRecord hooks
 * once the target process has been authorised by the control service configuration.
 */
public final class EchidnaModule implements IXposedHookLoadPackage {

    private final ModuleState moduleState = ModuleState.getInstance();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            moduleState.onProcessAttached(lpparam.packageName, lpparam.processName);
            // Policy arrives asynchronously over the profile socket. Install inert hooks now and
            // gate every callback against ModuleState; otherwise a cold process permanently misses
            // its only installation opportunity before the first snapshot can arrive.
            AudioRecordHook.install(lpparam.classLoader, moduleState);
        } catch (Throwable throwable) {
            XposedBridge.log(
                    "EchidnaModule: failed closed for "
                            + lpparam.packageName
                            + "/"
                            + lpparam.processName
                            + ": "
                            + throwable);
        }
    }
}
