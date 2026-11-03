package dev.rboza.android.opensyncforproton.crypto

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * SecretBox's format and failures, with a JVM AES-256 key standing in for the Keystore key. Both providers pick the
 * IV when the cipher is initialised.
 */
class SecretBoxTest {
    /** A software key store: one AES-256 key per alias, with counts of the calls the box makes. */
    private class StandInKeys {
        val keys = mutableMapOf<String, SecretKey>()
        val created = AtomicInteger()
        var onCreate: () -> Unit = {}

        fun existing(alias: String): SecretKey? = synchronized(keys) { keys[alias] }

        fun create(alias: String): SecretKey {
            created.incrementAndGet()
            onCreate()
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            synchronized(keys) { keys[alias] = key }
            return key
        }

        fun delete(alias: String) {
            synchronized(keys) { keys.remove(alias) }
        }

        fun box() = KeystoreSecretBox(::existing, ::create, ::delete)
    }

    private val keys = StandInKeys()
    private val box = keys.box()
    private val plaintext = "tokens, salts, and passphrases".encodeToByteArray()

    @Test
    fun `secretbox_round_trip`() {
        val sealed = box.seal("a", plaintext)

        assertEquals(12 + plaintext.size + 16, sealed.size, "12-byte IV, ciphertext, 128-bit tag")
        assertFalse(sealed.decodeToString().contains("tokens"))
        assertArrayEquals(plaintext, box.open("a", sealed))
    }

    @Test
    fun `secretbox_round_trip of empty data`() {
        assertArrayEquals(ByteArray(0), box.open("a", box.seal("a", ByteArray(0))))
    }

    @Test
    fun `secretbox_fresh_iv`() {
        val first = box.seal("a", plaintext)
        val second = box.seal("a", plaintext)

        assertNotEquals(first.copyOf(12).toList(), second.copyOf(12).toList())
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `one key per alias, created on the first seal only`() {
        val sealed = box.seal("a", plaintext)
        box.seal("a", plaintext)
        box.seal("b", plaintext)

        assertEquals(2, keys.created.get())
        assertThrows(SecretBoxException::class.java) { box.open("b", sealed) }
    }

    @Test
    fun `secretbox_open_failure_throws on any changed bit`() {
        val sealed = box.seal("a", plaintext)

        // Every bit of the IV, the ciphertext, and the tag.
        for (bit in 0 until sealed.size * 8) {
            val changed = sealed.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
            assertThrows(SecretBoxException::class.java, { box.open("a", changed) }, "bit $bit")
        }
    }

    @Test
    fun `secretbox_open_failure_throws on short or extended data`() {
        val sealed = box.seal("a", plaintext)

        for (size in 0 until sealed.size) {
            assertThrows(SecretBoxException::class.java, { box.open("a", sealed.copyOf(size)) }, "size $size")
        }
        assertThrows(SecretBoxException::class.java) { box.open("a", sealed + 0) }
    }

    @Test
    fun `secretbox_open_failure_throws without a key, and never creates one`() {
        val sealed = keys.box().seal("a", plaintext)
        keys.delete("a")
        val before = keys.created.get()

        assertThrows(SecretBoxException::class.java) { box.open("a", sealed) }
        assertEquals(before, keys.created.get())
        assertEquals(null, keys.existing("a"))
    }

    @Test
    fun `secretbox_open_failure_throws when the key store fails`() {
        val sealed = box.seal("a", plaintext)
        val failing = KeystoreSecretBox({ throw IllegalStateException("keystore") }, keys::create, keys::delete)

        assertThrows(SecretBoxException::class.java) { failing.open("a", sealed) }
    }

    @Test
    fun `secretbox_seal_failure_throws when no key can be made`() {
        val failing = KeystoreSecretBox(keys::existing, { throw IllegalStateException("keystore") }, keys::delete)

        assertThrows(SecretBoxException::class.java) { failing.seal("a", plaintext) }
    }

    @Test
    fun `secretbox_seal_failure_throws when the key store fails`() {
        val failing = KeystoreSecretBox({ throw IllegalStateException("keystore") }, keys::create, keys::delete)

        assertThrows(SecretBoxException::class.java) { failing.seal("a", plaintext) }
    }

    @Test
    fun `secretbox_seal_failure_throws for a key that isn't AES`() {
        val hmac = KeyGenerator.getInstance("HmacSHA256").generateKey()
        val failing = KeystoreSecretBox({ hmac }, keys::create, keys::delete)

        assertThrows(SecretBoxException::class.java) { failing.seal("a", plaintext) }
    }

    @Test
    fun `secretbox_open_failure_throws for a key that isn't AES`() {
        val sealed = box.seal("a", plaintext)
        // The same 32 bytes, labelled as another algorithm.
        val relabelled = javax.crypto.spec.SecretKeySpec(keys.existing("a")!!.encoded, "HmacSHA256")
        val failing = KeystoreSecretBox({ relabelled }, keys::create, keys::delete)

        assertThrows(SecretBoxException::class.java) { failing.open("a", sealed) }
    }

    @Test
    fun `delete removes the key, so earlier data can't be opened`() {
        val sealed = box.seal("a", plaintext)

        box.delete("a")

        assertEquals(null, keys.existing("a"))
        assertThrows(SecretBoxException::class.java) { box.open("a", sealed) }
    }

    @Test
    fun `delete failure throws`() {
        val failing = KeystoreSecretBox(keys::existing, keys::create, { throw IllegalStateException("keystore") })

        assertThrows(SecretBoxException::class.java) { failing.delete("a") }
    }

    /**
     * Making a key for an alias that has one replaces it (Android Keystore does), which would make everything
     * sealed with the first key unreadable. Two first seals at once must make one key.
     */
    @Test
    fun `concurrent first seals make one key`() = concurrentFirstSeals(box, box)

    /** The lock is the process's, not one instance's: a second box (another component's) shares it. */
    @Test
    fun `concurrent first seals through two boxes make one key`() = concurrentFirstSeals(box, keys.box())

    private fun concurrentFirstSeals(first: SecretBox, second: SecretBox) {
        val bothArrived = CountDownLatch(2)
        keys.onCreate = {
            bothArrived.countDown()
            // Without a lock, the other seal reaches create too; with one, it waits outside.
            bothArrived.await(300, TimeUnit.MILLISECONDS)
        }
        val results = arrayOfNulls<ByteArray>(2)
        val threads = listOf(first, second).mapIndexed { i, b -> thread { results[i] = b.seal("a", plaintext) } }
        threads.forEach { it.join() }

        assertEquals(1, keys.created.get())
        results.forEach { assertArrayEquals(plaintext, box.open("a", it!!)) }
    }
}
