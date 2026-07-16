package com.echidna.app.ui.install

import android.net.Uri
import com.echidna.app.model.AudioStackInfo
import com.echidna.app.model.CpuArchInfo
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.ModuleStatus
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

    private fun viewModel(
        controller: FakeController,
        archive: FakeArchive = FakeArchive(),
    ): InstallEngineViewModel {
        val scope = CoroutineScope(Dispatchers.Unconfined).also(scopes::add)
        return InstallEngineViewModel(controller, archive, overrideScope = scope)
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
        @Volatile var lastInstallPath: String? = null
        @Volatile var onInstall: (() -> Unit)? = null
        @Volatile var onUninstall: (() -> Unit)? = null

        override fun isServiceConnected(): Boolean = connected

        override suspend fun refreshStatus(): ModuleStatus? {
            moduleStatusFlow.value = refreshResult
            return refreshResult
        }

        override fun install(archivePath: String) {
            installCalls.incrementAndGet()
            lastInstallPath = archivePath
            onInstall?.invoke()
        }

        override fun uninstall() {
            uninstallCalls.incrementAndGet()
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
}
