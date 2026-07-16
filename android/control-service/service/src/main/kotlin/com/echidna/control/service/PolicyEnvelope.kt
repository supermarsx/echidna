package com.echidna.control.service

import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal const val POLICY_SCHEMA_VERSION = 2
internal const val MAX_POLICY_ENVELOPE_BYTES = 512 * 1024
internal const val MAX_POLICY_PRESET_BYTES = 256 * 1024
private const val MAX_POLICY_ENTRIES = 256
private const val MAX_PROFILE_ID_BYTES = 128
private const val MAX_PROCESS_NAME_BYTES = 255
private val PROFILE_ID_PATTERN = Regex("[A-Za-z0-9._-]+")
private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9._]+")
private val PROCESS_NAME_PATTERN = Regex("[A-Za-z0-9._:]+")
private val CAPTURE_OWNERS = setOf("zygisk", "lsposed")
private val ENGINE_MODES = setOf("native_first", "low_latency", "compatibility")

internal data class PolicyControl(
    val masterEnabled: Boolean,
    val bypass: Boolean,
    val panicUntilEpochMs: Long,
    val sidetoneEnabled: Boolean,
    val sidetoneGainDb: Double,
    val engineMode: String,
)

internal data class PolicyEnvelope(
    val profiles: LinkedHashMap<String, JSONObject>,
    val defaultProfileId: String,
    val appBindings: LinkedHashMap<String, String>,
    val whitelist: LinkedHashMap<String, Boolean>,
    val captureOwners: LinkedHashMap<String, String>,
    val control: PolicyControl,
    val appIdentities: LinkedHashMap<String, PublishedAppIdentity> = linkedMapOf(),
)

internal data class VersionedPolicyEnvelope(
    val generation: Long,
    val envelope: PolicyEnvelope,
)

