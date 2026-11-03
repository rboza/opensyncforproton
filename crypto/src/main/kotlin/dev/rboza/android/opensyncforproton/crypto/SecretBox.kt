package dev.rboza.android.opensyncforproton.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets with a key that never leaves the Keystore (D22). Every call throws [SecretBoxException] on any
 * failure.
 */
interface SecretBox {
    /** A 12-byte IV followed by the AES-GCM ciphertext and its 128-bit tag. Makes the alias's key if it has none. */
    fun seal(alias: String, plaintext: ByteArray): ByteArray

    /** Never makes a key, and never returns unauthenticated data. */
    fun open(alias: String, ciphertext: ByteArray): ByteArray

    fun delete(alias: String)
}

/** Any SecretBox failure: a missing or unusable key, a Keystore error, or data that doesn't authenticate. */
class SecretBoxException(cause: Throwable? = null) : Exception(cause)

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_BYTES = 12
private const val TAG_BITS = 128
private const val TAG_BYTES = TAG_BITS / 8
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

// Making a key for an alias that has one replaces it, so finding or making a key happens under one lock: the
// process's, so that boxes made by different components share it.
private val keyLock = Any()

class KeystoreSecretBox internal constructor(
    private val existingKey: (alias: String) -> SecretKey?,
    private val newKey: (alias: String) -> SecretKey,
    private val deleteKey: (alias: String) -> Unit,
) : SecretBox {
    constructor() : this(::keystoreKey, ::newKeystoreKey, ::deleteKeystoreKey)

    override fun seal(alias: String, plaintext: ByteArray): ByteArray = failClosed {
        val key = aes(synchronized(keyLock) { existingKey(alias) ?: newKey(alias) })
        // No IV is passed: the provider picks a fresh one (the Keystore insists on it).
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = checkNotNull(cipher.iv)
        val sealed = cipher.doFinal(plaintext)
        // The format open() reads; the JDK provider always meets it, a Keystore provider is checked here.
        check(iv.size == IV_BYTES && sealed.size == plaintext.size + TAG_BYTES)
        iv + sealed
    }

    override fun open(alias: String, ciphertext: ByteArray): ByteArray = failClosed {
        val key = aes(synchronized(keyLock) { existingKey(alias) } ?: throw SecretBoxException())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES))
        cipher.doFinal(ciphertext, IV_BYTES, ciphertext.size - IV_BYTES)
    }

    override fun delete(alias: String) = failClosed {
        synchronized(keyLock) { deleteKey(alias) }
    }
}

// The JDK's AES cipher takes any 32-byte key, whatever its algorithm.
private fun aes(key: SecretKey): SecretKey = key.also { check(it.algorithm == KeyProperties.KEY_ALGORITHM_AES) }

private inline fun <T> failClosed(block: () -> T): T = try {
    block()
} catch (e: SecretBoxException) {
    throw e
} catch (e: Exception) {
    throw SecretBoxException(e)
}

/** AES-256-GCM, IVs picked by the Keystore, usable while the phone is locked (after the first unlock). */
internal fun keystoreKeySpec(alias: String): KeyGenParameterSpec =
    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(false)
        .setUnlockedDeviceRequired(false)
        .build()

private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

private fun keystoreKey(alias: String): SecretKey? = androidKeyStore().getKey(alias, null) as SecretKey?

private fun newKeystoreKey(alias: String): SecretKey =
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply { init(keystoreKeySpec(alias)) }
        .generateKey()

private fun deleteKeystoreKey(alias: String) = androidKeyStore().deleteEntry(alias)
