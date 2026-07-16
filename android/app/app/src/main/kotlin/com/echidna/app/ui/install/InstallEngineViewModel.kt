package com.echidna.app.ui.install

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.ModuleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backend the installer drives. Kept behind an interface so the state machine can be unit-tested
 * with a fake, and the production implementation simply delegates to [ControlStateRepository].
 */
interface EngineInstallController {
    val moduleStatus: StateFlow<ModuleStatus?>
    val engineStatus: StateFlow<EngineStatus>
    fun isServiceConnected(): Boolean
    suspend fun refreshStatus(): ModuleStatus?
    fun install(archivePath: String)
    fun uninstall()
}

/** Source of the flashable module archive: a bundled asset or a user-picked `.zip`. */
interface EngineArchiveSource {
    /** Path to the bundled archive, or null when this build ships no bundled package. */
    fun bundledArchivePath(): String?

    /** Stages a user-picked archive and returns its path, or null on failure. */
    fun stageArchive(uri: Uri): String?
}

private class RepositoryEngineInstallController : EngineInstallController {
    private val repo = ControlStateRepository
    override val moduleStatus = repo.moduleStatus
    override val engineStatus = repo.engineStatus
    override fun isServiceConnected(): Boolean = repo.isServiceBound()
    override suspend fun refreshStatus(): ModuleStatus? = repo.refreshModuleStatus()
    override fun install(archivePath: String) = repo.installEngineModule(archivePath)
    override fun uninstall() = repo.uninstallEngineModule()
}

private class RepositoryEngineArchiveSource : EngineArchiveSource {
    private val repo = ControlStateRepository
    override fun bundledArchivePath(): String? = repo.bundledEngineArchivePath()
    override fun stageArchive(uri: Uri): String? = repo.stageEngineArchive(uri)
}

/** The phases of the guided install/uninstall flow. */
enum class InstallPhase {
    /** First privileged probe is in flight; the screen shows a spinner. */
    DETECTING,

    /** Ready — show install or uninstall actions based on the detected state. */
    IDLE,

    /** Staging the module archive (extract asset / copy picked file). */
    PREPARING,

    /** Install dispatched; polling for the module to register. */
    INSTALLING,

    /** Uninstall dispatched; polling for the module to disappear. */
    UNINSTALLING,

    /** Install confirmed (module now present) — reboot required to activate. */
    INSTALL_REBOOT,

    /** Uninstall confirmed / scheduled — reboot required to finish removal. */
    UNINSTALL_REBOOT,

    /** A step failed; [InstallUiState.message] carries the honest reason. */
    FAILED,
}

/**
 * Immutable snapshot rendered by the install screen. Detection fields are always populated from the
 * real [ModuleStatus]; nothing here fabricates an "installed" or "active" state.
 */
data class InstallUiState(
    val phase: InstallPhase = InstallPhase.DETECTING,
    val serviceConnected: Boolean = false,
    val moduleInstalled: Boolean = false,
    val zygiskEnabled: Boolean = false,
    val magiskDetected: Boolean = false,
    val engineSummary: String = "Unknown",
    val selinuxMode: String = "Unknown",
    val message: String? = null,
) {
    val busy: Boolean
        get() = phase == InstallPhase.DETECTING ||
            phase == InstallPhase.PREPARING ||
            phase == InstallPhase.INSTALLING ||
            phase == InstallPhase.UNINSTALLING

    /** Install is offered only when a rooted Magisk/Zygisk device is detected and idle. */
    val canInstall: Boolean
        get() = !busy && serviceConnected && magiskDetected && !moduleInstalled

    /** Uninstall is offered only when the module is actually present. */
    val canUninstall: Boolean
        get() = !busy && serviceConnected && moduleInstalled
}

/**
 * Drives the honest install/uninstall state machine. Root/Magisk availability is derived from the
 * existing privileged [ModuleStatus] probe (module-id "echidna", Zygisk via the Magisk settings DB)
 * — the installer never claims success it has not confirmed through the status poll.
 */
