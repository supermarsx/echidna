package com.echidna.app.system

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MagiskLauncherTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pm = context.packageManager

    /** Registers [pkg] with a launchable MAIN/LAUNCHER activity in the Robolectric package manager. */
    private fun installLaunchablePackage(pkg: String) {
        val shadowPm = shadowOf(pm)
        shadowPm.installPackage(
            android.content.pm.PackageInfo().apply { packageName = pkg }
        )
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(pkg, "$pkg.MainActivity")
        }
        shadowPm.addActivityIfNotPresent(launchIntent.component!!)
        shadowPm.addIntentFilterForActivity(
            launchIntent.component!!,
            android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )
    }

    @Test
    fun `resolves the stock Magisk package when installed`() {
        installLaunchablePackage("com.topjohnwu.magisk")

        val intent = MagiskLauncher.resolveLaunchIntent(pm)

        assertNotNull("expected a launch intent for stock Magisk", intent)
        assertEquals("com.topjohnwu.magisk", intent!!.component?.packageName)
        // Launched outside an Activity context, so it must carry NEW_TASK.
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `resolves a known Magisk fork when only the fork is installed`() {
        installLaunchablePackage("io.github.huskydg.magisk")

        val intent = MagiskLauncher.resolveLaunchIntent(pm)

        assertNotNull(intent)
        assertEquals("io.github.huskydg.magisk", intent!!.component?.packageName)
    }

    @Test
    fun `returns null when no Magisk package is installed (hidden or absent) so caller shows fallback`() {
        // No Magisk-family package registered — mirrors a hidden/repackaged Magisk whose random
        // package cannot be discovered, or a device with no Magisk at all.
        val intent = MagiskLauncher.resolveLaunchIntent(pm)

        assertNull(intent)
    }

    @Test
    fun `prefers the stock package over a fork when both are installed`() {
        installLaunchablePackage("io.github.vvb2060.magisk")
        installLaunchablePackage("com.topjohnwu.magisk")

        val intent = MagiskLauncher.resolveLaunchIntent(pm)

        assertEquals("com.topjohnwu.magisk", intent!!.component?.packageName)
    }
}
