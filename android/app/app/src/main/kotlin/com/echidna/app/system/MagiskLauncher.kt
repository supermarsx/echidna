package com.echidna.app.system

import android.content.Intent
import android.content.pm.PackageManager

/**
 * Resolves and launches the Magisk manager app so the installer can send users straight to it
 * (e.g. to enable Zygisk after installing the module).
 *
 * Magisk is frequently *hidden* (stub-repackaged under a random package name) as an anti-detection
 * measure. When that is the case there is no reliable, non-privileged way to discover the random
 * package, so this helper is honest about it: it tries the packages it *can* name, and when none
 * resolve it reports failure to the caller, which shows fallback guidance ("open Magisk manually")
 * rather than pretending it launched something.
 */
object MagiskLauncher {

    /**
     * Known Magisk manager package names, in preference order. The stock package first, then the
     * common maintained forks. A hidden/repackaged Magisk uses a random package not in this list —
     * that case is handled by the honest fallback in [resolveLaunchIntent] returning null.
     */
    val CANDIDATE_PACKAGES: List<String> = listOf(
        "com.topjohnwu.magisk", // stock Magisk
        "io.github.huskydg.magisk", // Magisk Delta / Kitsune fork
        "io.github.vvb2060.magisk", // Magisk Alpha
        "com.topjohnwu.magisk.debug", // Magisk debug builds
    )

    /**
     * Returns a launch [Intent] for the first installed, resolvable Magisk manager, or null when
     * none of the [CANDIDATE_PACKAGES] is installed with a launchable activity (including the case
     * of a hidden/repackaged Magisk whose random package cannot be discovered here).
     *
     * Package visibility on Android 11+ is provided by the `<queries>` LAUNCHER intent already
     * declared in the app manifest, so `getLaunchIntentForPackage` can see launcher apps.
     */
    fun resolveLaunchIntent(packageManager: PackageManager): Intent? {
        for (pkg in CANDIDATE_PACKAGES) {
            val intent = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            if (intent != null) {
                return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return null
    }
}
