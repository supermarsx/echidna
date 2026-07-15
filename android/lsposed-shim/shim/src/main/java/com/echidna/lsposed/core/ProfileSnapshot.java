package com.echidna.lsposed.core;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, strictly validated v2 policy view for one LSPosed target process. */
public final class ProfileSnapshot {

    static final int MAX_ENVELOPE_BYTES = 512 * 1024;
    static final int MAX_PRESET_BYTES = 256 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_PROFILE_ID_BYTES = 128;
    private static final int MAX_PROCESS_NAME_BYTES = 255;
    private static final Pattern PROFILE_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z0-9._]+");
    private static final Pattern PROCESS_NAME = Pattern.compile("[A-Za-z0-9._:]+");
    private static final Set<String> OWNERS = setOf("zygisk", "lsposed");
    private static final Set<String> ENGINE_MODES =
            setOf("native_first", "low_latency", "compatibility");
    private static final Set<String> ROOT_KEYS = setOf(
            "schemaVersion",
            "generation",
            "profiles",
            "defaultProfileId",
            "appBindings",
            "whitelist",
            "captureOwners",
            "control");
    private static final Set<String> CONTROL_KEYS = setOf(
            "masterEnabled",
            "bypass",
            "panicUntilEpochMs",
            "sidetoneEnabled",
            "sidetoneGainDb",
            "engineMode");

    private static final ProfileSnapshot EMPTY = new ProfileSnapshot(
            0L,
            "",
            "",
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            false,
            true,
            0L,
            "native_first");

    private final long generation;
    private final String rawPayload;
    private final String defaultProfileId;
    private final Map<String, Boolean> whitelist;
    private final Map<String, String> captureOwners;
    private final Map<String, String> appBindings;
    private final Map<String, String> profilePayloads;
    private final boolean masterEnabled;
    private final boolean bypass;
    private final long panicUntilEpochMs;
    private final String engineMode;

    private ProfileSnapshot(
            long generation,
            String rawPayload,
            String defaultProfileId,
            Map<String, Boolean> whitelist,
            Map<String, String> captureOwners,
            Map<String, String> appBindings,
            Map<String, String> profilePayloads,
            boolean masterEnabled,
            boolean bypass,
            long panicUntilEpochMs,
            String engineMode) {
        this.generation = generation;
        this.rawPayload = rawPayload;
        this.defaultProfileId = defaultProfileId;
        this.whitelist = whitelist;
        this.captureOwners = captureOwners;
        this.appBindings = appBindings;
        this.profilePayloads = profilePayloads;
        this.masterEnabled = masterEnabled;
        this.bypass = bypass;
        this.panicUntilEpochMs = panicUntilEpochMs;
        this.engineMode = engineMode;
    }

    /** Fail-closed sentinel. It is never confused with a valid but policy-denied v2 document. */
    public static ProfileSnapshot empty() {
        return EMPTY;
    }

    /** Parses one complete, strict v2 envelope. Any ambiguity returns the fail-closed sentinel. */
    public static ProfileSnapshot parse(String json) {
        if (!StrictJsonValidator.isSafe(json)) {
            return EMPTY;
        }
        byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_ENVELOPE_BYTES) {
            return EMPTY;
        }
        try {
            JSONObject root = new JSONObject(json);
            if (!hasExactlyKeys(root, ROOT_KEYS) || exactLong(root.opt("schemaVersion")) != 2L) {
                return EMPTY;
            }
            long generation = exactLong(root.opt("generation"));
            if (generation < 1L) {
                return EMPTY;
            }

            JSONObject profilesJson = root.optJSONObject("profiles");
            if (profilesJson == null || profilesJson.length() == 0
                    || profilesJson.length() > MAX_ENTRIES) {
                return EMPTY;
            }
            Map<String, String> profiles = new HashMap<>();
            for (Iterator<String> keys = profilesJson.keys(); keys.hasNext(); ) {
                String id = keys.next();
                JSONObject preset = profilesJson.optJSONObject(id);
                if (!validProfileId(id) || preset == null
                        || preset.optJSONObject("engine") == null
                        || preset.optJSONArray("modules") == null) {
                    return EMPTY;
                }
                String payload = preset.toString();
                if (!StrictJsonValidator.isWellFormedUtf16(payload)
                        || utf8Size(payload) > MAX_PRESET_BYTES) {
                    return EMPTY;
                }
                profiles.put(id, payload);
            }

            Object rawDefault = root.opt("defaultProfileId");
            if (!(rawDefault instanceof String)) {
                return EMPTY;
            }
            String defaultId = (String) rawDefault;
            if (!validProfileId(defaultId) || !profiles.containsKey(defaultId)) {
                return EMPTY;
            }

            Map<String, String> bindings = parseBindings(root.optJSONObject("appBindings"), profiles);
            Map<String, Boolean> whitelist = parseWhitelist(root.optJSONObject("whitelist"));
            Map<String, String> owners = parseOwners(root.optJSONObject("captureOwners"));
            if (bindings == null || whitelist == null || owners == null) {
                return EMPTY;
            }

            JSONObject control = root.optJSONObject("control");
            if (control == null || !hasExactlyKeys(control, CONTROL_KEYS)) {
                return EMPTY;
            }
            Object master = control.opt("masterEnabled");
            Object bypass = control.opt("bypass");
            Object sidetone = control.opt("sidetoneEnabled");
            Object gainValue = control.opt("sidetoneGainDb");
            Object modeValue = control.opt("engineMode");
            long panicUntil = exactLong(control.opt("panicUntilEpochMs"));
            if (!(master instanceof Boolean)
                    || !(bypass instanceof Boolean)
                    || !(sidetone instanceof Boolean)
                    || !(gainValue instanceof Number)
                    || !Double.isFinite(((Number) gainValue).doubleValue())
                    || !(modeValue instanceof String)
                    || !ENGINE_MODES.contains(modeValue)
                    || panicUntil < 0L) {
                return EMPTY;
            }

            return new ProfileSnapshot(
                    generation,
                    json,
                    defaultId,
                    Collections.unmodifiableMap(whitelist),
                    Collections.unmodifiableMap(owners),
                    Collections.unmodifiableMap(bindings),
                    Collections.unmodifiableMap(profiles),
                    (Boolean) master,
                    (Boolean) bypass,
                    panicUntil,
                    (String) modeValue);
        } catch (Exception ignored) {
            return EMPTY;
        }
    }

    private static Map<String, String> parseBindings(
            JSONObject root,
            Map<String, String> profiles) {
        if (root == null || root.length() > MAX_ENTRIES) {
            return null;
        }
        Map<String, String> bindings = new HashMap<>();
        for (Iterator<String> keys = root.keys(); keys.hasNext(); ) {
            String packageName = keys.next();
            Object preset = root.opt(packageName);
            if (!validPackageName(packageName)
                    || !(preset instanceof String)
                    || !profiles.containsKey(preset)) {
                return null;
            }
            bindings.put(packageName, (String) preset);
        }
        return bindings;
    }

    private static Map<String, Boolean> parseWhitelist(JSONObject root) {
        if (root == null || root.length() > MAX_ENTRIES) {
            return null;
        }
        Map<String, Boolean> entries = new HashMap<>();
        for (Iterator<String> keys = root.keys(); keys.hasNext(); ) {
            String processName = keys.next();
            Object enabled = root.opt(processName);
            if (!validProcessName(processName) || !(enabled instanceof Boolean)) {
                return null;
            }
            entries.put(processName, (Boolean) enabled);
        }
        return entries;
    }

    private static Map<String, String> parseOwners(JSONObject root) {
        if (root == null || root.length() > MAX_ENTRIES) {
            return null;
        }
        Map<String, String> entries = new HashMap<>();
        for (Iterator<String> keys = root.keys(); keys.hasNext(); ) {
            String processName = keys.next();
            Object owner = root.opt(processName);
            if (!validProcessName(processName)
                    || !(owner instanceof String)
                    || !OWNERS.contains(owner)) {
                return null;
            }
            entries.put(processName, (String) owner);
        }
        return entries;
    }

    /** Exact process policy overrides the base package; absence remains fail-closed. */
    public boolean isProcessAllowed(String packageName, String processName) {
        Boolean value = exactThenBase(whitelist, packageName, processName);
        return value != null && value;
    }

    /** Exact owner overrides the base owner; absence or mismatch is fail-closed. */
    public String captureOwner(String packageName, String processName) {
        String owner = exactThenBase(captureOwners, packageName, processName);
        return owner != null ? owner : "";
    }

    /** Binding lookup is always by base package; an unbound admitted process uses the default. */
    public String resolveProfile(String packageName) {
        if (!validPackageName(packageName)) {
            return "";
        }
        String presetId = appBindings.get(packageName);
        if (presetId == null) {
            presetId = defaultProfileId;
        }
        String payload = profilePayloads.get(presetId);
        return payload != null ? payload : "";
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

    public long generation() {
        return generation;
    }

    String rawPayload() {
        return rawPayload;
    }

    public boolean isValid() {
        return generation > 0L;
    }

    private static <T> T exactThenBase(
            Map<String, T> entries,
            String packageName,
            String processName) {
        if (processName != null && entries.containsKey(processName)) {
            return entries.get(processName);
        }
        return packageName != null ? entries.get(packageName) : null;
    }

    private static boolean hasExactlyKeys(JSONObject root, Set<String> allowed) {
        if (root.length() != allowed.size()) {
            return false;
        }
        for (Iterator<String> keys = root.keys(); keys.hasNext(); ) {
            if (!allowed.contains(keys.next())) {
                return false;
            }
        }
        return true;
    }

    private static long exactLong(Object value) {
        if (!(value instanceof Number)) {
            return -1L;
        }
        String decimal = value.toString();
        if (!decimal.matches("0|[1-9][0-9]*")) {
            return -1L;
        }
        try {
            return Long.parseLong(decimal);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static boolean validProfileId(String value) {
        return value != null && !value.isEmpty()
                && utf8Size(value) <= MAX_PROFILE_ID_BYTES
                && PROFILE_ID.matcher(value).matches();
    }

    private static boolean validPackageName(String value) {
        return value != null && !value.isEmpty()
                && utf8Size(value) <= MAX_PROCESS_NAME_BYTES
                && PACKAGE_NAME.matcher(value).matches();
    }

    private static boolean validProcessName(String value) {
        return value != null && !value.isEmpty()
                && utf8Size(value) <= MAX_PROCESS_NAME_BYTES
                && PROCESS_NAME.matcher(value).matches();
    }

    private static int utf8Size(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @SafeVarargs
    private static <T> Set<T> setOf(T... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