internal object PolicyEnvelopeCodec {
    fun parseRequest(payload: String): PolicyEnvelope? {
        if (!isWellFormedUtf16(payload)) return null
        if (utf8Size(payload) > MAX_POLICY_ENVELOPE_BYTES) return null
        if (JsonDuplicateKeyScanner.hasDuplicateKeys(payload)) return null
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!hasExactlyAllowedKeys(root, REQUEST_ROOT_KEYS)) return null
        if (root.optInt("schemaVersion", -1) != POLICY_SCHEMA_VERSION) return null
        return parseEnvelope(root)
    }

    fun parsePublished(payload: String): VersionedPolicyEnvelope? {
        if (!isWellFormedUtf16(payload)) return null
        if (utf8Size(payload) > MAX_POLICY_ENVELOPE_BYTES) return null
        if (JsonDuplicateKeyScanner.hasDuplicateKeys(payload)) return null
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!hasExactlyAllowedKeys(root, IDENTITY_BOUND_PUBLISHED_ROOT_KEYS)) return null
        if (root.optInt("schemaVersion", -1) != POLICY_SCHEMA_VERSION) return null
        val generationValue = root.opt("generation") as? Number ?: return null
        val generation = exactNonNegativeLong(generationValue)?.takeIf { it >= 1L } ?: return null
        val envelope = parseEnvelope(root)?.copy(
            appIdentities = parseAppIdentities(root.optJSONObject("appIdentities") ?: return null)
                ?: return null,
        ) ?: return null
        if (!isValidEnvelope(envelope)) return null
        return VersionedPolicyEnvelope(generation, envelope)
    }

    /** Parses a pre-identity v2 store only so ProfileStore can rewrite it fail-closed. */
    fun parseUnboundPublished(payload: String): VersionedPolicyEnvelope? = parseTransport(payload)

    /** Parses a strict process-scoped wire policy, which never carries install identity metadata. */
    fun parseTransport(payload: String): VersionedPolicyEnvelope? {
        if (!isWellFormedUtf16(payload) || utf8Size(payload) > MAX_POLICY_ENVELOPE_BYTES) return null
        if (JsonDuplicateKeyScanner.hasDuplicateKeys(payload)) return null
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!hasExactlyAllowedKeys(root, PUBLISHED_ROOT_KEYS)) return null
        if (root.optInt("schemaVersion", -1) != POLICY_SCHEMA_VERSION) return null
        val generationValue = root.opt("generation") as? Number ?: return null
        val generation = exactNonNegativeLong(generationValue)?.takeIf { it >= 1L } ?: return null
        val envelope = parseEnvelope(root) ?: return null
        if (!isValidEnvelope(envelope)) return null
        return VersionedPolicyEnvelope(generation, envelope)
    }

    fun encode(envelope: PolicyEnvelope, generation: Long): String? {
        if (generation < 1L || !isValidEnvelope(envelope)) return null
        return encodeEnvelope(envelope, generation, includeIdentities = true)
    }

    private fun encodeTransport(envelope: PolicyEnvelope, generation: Long): String? {
        if (generation < 1L || !isValidEnvelope(envelope)) return null
        return encodeEnvelope(envelope, generation, includeIdentities = false)
    }

    private fun encodeEnvelope(
        envelope: PolicyEnvelope,
        generation: Long,
        includeIdentities: Boolean,
    ): String? {
        val profiles = JSONObject()
        envelope.profiles.forEach { (id, preset) -> profiles.put(id, preset) }
        val bindings = JSONObject()
        envelope.appBindings.forEach { (name, id) -> bindings.put(name, id) }
        val whitelist = JSONObject()
        envelope.whitelist.forEach { (name, enabled) -> whitelist.put(name, enabled) }
        val owners = JSONObject()
        envelope.captureOwners.forEach { (name, owner) -> owners.put(name, owner) }
        val control = JSONObject()
            .put("masterEnabled", envelope.control.masterEnabled)
            .put("bypass", envelope.control.bypass)
            .put("panicUntilEpochMs", envelope.control.panicUntilEpochMs)
            .put("sidetoneEnabled", envelope.control.sidetoneEnabled)
            .put("sidetoneGainDb", envelope.control.sidetoneGainDb)
            .put("engineMode", envelope.control.engineMode)
        val payload = JSONObject()
            .put("schemaVersion", POLICY_SCHEMA_VERSION)
            .put("generation", generation)
            .put("profiles", profiles)
            .put("defaultProfileId", envelope.defaultProfileId)
            .put("appBindings", bindings)
            .put("whitelist", whitelist)
            .put("captureOwners", owners)
            .put("control", control)
        if (includeIdentities) {
            val identities = JSONObject()
            envelope.appIdentities.forEach { (packageName, identity) ->
                identities.put(packageName, identity.toJson())
            }
            payload.put("appIdentities", identities)
        }
        val encoded = payload.toString()
        return encoded.takeIf {
            isWellFormedUtf16(it) && utf8Size(it) <= MAX_POLICY_ENVELOPE_BYTES
        }
    }

    fun migrateLegacy(payload: String): VersionedPolicyEnvelope? {
        if (!isWellFormedUtf16(payload)) return null
        if (utf8Size(payload) > MAX_POLICY_ENVELOPE_BYTES) return null
        if (JsonDuplicateKeyScanner.hasDuplicateKeys(payload)) return null
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val profilesJson = root.optJSONObject("profiles") ?: return null
        val profiles = parseProfiles(profilesJson) ?: return null
        if (profiles.isEmpty()) return null
        val bindings = parseBindings(root.optJSONObject("appBindings") ?: JSONObject(), profiles)
            ?: return null
        val whitelist = parseWhitelist(root.optJSONObject("whitelist") ?: JSONObject()) ?: return null
        val control = parseLegacyControl(root.optJSONObject("control"))
        val owner = if (control.engineMode == ENGINE_MODE_COMPATIBILITY) "lsposed" else "zygisk"
        val owners = linkedMapOf<String, String>()
        whitelist.filterValues { it }.keys.forEach { owners[it] = owner }
        val envelope = PolicyEnvelope(
            profiles = profiles,
            defaultProfileId = profiles.keys.first(),
            appBindings = bindings,
            whitelist = whitelist,
            captureOwners = owners,
            control = control,
        )
        return VersionedPolicyEnvelope(1L, envelope)
    }

    /**
     * Produces a read-only policy view for packages proven to share the socket peer UID.
     * Unknown UIDs receive no document at all, rather than learning the default preset.
     */
    fun encodeScopedForPackages(
        published: VersionedPolicyEnvelope,
        packageNames: Set<String>,
        requireWhitelistMatch: Boolean = true,
    ): String? {
        val packages = packageNames.filterTo(linkedSetOf(), ::isValidPackageName)
        if (packages.isEmpty()) return null
        val whitelist = published.envelope.whitelist.filterTo(linkedMapOf()) { (name, _) ->
            processBase(name) in packages
        }
        if (requireWhitelistMatch && whitelist.isEmpty()) return null
        val policyKeys = linkedSetOf<String>()
        policyKeys.addAll(whitelist.keys)
        policyKeys.addAll(
            published.envelope.captureOwners.keys.filter { processBase(it) in packages },
        )
        return encodeTransport(
            scopeEnvelope(published.envelope, packages, policyKeys),
            published.generation,
        )
    }

    /** Produces the exact/base view authorized for one explicit Binder caller process. */
    fun encodeScopedForProcess(
        published: VersionedPolicyEnvelope,
        packageName: String,
        processName: String,
    ): String? {
        if (!isValidPackageName(packageName) || !isValidProcessName(processName)) return null
        if (processBase(processName) != packageName) return null
        val policyKeys = linkedSetOf(packageName, processName)
        val whitelist = published.envelope.whitelist.filter { (name, _) ->
            name in policyKeys
        }
        if (whitelist.isEmpty()) return null
        return encodeTransport(
            scopeEnvelope(published.envelope, setOf(packageName), policyKeys),
            published.generation,
        )
    }

    /** Process-scoped transport view with a coordinator-selected effective capture owner. */
    fun encodeScopedForProcessWithOwner(
        published: VersionedPolicyEnvelope,
        packageName: String,
        processName: String,
        owner: String?,
    ): String? {
        if (!isValidPackageName(packageName) || !isValidProcessName(processName)) return null
        if (processBase(processName) != packageName) return null
        if (owner != null && owner !in CAPTURE_OWNERS) return null
        val policyKeys = linkedSetOf(packageName, processName)
        val scoped = scopeEnvelope(published.envelope, setOf(packageName), policyKeys)
        val processAllowed = published.envelope.whitelist[processName]
            ?: published.envelope.whitelist[packageName]
            ?: false
        val effectiveOwners = linkedMapOf<String, String>()
        if (owner != null) effectiveOwners[processName] = owner
        return encodeTransport(
            scoped.copy(
                whitelist = linkedMapOf(processName to processAllowed),
                captureOwners = effectiveOwners,
            ),
            published.generation,
        )
    }

    private fun scopeEnvelope(
        envelope: PolicyEnvelope,
        packages: Set<String>,
        whitelistKeys: Set<String>,
    ): PolicyEnvelope {
        val bindings = envelope.appBindings.filterTo(linkedMapOf()) { (name, _) ->
            name in packages
        }
        val neededProfiles = linkedSetOf(envelope.defaultProfileId)
        neededProfiles.addAll(bindings.values)
        val profiles = envelope.profiles.filterTo(linkedMapOf()) { (id, _) -> id in neededProfiles }
        val whitelist = envelope.whitelist.filterTo(linkedMapOf()) { (name, _) ->
            name in whitelistKeys
        }
        val owners = envelope.captureOwners.filterTo(linkedMapOf()) { (name, _) ->
            name in whitelistKeys
        }
        return PolicyEnvelope(
            profiles = profiles,
            defaultProfileId = envelope.defaultProfileId,
            appBindings = bindings,
            whitelist = whitelist,
            captureOwners = owners,
            control = envelope.control,
        )
    }

    private fun parseEnvelope(root: JSONObject): PolicyEnvelope? {
        val profiles = parseProfiles(root.optJSONObject("profiles") ?: return null) ?: return null
        if (profiles.isEmpty()) return null
        val defaultProfileId = root.optString("defaultProfileId", "")
        if (!isValidProfileId(defaultProfileId) || !profiles.containsKey(defaultProfileId)) return null
        val bindings = parseBindings(root.optJSONObject("appBindings") ?: return null, profiles)
            ?: return null
        val whitelist = parseWhitelist(root.optJSONObject("whitelist") ?: return null) ?: return null
        val owners = parseOwners(root.optJSONObject("captureOwners") ?: return null) ?: return null
        val control = parseControl(root.optJSONObject("control") ?: return null) ?: return null
        return PolicyEnvelope(profiles, defaultProfileId, bindings, whitelist, owners, control)
    }

    private fun parseProfiles(root: JSONObject): LinkedHashMap<String, JSONObject>? {
        if (root.length() > MAX_POLICY_ENTRIES) return null
        val profiles = linkedMapOf<String, JSONObject>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val preset = root.optJSONObject(id) ?: return null
            if (!isValidProfileId(id) || !isStructuredPreset(preset)) return null
            if (utf8Size(preset.toString()) > MAX_POLICY_PRESET_BYTES) return null
            profiles[id] = JSONObject(preset.toString())
        }
        return profiles
    }

    private fun parseBindings(
        root: JSONObject,
        profiles: Map<String, JSONObject>,
    ): LinkedHashMap<String, String>? {
        if (root.length() > MAX_POLICY_ENTRIES) return null
        val bindings = linkedMapOf<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val rawId = root.opt(name)
            if (!isValidPackageName(name) || rawId !is String || rawId !in profiles) return null
            bindings[name] = rawId
        }
        return bindings
    }

    private fun parseWhitelist(root: JSONObject): LinkedHashMap<String, Boolean>? {
        if (root.length() > MAX_POLICY_ENTRIES) return null
        val whitelist = linkedMapOf<String, Boolean>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val enabled = root.opt(name)
            if (!isValidProcessName(name) || enabled !is Boolean) return null
            whitelist[name] = enabled
        }
        return whitelist
    }

    private fun parseOwners(root: JSONObject): LinkedHashMap<String, String>? {
        if (root.length() > MAX_POLICY_ENTRIES) return null
        val owners = linkedMapOf<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val owner = root.opt(name)
            if (!isValidProcessName(name) || owner !is String || owner !in CAPTURE_OWNERS) return null
            owners[name] = owner
        }
        return owners
    }

    private fun parseAppIdentities(
        root: JSONObject,
    ): LinkedHashMap<String, PublishedAppIdentity>? {
        if (root.length() > MAX_POLICY_ENTRIES) return null
        val identities = linkedMapOf<String, PublishedAppIdentity>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val packageName = keys.next()
            if (!isPolicyPackageName(packageName)) return null
            val identity = PublishedAppIdentity.parse(
                packageName,
                root.optJSONObject(packageName) ?: return null,
            ) ?: return null
            identities[packageName] = identity
        }
        return identities
    }

    private fun parseControl(root: JSONObject): PolicyControl? {
        if (!hasExactlyAllowedKeys(root, CONTROL_KEYS)) return null
        val master = root.opt("masterEnabled") as? Boolean ?: return null
        val bypass = root.opt("bypass") as? Boolean ?: return null
        val panic = exactNonNegativeLong(root.opt("panicUntilEpochMs") as? Number ?: return null)
            ?: return null
        val sidetone = root.opt("sidetoneEnabled") as? Boolean ?: return null
        val gain = (root.opt("sidetoneGainDb") as? Number)?.toDouble() ?: return null
        if (!gain.isFinite()) return null
        val mode = root.opt("engineMode") as? String ?: return null
        if (mode !in ENGINE_MODES) return null
        return PolicyControl(master, bypass, panic, sidetone, gain, mode)
    }

    private fun parseLegacyControl(root: JSONObject?): PolicyControl {
        val mode = root?.optString("engineMode", ENGINE_MODE_NATIVE_FIRST)
            ?.takeIf { it in ENGINE_MODES }
            ?: ENGINE_MODE_NATIVE_FIRST
        return PolicyControl(
            masterEnabled = root?.optBoolean("masterEnabled", true) ?: true,
            bypass = root?.optBoolean("bypass", false) ?: false,
            panicUntilEpochMs = root?.optLong("panicUntilEpochMs", 0L)?.coerceAtLeast(0L) ?: 0L,
            sidetoneEnabled = root?.optBoolean("sidetoneEnabled", false) ?: false,
            sidetoneGainDb = root?.optDouble("sidetoneGainDb", 0.0)
                ?.takeIf { it.isFinite() }
                ?: 0.0,
            engineMode = mode,
        )
    }

    private fun isValidEnvelope(envelope: PolicyEnvelope): Boolean =
        envelope.profiles.isNotEmpty() &&
            envelope.profiles.size <= MAX_POLICY_ENTRIES &&
            envelope.appBindings.size <= MAX_POLICY_ENTRIES &&
            envelope.whitelist.size <= MAX_POLICY_ENTRIES &&
            envelope.captureOwners.size <= MAX_POLICY_ENTRIES &&
            envelope.defaultProfileId in envelope.profiles &&
            envelope.profiles.all { (id, preset) ->
                isValidProfileId(id) &&
                    isStructuredPreset(preset) &&
                    utf8Size(preset.toString()) <= MAX_POLICY_PRESET_BYTES
            } &&
            envelope.appBindings.all { (name, id) ->
                isValidPackageName(name) && id in envelope.profiles
            } &&
            envelope.whitelist.keys.all(::isValidProcessName) &&
            envelope.captureOwners.all { (name, owner) ->
                isValidProcessName(name) && owner in CAPTURE_OWNERS
            } &&
            envelope.appIdentities.size <= MAX_POLICY_ENTRIES &&
            envelope.appIdentities.all { (packageName, identity) ->
                packageName == identity.packageName &&
                    packageName in envelope.whitelist.keys.map(::processBase) &&
                    identity.isValid()
            } &&
            envelope.control.panicUntilEpochMs >= 0L &&
            envelope.control.sidetoneGainDb.isFinite() &&
            envelope.control.engineMode in ENGINE_MODES

    private fun exactNonNegativeLong(number: Number): Long? {
        val asDouble = number.toDouble()
        if (!asDouble.isFinite()) return null
        val asLong = number.toLong()
        return asLong.takeIf { it >= 0L && asDouble == asLong.toDouble() }
    }

    private fun isStructuredPreset(root: JSONObject): Boolean =
        root.optJSONArray("modules") != null && root.optJSONObject("engine") != null

    private fun isValidProfileId(id: String): Boolean =
        id.isNotEmpty() && utf8Size(id) <= MAX_PROFILE_ID_BYTES && PROFILE_ID_PATTERN.matches(id)

    private fun isValidProcessName(name: String): Boolean = isPolicyProcessName(name)

    private fun isValidPackageName(name: String): Boolean = isPolicyPackageName(name)

    private fun processBase(name: String): String = policyProcessBase(name)

    private fun hasExactlyAllowedKeys(root: JSONObject, allowed: Set<String>): Boolean {
        if (root.length() != allowed.size) return false
        val keys = root.keys()
        while (keys.hasNext()) {
            if (keys.next() !in allowed) return false
        }
        return true
    }

    private fun utf8Size(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    private const val ENGINE_MODE_NATIVE_FIRST = "native_first"
    private const val ENGINE_MODE_COMPATIBILITY = "compatibility"
    private val REQUEST_ROOT_KEYS = setOf(
        "schemaVersion",
        "profiles",
        "defaultProfileId",
        "appBindings",
        "whitelist",
        "captureOwners",
        "control",
    )
    private val PUBLISHED_ROOT_KEYS = REQUEST_ROOT_KEYS + "generation"
    private val IDENTITY_BOUND_PUBLISHED_ROOT_KEYS = PUBLISHED_ROOT_KEYS + "appIdentities"
    private val CONTROL_KEYS = setOf(
        "masterEnabled",
        "bypass",
        "panicUntilEpochMs",
        "sidetoneEnabled",
        "sidetoneGainDb",
        "engineMode",
    )
}

