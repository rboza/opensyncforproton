package dev.rboza.android.opensyncforproton.account

import dev.rboza.android.opensyncforproton.crypto.SecretBox
import dev.rboza.android.opensyncforproton.crypto.SecretBoxException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SecretBox's sealed format, with a JVM AES key per alias standing in for the Keystore. */
class JvmSecretBox : SecretBox {
    private val keys = java.util.concurrent.ConcurrentHashMap<String, SecretKey>()
    var lastSealed: ByteArray? = null
    var lastOpened: ByteArray? = null

    fun aliases(): Set<String> = keys.keys.toSet()

    override fun seal(alias: String, plaintext: ByteArray): ByteArray {
        lastSealed = plaintext
        val key = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(alias: String, ciphertext: ByteArray): ByteArray {
        val key = keys[alias] ?: throw SecretBoxException()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
            return cipher.doFinal(ciphertext, 12, ciphertext.size - 12).also { lastOpened = it }
        } catch (e: Exception) {
            throw SecretBoxException(e)
        }
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }
}
