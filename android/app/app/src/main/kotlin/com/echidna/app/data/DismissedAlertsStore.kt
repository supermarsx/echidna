package com.echidna.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the set of dismissed in-app alerts so a banner the user dismissed stays dismissed
 * across app restarts.
 *
 * Backing store is a small [SharedPreferences] holding two independent `String` sets of stable
 * per-alert keys — one for temporary dismissals, one for permanent ("don't remind") dismissals.
 * SharedPreferences (not the app's atomic-JSON settings files) is deliberate: dismissed state is a
 * tiny, independent, key/value concern that never needs to round-trip through the control service,
 * and a synchronous read is required to decide a banner's *initial* visibility during composition
 * without an async gap that would make dismissed alerts flash on every launch.
 *
 * ### Persistence semantics
 * Three usage modes are supported, chosen per alert surface (see callers):
 *  - **Temporary / condition-scoped dismissal** — [setDismissed]. For alerts driven by a live
 *    condition (e.g. "no apps whitelisted", a preset warning), key on the *condition* and call
 *    [reconcileActive] with the keys of the conditions currently active before rendering. Any
 *    dismissed key whose condition is no longer active is cleared, so the alert reappears if the
 *    condition recurs — while a still-active condition stays dismissed across restarts.
 *  - **Permanent ("don't remind") dismissal** — [setPermanentlyDismissed]. Once chosen, the alert
 *    NEVER shows again for that key: permanent keys live in their own set that [reconcileActive]
 *    never touches, so a recurring condition does not bring the alert back. Callers that want a
 *    safety-relevant alert to re-appear only on a *material* state change encode that state into
 *    the permanent key (a new state → a new key → the alert shows once more).
 *  - **One-time informational** — a stable [setDismissed] key with no [reconcileActive] call also
 *    stays dismissed forever; use [setPermanentlyDismissed] when the intent is an explicit,
 *    user-chosen "don't remind".
 *
 * [isDismissed] reports true when a key is dismissed by *either* mechanism, so a banner's initial
 * visibility check is a single call.
 */
class DismissedAlertsStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /** Returns true if [key] has been dismissed temporarily or permanently and not since cleared. */
    fun isDismissed(key: String): Boolean =
        current(KEY_DISMISSED).contains(key) || current(KEY_PERMANENT).contains(key)

    /** Returns true only if [key] was dismissed via the permanent "don't remind" affordance. */
    fun isPermanentlyDismissed(key: String): Boolean = current(KEY_PERMANENT).contains(key)

    /** Marks [key] temporarily dismissed (true) or restores it (false). Persists immediately. */
    fun setDismissed(key: String, dismissed: Boolean) {
        mutate(KEY_DISMISSED) { set -> if (dismissed) set.add(key) else set.remove(key) }
    }

    /**
     * Marks [key] permanently dismissed (true) or restores it (false). A permanent dismissal is
     * never cleared by [reconcileActive], so once the user picks "don't remind" the alert stays
     * gone across restarts and even when its condition recurs. Persists immediately.
     */
    fun setPermanentlyDismissed(key: String, dismissed: Boolean) {
        mutate(KEY_PERMANENT) { set -> if (dismissed) set.add(key) else set.remove(key) }
    }

    /** Snapshot of all currently temp-dismissed keys. */
    fun dismissedKeys(): Set<String> = current(KEY_DISMISSED)

    /** Snapshot of all currently permanently-dismissed keys. */
    fun permanentlyDismissedKeys(): Set<String> = current(KEY_PERMANENT)

    /** Clears every dismissed key, temporary and permanent. */
    fun clear() {
        prefs.edit().remove(KEY_DISMISSED).remove(KEY_PERMANENT).apply()
    }

    /**
     * Within the temporary-dismissal namespace identified by [keyPrefix], drops any dismissed key
     * that is not in [activeKeys]. Call this for condition-driven alerts with the set of keys whose
     * conditions are currently active, so a previously-dismissed alert reappears once its condition
     * has cleared and later recurs. Keys outside [keyPrefix], and *all* permanent dismissals, are
     * never touched. No-op if nothing changes.
     */
    fun reconcileActive(activeKeys: Set<String>, keyPrefix: String) {
        mutate(KEY_DISMISSED) { set ->
            set.removeAll { it.startsWith(keyPrefix) && it !in activeKeys }
        }
    }

    // SharedPreferences hands back an immutable/shared set instance; copy before mutating.
    private fun current(storageKey: String): MutableSet<String> =
        HashSet(prefs.getStringSet(storageKey, emptySet()) ?: emptySet())

    private inline fun mutate(storageKey: String, block: (MutableSet<String>) -> Boolean) {
        val set = current(storageKey)
        if (block(set)) prefs.edit().putStringSet(storageKey, HashSet(set)).apply()
    }

    companion object {
        const val PREFS_NAME = "echidna_dismissed_alerts"
        private const val KEY_DISMISSED = "dismissed_keys"
        private const val KEY_PERMANENT = "permanent_keys"
    }
}
