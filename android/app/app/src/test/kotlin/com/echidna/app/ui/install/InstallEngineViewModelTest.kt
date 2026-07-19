package com.echidna.app.ui.install

import android.net.Uri
import com.echidna.app.model.AudioStackInfo
import com.echidna.app.model.CpuArchInfo
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.ModuleStatus
import com.echidna.app.system.ReleaseArtifactException
import com.echidna.app.system.ReleaseArtifactSource
import com.echidna.app.system.ReleaseAsset
import com.echidna.app.system.ResolvedRelease
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class InstallEngineViewModelTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `not detected device offers no install and states so honestly`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = false) }
        val vm = viewModel(controller)

        assertTrue(await { vm.state.value.phase == InstallPhase.IDLE })
        val state = vm.state.value
        assertFalse(state.magiskDetected)
        assertFalse(state.canInstall)
        assertFalse(state.canUninstall)
        assertTrue(state.message!!.contains("not detected", ignoreCase = true))
    }

    @Test
    fun `detected magisk zygisk offers install`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = true) }
        val vm = viewModel(controller)

        assertTrue(await { vm.state.value.phase == InstallPhase.IDLE })
        assertTrue(vm.state.value.magiskDetected)
        assertTrue(vm.state.value.canInstall)
        assertFalse(vm.state.value.canUninstall)
    }

    @Test
    fun `install stages the bundled archive and reaches the reboot prompt once confirmed`() {
        val controller = FakeController().apply {
            refreshResult = status(installed = false, zygisk = true)
        }
        val archive = FakeArchive(bundled = "/cache/echidna-magisk.zip")
        val vm = viewModel(controller, archive)
        assertTrue(await { vm.state.value.canInstall })

        // Once the install command runs, the module registers (poll sees it installed).
        controller.onInstall = { controller.refreshResult = status(installed = true, zygisk = true) }
        vm.install()

        assertTrue(await { vm.state.value.phase == InstallPhase.INSTALL_REBOOT })
        assertEquals(1, controller.installCalls.get())
        assertEquals("/cache/echidna-magisk.zip", controller.lastInstallPath)
        assertTrue(vm.state.value.moduleInstalled)
        assertTrue(vm.state.value.message!!.contains("Reboot", ignoreCase = true))
    }

    @Test
    fun `install without a bundled archive fails honestly and does not call the service`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = true) }
        val archive = FakeArchive(bundled = null)
        val vm = viewModel(controller, archive)
        assertTrue(await { vm.state.value.canInstall })

        vm.install()

        assertTrue(await { vm.state.value.phase == InstallPhase.FAILED })
        assertEquals(0, controller.installCalls.get())
        assertTrue(vm.state.value.message!!.contains("bundled", ignoreCase = true))
    }

    @Test
    fun `install from a picked uri stages and installs`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = true) }
        val archive = FakeArchive(staged = "/cache/picked.zip")
        val vm = viewModel(controller, archive)
        assertTrue(await { vm.state.value.canInstall })

        controller.onInstall = { controller.refreshResult = status(installed = true, zygisk = true) }
        vm.installFromUri(Uri.parse("content://docs/echidna.zip"))

        assertTrue(await { vm.state.value.phase == InstallPhase.INSTALL_REBOOT })
        assertEquals("/cache/picked.zip", controller.lastInstallPath)
    }

    @Test
    fun `uninstall removes the module and reaches the reboot prompt`() {
        val controller = FakeController().apply { refreshResult = status(installed = true, zygisk = true) }
        val vm = viewModel(controller)
        assertTrue(await { vm.state.value.canUninstall })

        controller.onUninstall = { controller.refreshResult = status(installed = false, zygisk = true) }
        vm.uninstall()

        assertTrue(await { vm.state.value.phase == InstallPhase.UNINSTALL_REBOOT })
        assertEquals(1, controller.uninstallCalls.get())
        assertFalse(vm.state.value.moduleInstalled)
    }

    @Test
    fun `uninstall quiesces and disables the engine BEFORE removing it`() {
        val controller = FakeController().apply { refreshResult = status(installed = true, zygisk = true) }
        val vm = viewModel(controller)
        assertTrue(await { vm.state.value.canUninstall })

        controller.onUninstall = { controller.refreshResult = status(installed = false, zygisk = true) }
        vm.uninstall()

        assertTrue(await { vm.state.value.phase == InstallPhase.UNINSTALL_REBOOT })
        // Unload-first: master-off (quiesce) and the disable marker precede the remove command.
        assertEquals(listOf("quiesce", "disable", "remove"), controller.events.toList())
        assertEquals(1, controller.quiesceCalls.get())
        assertEquals(1, controller.disableCalls.get())
    }

    @Test
    fun `uninstall aborts honestly when the disable step fails and never removes the module`() {
        val controller = FakeController().apply {
            refreshResult = status(installed = true, zygisk = true)
            disableResult = false
        }
        val vm = viewModel(controller)
        assertTrue(await { vm.state.value.canUninstall })

        vm.uninstall()

        assertTrue(await { vm.state.value.phase == InstallPhase.FAILED })
        // Quiesce + disable were attempted, but the remove command must NOT run on a failed disable.
        assertEquals(0, controller.uninstallCalls.get())
        assertEquals(listOf("quiesce", "disable"), controller.events.toList())
        assertTrue(vm.state.value.message!!.contains("disable", ignoreCase = true))
    }

    @Test
    fun `updating an already-installed module unloads it first, then installs, then requires reboot`() {
        val controller = FakeController().apply { refreshResult = status(installed = true, zygisk = true) }
        val archive = FakeArchive(bundled = "/cache/echidna-magisk.zip")
        val vm = viewModel(controller, archive)
        // Module already installed: canUninstall true, canInstall false — drive install() directly.
        assertTrue(await { vm.state.value.moduleInstalled })

        vm.install()

        assertTrue(await { vm.state.value.phase == InstallPhase.INSTALL_REBOOT })
        // Unload-first: quiesce + disable the existing module BEFORE writing the replacement.
        assertEquals(listOf("quiesce", "disable", "install"), controller.events.toList())
        assertTrue(vm.state.value.message!!.contains("Reboot", ignoreCase = true))
    }

    @Test
    fun `fresh install does not quiesce or disable when no module is present`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = true) }
        val archive = FakeArchive(bundled = "/cache/echidna-magisk.zip")
        val vm = viewModel(controller, archive)
        assertTrue(await { vm.state.value.canInstall })

        controller.onInstall = { controller.refreshResult = status(installed = true, zygisk = true) }
        vm.install()

        assertTrue(await { vm.state.value.phase == InstallPhase.INSTALL_REBOOT })
        assertEquals(listOf("install"), controller.events.toList())
        assertEquals(0, controller.quiesceCalls.get())
        assertEquals(0, controller.disableCalls.get())
    }

    @Test
    fun `reboot dispatches the privileged reboot`() {
        val controller = FakeController().apply { refreshResult = status(installed = true, zygisk = true) }
        val vm = viewModel(controller)
        assertTrue(await { vm.state.value.phase == InstallPhase.IDLE })

        vm.reboot()

        assertTrue(await { controller.rebootCalls.get() == 1 })
    }

    @Test
    fun `reboot failure surfaces a manual-reboot message`() {
        val controller = FakeController().apply {
            refreshResult = status(installed = true, zygisk = true)
            rebootResult = false
        }
        val vm = viewModel(controller)
        assertTrue(await { vm.state.value.phase == InstallPhase.IDLE })

        vm.reboot()

        assertTrue(await { vm.state.value.message?.contains("manually", ignoreCase = true) == true })
    }

    @Test
    fun `install is refused when the control service is disconnected`() {
        val controller = FakeController().apply {
            connected = false
            refreshResult = status(installed = false, zygisk = true)
        }
        val vm = viewModel(controller)
        assertTrue(await { vm.state.value.phase == InstallPhase.IDLE })
        assertFalse(vm.state.value.serviceConnected)

        vm.install()

        assertTrue(await { vm.state.value.phase == InstallPhase.FAILED })
        assertEquals(0, controller.installCalls.get())
    }

    @Test
    fun `checking for a release resolves a tag and asset name but downloads nothing`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = true) }
        val releases = FakeReleases()
        val vm = viewModel(controller, releases = releases)
        assertTrue(await { vm.state.value.canInstall })

        vm.checkForLatestRelease()

        assertTrue(await { vm.state.value.release.phase == ReleasePhase.RESOLVED })
        assertEquals("v1.4.0", vm.state.value.release.tag)
        assertEquals("echidna-magisk-v1.4.0.zip", vm.state.value.release.assetName)
        // Nothing was fetched, and nothing was handed to the privileged installer.
        assertEquals(0, releases.stageCalls.get())
        assertEquals(0, controller.installCalls.get())
    }

    @Test
    fun `a download is refused until the resolved release has been shown`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = true) }
        val releases = FakeReleases()
        val vm = viewModel(controller, releases = releases)
        assertTrue(await { vm.state.value.canInstall })

        vm.downloadResolvedModule()

        assertEquals(0, releases.stageCalls.get())
        assertEquals(ReleasePhase.IDLE, vm.state.value.release.phase)
    }

    @Test
    fun `a verified download is staged but never installed without a separate action`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = true) }
        val releases = FakeReleases(stagedPath = "/cache/release-downloads/echidna-magisk-v1.4.0.zip")
        val vm = viewModel(controller, releases = releases)
        assertTrue(await { vm.state.value.canInstall })

        vm.checkForLatestRelease()
        assertTrue(await { vm.state.value.release.canDownload })
        vm.downloadResolvedModule()

        assertTrue(await { vm.state.value.release.phase == ReleasePhase.STAGED })
        assertEquals(0, controller.installCalls.get())

        controller.onInstall = { controller.refreshResult = status(installed = true, zygisk = true) }
        vm.installDownloadedModule()

        assertTrue(await { vm.state.value.phase == InstallPhase.INSTALL_REBOOT })
        assertEquals("/cache/release-downloads/echidna-magisk-v1.4.0.zip", controller.lastInstallPath)
    }

    @Test
    fun `a failed verification surfaces the reason and never falls back to the bundled archive`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = true) }
        val releases = FakeReleases(
            stageFailure = ReleaseArtifactException(
                "Origin check failed: echidna-magisk-v1.4.0.zip is pinned to a different release certificate."
            )
        )
        val vm = viewModel(controller, releases = releases)
        assertTrue(await { vm.state.value.canInstall })

        vm.checkForLatestRelease()
        assertTrue(await { vm.state.value.release.canDownload })
        vm.downloadResolvedModule()

        assertTrue(await { vm.state.value.release.phase == ReleasePhase.FAILED })
        assertTrue(vm.state.value.release.message!!.contains("Origin check failed"))
        // Fail closed: nothing installable is offered, and the bundled asset was NOT substituted.
        assertFalse(vm.state.value.release.canInstallStaged)
        vm.installDownloadedModule()
        assertEquals(0, controller.installCalls.get())
    }

    @Test
    fun `a release lookup failure leaves the offline install path untouched`() {
        val controller = FakeController().apply { refreshResult = status(installed = false, zygisk = true) }
        val releases = FakeReleases(resolveFailure = java.io.IOException("no route to host"))
        val archive = FakeArchive(bundled = "/cache/echidna-magisk.zip")
        val vm = viewModel(controller, archive, releases)
        assertTrue(await { vm.state.value.canInstall })

        vm.checkForLatestRelease()
        assertTrue(await { vm.state.value.release.phase == ReleasePhase.FAILED })

        // The bundled/offline install is completely unaffected by the network failure.
        controller.onInstall = { controller.refreshResult = status(installed = true, zygisk = true) }
        vm.install()

        assertTrue(await { vm.state.value.phase == InstallPhase.INSTALL_REBOOT })
        assertEquals("/cache/echidna-magisk.zip", controller.lastInstallPath)
    }

    private fun viewModel(
        controller: FakeController,
        archive: FakeArchive = FakeArchive(),
        releases: FakeReleases = FakeReleases(),
    ): InstallEngineViewModel {
        val scope = CoroutineScope(Dispatchers.Unconfined).also(scopes::add)
        return InstallEngineViewModel(controller, archive, releases, overrideScope = scope)
    }

    private fun await(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun status(installed: Boolean, zygisk: Boolean, lastError: String? = null) = ModuleStatus(
        magiskModuleInstalled = installed,
        zygiskEnabled = zygisk,
        selinuxState = "ENFORCING",
        selinuxStatus = "Enforcing",
        policyToolAvailable = false,
        policyAppliedVerified = false,
        nativeRouteVerified = false,
        javaFallbackRecommended = true,
        cpu = CpuArchInfo(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            cpuFamily = "arm64",
            is64Bit = true,
            zygiskAbi = "arm64-v8a",
            moduleSupported = true,
            nativeHooksSupported = true,
            supportLevel = "full",
            message = "",
        ),
        audioStack = AudioStackInfo(
            hal = "hal",
            manufacturer = "m",
            boardPlatform = "bp",
            vendorFamily = "vf",
            aaudioSupported = true,
            openSlEsAvailable = true,
            audioFlingerClientAvailable = true,
            tinyAlsaAvailable = true,
            lowLatency = true,
            proAudio = false,
            sampleRate = 48000,
            framesPerBuffer = 192,
        ),
        notes = null,
        lastError = lastError,
    )

    private class FakeController(
        var connected: Boolean = true,
    ) : EngineInstallController {
        private val moduleStatusFlow = MutableStateFlow<ModuleStatus?>(null)
        private val engineStatusFlow = MutableStateFlow(
            EngineStatus(nativeInstalled = false, active = false, selinuxMode = "Enforcing", latencyMs = 0)
        )
        override val moduleStatus: StateFlow<ModuleStatus?> = moduleStatusFlow
        override val engineStatus: StateFlow<EngineStatus> = engineStatusFlow

        @Volatile var refreshResult: ModuleStatus? = null
        val installCalls = AtomicInteger(0)
        val uninstallCalls = AtomicInteger(0)
        val quiesceCalls = AtomicInteger(0)
        val disableCalls = AtomicInteger(0)
        val rebootCalls = AtomicInteger(0)
        @Volatile var disableResult = true
        @Volatile var rebootResult = true
        @Volatile var lastInstallPath: String? = null
        @Volatile var onInstall: (() -> Unit)? = null
        @Volatile var onUninstall: (() -> Unit)? = null
        // Ordered record of the module-op steps so tests can assert unload-first ordering.
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun isServiceConnected(): Boolean = connected

        override suspend fun refreshStatus(): ModuleStatus? {
            moduleStatusFlow.value = refreshResult
            return refreshResult
        }

        override fun quiesceEngine() {
            quiesceCalls.incrementAndGet()
            events += "quiesce"
        }

        override suspend fun disableModule(): Boolean {
            disableCalls.incrementAndGet()
            events += "disable"
            return disableResult
        }

        override suspend fun rebootDevice(): Boolean {
            rebootCalls.incrementAndGet()
            events += "reboot"
            return rebootResult
        }

        override fun install(archivePath: String) {
            installCalls.incrementAndGet()
            events += "install"
            lastInstallPath = archivePath
            onInstall?.invoke()
        }

        override fun uninstall() {
            uninstallCalls.incrementAndGet()
            events += "remove"
            onUninstall?.invoke()
        }
    }

    private class FakeArchive(
        var bundled: String? = "/cache/echidna-magisk.zip",
        var staged: String? = "/cache/picked.zip",
    ) : EngineArchiveSource {
        override fun bundledArchivePath(): String? = bundled
        override fun stageArchive(uri: Uri): String? = staged
    }

    /** Stands in for the GitHub release path; the real verification rules are covered separately. */
    private class FakeReleases(
        private val stagedPath: String = "/cache/release-downloads/echidna-magisk-v1.4.0.zip",
        private val resolveFailure: Throwable? = null,
        private val stageFailure: Throwable? = null,
    ) : ReleaseArtifactSource {
        val stageCalls = AtomicInteger(0)

        override suspend fun resolveLatest(): ResolvedRelease {
            resolveFailure?.let { throw it }
            return ResolvedRelease(
                tag = "v1.4.0",
                assets = listOf(
                    ReleaseAsset("echidna-magisk-v1.4.0.zip", "https://github.com/x/echidna-magisk-v1.4.0.zip", 4096),
                    ReleaseAsset("SHA256SUMS.txt", "https://github.com/x/SHA256SUMS.txt", 128),
                ),
            )
        }

        override suspend fun stageForInstall(release: ResolvedRelease, asset: ReleaseAsset): String {
            stageCalls.incrementAndGet()
            stageFailure?.let { throw it }
            return stagedPath
        }
    }
}
