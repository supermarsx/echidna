package com.echidna.app.ui.install

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.ModuleStatus
import com.echidna.app.system.ReleaseArtifactException
import com.echidna.app.system.ReleaseArtifactSource
import com.echidna.app.system.ReleaseAsset
import com.echidna.app.system.ResolvedRelease
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

    /** Master-off + bypass so the live engine stops mutating audio before a module op. */
    fun quiesceEngine()

    /**
     * Writes the Magisk disable marker so Zygisk stops loading the module on the next boot.
     * Returns true only when confirmed — a false result aborts the flow honestly.
     */
    suspend fun disableModule(): Boolean

    /** Best-effort privileged reboot to complete the load/unload. Returns whether it dispatched. */
    suspend fun rebootDevice(): Boolean

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
    override fun quiesceEngine() = repo.quiesceEngineForModuleOp()
    override suspend fun disableModule(): Boolean = repo.disableEngineModule()
    override suspend fun rebootDevice(): Boolean = repo.rebootDevice()
    override fun install(archivePath: String) = repo.installEngineModule(archivePath)
    override fun uninstall() = repo.uninstallEngineModule()
}

private class RepositoryEngineArchiveSource : EngineArchiveSource {
    private val repo = ControlStateRepository
    override fun bundledArchivePath(): String? = repo.bundledEngineArchivePath()
    override fun stageArchive(uri: Uri): String? = repo.stageEngineArchive(uri)
}

/**
 * Production wiring for the optional download path. The repository owns the app context, so this
 * fails with an honest message rather than a crash when it is asked for before initialization.
 */
private class RepositoryReleaseArtifactSource : ReleaseArtifactSource {
    private fun delegate(): ReleaseArtifactSource = ControlStateRepository.releaseArtifactSource()
        ?: throw ReleaseArtifactException("The app is still starting up. Try again in a moment.")

    override suspend fun resolveLatest(): ResolvedRelease = delegate().resolveLatest()

    override suspend fun stageForInstall(release: ResolvedRelease, asset: ReleaseAsset): String =
        delegate().stageForInstall(release, asset)
}

/** The phases of the guided install/uninstall flow. */
enum class InstallPhase {
    /** First privileged probe is in flight; the screen shows a spinner. */
    DETECTING,

    /** Ready — show install or uninstall actions based on the detected state. */
    IDLE,

    /**
     * Unload-first step: master-off + bypass, then the Magisk disable marker is written so Zygisk
     * stops loading the module. A live Zygisk module can't be hot-unloaded, so this precedes every
     * module op (existing-module update, or uninstall).
     */
    QUIESCING,

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
 * Phases of the OPTIONAL download-from-GitHub path. It is deliberately a separate axis from
 * [InstallPhase]: the offline install (bundled asset or picked .zip) must keep working exactly as
 * before with no network at all, so nothing in the install state machine depends on these values.
 */
enum class ReleasePhase {
    /** Nothing requested. The download card only offers "Check for the latest release". */
    IDLE,

    /** Asking the GitHub API which release is latest. */
    RESOLVING,

    /** A tag and asset name are known and shown; the user has not yet asked to download. */
    RESOLVED,

    /** Downloading and verifying (checksum + origin). */
    DOWNLOADING,

    /** Verified and staged on disk. Installing it is still a separate, explicit action. */
    STAGED,

