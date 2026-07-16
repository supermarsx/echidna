package com.echidna.app.ui.alerts

import com.echidna.app.model.CompatibilityResult
import com.echidna.app.model.DspEngineMode
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.ModuleStatus
import com.echidna.app.model.SettingsState
import com.echidna.app.model.TelemetrySnapshot
import com.echidna.app.model.WhitelistBindings
import com.echidna.app.ui.components.AlertSeverity
import kotlin.math.roundToInt

/**
 * Where an actionable advisory should send the user. Each maps to an in-app destination the Alerts
 * screen already knows how to reach; [NONE] is used when honest guidance text is the only correct
 * response (no in-app destination exists), so the alert shows no dead button.
 */
enum class AlertActionTarget {
    /** Guided engine installer (module not installed / missing / update). */
    INSTALLER,

    /** Launch the Magisk manager (enable Zygisk, manage modules). */
    OPEN_MAGISK,

    /** Per-App Whitelist editor (hook scope / no whitelisted apps). */
    WHITELIST,

    /** Compatibility Wizard (re-probe hardware, SELinux, bridge). */
    COMPAT_WIZARD,

    /** No in-app destination — the alert carries its own guidance. */
    NONE,
}

/** A single live, condition-driven advisory surfaced in the Alerts tab. */
data class AdvisoryAlert(
    val title: String,
    val detail: String,
    val category: String,
    val action: AlertActionTarget,
)

/** Stable namespace prefix for dismissed advisory-alert keys. */
const val ADVISORY_KEY_PREFIX = "alerts.advisory:"

/** Condition-stable dismiss key for an advisory alert (category + title). */
fun advisoryAlertKey(alert: AdvisoryAlert): String =
    "$ADVISORY_KEY_PREFIX${alert.category}|${alert.title}"

fun advisorySeverity(category: String): AlertSeverity = when (category) {
    "Incomplete install", "Incomplete bridge", "Bridge risk" -> AlertSeverity.ERROR
    else -> AlertSeverity.WARNING
}

/** Default action-button label for a target, or null for [AlertActionTarget.NONE]. */
fun AlertActionTarget.label(): String? = when (this) {
    AlertActionTarget.INSTALLER -> "Install engine"
    AlertActionTarget.OPEN_MAGISK -> "Open Magisk"
    AlertActionTarget.WHITELIST -> "Open Whitelist"
    AlertActionTarget.COMPAT_WIZARD -> "Run Wizard"
    AlertActionTarget.NONE -> null
}

/**
 * Builds the live advisory-alert list from current control state. Each alert is tagged with the
 * in-app destination that resolves it (or [AlertActionTarget.NONE] when only guidance applies).
 * These conditions are computed fresh on every state change; callers reconcile the dismissed set
 * against the currently-active keys so a temporarily-dismissed advisory returns when its condition
 * recurs (permanent "don't remind" dismissals are honored forever — see [AdvisoryAlert]).
 */
