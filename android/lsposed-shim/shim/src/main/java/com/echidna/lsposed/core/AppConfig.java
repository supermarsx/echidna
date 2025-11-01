package com.echidna.lsposed.core;

import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lightweight representation of the control service response for a process.
 */
public final class AppConfig {

    private static final long DEFAULT_TTL_MS = 30_000L;

    private final boolean hookingEnabled;
    private final boolean bypassRequested;
    private final Set<String> packageFilters;
    private final Set<String> processFilters;
    private final String profile;
    private final long expiresAtMs;

    private AppConfig(
            boolean hookingEnabled,
            boolean bypassRequested,
            Set<String> packageFilters,
            Set<String> processFilters,
            String profile,
            long expiresAtMs) {
        this.hookingEnabled = hookingEnabled;
        this.bypassRequested = bypassRequested;
        this.packageFilters = packageFilters;
        this.processFilters = processFilters;
        this.profile = profile != null ? profile : "";
        this.expiresAtMs = expiresAtMs;
    }

    public static AppConfig disabled() {
        return new AppConfig(false, true, Collections.emptySet(), Collections.emptySet(), "", 0L);
    }

    public static AppConfig fromBundle(Bundle bundle) {
        boolean enabled = bundle.getBoolean("enabled", false);
        boolean bypass = bundle.getBoolean("bypass", !enabled);
        String profile = bundle.getString("profile", "");
        Set<String> packages = readSet(bundle, "packages");
        Set<String> processes = readSet(bundle, "processes");
        long ttl = bundle.getLong("ttlMs", DEFAULT_TTL_MS);
        long expiresAt = ttl > 0 ? SystemClock.elapsedRealtime() + ttl : 0L;
        return new AppConfig(enabled, bypass, packages, processes, profile, expiresAt);
    }

    public boolean shouldHook(String packageName, @Nullable String processName) {
        if (!hookingEnabled) {
            return false;
        }
        boolean packageMatch = packageFilters.isEmpty() || packageFilters.contains(packageName);
        boolean processMatch = processFilters.isEmpty() || (processName != null && processFilters.contains(processName));
        return packageMatch && processMatch;
    }

    public boolean bypassRequested() {
        return bypassRequested;
    }

    public String profile() {
        return profile;
    }

    public boolean isExpired() {
        long expiry = expiresAtMs;
        return expiry > 0 && SystemClock.elapsedRealtime() > expiry;
    }

    private static Set<String> readSet(Bundle bundle, String key) {
        List<String> list = bundle.getStringArrayList(key);
        if (list != null) {
            return new HashSet<>(list);
        }
        String[] array = bundle.getStringArray(key);
        if (array != null) {
            return new HashSet<>(Arrays.asList(array));
        }
        return Collections.emptySet();
    }
}
