package com.echidna.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.echidna.app.data.ControlStateRepository
import com.echidna.app.model.DspEngineMode
import com.echidna.control.service.EchidnaControlService
import com.echidna.control.service.IEchidnaControlService
import com.echidna.control.service.IEchidnaPolicyProvider
import com.echidna.control.service.PolicySnapshotService
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prepares production-equivalent app-owned trust and policy state for the disposable AVD proof. */
@RunWith(AndroidJUnit4::class)
class LegacyPreprocessorSessionSetupInstrumentedTest {
    @Test
    fun publishesPolicyAndAppOwnedTrustMaterial() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(
            "standalone session proof requires the installed LSPosed shim",
            isShimInstalled(context),
        )
        val keyFile = prepareTelemetryKey(context)

        val controlConnection = Binding<IEchidnaControlService> {
            IEchidnaControlService.Stub.asInterface(it)
        }
        bind(context, EchidnaControlService::class.java.name, controlConnection)
        val control = controlConnection.await()
        assertTrue(control.setLegacyPreprocessorEnabled(true))

        // Publish through the same app-owned repository path as production. Calling the private
        // service's complete-state method directly would be an instrumentation-only second writer
        // that can race the repository's legitimate startup replay.
        val profileId = ControlStateRepository.presets.value
            .firstOrNull { it.name == PROFILE_NAME }
            ?.id
            ?: requireNotNull(ControlStateRepository.importPreset(PRESET_JSON))
        ControlStateRepository.setDspEngineMode(DspEngineMode.COMPATIBILITY)
        ControlStateRepository.updateWhitelist(SHIM_PACKAGE, true)
        ControlStateRepository.setAppPresetBinding(SHIM_PACKAGE, profileId)
        waitForPublishedPolicy(control, profileId)

        val providerConnection = Binding<IEchidnaPolicyProvider> {
            IEchidnaPolicyProvider.Stub.asInterface(it)
        }
        bind(context, PolicySnapshotService::class.java.name, providerConnection)
        val provider = providerConnection.await()
        assertEquals(PROVIDER_API_VERSION, provider.apiVersion)

        val spkiFile = File(File(context.filesDir, "echidna"), CONTROLLER_SPKI)
        waitForRegularFile(spkiFile)
        val persisted = File(File(context.filesDir, "profiles"), "profiles.json")
        waitForRegularFile(persisted)
        Thread.sleep(500L)
        val persistedPolicy = JSONObject(persisted.readText())
        assertTrue(persistedPolicy.getJSONObject("whitelist").getBoolean(SHIM_PACKAGE))
        assertTrue(
            "headless shim identity must be published for provider authorization",
            persistedPolicy.getJSONObject("appIdentities").has(SHIM_PACKAGE),
        )
        assertEquals(
            "lsposed",
            persistedPolicy.getJSONObject("captureOwners").getString(SHIM_PACKAGE),
        )
        val generation = persistedPolicy.getLong("generation")
        assertTrue(generation > 0L)

        val evidenceDir = File(context.filesDir, "session-proof").apply { mkdirs() }
        File(evidenceDir, "setup.json").writeText(
            JSONObject()
                .put("providerApiVersion", provider.apiVersion)
                .put("generation", generation)
                .put("process", SHIM_PACKAGE)
                .put("profileId", profileId)
                .put("controllerSpkiSha256", sha256(spkiFile.readBytes()))
                .put("telemetryKeyId", sha256(keyFile.readBytes()).substring(0, 32))
                .put("telemetryKeyMode", Integer.toOctalString(Os.stat(keyFile.path).st_mode and 0x1ff))
                .put("telemetryKeyUid", Os.stat(keyFile.path).st_uid)
                .put("telemetryKeyGid", Os.stat(keyFile.path).st_gid)
                .toString(2),
        )

        context.unbindService(providerConnection)
        context.unbindService(controlConnection)
    }

    private fun isShimInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                SHIM_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(SHIM_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun prepareTelemetryKey(context: Context): File {
        val directory = File(context.filesDir, "echidna").apply { mkdirs() }
        val file = File(directory, TELEMETRY_KEY)
        if (!file.exists()) {
            val key = ByteArray(32).also(SecureRandom()::nextBytes)
            file.outputStream().use { it.write(key) }
            key.fill(0)
        }
        Os.chmod(file.path, 0x180) // 0600
        val stat = Os.stat(file.path)
        assertEquals(32L, stat.st_size)
        assertEquals(Process.myUid(), stat.st_uid)
        assertEquals(Process.myUid(), stat.st_gid)
        assertEquals(0x180, stat.st_mode and 0x1ff)
        assertTrue(file.readBytes().any { it != 0.toByte() })
        return file
    }

    private fun waitForPublishedPolicy(control: IEchidnaControlService, profileId: String) {
        repeat(100) {
            val bindings = JSONObject(control.whitelistBindings)
            val state = JSONObject(control.controlState)
            val whitelisted = bindings.getJSONObject("whitelist")
                .optBoolean(SHIM_PACKAGE, false)
            val bound = bindings.getJSONObject("appBindings")
                .optString(SHIM_PACKAGE, "") == profileId
            if (whitelisted && bound && state.optString("engineMode") == "compatibility") return
            Thread.sleep(100L)
        }
        throw AssertionError("production repository policy did not reach the service")
    }

    private fun bind(context: Context, className: String, connection: ServiceConnection) {
        val intent = Intent().apply {
            component = ComponentName(context.packageName, className)
        }
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
    }

    private fun waitForRegularFile(file: File) {
        repeat(50) {
            if (file.isFile && file.length() > 0L) return
            Thread.sleep(100L)
        }
        throw AssertionError("missing generated file: ${file.path}")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class Binding<T>(private val adapter: (IBinder) -> T) : ServiceConnection {
        private val connected = CountDownLatch(1)
        @Volatile private var service: T? = null

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = adapter(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }

        fun await(): T {
            assertTrue("service bind timed out", connected.await(10, TimeUnit.SECONDS))
            return requireNotNull(service)
        }
    }

    private companion object {
        const val PROVIDER_API_VERSION = 7L
        const val SHIM_PACKAGE = "com.echidna.lsposed"
        const val PROFILE_NAME = "Session proof -9 dB"
        const val CONTROLLER_SPKI = "preprocessor_controller_p256.spki"
        const val TELEMETRY_KEY = "preprocessor_telemetry_hmac.key"
        const val PRESET_JSON = """
            {
              "name": "$PROFILE_NAME",
              "engine": {"latencyMode": "LL", "blockMs": 10},
              "modules": [
                {"id": "gate", "enabled": false},
                {"id": "eq", "enabled": false, "bands": []},
                {"id": "comp", "enabled": false},
                {"id": "pitch", "enabled": false},
                {"id": "formant", "enabled": false},
                {"id": "autotune", "enabled": false},
                {"id": "reverb", "enabled": false},
                {"id": "mix", "wet": 100.0, "outGain": -9.0}
              ]
            }
        """
    }
}
