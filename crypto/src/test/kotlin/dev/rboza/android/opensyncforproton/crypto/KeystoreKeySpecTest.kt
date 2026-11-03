package dev.rboza.android.opensyncforproton.crypto

import android.security.keystore.KeyProperties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * docs/architecture.md § Security: the Keystore key is AES-256-GCM with a Keystore-generated IV and needs neither
 * user authentication nor an unlocked device. The key itself is made on a device (instrumented tests).
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreKeySpecTest {
    private val spec = keystoreKeySpec("alias-1")

    @Test
    fun `AES-256 in GCM mode, for encrypting and decrypting only`() {
        assertEquals("alias-1", spec.keystoreAlias)
        assertEquals(256, spec.keySize)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings)
    }

    @Test
    fun `the Keystore picks every IV`() {
        assertTrue(spec.isRandomizedEncryptionRequired)
    }

    @Test
    fun `usable by scheduled syncs while the phone is locked`() {
        assertFalse(spec.isUserAuthenticationRequired)
        assertFalse(spec.isUnlockedDeviceRequired)
    }
}
