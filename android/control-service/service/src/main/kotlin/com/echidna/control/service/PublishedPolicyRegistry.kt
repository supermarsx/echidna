package com.echidna.control.service

import java.io.Closeable
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

    fun current(): VersionedPolicyEnvelope? = state.get()?.published

    fun authorizeProcess(
        peerUid: Int,
        processName: String?,
        resolver: PublishedAppIdentityResolver,
    ): PublishedProcessIdentityBinding? = state.get()?.let { current ->
        PublishedProcessIdentityAuthorizer.authorize(
            current.published,
            peerUid,
            processName,
            resolver,
        )
    }

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

    fun observe(observer: (Long) -> Unit): Closeable {
        observers.add(observer)
        return Closeable { observers.remove(observer) }
    }

    internal fun resetForTests() {
        state.set(null)
        observers.clear()
    }
}
