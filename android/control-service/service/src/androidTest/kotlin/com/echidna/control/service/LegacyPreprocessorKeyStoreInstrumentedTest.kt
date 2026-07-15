package com.echidna.control.service

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyPreprocessorKeyStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun keyIsNonExportableP256AndSpkiIsRestrictive() {
        val signer = AndroidKeyStoreLegacyCapabilitySigner(context)
        val spki = signer.preparePublicKey()
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
        assertEquals(256, publicKey.params.curve.field.fieldSize)

        val privateKey = KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            getKey(LEGACY_CAPABILITY_KEY_ALIAS, null)
        }
        assertNull("AndroidKeyStore private key material must not export", privateKey.encoded)

        val body = "instrumentation-capability".toByteArray()
        val signature = signer.sign(body)
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(body)
            verify(signature)
        })

        val file = signer.exportedSpkiFile()
        assertTrue(file.absolutePath.endsWith("/files/echidna/preprocessor_controller_p256.spki"))
        assertArrayEquals(spki, file.readBytes())
        assertEquals(384, Os.stat(file.absolutePath).st_mode and 511) // 0600
    }

    @Test
    fun featureFlagMigratesFalseAndPersistsTrueIndependently() {
        context.deleteSharedPreferences(LEGACY_PREPROCESSOR_PREFERENCES)
        val migrated = LegacyPreprocessorFlagStore(context)
        assertFalse(migrated.isEnabled())
        assertTrue(migrated.setEnabled(true))
        assertTrue(LegacyPreprocessorFlagStore(context).isEnabled())
        assertTrue(migrated.setEnabled(false))
    }

    @Test
    fun telemetryProofKeyLoaderRejectsModeSizeZeroAndSymlinkDrift() {
        val directory = File(context.cacheDir, "telemetry-proof-loader").apply {
            deleteRecursively()
            assertTrue(mkdirs())
        }
        val keyFile = File(directory, "proof.key")
        val link = File(directory, "proof.link")
        try {
            val key = ByteArray(PREPROCESSOR_TELEMETRY_PROOF_KEY_BYTES) { (it + 1).toByte() }
            keyFile.writeBytes(key)
            Os.chmod(keyFile.absolutePath, 384) // 0600
            assertArrayEquals(key, AppPrivateTelemetryProofKeySource(keyFile).load())

            Os.chmod(keyFile.absolutePath, 420) // 0644
            assertNull(AppPrivateTelemetryProofKeySource(keyFile).load())

            keyFile.writeBytes(ByteArray(PREPROCESSOR_TELEMETRY_PROOF_KEY_BYTES - 1) { 1 })
            Os.chmod(keyFile.absolutePath, 384)
            assertNull(AppPrivateTelemetryProofKeySource(keyFile).load())

            keyFile.writeBytes(ByteArray(PREPROCESSOR_TELEMETRY_PROOF_KEY_BYTES))
            Os.chmod(keyFile.absolutePath, 384)
            assertNull(AppPrivateTelemetryProofKeySource(keyFile).load())

            keyFile.writeBytes(key)
            Os.chmod(keyFile.absolutePath, 384)
            Os.symlink(keyFile.absolutePath, link.absolutePath)
            assertNull(AppPrivateTelemetryProofKeySource(link).load())
        } finally {
            directory.deleteRecursively()
        }
    }
}
