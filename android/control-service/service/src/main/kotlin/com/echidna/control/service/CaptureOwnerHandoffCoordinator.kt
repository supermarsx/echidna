package com.echidna.control.service

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal enum class CaptureRouteOwner {
    NONE,
    ZYGISK,
    LSPOSED,
}

internal enum class CaptureHandoffPhase {
    INACTIVE,
    WAIT_NATIVE_INACTIVE,
    WAIT_LSPOSED_INACTIVE,
    WAIT_NATIVE_ACTIVE,
    ACTIVE_ZYGISK,
    ACTIVE_LSPOSED,
    FAILED,
}

internal fun nextCaptureHandoffToken(current: Long): Long =
    if (current <= 0L || current == Long.MAX_VALUE) 1L else current + 1L

internal fun interface CaptureHandoffTask {
    fun cancel()
}

internal fun interface CaptureHandoffScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): CaptureHandoffTask?
}

internal interface NativeCaptureEndpoint {
    val processName: String
    fun publishPolicy(payload: String, handoffToken: Long): Boolean
    fun close()
}

internal interface LsposedCaptureEndpoint {
    val processName: String
    fun revoke(generation: Long, handoffToken: Long): Boolean
    fun policyChanged(generation: Long)
}

/**
 * Serial, process-scoped two-phase ownership gate.
 *
 * Policy generation remains the capability incarnation. The incoming transport never receives an
 * activating document until the current outgoing transport has acknowledged the same generation
 * inactive. Endpoint identity is part of every acknowledgement, so a replay from a replaced
 * socket or Binder registration cannot advance the state machine.
 */