internal fun isPolicyProcessName(name: String): Boolean =
    name.isNotEmpty() &&
        name.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROCESS_NAME_BYTES &&
        PROCESS_NAME_PATTERN.matches(name)

internal fun isPolicyPackageName(name: String): Boolean =
    name.isNotEmpty() &&
        name.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROCESS_NAME_BYTES &&
        PACKAGE_NAME_PATTERN.matches(name)

internal fun policyProcessBase(name: String): String = name.substringBefore(':')

/** Small strict pre-scan because org.json silently keeps only the last duplicate object key. */
internal object JsonDuplicateKeyScanner {
    fun hasDuplicateKeys(json: String): Boolean = Scanner(json).isDuplicateOrMalformed()

    private class Scanner(private val text: String) {
        private var index = 0
        private var duplicate = false

        fun isDuplicateOrMalformed(): Boolean {
            skipWhitespace()
            if (!readValue(0)) return true
            skipWhitespace()
            return duplicate || index != text.length
        }

        private fun readValue(depth: Int): Boolean {
            if (depth > 128) return false
            skipWhitespace()
            if (index >= text.length) return false
            return when (text[index]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString() != null
                else -> readPrimitive()
            }
        }

        private fun readObject(depth: Int): Boolean {
            index += 1
            skipWhitespace()
            if (consume('}')) return true
            val keys = mutableSetOf<String>()
            while (index < text.length) {
                skipWhitespace()
                val key = readString() ?: return false
                if (!keys.add(key)) duplicate = true
                skipWhitespace()
                if (!consume(':') || !readValue(depth)) return false
                skipWhitespace()
                if (consume('}')) return true
                if (!consume(',')) return false
            }
            return false
        }

