package com.echidna.app.model

/**
 * Read-back of the persisted per-process whitelist and per-app preset bindings
 * from the control service's getWhitelistBindings() (t2-e6 signatures §2):
 * `{"whitelist":{proc:bool},"appBindings":{pkg:presetId}}`.
 */
data class WhitelistBindings(
    val whitelist: Map<String, Boolean>,
    val appBindings: Map<String, String>
)