internal class CaptureOwnerHandoffCoordinator(
    private val scheduler: CaptureHandoffScheduler = RealCaptureHandoffScheduler(),
    private val timeoutMs: Long = 2_000L,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : Closeable {
    private data class ProcessState(
        val processName: String,
        var policy: VersionedPolicyEnvelope? = null,
        var pendingPolicy: VersionedPolicyEnvelope? = null,
        var desired: CaptureRouteOwner = CaptureRouteOwner.NONE,
        var effective: CaptureRouteOwner = CaptureRouteOwner.NONE,
        var phase: CaptureHandoffPhase = CaptureHandoffPhase.INACTIVE,
        var native: NativeCaptureEndpoint? = null,
        var nativeBinding: PublishedProcessIdentityBinding? = null,
        var lsposed: LsposedCaptureEndpoint? = null,
        var lsposedBinding: PublishedProcessIdentityBinding? = null,
        var nativeSeen: Boolean = false,
        var lsposedSeen: Boolean = false,
        var transition: Long = 0L,
        var timeout: CaptureHandoffTask? = null,
    )

    private sealed interface Action {
        data class PublishNative(
            val state: ProcessState,
            val endpoint: NativeCaptureEndpoint,
            val payload: String,
            val generation: Long,
            val token: Long,
            val phase: CaptureHandoffPhase,
        ) : Action

        data class RevokeLsposed(
            val state: ProcessState,
            val endpoint: LsposedCaptureEndpoint,
            val generation: Long,
            val token: Long,
            val phase: CaptureHandoffPhase,
        ) : Action

        data class PolicyChanged(
            val state: ProcessState,
            val endpoint: LsposedCaptureEndpoint,
            val generation: Long,
            val token: Long,
        ) : Action

        data class ScheduleTimeout(
            val state: ProcessState,
            val token: Long,
            val phase: CaptureHandoffPhase,
        ) : Action

        data class CancelTimeout(val task: CaptureHandoffTask) : Action

        data class CloseNative(val endpoint: NativeCaptureEndpoint) : Action

        data class Notify(
            val state: ProcessState,
            val processName: String,
            val generation: Long,
            val token: Long,
            val phase: CaptureHandoffPhase,
            val owner: CaptureRouteOwner,
        ) : Action
    }

    private val states = ConcurrentHashMap<String, ProcessState>()
    private val observers = CopyOnWriteArraySet<(String, Long, CaptureRouteOwner) -> Unit>()
    private val policyLock = Any()
    @Volatile private var latestPolicy: VersionedPolicyEnvelope? = null
    @Volatile private var closed = false

    fun publishPolicy(policy: VersionedPolicyEnvelope): Boolean {
        synchronized(policyLock) {
            if (closed || policy.generation <= 0L) return false
            val current = latestPolicy
            if (current != null && policy.generation < current.generation) return false
            if (current != null && policy.generation == current.generation) return true
            latestPolicy = policy
        }
        val actions = mutableListOf<Action>()
        states.values.forEach { state ->
            synchronized(state) {
                if (latestPolicy === policy) beginPolicy(state, policy, actions)
            }
        }
        execute(actions)
        return true
    }

    fun registerNative(
        endpoint: NativeCaptureEndpoint,
        binding: PublishedProcessIdentityBinding,
    ): Boolean {
        if (closed || !validProcess(endpoint.processName)) return false
        if (binding.processName != endpoint.processName) return false
        val state = states.computeIfAbsent(endpoint.processName) { ProcessState(it) }
        val actions = mutableListOf<Action>()
        val registered = synchronized(state) {
            if (closed) return@synchronized false
            if (state.native === endpoint) return@synchronized true
            if (state.native != null) return@synchronized false
            val policy = latestPolicy ?: return@synchronized false
            if (
                binding.generation != policy.generation ||
                !PublishedProcessIdentityAuthorizer.matches(policy, binding)
            ) {
                return@synchronized false
            }
            state.native = endpoint
            state.nativeBinding = binding
            state.nativeSeen = true
            beginTransition(state, policy, actions)
            notifyState(state, actions)
            true
        }
        execute(actions)
        return registered
    }

    fun unregisterNative(endpoint: NativeCaptureEndpoint) {
        val state = states[endpoint.processName] ?: return
        val actions = mutableListOf<Action>()
        synchronized(state) {
            if (state.native !== endpoint) return
            state.native = null
            state.nativeBinding = null
            clearTimeout(state, actions)
            state.transition = nextCaptureHandoffToken(state.transition)
            state.effective = CaptureRouteOwner.NONE
            state.phase = CaptureHandoffPhase.FAILED
            state.lsposed?.let {
                actions += Action.RevokeLsposed(
                    state,
                    it,
                    state.policy?.generation ?: 0L,
                    state.transition,
                    CaptureHandoffPhase.FAILED,
                )
            }
            notifyState(state, actions)
        }
        execute(actions)
    }

    fun registerLsposed(
        endpoint: LsposedCaptureEndpoint,
        binding: PublishedProcessIdentityBinding,
    ): Boolean {
        if (closed || !validProcess(endpoint.processName)) return false
        if (binding.processName != endpoint.processName) return false
        val state = states.computeIfAbsent(endpoint.processName) { ProcessState(it) }
        val actions = mutableListOf<Action>()
        val registered = synchronized(state) {
            if (closed) return@synchronized false
            if (state.lsposed === endpoint) return@synchronized true
            if (state.lsposed != null) return@synchronized false
            val policy = latestPolicy ?: return@synchronized false
            if (
                binding.generation != policy.generation ||
                !PublishedProcessIdentityAuthorizer.matches(policy, binding)
            ) {
                return@synchronized false
            }
            state.lsposed = endpoint
            state.lsposedBinding = binding
            state.lsposedSeen = true
            beginTransition(state, policy, actions)
            notifyState(state, actions)
            true
        }
        execute(actions)
        return registered
    }

    fun unregisterLsposed(endpoint: LsposedCaptureEndpoint) {
        val state = states[endpoint.processName] ?: return
        val actions = mutableListOf<Action>()
        synchronized(state) {
            if (state.lsposed !== endpoint) return
            state.lsposed = null
            state.lsposedBinding = null
            clearTimeout(state, actions)
            state.transition = nextCaptureHandoffToken(state.transition)
            state.effective = CaptureRouteOwner.NONE
            state.phase = CaptureHandoffPhase.FAILED
            // Provider death must also revoke an already admitted native route.
            state.native?.let { actions += Action.CloseNative(it) }
            state.native = null
            state.nativeBinding = null
            notifyState(state, actions)
        }
        execute(actions)
    }

    fun acknowledgeNative(
        endpoint: NativeCaptureEndpoint,
        processName: String,
        generation: Long,
        handoffToken: Long,
        active: Boolean,
    ): Boolean {
        val state = states[processName] ?: return false
        val actions = mutableListOf<Action>()
        val accepted = synchronized(state) {
            if (
                closed || state.native !== endpoint || endpoint.processName != processName ||
                state.policy?.generation != generation || state.transition != handoffToken
            ) return@synchronized false
            when (state.phase) {
            CaptureHandoffPhase.WAIT_NATIVE_INACTIVE -> {
                if (active) return@synchronized false
                clearTimeout(state, actions)
                if (resumePendingPolicy(state, actions)) {
                    notifyState(state, actions)
                    return@synchronized true
                }
                startLsposedDrain(state, actions)
                notifyState(state, actions)
                true
            }

            CaptureHandoffPhase.WAIT_NATIVE_ACTIVE -> {
                if (!active || state.desired != CaptureRouteOwner.ZYGISK) {
                    return@synchronized false
                }
                clearTimeout(state, actions)
                state.phase = CaptureHandoffPhase.ACTIVE_ZYGISK
                state.effective = CaptureRouteOwner.ZYGISK
                notifyState(state, actions)
                true
            }

            else -> false
            }
        }
        execute(actions)
        return accepted
    }

    fun acknowledgeLsposedInactive(
        endpoint: LsposedCaptureEndpoint,
        processName: String,
        generation: Long,
        handoffToken: Long,
    ): Boolean {
        val state = states[processName] ?: return false
        val actions = mutableListOf<Action>()
        val accepted = synchronized(state) {
            if (
                closed || state.lsposed !== endpoint || endpoint.processName != processName ||
                state.policy?.generation != generation || state.transition != handoffToken ||
                state.phase != CaptureHandoffPhase.WAIT_LSPOSED_INACTIVE
            ) return@synchronized false
            clearTimeout(state, actions)
            if (resumePendingPolicy(state, actions)) {
                notifyState(state, actions)
                return@synchronized true
            }
            activateDesired(state, actions)
            notifyState(state, actions)
            true
        }
        execute(actions)
        return accepted
    }

    fun lsposedPolicy(
        endpoint: LsposedCaptureEndpoint,
        packageName: String,
        processName: String,
    ): String? {
        val state = states[processName] ?: return null
        return synchronized(state) {
            if (
                state.lsposed !== endpoint || state.effective != CaptureRouteOwner.LSPOSED ||
                state.phase != CaptureHandoffPhase.ACTIVE_LSPOSED ||
                !PublishedProcessIdentityAuthorizer.matches(
                    state.policy ?: return@synchronized null,
                    state.lsposedBinding,
                ) ||
                processName.substringBefore(':') != packageName
            ) return@synchronized null
            scopedPolicy(state, CaptureRouteOwner.LSPOSED)
        }
    }

    fun capabilityPolicy(
        endpoint: LsposedCaptureEndpoint,
        packageName: String,
        processName: String,
        nowEpochMs: Long,
    ): LegacyCapabilityPolicy? {
        val state = states[processName] ?: return null
        return synchronized(state) {
            if (
                state.lsposed !== endpoint || state.effective != CaptureRouteOwner.LSPOSED ||
                state.phase != CaptureHandoffPhase.ACTIVE_LSPOSED ||
                !PublishedProcessIdentityAuthorizer.matches(
                    state.policy ?: return@synchronized null,
                    state.lsposedBinding,
                )
            ) return@synchronized null
            val policy = state.policy ?: return@synchronized null
            capabilityForPublished(policy, packageName, processName, nowEpochMs)
        }
    }

    fun phase(processName: String): CaptureHandoffPhase {
        val state = states[processName] ?: return CaptureHandoffPhase.INACTIVE
        return synchronized(state) { state.phase }
    }

    fun effectiveOwner(processName: String): CaptureRouteOwner {
        val state = states[processName] ?: return CaptureRouteOwner.NONE
        return synchronized(state) { state.effective }
    }

    fun acceptsNativeTelemetry(
        endpoint: NativeCaptureEndpoint,
        processName: String,
        generation: Long,
    ): Boolean {
        val state = states[processName] ?: return false
        return synchronized(state) {
            state.native === endpoint && state.policy?.generation == generation &&
                state.phase == CaptureHandoffPhase.ACTIVE_ZYGISK &&
                state.effective == CaptureRouteOwner.ZYGISK &&
                PublishedProcessIdentityAuthorizer.matches(state.policy!!, state.nativeBinding)
        }
    }

    fun observe(observer: (String, Long, CaptureRouteOwner) -> Unit): Closeable {
        observers.add(observer)
        return Closeable { observers.remove(observer) }
    }

    override fun close() {
        synchronized(policyLock) {
            if (closed) return
            closed = true
        }
        val actions = mutableListOf<Action>()
        states.values.forEach { state ->
            synchronized(state) {
                clearTimeout(state, actions)
                state.transition = nextCaptureHandoffToken(state.transition)
                state.effective = CaptureRouteOwner.NONE
                state.phase = CaptureHandoffPhase.FAILED
                state.lsposed?.let {
                    actions += Action.RevokeLsposed(
                        state,
                        it,
                        state.policy?.generation ?: 0L,
                        state.transition,
                        state.phase,
                    )
                }
                state.native?.let { actions += Action.CloseNative(it) }
            }
        }
        states.clear()
        observers.clear()
        execute(actions)
        (scheduler as? Closeable)?.close()
    }

    private fun beginPolicy(
        state: ProcessState,
        policy: VersionedPolicyEnvelope,
        actions: MutableList<Action>,
    ) {
        if (
            state.phase == CaptureHandoffPhase.WAIT_NATIVE_INACTIVE ||
            state.phase == CaptureHandoffPhase.WAIT_LSPOSED_INACTIVE
        ) {
            state.pendingPolicy = policy
            state.effective = CaptureRouteOwner.NONE
            notifyState(state, actions)
            return
        }
        beginTransition(state, policy, actions)
        notifyState(state, actions)
    }

    private fun beginTransition(
        state: ProcessState,
        policy: VersionedPolicyEnvelope,
        actions: MutableList<Action>,
    ) {
        state.policy = policy
        state.pendingPolicy = null
        state.desired = desiredOwner(policy, state.processName)
        state.effective = CaptureRouteOwner.NONE
        clearTimeout(state, actions)
        state.transition = nextCaptureHandoffToken(state.transition)
        startNativeDrain(state, actions)
    }

    private fun resumePendingPolicy(state: ProcessState, actions: MutableList<Action>): Boolean {
        val pending = state.pendingPolicy ?: return false
        state.pendingPolicy = null
        beginTransition(state, pending, actions)
        return true
    }

    private fun startNativeDrain(state: ProcessState, actions: MutableList<Action>) {
        val native = state.native
        if (native == null) {
            if (state.nativeSeen) return fail(state, actions)
            startLsposedDrain(state, actions)
            return
        }
        val payload = scopedPolicy(state, CaptureRouteOwner.NONE)
            ?: return fail(state, actions)
        state.phase = CaptureHandoffPhase.WAIT_NATIVE_INACTIVE
        actions += Action.ScheduleTimeout(state, state.transition, state.phase)
        actions += Action.PublishNative(
            state,
            native,
            payload,
            state.policy?.generation ?: 0L,
            state.transition,
            state.phase,
        )
    }

    private fun startLsposedDrain(state: ProcessState, actions: MutableList<Action>) {
        val lsposed = state.lsposed
        if (lsposed == null) {
            if (state.lsposedSeen) return fail(state, actions)
            activateDesired(state, actions)
            return
        }
        val generation = state.policy?.generation ?: return fail(state, actions)
        state.phase = CaptureHandoffPhase.WAIT_LSPOSED_INACTIVE
        actions += Action.ScheduleTimeout(state, state.transition, state.phase)
        actions += Action.RevokeLsposed(
            state,
            lsposed,
            generation,
            state.transition,
            state.phase,
        )
    }

    private fun activateDesired(state: ProcessState, actions: MutableList<Action>) {
        val generation = state.policy?.generation ?: return fail(state, actions)
        when (state.desired) {
            CaptureRouteOwner.NONE -> {
                state.phase = CaptureHandoffPhase.INACTIVE
                state.effective = CaptureRouteOwner.NONE
            }

            CaptureRouteOwner.ZYGISK -> {
                val native = state.native
                if (native == null) {
                    state.phase = if (state.nativeSeen) {
                        CaptureHandoffPhase.FAILED
                    } else {
                        CaptureHandoffPhase.INACTIVE
                    }
                    return
                }
                if (!PublishedProcessIdentityAuthorizer.matches(state.policy!!, state.nativeBinding)) {
                    return fail(state, actions)
                }
                val payload = scopedPolicy(state, CaptureRouteOwner.ZYGISK)
                    ?: return fail(state, actions)
                state.phase = CaptureHandoffPhase.WAIT_NATIVE_ACTIVE
                actions += Action.ScheduleTimeout(state, state.transition, state.phase)
                actions += Action.PublishNative(
                    state,
                    native,
                    payload,
                    generation,
                    state.transition,
                    state.phase,
                )
            }

            CaptureRouteOwner.LSPOSED -> {
                val lsposed = state.lsposed
                if (lsposed == null) {
                    state.phase = if (state.lsposedSeen) {
                        CaptureHandoffPhase.FAILED
                    } else {
                        CaptureHandoffPhase.INACTIVE
                    }
                    return
                }
                if (!PublishedProcessIdentityAuthorizer.matches(state.policy!!, state.lsposedBinding)) {
                    return fail(state, actions)
                }
                state.phase = CaptureHandoffPhase.ACTIVE_LSPOSED
                state.effective = CaptureRouteOwner.LSPOSED
                actions += Action.PolicyChanged(
                    state,
                    lsposed,
                    generation,
                    state.transition,
                )
            }
        }
    }

    private fun fail(state: ProcessState, actions: MutableList<Action>) {
        clearTimeout(state, actions)
        state.transition = nextCaptureHandoffToken(state.transition)
        state.phase = CaptureHandoffPhase.FAILED
        state.effective = CaptureRouteOwner.NONE
        state.lsposed?.let {
            actions += Action.RevokeLsposed(
                state,
                it,
                state.policy?.generation ?: 0L,
                state.transition,
                state.phase,
            )
        }
        state.native?.let { actions += Action.CloseNative(it) }
        state.native = null
        state.nativeBinding = null
        notifyState(state, actions)
    }

    private fun scopedPolicy(state: ProcessState, owner: CaptureRouteOwner): String? {
        val policy = state.policy ?: return null
        val packageName = state.processName.substringBefore(':')
        val wireOwner = when (owner) {
            CaptureRouteOwner.ZYGISK -> "zygisk"
            CaptureRouteOwner.LSPOSED -> "lsposed"
            CaptureRouteOwner.NONE -> null
        }
        return PolicyEnvelopeCodec.encodeScopedForProcessWithOwner(
            policy,
            packageName,
            state.processName,
            wireOwner,
        )
    }

    private fun desiredOwner(
        policy: VersionedPolicyEnvelope,
        processName: String,
    ): CaptureRouteOwner {
        val packageName = processName.substringBefore(':')
        val allowed = policy.envelope.whitelist[processName]
            ?: policy.envelope.whitelist[packageName]
            ?: false
        if (!allowed) return CaptureRouteOwner.NONE
        val control = policy.envelope.control
        if (
            !control.masterEnabled || control.bypass ||
            (control.panicUntilEpochMs > 0L && nowEpochMs() < control.panicUntilEpochMs)
        ) {
            return CaptureRouteOwner.NONE
        }
        return when (
            policy.envelope.captureOwners[processName]
                ?: policy.envelope.captureOwners[packageName]
        ) {
            "zygisk" -> if (control.engineMode == "compatibility") {
                CaptureRouteOwner.NONE
            } else {
                CaptureRouteOwner.ZYGISK
            }
            "lsposed" -> if (control.engineMode == "compatibility") {
                CaptureRouteOwner.LSPOSED
            } else {
                CaptureRouteOwner.NONE
            }
            else -> CaptureRouteOwner.NONE
        }
    }

    private fun notifyState(state: ProcessState, actions: MutableList<Action>) {
        actions += Action.Notify(
            state,
            state.processName,
            state.policy?.generation ?: 0L,
            state.transition,
            state.phase,
            state.effective,
        )
    }

    private fun clearTimeout(state: ProcessState, actions: MutableList<Action>) {
        state.timeout?.let { actions += Action.CancelTimeout(it) }
        state.timeout = null
    }

    private fun execute(initial: Collection<Action>) {
        val pending = java.util.ArrayDeque<Action>()
        pending.addAll(initial)
        while (pending.isNotEmpty()) {
            when (val action = pending.removeFirst()) {
                is Action.PublishNative -> {
                    val current = synchronized(action.state) {
                        action.state.native === action.endpoint &&
                            action.state.policy?.generation == action.generation &&
                            action.state.transition == action.token &&
                            action.state.phase == action.phase
                    }
                    if (!current) continue
                    val published = runCatching {
                        action.endpoint.publishPolicy(action.payload, action.token)
                    }.getOrDefault(false)
                    if (!published) {
                        failAction(
                            action.state,
                            action.token,
                            action.phase,
                            pending,
                        )
                    }
                }

                is Action.RevokeLsposed -> {
                    val current = synchronized(action.state) {
                        action.state.lsposed === action.endpoint &&
                            action.state.policy?.generation == action.generation &&
                            action.state.transition == action.token &&
                            action.state.phase == action.phase
                    }
                    if (!current) continue
                    val revoked = runCatching {
                        action.endpoint.revoke(action.generation, action.token)
                    }.getOrDefault(false)
                    if (!revoked && action.phase != CaptureHandoffPhase.FAILED) {
                        failAction(
                            action.state,
                            action.token,
                            action.phase,
                            pending,
                        )
                    }
                }

                is Action.PolicyChanged -> {
                    val current = synchronized(action.state) {
                        action.state.lsposed === action.endpoint &&
                            action.state.policy?.generation == action.generation &&
                            action.state.transition == action.token &&
                            action.state.phase == CaptureHandoffPhase.ACTIVE_LSPOSED
                    }
                    if (current) runCatching { action.endpoint.policyChanged(action.generation) }
                }

                is Action.ScheduleTimeout -> scheduleTimeout(action, pending)
                is Action.CancelTimeout -> runCatching(action.task::cancel)
                is Action.CloseNative -> runCatching(action.endpoint::close)
                is Action.Notify -> {
                    val current = synchronized(action.state) {
                        action.state.policy?.generation == action.generation &&
                            action.state.transition == action.token &&
                            action.state.phase == action.phase &&
                            action.state.effective == action.owner
                    }
                    if (!current) continue
                    observers.toList().forEach { observer ->
                        runCatching {
                            observer(action.processName, action.generation, action.owner)
                        }
                    }
                }
            }
        }
    }

    private fun scheduleTimeout(
        action: Action.ScheduleTimeout,
        pending: java.util.ArrayDeque<Action>,
    ) {
        val current = synchronized(action.state) {
            action.state.transition == action.token && action.state.phase == action.phase
        }
        if (!current) return
        val scheduled = scheduler.schedule(timeoutMs) {
            val followUp = mutableListOf<Action>()
            synchronized(action.state) {
                if (
                    !closed && action.state.transition == action.token &&
                    action.state.phase == action.phase &&
                    action.state.phase !in STABLE_PHASES
                ) {
                    fail(action.state, followUp)
                }
            }
            execute(followUp)
        }
        if (scheduled == null) {
            failAction(action.state, action.token, action.phase, pending)
            return
        }
        var replaced: CaptureHandoffTask? = null
        val retained = synchronized(action.state) {
            if (action.state.transition == action.token && action.state.phase == action.phase) {
                replaced = action.state.timeout
                action.state.timeout = scheduled
                true
            } else {
                false
            }
        }
        runCatching { replaced?.cancel() }
        if (!retained) scheduled.cancel()
    }

    private fun failAction(
        state: ProcessState,
        token: Long,
        phase: CaptureHandoffPhase,
        pending: java.util.ArrayDeque<Action>,
    ) {
        val followUp = mutableListOf<Action>()
        synchronized(state) {
            if (state.transition == token && state.phase == phase) {
                fail(state, followUp)
            }
        }
        pending.addAll(followUp)
    }

    private fun validProcess(processName: String): Boolean =
        processName.isNotEmpty() &&
            processName.length <= 255 &&
            processName.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]*"))

    private companion object {
        val STABLE_PHASES = setOf(
            CaptureHandoffPhase.INACTIVE,
            CaptureHandoffPhase.ACTIVE_ZYGISK,
            CaptureHandoffPhase.ACTIVE_LSPOSED,
            CaptureHandoffPhase.FAILED,
        )
    }
}