fun buildAdvisoryAlerts(
    settings: SettingsState,
    engineStatus: EngineStatus,
    moduleStatus: ModuleStatus?,
    compatibility: CompatibilityResult?,
    telemetry: TelemetrySnapshot,
    whitelistBindings: WhitelistBindings
): List<AdvisoryAlert> = buildList {
    if (settings.showInstallAlerts) {
        if (moduleStatus == null) {
            add(
                AdvisoryAlert(
                    title = "Control service status unavailable",
                    detail = "The companion has not received module status yet. The UI remains usable, " +
                        "but native install checks may be stale or incomplete.",
                    category = "Incomplete install",
                    action = AlertActionTarget.INSTALLER
                )
            )
        } else if (!moduleStatus.magiskModuleInstalled) {
            add(
                AdvisoryAlert(
                    title = "Magisk module not detected",
                    detail = "Install or re-flash echidna-magisk.zip, reboot, then rerun the " +
                        "compatibility probe before relying on native hooks.",
                    category = "Incomplete install",
                    action = AlertActionTarget.INSTALLER
                )
            )
        }
        if (!engineStatus.nativeInstalled && settings.masterEnabled && !settings.bypass) {
            add(
                AdvisoryAlert(
                    title = "Native engine is not installed",
                    detail = "Master processing is enabled, but the native engine is not reported as " +
                        "installed. Audio should pass through until the module is present.",
                    category = "Incomplete install",
                    action = AlertActionTarget.INSTALLER
                )
            )
        }
    }

    if (settings.showBridgeAlerts) {
        if (settings.masterEnabled && !settings.bypass && whitelistBindings.enabledCount() == 0) {
            add(
                AdvisoryAlert(
                    title = "No target apps are whitelisted",
                    detail = "Echidna fails closed until at least one app is enabled in the " +
                        "Per-App Whitelist. Open the whitelist and enable each app you expect " +
                        "to intercept.",
                    category = "Hook scope",
                    action = AlertActionTarget.WHITELIST
                )
            )
        }
        engineStatus.lastError?.let { error ->
            add(
                AdvisoryAlert(
                    title = "Engine status reports an error",
                    detail = error.compactForAlert(),
                    category = "Incomplete bridge",
                    action = AlertActionTarget.NONE
                )
            )
        }
        moduleStatus?.let { status ->
            if (!status.zygiskEnabled) {
                add(
                    AdvisoryAlert(
                        title = "Zygisk is disabled or not visible",
                        detail = "The native hook path depends on Zygisk. Enable it in Magisk and reboot " +
                            "if you expect native injection.",
                        category = "Incomplete bridge",
                        action = AlertActionTarget.OPEN_MAGISK
                    )
                )
            }
            if (status.selinuxState.containsAdvisoryWord() ||
                status.selinuxStatus.containsAdvisoryWord()
            ) {
                add(
                    AdvisoryAlert(
                        title = "SELinux or policy probe needs attention",
                        detail = "Reported SELinux state ${status.selinuxState}; status " +
                            "${status.selinuxStatus}. Native readers may fail closed if policy " +
                            "blocks the profile or telemetry bridge.",
                        category = "Bridge risk",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            status.notes?.takeIf { it.containsAdvisoryWord() }?.let { note ->
                add(
                    AdvisoryAlert(
                        title = "Module status includes a warning note",
                        detail = note.compactForAlert(),
                        category = "Bridge note",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            status.lastError?.let { error ->
                if (error != engineStatus.lastError) {
                    add(
                        AdvisoryAlert(
                            title = "Control bridge reported an error",
                            detail = error.compactForAlert(),
                            category = "Incomplete bridge",
                            action = AlertActionTarget.NONE
                        )
                    )
                }
            }
        }
        if (settings.remindCompatibilityProbe && compatibility == null) {
            add(
                AdvisoryAlert(
                    title = "Compatibility probe has not run",
                    detail = "Run the wizard after installing or updating modules so hardware, SELinux, " +
                        "and bridge status are based on a fresh probe.",
                    category = "Bridge status",
                    action = AlertActionTarget.COMPAT_WIZARD
                )
            )
        }
        compatibility?.notes
            ?.filter { it.containsAdvisoryWord() }
            ?.take(3)
            ?.forEach { note ->
                add(
                    AdvisoryAlert(
                        title = "Compatibility probe note needs review",
                        detail = note.compactForAlert(),
                        category = "Bridge status",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
    }

    if (settings.showHardwareAlerts) {
        moduleStatus?.cpu?.let { cpu ->
            if (!cpu.moduleSupported) {
                add(
                    AdvisoryAlert(
                        title = "CPU ABI is not packaged by Echidna",
                        detail = cpu.message.ifBlank {
                            "Primary ABI ${cpu.primaryAbi.ifBlank { "unknown" }} is not supported."
                        },
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            } else if (!cpu.nativeHooksSupported) {
                add(
                    AdvisoryAlert(
                        title = "CPU ABI has limited native hook support",
                        detail = cpu.message.ifBlank {
                            "The module may load, but active audio hooks are not enabled for " +
                                cpu.zygiskAbi.ifBlank { "this ABI" } + "."
                        },
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            Unit
        }
        moduleStatus?.audioStack?.let { stack ->
            if (stack.vendorFamily.equals("Unknown", ignoreCase = true) ||
                stack.vendorFamily.contains("unclassified", ignoreCase = true)
            ) {
                add(
                    AdvisoryAlert(
                        title = "Vendor audio family is not classified",
                        detail = "HAL label ${stack.hal.ifBlank { "unknown" }} did not match " +
                            "known emulator, Qualcomm, MediaTek, Exynos, or Tensor patterns.",
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            if (!stack.aaudioSupported) {
                add(
                    AdvisoryAlert(
                        title = "AAudio low-latency path unavailable",
                        detail = "This device did not report native AAudio support. Echidna can still try " +
                            "fallback hooks, but latency and app coverage may vary.",
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            if (!stack.openSlEsAvailable) {
                add(
                    AdvisoryAlert(
                        title = "OpenSL ES library not found",
                        detail = "The compatibility probe could not find libOpenSLES.so in common " +
                            "system or vendor paths. OpenSL hook coverage is unlikely on this image.",
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            if (!stack.audioFlingerClientAvailable) {
                add(
                    AdvisoryAlert(
                        title = "AudioFlinger client library not found",
                        detail = "The compatibility probe could not find libaudioclient.so in common " +
                            "system or vendor paths. AudioFlinger client hook coverage is unlikely.",
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            if (!stack.tinyAlsaAvailable) {
                add(
                    AdvisoryAlert(
                        title = "tinyalsa library not found",
                        detail = "The compatibility probe could not find libtinyalsa.so in common " +
                            "system or vendor paths. tinyalsa/HAL fallback coverage is unlikely.",
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            if (!stack.lowLatency) {
                add(
                    AdvisoryAlert(
                        title = "Low-latency audio feature absent",
                        detail = "Android does not report FEATURE_AUDIO_LOW_LATENCY. Calls and live " +
                            "monitoring may need balanced or compatibility mode.",
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            if (!stack.proAudio) {
                add(
                    AdvisoryAlert(
                        title = "Pro audio feature absent",
                        detail = "Android does not report FEATURE_AUDIO_PRO. Echidna remains usable, " +
                            "but device routing may not be tuned for stable low-latency capture.",
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            if (stack.hal.isBlank() || stack.hal.equals("unknown", ignoreCase = true)) {
                add(
                    AdvisoryAlert(
                        title = "Audio HAL could not be identified",
                        detail = "Vendor audio routing is unknown. Validate the target apps manually " +
                            "before using the native hook path.",
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
            if (stack.sampleRate <= 0 || stack.framesPerBuffer <= 0) {
                add(
                    AdvisoryAlert(
                        title = "Incomplete audio stack probe",
                        detail = "Sample rate or buffer size was not reported. This can indicate an " +
                            "incomplete bridge or a vendor HAL that hides useful diagnostics.",
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
        }
        if (telemetry.averageLatencyMs > settings.alertLatencyThresholdMs) {
            add(
                AdvisoryAlert(
                    title = "High processing latency",
                    detail = "Telemetry average is ${telemetry.averageLatencyMs.roundToInt()} ms, above " +
                        "the configured ${settings.alertLatencyThresholdMs} ms alert threshold.",
                    category = "Runtime performance",
                    action = AlertActionTarget.NONE
                )
            )
        }
        if (telemetry.xruns >= settings.alertXrunThreshold) {
            add(
                AdvisoryAlert(
                    title = "Audio XRuns detected",
                    detail = "Telemetry reports ${telemetry.xruns} XRuns. Reduce DSP load, use bypass, " +
                        "or switch latency mode if audio glitches.",
                    category = "Runtime performance",
                    action = AlertActionTarget.NONE
                )
            )
        }
        compatibility?.audioStack
            ?.filter { !it.supported }
            ?.filterNot { probe ->
                moduleStatus != null &&
                    (probe.name.contains("AAudio", ignoreCase = true) ||
                        probe.name.contains("Low-latency", ignoreCase = true) ||
                        probe.name.contains("Pro audio", ignoreCase = true))
            }
            ?.take(3)
            ?.forEach { probe ->
                add(
                    AdvisoryAlert(
                        title = "${probe.name} probe is unsupported",
                        detail = probe.message.compactForAlert(),
                        category = "Hardware compatibility",
                        action = AlertActionTarget.COMPAT_WIZARD
                    )
                )
            }
        if (telemetry.warnings.isNotEmpty()) {
            add(
                AdvisoryAlert(
                    title = "Runtime telemetry has warnings",
                    detail = telemetry.warnings.joinToString("; ").compactForAlert(),
                    category = "Runtime performance",
                    action = AlertActionTarget.NONE
                )
            )
        }
    }

    if (settings.showInstallMixupAlerts) {
        moduleStatus?.let { status ->
            if (status.magiskModuleInstalled && !status.zygiskEnabled) {
                add(
                    AdvisoryAlert(
                        title = "Magisk module present but Zygisk is not active",
                        detail = "The module package appears installed, but the expected Zygisk " +
                            "bridge is disabled or not visible after boot.",
                        category = "Install mix-up",
                        action = AlertActionTarget.OPEN_MAGISK
                    )
                )
            }
            if (status.zygiskEnabled && status.javaFallbackRecommended) {
                add(
                    AdvisoryAlert(
                        title = "Native capture route remains unverified",
                        detail = "Zygisk availability does not prove audio buffers were transformed. " +
                            "Use LSPosed compatibility mode only for targets assigned to that owner.",
                        category = "Install mix-up",
                        action = AlertActionTarget.NONE
                    )
                )
            }
            if (!status.zygiskEnabled && status.javaFallbackRecommended) {
                add(
                    AdvisoryAlert(
                        title = "LSPosed compatibility mode recommended",
                        detail = "No native route is verified. LSPosed may cover selected AudioRecord " +
                            "targets after its scope and capture owner are configured.",
                        category = "Install mix-up",
                        action = AlertActionTarget.NONE
                    )
                )
            }
            if (!status.magiskModuleInstalled && status.javaFallbackRecommended) {
                add(
                    AdvisoryAlert(
                        title = "Native module missing; fallback is only a recommendation",
                        detail = "The app has not verified an active LSPosed route. Install and scope " +
                            "the shim before relying on Java AudioRecord coverage.",
                        category = "Install mix-up",
                        action = AlertActionTarget.INSTALLER
                    )
                )
            }
        }
        if (settings.engineMode == DspEngineMode.COMPATIBILITY && engineStatus.nativeInstalled) {
            add(
                AdvisoryAlert(
                    title = "Compatibility mode selected with native module present",
                    detail = "This is allowed, but native hooks may be intentionally de-emphasized. " +
                        "Switch modes if you expected the native-first path.",
                    category = "Install mix-up",
                    action = AlertActionTarget.NONE
                )
            )
        }
    }
}

internal fun String.containsAdvisoryWord(): Boolean {
    val lower = lowercase()
    return listOf(
        "absent",
        "denied",
        "disabled",
        "error",
        "fail",
        "missing",
        "not installed",
        "partial",
        "unavailable",
        "unbound",
        "unknown",
        "unsupported",
        "warning"
    ).any(lower::contains)
}

internal fun WhitelistBindings.enabledCount(): Int = whitelist.count { it.value }

internal fun String.compactForAlert(maxLength: Int = 220): String =
    if (length <= maxLength) this else take(maxLength - 3).trimEnd() + "..."
