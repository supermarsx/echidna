package com.echidna.lsposed.core;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Immutable view of the per-app policy published by the control service over the
 * ProfileSyncBridge contract (see t2-e6 signatures &sect;5). Carries the process
 * whitelist, per-package preset bindings, the raw preset payloads, and the
 * global control flags.
 *
 * <p>All lookups are fail-closed: an absent/false whitelist entry means "not
 * hooked", and an unparseable or missing snapshot yields {@link #empty()} which
 * denies everything.
 */
public final class ProfileSnapshot {

    private static final ProfileSnapshot EMPTY = new ProfileSnapshot(
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(),
            false,
            true,
            0L,
            "native_first");

    private final Map<String, Boolean> whitelist;
    private final Map<String, String> appBindings;
    private final Map<String, String> profilePayloads;
    private final boolean masterEnabled;
    private final boolean bypass;
    private final long panicUntilEpochMs;
    private final String engineMode;

    private ProfileSnapshot(
            Map<String, Boolean> whitelist,
            Map<String, String> appBindings,
            Map<String, String> profilePayloads,
            boolean masterEnabled,
            boolean bypass,
            long panicUntilEpochMs,
            String engineMode) {
        this.whitelist = whitelist;
        this.appBindings = appBindings;
        this.profilePayloads = profilePayloads;
        this.masterEnabled = masterEnabled;
        this.bypass = bypass;
        this.panicUntilEpochMs = panicUntilEpochMs;
        this.engineMode = engineMode != null ? engineMode : "native_first";
    }

    /** Fail-closed empty snapshot: nothing whitelisted, engine treated as disabled. */
    public static ProfileSnapshot empty() {
        return EMPTY;
    }

    /**
     * Parses the JSON snapshot documented in t2-e6 &sect;5
     * ({@code {"profiles":{...},"whitelist":{...},"appBindings":{...},"control":{...}}}).
     * The {@code control} object is additive; when absent the global switches
     * default to enabled so a snapshot from an older service still resolves via
     * the whitelist. Any parse failure returns {@link #empty()} (fail-closed).
     */
    public static ProfileSnapshot parse(String json) {
        if (TextUtils.isEmpty(json)) {
            return EMPTY;
        }
        try {
            JSONObject root = new JSONObject(json);

            Map<String, Boolean> whitelist = new HashMap<>();
            JSONObject whitelistJson = root.optJSONObject("whitelist");
            if (whitelistJson != null) {
                for (Iterator<String> it = whitelistJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    whitelist.put(key, whitelistJson.optBoolean(key, false));
                }
            }

            Map<String, String> appBindings = new HashMap<>();
            JSONObject bindingsJson = root.optJSONObject("appBindings");
            if (bindingsJson != null) {
                for (Iterator<String> it = bindingsJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    String presetId = bindingsJson.optString(key, "");
                    if (!TextUtils.isEmpty(presetId)) {
                        appBindings.put(key, presetId);
                    }
                }
            }

            Map<String, String> profilePayloads = new HashMap<>();
            JSONObject profilesJson = root.optJSONObject("profiles");
            if (profilesJson != null) {
                for (Iterator<String> it = profilesJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    JSONObject preset = profilesJson.optJSONObject(key);
                    if (preset != null) {
                        profilePayloads.put(key, preset.toString());
                    }
                }
            }

            boolean masterEnabled = true;
            boolean bypass = false;
            long panicUntil = 0L;
            String engineMode = "native_first";
            JSONObject control = root.optJSONObject("control");
            if (control != null) {
                masterEnabled = control.optBoolean("masterEnabled", true);
                bypass = control.optBoolean("bypass", false);
                panicUntil = control.optLong("panicUntilEpochMs", 0L);
                engineMode = control.optString("engineMode", "native_first");
            }

            return new ProfileSnapshot(
                    Collections.unmodifiableMap(whitelist),
                    Collections.unmodifiableMap(appBindings),
                    Collections.unmodifiableMap(profilePayloads),
                    masterEnabled,
                    bypass,
                    panicUntil,
                    engineMode);
        } catch (JSONException e) {
            // Fail-closed: an unparseable snapshot yields no policy at all.
            return EMPTY;
        }
    }

    /**
     * Returns true only when the process (checked first) or its package has an
     * explicit whitelist entry set to {@code true}. Absent/false is fail-closed.
     */
    public boolean isProcessAllowed(String packageName, String processName) {
        Boolean byProcess = processName != null ? whitelist.get(processName) : null;
        if (byProcess != null) {
            return byProcess;
        }
        Boolean byPackage = packageName != null ? whitelist.get(packageName) : null;
        return byPackage != null && byPackage;
    }

    /**
     * Resolves the preset payload bound to {@code packageName}. Prefers the full
     * preset JSON from {@code profiles} when the bound id is present there; falls
     * back to the raw binding id, or empty string when there is no binding.
     */
    public String resolveProfile(String packageName) {
        if (packageName == null) {
            return "";
        }
        String presetId = appBindings.get(packageName);
        if (TextUtils.isEmpty(presetId)) {
            return "";
        }
        String payload = profilePayloads.get(presetId);
        return payload != null ? payload : presetId;
    }

    /** Global gate: master enabled, not bypassed, and not inside an active panic hold. */
    public boolean isGloballyEnabled() {
        if (!masterEnabled || bypass) {
            return false;
        }
        return panicUntilEpochMs <= 0L || System.currentTimeMillis() >= panicUntilEpochMs;
    }

    public String engineMode() {
        return engineMode;
    }
}
