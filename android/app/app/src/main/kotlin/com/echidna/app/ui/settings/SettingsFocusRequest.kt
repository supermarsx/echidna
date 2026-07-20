package com.echidna.app.ui.settings

/**
 * A settings area another screen wants opened. Kept out of the nav route so the Settings
 * destination string stays exactly `settings` — the top bar, bottom bar, and back stack all match
 * routes by equality, and a query argument would quietly break every one of them.
 */
enum class SettingsFocus {
    /** Settings → Engine, which holds DSP engine mode. */
    ENGINE,
}

/**
 * One-shot hand-off for "open Settings, on this tab". An alert action navigates to Settings and
 * leaves a request here; the Settings screen consumes it on first composition and it is gone, so a
 * later manual visit to Settings opens on the tab the user last chose rather than replaying an old
 * jump.
 */
object SettingsFocusRequest {

    @Volatile
    private var pending: SettingsFocus? = null

    /** Records where the next Settings composition should land. */
    fun request(focus: SettingsFocus) {
        pending = focus
    }

    /** Returns and clears the pending request, or null when there is none. */
    @Synchronized
    fun consume(): SettingsFocus? {
        val current = pending
        pending = null
        return current
    }
}
