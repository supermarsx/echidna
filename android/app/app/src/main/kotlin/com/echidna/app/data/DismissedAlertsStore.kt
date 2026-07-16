package com.echidna.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the set of dismissed in-app alerts so a banner the user dismissed stays dismissed
 * across app restarts.
 *
 * Backing store is a small [SharedPreferences] holding a single `String` set of stable per-alert
 * keys. SharedPreferences (not the app's atomic-JSON settings files) is deliberate: dismissed
 * state is a tiny, independent, key/value concern that never needs to round-trip through the
 * control service, and a synchronous read is required to decide a banner's *initial* visibility
 * during composition without an async gap that would make dismissed alerts flash on every launch.
 *
 * ### Persistence semantics
 * Two usage modes are supported, chosen per alert surface (see callers):
 *  - **Permanent dismissal** — call [setDismissed] with a key that is stable for that specific
 *    notice. Once dismissed it stays dismissed forever. Use for one-time informational notices.
 *  - **Condition-scoped dismissal** — for alerts driven by a live condition (e.g. "no apps
 *    whitelisted", a preset warning), key on the *condition* and call [reconcileActive] with the
 *    keys of the conditions currently active before rendering. Any dismissed key whose condition
 *    is no longer active is cleared, so the alert reappears if the condition recurs — while a
 *    still-active condition stays dismissed across restarts. This satisfies both "stays dismissed
 *    across restarts" and "reappears when the condition recurs", and avoids permanently silencing
 *    safety-relevant advisories.
 */
class DismissedAlertsStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /** Returns true if [key] has been dismissed and not since cleared. */
    fun isDismissed(key: String): Boolean = current().contains(key)

    /** Marks [key] dismissed (true) or restores it (false). Persists immediately. */
    fun setDismissed(key: String, dismissed: Boolean) {
        val set = current()
        val changed = if (dismissed) set.add(key) else set.remove(key)
        if (changed) persist(set)
    }

    /** Snapshot of all currently-dismissed keys. */
    fun dismissedKeys(): Set<String> = current()

    /** Clears every dismissed key. */
    fun clear() {
        prefs.edit().remove(KEY_DISMISSED).apply()
    }

    /**
     * Within the namespace identified by [keyPrefix], drops any dismissed key that is not in
     * [activeKeys]. Call this for condition-driven alerts with the set of keys whose conditions
     * are currently active, so a previously-dismissed alert reappears once its condition has
     * cleared and later recurs. Keys outside [keyPrefix] (other alert surfaces sharing this store)
     * are never touched. No-op if nothing changes.
     */
    fun reconcileActive(activeKeys: Set<String>, keyPrefix: String) {
        val set = current()
        val changed = set.removeAll { it.startsWith(keyPrefix) && it !in activeKeys }
        if (changed) persist(set)
    }

    // SharedPreferences hands back an immutable/shared set instance; copy before mutating.
    private fun current(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet())

    private fun persist(set: Set<String>) {
        prefs.edit().putStringSet(KEY_DISMISSED, HashSet(set)).apply()
    }

    companion object {
        const val PREFS_NAME = "echidna_dismissed_alerts"
        private const val KEY_DISMISSED = "dismissed_keys"
    }
}