    /** A lookup, download, or verification check failed; [ReleaseUiState.message] says which. */
    FAILED,
}

/**
 * Snapshot of the download path. [tag] and [assetName] come from the resolved API response and are
 * shown to the user BEFORE any download is offered — requirement of the flow is that nothing is
 * fetched or installed without the user seeing what it is first.
 */
data class ReleaseUiState(
    val phase: ReleasePhase = ReleasePhase.IDLE,
    val tag: String? = null,
    val assetName: String? = null,
    val assetSizeBytes: Long = 0L,
    val message: String? = null,
) {
    val busy: Boolean
        get() = phase == ReleasePhase.RESOLVING || phase == ReleasePhase.DOWNLOADING

    /** Download is offered only once the user has seen the resolved tag and asset name. */
    val canDownload: Boolean
        get() = phase == ReleasePhase.RESOLVED && assetName != null

    /** Install of a downloaded artifact is offered only after every check passed. */
    val canInstallStaged: Boolean
        get() = phase == ReleasePhase.STAGED
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
    val release: ReleaseUiState = ReleaseUiState(),
) {
    val busy: Boolean
        get() = phase == InstallPhase.DETECTING ||
            phase == InstallPhase.QUIESCING ||
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
    private val releases: ReleaseArtifactSource = RepositoryReleaseArtifactSource(),
    // Injectable so the state machine can be exercised on a test dispatcher; production uses the
    // ViewModel's own scope.
    overrideScope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = overrideScope ?: viewModelScope

    /** The release currently shown in the download card, and the artifact verified out of it. */
    private var resolvedRelease: ResolvedRelease? = null
    private var resolvedAsset: ReleaseAsset? = null
    private var stagedDownloadPath: String? = null

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

    /** Remove the installed module — unload-first: quiesce + disable, then remove, then reboot. */
    fun uninstall() {
        if (_state.value.busy) return
        workScope.launch {
            if (!controller.isServiceConnected()) {
                fail(DISCONNECTED_MESSAGE)
                return@launch
            }
            // 1. Quiesce the live engine so it stops mutating audio immediately (master-off + bypass).
            _state.update {
                it.copy(phase = InstallPhase.QUIESCING, message = "Disabling the engine (master off)…")
            }
            controller.quiesceEngine()
            // 2. Disable the module so Zygisk stops loading it on the next boot. Fail-safe: abort if
            //    this fails so we never leave the module active-but-being-removed silently.
            if (!controller.disableModule()) {
                fail(DISABLE_FAILED_MESSAGE)
                return@launch
            }
            // 3. Remove the module (reboot-completed by Magisk).
            _state.update {
                it.copy(phase = InstallPhase.UNINSTALLING, message = "Removing module via Magisk…")
            }
            controller.uninstall()
            val removed = pollForInstalled(desired = false)
            val lastError = controller.moduleStatus.value?.lastError
            when {
                removed -> rebootRequired(
                    InstallPhase.UNINSTALL_REBOOT,
                    "Engine disabled and module removed. Reboot to finish unloading it — a live " +
                        "Zygisk module stays in running processes until the device restarts.",
                )
                lastError?.contains("uninstall failed", ignoreCase = true) == true -> fail(lastError)
                else -> rebootRequired(
                    InstallPhase.UNINSTALL_REBOOT,
                    "Engine disabled; removal scheduled. Reboot to complete unloading and removal — " +
                        "a live Zygisk module can't be hot-unloaded.",
                )
            }
        }
    }

    /**
     * Asks GitHub which release is latest right now. Explicitly user-initiated: nothing polls, and
     * nothing is downloaded by this call — it only resolves the tag and the module asset name so
     * the user can see exactly what a download would fetch before deciding.
     */
    fun checkForLatestRelease() {
        if (_state.value.release.busy) return
        stagedDownloadPath = null
        resolvedRelease = null
        resolvedAsset = null
        updateRelease {
            ReleaseUiState(
                phase = ReleasePhase.RESOLVING,
                message = "Asking GitHub for the latest release…",
            )
        }
        workScope.launch {
            try {
                val release = releases.resolveLatest()
                val asset = release.moduleAsset ?: throw ReleaseArtifactException(
                    "Release ${release.tag} publishes no Echidna module .zip, so there is nothing " +
                        "to install from it."
                )
                resolvedRelease = release
                resolvedAsset = asset
                updateRelease {
                    ReleaseUiState(
                        phase = ReleasePhase.RESOLVED,
                        tag = release.tag,
                        assetName = asset.name,
                        assetSizeBytes = asset.sizeBytes,
                        message = "Latest release is ${release.tag}. Download ${asset.name} to " +
                            "verify it against this release's SHA256SUMS.txt and this app's own " +
                            "signing certificate.",
                    )
                }
            } catch (error: Throwable) {
                failRelease(error)
            }
        }
    }

    /**
     * Downloads the resolved module archive and verifies it. Only reachable after
     * [checkForLatestRelease] has shown the tag and asset name. Every check is fail-closed: a
     * checksum or origin mismatch leaves the release card in FAILED with the specific reason and
     * stages nothing — it never quietly falls back to the bundled asset.
     */
    fun downloadResolvedModule() {
        val release = resolvedRelease
        val asset = resolvedAsset
        if (!_state.value.release.canDownload || release == null || asset == null) return
        stagedDownloadPath = null
        updateRelease {
            it.copy(
                phase = ReleasePhase.DOWNLOADING,
                message = "Downloading and verifying ${asset.name}…",
            )
        }
        workScope.launch {
            try {
                val path = releases.stageForInstall(release, asset)
                stagedDownloadPath = path
                updateRelease {
                    it.copy(
                        phase = ReleasePhase.STAGED,
                        message = "${asset.name} verified: its SHA-256 matches this release's " +
                            "SHA256SUMS.txt, and its embedded release certificate pin matches the " +
                            "certificate that signed this app. Nothing has been installed yet.",
                    )
                }
            } catch (error: Throwable) {
                failRelease(error)
            }
        }
    }

    /**
     * Installs the verified download through the same guided flow as every other source. Separate
     * from [downloadResolvedModule] on purpose: verifying an artifact never implies consent to
     * flash it.
     */
    fun installDownloadedModule() {
        val path = stagedDownloadPath
        if (_state.value.busy || path == null || !_state.value.release.canInstallStaged) return
        workScope.launch {
            if (!controller.isServiceConnected()) {
                fail(DISCONNECTED_MESSAGE)
                return@launch
            }
            runInstall(path)
        }
    }

    /** Best-effort privileged reboot, offered from the reboot-required screen. */
    fun reboot() {
        workScope.launch {
            val dispatched = controller.rebootDevice()
            if (!dispatched) {
                _state.update { it.copy(message = REBOOT_MANUAL_MESSAGE) }
            }
        }
    }

    private suspend fun runInstall(path: String) {
        // Unload-first: if a module is already present, quiesce + disable it before overwriting so
        // Zygisk stops loading the old copy — a live Zygisk module can't be hot-swapped. Staging has
        // already succeeded here, so we only disable a working module when a replacement is in hand.
        if (_state.value.moduleInstalled) {
            _state.update {
                it.copy(
                    phase = InstallPhase.QUIESCING,
                    message = "Disabling the current engine before updating…",
                )
            }
            controller.quiesceEngine()
            if (!controller.disableModule()) {
                fail(DISABLE_FAILED_MESSAGE)
                return
            }
        }
        _state.update { it.copy(phase = InstallPhase.INSTALLING, message = "Installing module via Magisk…") }
        controller.install(path)
        val installed = pollForInstalled(desired = true)
        if (installed) {
            rebootRequired(
                InstallPhase.INSTALL_REBOOT,
                "Module installed. A reboot is required to load the engine because a live Zygisk " +
                    "module can't be hot-swapped. Reboot, then reopen Echidna.",
            )
        } else {
            val lastError = controller.moduleStatus.value?.lastError
            fail(lastError ?: "The module did not register after installation. Check Magisk and try again.")
        }
    }

    private fun rebootRequired(phase: InstallPhase, message: String) {
        _state.update { it.copy(phase = phase, message = message) }
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

    private fun updateRelease(transform: (ReleaseUiState) -> ReleaseUiState) {
        _state.update { it.copy(release = transform(it.release)) }
    }

    /**
     * Surfaces a download/verification failure verbatim. [ReleaseArtifactException] messages already
     * name which check failed; anything else is reported as the transport failure it is rather than
     * being dressed up as a passing verification.
     */
    private fun failRelease(error: Throwable) {
        val message = when {
            error is ReleaseArtifactException -> error.message
            else -> "Couldn't reach the GitHub release API (${error.javaClass.simpleName}). " +
                "Nothing was downloaded. The bundled package and the .zip picker still work offline."
        }
        stagedDownloadPath = null
        updateRelease {
            it.copy(
                phase = ReleasePhase.FAILED,
                message = message ?: RELEASE_UNKNOWN_FAILURE,
            )
        }
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
        const val DISABLE_FAILED_MESSAGE =
            "Couldn't disable the engine module — the privileged disable step failed. Nothing else " +
                "was changed: the module was left as-is rather than half-removed. Check Magisk root " +
                "access and try again."
        const val RELEASE_UNKNOWN_FAILURE =
            "The download was refused by a verification check that reported no detail. Nothing " +
                "was installed."
        const val REBOOT_MANUAL_MESSAGE =
            "Couldn't trigger a reboot automatically. Reboot the device manually to finish " +
                "loading/unloading the engine."
    }
}
