package com.echidna.control.service

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
