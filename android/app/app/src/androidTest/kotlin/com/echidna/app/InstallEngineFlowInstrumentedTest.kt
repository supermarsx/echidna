package com.echidna.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.echidna.app.model.AudioStackInfo
import com.echidna.app.model.CpuArchInfo
import com.echidna.app.model.EngineStatus
import com.echidna.app.model.ModuleStatus
import com.echidna.app.ui.install.EngineArchiveSource
import com.echidna.app.ui.install.EngineInstallController
import com.echidna.app.ui.install.InstallEngineScreen
import com.echidna.app.ui.install.InstallEngineViewModel
import com.echidna.app.ui.install.InstallPhase
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the guided installer end-to-end on a real device without ever running Magisk.
 *
 * Two proofs:
 *  1. **Detection renders honestly** — the real [InstallEngineViewModel] (backed by the live
 *     repository) renders the device-status card on this unrooted emulator with the honest
 *     "Not installed / Not detected" verdicts. Nothing is fabricated as installed.
 *  2. **Client actions dispatch to the controller** — with a *fake* [EngineInstallController] the
 *     install/uninstall client methods are verified to call the controller (mock/verify the binder
 *     call, per the task) and to drive the honest state machine: an update or uninstall performs the
 *     unload-first QUIESCE → disable ordering before touching the module, and each op settles on the
 *     REBOOT-required phase a live Zygisk module demands.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class InstallEngineFlowInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun detectionCardRendersHonestUnrootedStatus() {
        composeRule.setContent {
            MaterialTheme {
                InstallEngineScreen(viewModel = InstallEngineViewModel(), onClose = {})
            }
        }
        composeRule.onNodeWithText("Flashing a root module is risky").assertIsDisplayed()
        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Device status"))
        composeRule.onNodeWithText("Device status").assertIsDisplayed()
        // Unrooted emulator: the module is not installed and Magisk/Zygisk is not detected.
        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Not installed"))
        composeRule.onNodeWithText("Not installed").assertIsDisplayed()
        composeRule.onNodeWithText("Not detected").assertIsDisplayed()
    }

    @Test
    fun installFromCleanStateDispatchesInstallAndRequiresReboot() {
        val controller = FakeController(initiallyInstalled = false)
        val vm = InstallEngineViewModel(
            controller = controller,
            archive = FakeArchive(),
            overrideScope = CoroutineScope(Dispatchers.Default),
        )
        // Detection must settle out of the initial busy DETECTING phase before install is offered.
        awaitPhase(vm) { it == InstallPhase.IDLE }
        vm.install()
        awaitPhase(vm) { it == InstallPhase.INSTALL_REBOOT }

        assertEquals("module install must be dispatched to the controller once",
            listOf(BUNDLED_PATH), controller.installCalls)
        // Clean install: no quiesce/disable (nothing to unload yet), just install.
        assertEquals(listOf("install"), controller.events)
    }

    @Test
    fun updateOverExistingModuleQuiescesAndDisablesBeforeInstalling() {
        val controller = FakeController(initiallyInstalled = true)
        val vm = InstallEngineViewModel(
            controller = controller,
            archive = FakeArchive(),
            overrideScope = CoroutineScope(Dispatchers.Default),
        )
        awaitPhase(vm) { it == InstallPhase.IDLE }
        vm.install()
        awaitPhase(vm) { it == InstallPhase.INSTALL_REBOOT }

        // Unload-first ordering: the live module is quiesced (master-off) and Magisk-disabled before
        // the replacement is installed.
        assertEquals(listOf("quiesce", "disable", "install"), controller.events)
    }

    @Test
    fun uninstallQuiescesDisablesRemovesThenRequiresReboot() {
        val controller = FakeController(initiallyInstalled = true)
        val vm = InstallEngineViewModel(
            controller = controller,
            archive = FakeArchive(),
            overrideScope = CoroutineScope(Dispatchers.Default),
        )
        // Let the initial detection settle so canUninstall reflects the installed module.
        awaitPhase(vm) { it == InstallPhase.IDLE }
        vm.uninstall()
        // QUIESCING is a transient busy phase surfaced before the reboot-required terminal state.
        awaitPhase(vm) { it == InstallPhase.UNINSTALL_REBOOT }

        assertEquals(listOf("quiesce", "disable", "uninstall"), controller.events)
        assertTrue("module removal must be dispatched", controller.uninstallCalled)
    }

    private fun awaitPhase(
        vm: InstallEngineViewModel,
        timeoutMs: Long = 8_000L,
        predicate: (InstallPhase) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(vm.state.value.phase)) return
            Thread.sleep(25)
        }
        throw AssertionError("phase never satisfied predicate; last=${vm.state.value.phase}")
    }

    private companion object {
        const val BUNDLED_PATH = "/data/local/tmp/echidna-fake.zip"

        fun fakeModuleStatus(installed: Boolean): ModuleStatus = ModuleStatus(
            magiskModuleInstalled = installed,
            zygiskEnabled = installed,
            selinuxState = "enforcing",
            selinuxStatus = "enforcing",
            policyToolAvailable = false,
            policyAppliedVerified = false,
            nativeRouteVerified = false,
            javaFallbackRecommended = false,
            cpu = CpuArchInfo(
                primaryAbi = "x86_64",
                supportedAbis = listOf("x86_64"),
                cpuFamily = "x86",
                is64Bit = true,
                zygiskAbi = "x86_64",
                moduleSupported = true,
                nativeHooksSupported = true,
                supportLevel = "full",
                message = "",
            ),
            audioStack = AudioStackInfo(
                hal = "emulator",
                manufacturer = "Google",
                boardPlatform = "goldfish",
                vendorFamily = "Emulator",
                aaudioSupported = true,
                openSlEsAvailable = true,
                audioFlingerClientAvailable = true,
                tinyAlsaAvailable = false,
                lowLatency = false,
                proAudio = false,
                sampleRate = 48000,
                framesPerBuffer = 192,
            ),
            notes = null,
            lastError = null,
        )
    }

    /**
     * A stand-in for the real repository-backed controller. Records the exact sequence of privileged
     * operations so the unload-first ordering can be asserted, and updates its module-status flow so
     * the ViewModel's confirm-by-poll loop observes the operation completing.
     */
    private class FakeController(initiallyInstalled: Boolean) : EngineInstallController {
        val events = mutableListOf<String>()
        val installCalls = mutableListOf<String>()
        var uninstallCalled = false

        override val moduleStatus = MutableStateFlow(fakeModuleStatus(initiallyInstalled))
        override val engineStatus = MutableStateFlow(
            EngineStatus(nativeInstalled = initiallyInstalled, active = false, selinuxMode = "enforcing"),
        )

        override fun isServiceConnected(): Boolean = true

        override suspend fun refreshStatus(): ModuleStatus = moduleStatus.value

        override fun quiesceEngine() {
            events += "quiesce"
        }

        override suspend fun disableModule(): Boolean {
            events += "disable"
            return true
        }

        override suspend fun rebootDevice(): Boolean = true

        override fun install(archivePath: String) {
            events += "install"
            installCalls += archivePath
            // Simulate Magisk registering the module so the confirm-poll observes it installed.
            moduleStatus.value = fakeModuleStatus(installed = true)
        }

        override fun uninstall() {
            events += "uninstall"
            uninstallCalled = true
            moduleStatus.value = fakeModuleStatus(installed = false)
        }
    }

    private class FakeArchive : EngineArchiveSource {
        override fun bundledArchivePath(): String = BUNDLED_PATH
        override fun stageArchive(uri: Uri): String = BUNDLED_PATH
    }
}
