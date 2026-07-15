package com.echidna.control.service

import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

/** Process-local, read-only view shared by the control and exported policy-provider services. */
internal object PublishedPolicyRegistry {
    private data class State(
        val payload: String,
        val published: VersionedPolicyEnvelope,
    )

    private val state = AtomicReference<State?>()
    private val observers = CopyOnWriteArraySet<(Long) -> Unit>()

    fun publish(payload: String): Boolean {
        val parsed = PolicyEnvelopeCodec.parsePublished(payload) ?: return false
        while (true) {
            val current = state.get()
            if (current != null) {
                if (parsed.generation < current.published.generation) return false
                if (parsed.generation == current.published.generation) {
                    return current.payload == payload
                }
            }
            if (state.compareAndSet(current, State(payload, parsed))) {
                observers.forEach { observer -> observer(parsed.generation) }
                return true
            }
        }
    }

    fun generation(): Long = state.get()?.published?.generation ?: 0L

    fun scopedForPackages(packageNames: Set<String>): String? = state.get()?.let { current ->
        PolicyEnvelopeCodec.encodeScopedForPackages(current.published, packageNames)
    }

    fun scopedForProcess(packageName: String, processName: String): String? =
        state.get()?.let { current ->
            PolicyEnvelopeCodec.encodeScopedForProcess(
                current.published,
                packageName,
                processName,
            )
        }

    /** Resolves only service-owned current state; capability callers cannot provide preset bytes. */
    fun capabilityForProcess(
        packageName: String,
        processName: String,
        nowEpochMs: Long,
    ): LegacyCapabilityPolicy? = state.get()?.published?.let { published ->
        if (processName.substringBefore(':') != packageName) return@let null
        val envelope = published.envelope
        val allowed = envelope.whitelist[processName] ?: envelope.whitelist[packageName] ?: false
        val owner = envelope.captureOwners[processName] ?: envelope.captureOwners[packageName]
        val control = envelope.control
        if (
            !allowed || owner != "lsposed" || !control.masterEnabled || control.bypass ||
            (control.panicUntilEpochMs > 0L && control.panicUntilEpochMs > nowEpochMs)
        ) {
            return@let null
        }
        val profileId = envelope.appBindings[packageName] ?: envelope.defaultProfileId
        val preset = envelope.profiles[profileId]?.toString()?.toByteArray(StandardCharsets.UTF_8)
            ?: return@let null
        LegacyCapabilityPolicy(published.generation, processName, preset)
    }

    fun observe(observer: (Long) -> Unit): Closeable {
        observers.add(observer)
        return Closeable { observers.remove(observer) }
    }

    internal fun resetForTests() {
        state.set(null)
        observers.clear()
    }
}
