package com.echidna.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Persistence + condition-reconcile semantics of [DismissedAlertsStore]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DismissedAlertsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        context.getSharedPreferences(DismissedAlertsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `dismiss then restore toggles state`() {
        val store = DismissedAlertsStore(context)
        assertFalse(store.isDismissed("k1"))
        store.setDismissed("k1", true)
        assertTrue(store.isDismissed("k1"))
        store.setDismissed("k1", false)
        assertFalse(store.isDismissed("k1"))
    }

    @Test
    fun `dismissed state persists across store instances`() {
        DismissedAlertsStore(context).setDismissed("persisted", true)
        // A brand-new store instance reads the same SharedPreferences file (simulates restart).
        assertTrue(DismissedAlertsStore(context).isDismissed("persisted"))
    }

    @Test
    fun `reconcile clears keys whose condition is no longer active within the namespace`() {
        val store = DismissedAlertsStore(context)
        store.setDismissed("ns.a", true)
        store.setDismissed("ns.b", true)

        // Only condition "ns.a" is still active → "ns.b" should be cleared and thus reappear.
        store.reconcileActive(setOf("ns.a"), keyPrefix = "ns.")

        assertTrue(store.isDismissed("ns.a"))
        assertFalse(store.isDismissed("ns.b"))
    }

    @Test
    fun `reconcile never touches keys outside its namespace`() {
        val store = DismissedAlertsStore(context)
        store.setDismissed("effects.x", true)
        store.setDismissed("settings.y", true)

        // Reconciling the "effects." namespace with no active effect keys must not drop the
        // dismissed "settings." key belonging to another alert surface sharing the store.
        store.reconcileActive(emptySet(), keyPrefix = "effects.")

        assertFalse(store.isDismissed("effects.x"))
        assertTrue(store.isDismissed("settings.y"))
    }

    @Test
    fun `condition still active survives reconcile - stays dismissed across restart`() {
        DismissedAlertsStore(context).setDismissed("ns.live", true)
        // Restart: new instance, condition still active → reconcile keeps it dismissed.
        val afterRestart = DismissedAlertsStore(context)
        afterRestart.reconcileActive(setOf("ns.live"), keyPrefix = "ns.")
        assertTrue(afterRestart.isDismissed("ns.live"))
    }

    @Test
    fun `permanent dismissal persists and is reported by both accessors`() {
        DismissedAlertsStore(context).setPermanentlyDismissed("perm.key", true)
        val afterRestart = DismissedAlertsStore(context)
        assertTrue(afterRestart.isDismissed("perm.key"))
        assertTrue(afterRestart.isPermanentlyDismissed("perm.key"))
    }

    @Test
    fun `permanent dismissal is never cleared by reconcile - never reappears`() {
        val store = DismissedAlertsStore(context)
        // Permanent "don't remind" on a condition-keyed alert.
        store.setPermanentlyDismissed("ns.perm", true)

        // Reconcile with the condition NOT active (would clear a temporary dismissal) must leave the
        // permanent one untouched, so the alert never comes back even when the condition recurs.
        store.reconcileActive(emptySet(), keyPrefix = "ns.")

        assertTrue(store.isDismissed("ns.perm"))
        assertTrue(store.isPermanentlyDismissed("ns.perm"))
    }

    @Test
    fun `temporary and permanent dismissals are independent`() {
        val store = DismissedAlertsStore(context)
        store.setDismissed("ns.temp", true)
        // A plain temporary dismissal is not a permanent one.
        assertTrue(store.isDismissed("ns.temp"))
        assertFalse(store.isPermanentlyDismissed("ns.temp"))

        // Reconciling away the temp condition clears the temp dismissal entirely.
        store.reconcileActive(emptySet(), keyPrefix = "ns.")
        assertFalse(store.isDismissed("ns.temp"))
    }

    @Test
    fun `clear removes both temporary and permanent dismissals`() {
        val store = DismissedAlertsStore(context)
        store.setDismissed("a", true)
        store.setPermanentlyDismissed("b", true)
        store.clear()
        assertFalse(store.isDismissed("a"))
        assertFalse(store.isDismissed("b"))
        assertFalse(store.isPermanentlyDismissed("b"))
    }
}
