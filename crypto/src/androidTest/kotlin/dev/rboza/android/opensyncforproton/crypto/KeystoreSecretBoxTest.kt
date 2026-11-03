package dev.rboza.android.opensyncforproton.crypto

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SecretBox with a real AndroidKeyStore key (the JVM tests use a software stand-in).
 * Runs in this module's own test app, under a fresh test-only alias that is deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretBoxTest {
    private val alias = "test-only.secretbox.${UUID.randomUUID()}"
    private val box = KeystoreSecretBox()
    private val plaintext = "test-only credentials".encodeToByteArray()

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After
    fun deleteKey() = keyStore().deleteEntry(alias)

    @Test
    fun keystore_secretbox_round_trip() {
        val sealed = box.seal(alias, plaintext)

        assertEquals("12-byte IV, ciphertext, 128-bit tag", 12 + plaintext.size + 16, sealed.size)
        assertTrue(keyStore().containsAlias(alias))
        assertArrayEquals(plaintext, KeystoreSecretBox().open(alias, sealed))
    }

    /** The key the Keystore made matches KeystoreKeySpecTest's spec. */
    @Test
    fun keystore_secretbox_key() {
        box.seal(alias, plaintext)
        val key = keyStore().getKey(alias, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo

        assertEquals(KeyProperties.KEY_ALGORITHM_AES, key.algorithm)
        assertEquals("made inside the Keystore, never imported", KeyProperties.ORIGIN_GENERATED, info.origin)
        assertEquals(256, info.keySize)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), info.encryptionPaddings)
        assertFalse(info.isUserAuthenticationRequired)
    }

    @Test
    fun keystore_secretbox_fresh_iv() {
        val first = box.seal(alias, plaintext)
        val second = box.seal(alias, plaintext)

        assertNotEquals(first.copyOf(12).toList(), second.copyOf(12).toList())
    }

    @Test
    fun keystore_secretbox_tamper_throws() {
        val sealed = box.seal(alias, plaintext)

        for (index in listOf(0, 12, sealed.size - 1)) {
            val changed = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows("byte $index", SecretBoxException::class.java) { box.open(alias, changed) }
        }
    }

    @Test
    fun keystore_secretbox_delete() {
        val sealed = box.seal(alias, plaintext)

        box.delete(alias)

        assertFalse(keyStore().containsAlias(alias))
        assertThrows(SecretBoxException::class.java) { box.open(alias, sealed) }
        assertFalse("open never makes a key", keyStore().containsAlias(alias))
    }
}