class InstallEngineViewModel(
    private val controller: EngineInstallController = RepositoryEngineInstallController(),
    private val archive: EngineArchiveSource = RepositoryEngineArchiveSource(),
    // Injectable so the state machine can be exercised on a test dispatcher; production uses the
    // ViewModel's own scope.
    overrideScope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = overrideScope ?: viewModelScope

    private val _state = MutableStateFlow(InstallUiState())
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    init {
        // Keep the detection display fresh from the repository's ongoing status poll without ever
        // overriding an in-flight action or a reboot-confirmation screen's phase.
        workScope.launch {
            combine(controller.moduleStatus, controller.engineStatus) { module, engine ->
                module to engine
            }.collect { (module, engine) -> applyDetection(module, engine) }
        }
        refresh()
    }

    /** Forces a fresh privileged probe and settles into the IDLE (or FAILED) phase. */
    fun refresh() {
        workScope.launch {
            _state.update {
                if (it.phase == InstallPhase.IDLE || it.phase == InstallPhase.DETECTING) {
                    it.copy(phase = InstallPhase.DETECTING, message = null)
                } else {
                    it
                }
            }
            if (!controller.isServiceConnected()) {
                _state.update {
                    it.copy(
                        phase = InstallPhase.IDLE,
                        serviceConnected = false,
                        message = DISCONNECTED_MESSAGE,
                    )
                }
                return@launch
            }
            val status = controller.refreshStatus()
            applyDetection(status, controller.engineStatus.value, forceIdle = true)
        }
    }

    /** Install from the bundled archive, or report honestly that none is bundled. */
    fun install() {
        if (_state.value.busy) return
        workScope.launch {
            if (!controller.isServiceConnected()) {
                fail(DISCONNECTED_MESSAGE)
                return@launch
            }
            _state.update { it.copy(phase = InstallPhase.PREPARING, message = "Preparing module package…") }
            val path = withContext(Dispatchers.IO) { archive.bundledArchivePath() }
            if (path == null) {
                fail(NO_BUNDLED_ARCHIVE_MESSAGE)
                return@launch
            }
            runInstall(path)
        }
    }

    /** Install from a user-selected `.zip` (system document picker). */
    fun installFromUri(uri: Uri) {
        if (_state.value.busy) return
        workScope.launch {
            if (!controller.isServiceConnected()) {
                fail(DISCONNECTED_MESSAGE)
                return@launch
            }
            _state.update { it.copy(phase = InstallPhase.PREPARING, message = "Reading selected package…") }
            val path = withContext(Dispatchers.IO) { archive.stageArchive(uri) }
            if (path == null) {
                fail("Couldn't read the selected file. Choose the Echidna Magisk module .zip.")
                return@launch
            }
            runInstall(path)
        }
    }

    /** Remove the installed module. */
    fun uninstall() {
        if (_state.value.busy) return
        workScope.launch {
            if (!controller.isServiceConnected()) {
                fail(DISCONNECTED_MESSAGE)
                return@launch
            }
            _state.update { it.copy(phase = InstallPhase.UNINSTALLING, message = "Removing module via Magisk…") }
            controller.uninstall()
            val removed = pollForInstalled(desired = false)
            val lastError = controller.moduleStatus.value?.lastError
            when {
                removed -> _state.update {
                    it.copy(
                        phase = InstallPhase.UNINSTALL_REBOOT,
                        message = "Module removed. Reboot to finish removing the engine.",
                    )
                }
                lastError?.contains("uninstall failed", ignoreCase = true) == true -> fail(lastError)
                else -> _state.update {
                    it.copy(
                        phase = InstallPhase.UNINSTALL_REBOOT,
                        message = "Uninstall scheduled. Reboot to complete removal of the engine.",
                    )
                }
            }
        }
    }

    private suspend fun runInstall(path: String) {
        _state.update { it.copy(phase = InstallPhase.INSTALLING, message = "Installing module via Magisk…") }
        controller.install(path)
        val installed = pollForInstalled(desired = true)
        if (installed) {
            _state.update {
                it.copy(
                    phase = InstallPhase.INSTALL_REBOOT,
                    message = "Module installed. Reboot to activate the engine, then reopen Echidna.",
                )
            }
        } else {
            val lastError = controller.moduleStatus.value?.lastError
            fail(lastError ?: "The module did not register after installation. Check Magisk and try again.")
        }
    }

    /** Polls the privileged status until the module reaches [desired] presence, or times out. */
    private suspend fun pollForInstalled(desired: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ACTION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val status = controller.refreshStatus()
            if (status != null) {
                applyDetection(status, controller.engineStatus.value)
                if (status.magiskModuleInstalled == desired) return true
            }
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun fail(message: String) {
        _state.update { it.copy(phase = InstallPhase.FAILED, message = message) }
    }

    /**
     * Folds a fresh [ModuleStatus] into the detection display. Only changes [InstallPhase] when
     * either leaving the initial DETECTING phase or [forceIdle] is requested by [refresh]; an
     * in-flight action or reboot-confirmation phase is left untouched by the passive status poll.
     */
    private fun applyDetection(
        status: ModuleStatus?,
        engine: EngineStatus,
        forceIdle: Boolean = false,
    ) {
        _state.update { current ->
            val connected = controller.isServiceConnected()
            val moduleInstalled = status?.magiskModuleInstalled ?: current.moduleInstalled
            val zygisk = status?.zygiskEnabled ?: current.zygiskEnabled
            val detected = moduleInstalled || zygisk
            val settleToIdle = forceIdle || current.phase == InstallPhase.DETECTING
            val nextPhase = if (settleToIdle) InstallPhase.IDLE else current.phase
            current.copy(
                phase = nextPhase,
                serviceConnected = connected,
                moduleInstalled = moduleInstalled,
                zygiskEnabled = zygisk,
                magiskDetected = detected,
                engineSummary = engine.summary,
                selinuxMode = status?.selinuxStatus ?: current.selinuxMode,
                message = if (nextPhase == InstallPhase.IDLE) {
                    idleMessage(connected, detected, moduleInstalled, zygisk)
                } else {
                    current.message
                },
            )
        }
    }

    private fun idleMessage(
        connected: Boolean,
        detected: Boolean,
        moduleInstalled: Boolean,
        zygisk: Boolean,
    ): String = when {
        !connected -> DISCONNECTED_MESSAGE
        moduleInstalled && zygisk ->
            "Echidna module installed and Zygisk is enabled."
        moduleInstalled ->
            "Echidna module installed, but Zygisk is disabled — enable Zygisk in Magisk to load it."
        detected ->
            "Magisk with Zygisk detected. You can install the engine module."
        else ->
            "Magisk/Zygisk not detected. Installing the engine requires a rooted device with Magisk " +
                "and Zygisk enabled; nothing will be installed on this device."
    }

    private companion object {
        const val ACTION_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val DISCONNECTED_MESSAGE = "The Echidna control service is not connected yet."
        const val NO_BUNDLED_ARCHIVE_MESSAGE =
            "No engine package is bundled in this build. Select the Echidna Magisk module .zip to continue."
    }
}