private class RealCaptureHandoffScheduler : CaptureHandoffScheduler, Closeable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "echidna-capture-handoff").apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): CaptureHandoffTask? = try {
        val future: ScheduledFuture<*> = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        CaptureHandoffTask { future.cancel(false) }
    } catch (_: RuntimeException) {
        null
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private fun capabilityForPublished(
    published: VersionedPolicyEnvelope,
    packageName: String,
    processName: String,
    nowEpochMs: Long,
): LegacyCapabilityPolicy? {
    if (processName.substringBefore(':') != packageName) return null
    val envelope = published.envelope
    val allowed = envelope.whitelist[processName] ?: envelope.whitelist[packageName] ?: false
    val owner = envelope.captureOwners[processName] ?: envelope.captureOwners[packageName]
    val control = envelope.control
    if (
        !allowed || owner != "lsposed" || control.engineMode != "compatibility" ||
        !control.masterEnabled || control.bypass ||
        (control.panicUntilEpochMs > 0L && control.panicUntilEpochMs > nowEpochMs)
    ) {
        return null
    }
    val profileId = envelope.appBindings[packageName] ?: envelope.defaultProfileId
    val preset = envelope.profiles[profileId]?.toString()?.toByteArray(Charsets.UTF_8)
        ?: return null
    return LegacyCapabilityPolicy(published.generation, processName, preset)
}

/** One process-local coordinator shared by the private publisher and exported Binder service. */
internal object CaptureOwnerHandoffRegistry {
    private var coordinator: CaptureOwnerHandoffCoordinator? = null
    private var observation: Closeable? = null

    @Synchronized
    fun get(): CaptureOwnerHandoffCoordinator {
        coordinator?.let { return it }
        val created = CaptureOwnerHandoffCoordinator()
        observation = PublishedPolicyRegistry.observe {
            PublishedPolicyRegistry.current()?.let(created::publishPolicy)
        }
        // Subscribe before the snapshot read so a publish in the startup gap is
        // either observed directly or read as current (same generation is idempotent).
        PublishedPolicyRegistry.current()?.let(created::publishPolicy)
        coordinator = created
        return created
    }

    @Synchronized
    fun resetForTests(replacement: CaptureOwnerHandoffCoordinator? = null) {
        observation?.close()
        observation = null
        coordinator?.close()
        coordinator = replacement
    }
}