        private fun readArray(depth: Int): Boolean {
            index += 1
            skipWhitespace()
            if (consume(']')) return true
            while (index < text.length) {
                if (!readValue(depth)) return false
                skipWhitespace()
                if (consume(']')) return true
                if (!consume(',')) return false
            }
            return false
        }

        private fun readString(): String? {
            if (!consume('"')) return null
            val value = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                when {
                    char == '"' -> return value.toString().takeIf(::isWellFormedUtf16)
                    char.code < 0x20 -> return null
                    char != '\\' -> value.append(char)
                    index >= text.length -> return null
                    else -> {
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> value.append(escaped)
                            'b' -> value.append('\b')
                            'f' -> value.append('\u000c')
                            'n' -> value.append('\n')
                            'r' -> value.append('\r')
                            't' -> value.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) return null
                                val code = text.substring(index, index + 4).toIntOrNull(16)
                                    ?: return null
                                value.append(code.toChar())
                                index += 4
                            }
                            else -> return null
                        }
                    }
                }
            }
            return null
        }

        private fun readPrimitive(): Boolean {
            val start = index
            while (index < text.length) {
                val char = text[index]
                if (char == ',' || char == ']' || char == '}' || char.isJsonWhitespace()) break
                index += 1
            }
            return index > start
        }

        private fun consume(expected: Char): Boolean {
            if (index >= text.length || text[index] != expected) return false
            index += 1
            return true
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isJsonWhitespace()) index += 1
        }

        private fun Char.isJsonWhitespace(): Boolean =
            this == ' ' || this == '\t' || this == '\r' || this == '\n'
    }
}

internal fun isWellFormedUtf16(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when {
            current.isHighSurrogate() -> {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                index += 2
            }
            current.isLowSurrogate() -> return false
            else -> index += 1
        }
    }
    return true
}
