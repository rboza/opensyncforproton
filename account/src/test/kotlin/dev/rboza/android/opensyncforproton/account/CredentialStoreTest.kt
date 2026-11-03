package dev.rboza.android.opensyncforproton.account

import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.crypto.LockedUserKey
import dev.rboza.android.opensyncforproton.crypto.SecretBox
import dev.rboza.android.opensyncforproton.crypto.SecretBoxException
import dev.rboza.android.opensyncforproton.proton.Tokens
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

// Every value is generated for the test; none is a real Proton value.
class CredentialStoreTest {
    @TempDir
    lateinit var dir: File

    private val box = JvmSecretBox()
    private val account = AccountId("abc")

    // sha256("abc"), lowercase hex.
    private val accountHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val alias = "test.app.creds.$accountHash"

    private fun store(box: SecretBox = this.box) = FileCredentialStore(dir, "test.app", box)

    private fun credentials(access: String = "access-token-1") = Credentials(
        tokens = Tokens(uid = "session-uid-1", access = access, refresh = "refresh-token-1"),
        passwordMode = PasswordMode.TWO,
        keySalts = mapOf("key-id-1" to "salt-value-1", "address-key-id-1" to null),
        keys = LockedKeyBundle(
            listOf(
                LockedUserKey("key-id-1", "armored-key-text-1", active = true, primary = true),
                LockedUserKey("key-id-2", "armored-key-text-2", active = false, primary = false),
            ),
        ),
        passphrases = mapOf("key-id-1" to "passphrase-bytes-1".toByteArray(), "key-id-2" to byteArrayOf()),
    )

    private fun assertSameContent(expected: Credentials, actual: Credentials) {
        assertEquals(expected.tokens.uid, actual.tokens.uid)
        assertEquals(expected.tokens.access, actual.tokens.access)
        assertEquals(expected.tokens.refresh, actual.tokens.refresh)
        assertEquals(expected.passwordMode, actual.passwordMode)
        assertEquals(expected.keySalts, actual.keySalts)
        assertEquals(
            expected.keys.userKeys.map { listOf(it.id, it.armoredPrivateKey, it.active, it.primary) },
            actual.keys.userKeys.map { listOf(it.id, it.armoredPrivateKey, it.active, it.primary) },
        )
        assertEquals(expected.passphrases.keys.toList(), actual.passphrases.keys.toList())
        expected.passphrases.forEach { (id, bytes) -> assertArrayEquals(bytes, actual.passphrases.getValue(id)) }
    }

    @Test
    fun `a new instance reads back what was saved`() {
        val saved = credentials()
        store().save(account, saved)

        val loaded = checkNotNull(store().load(account))

        assertSameContent(saved, loaded)
    }

    @Test
    fun `both password modes round-trip`() {
        PasswordMode.entries.forEach { mode ->
            val saved = Credentials(credentials().tokens, mode, emptyMap(), LockedKeyBundle(emptyList()), emptyMap())
            store().save(account, saved)
            assertEquals(mode, store().load(account)!!.passwordMode)
        }
    }

    @Test
    fun `save replaces the envelope and leaves no other file`() {
        store().save(account, credentials(access = "access-token-1"))
        store().save(account, credentials(access = "access-token-2"))

        assertEquals("access-token-2", store().load(account)!!.tokens.access)
        assertEquals(listOf(accountHash), dir.walk().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun `save replaces the file rather than writing into it`() {
        store().save(account, credentials(access = "access-token-1"))
        // A second name for the old file: a rename leaves it whole, a write into the file would change it.
        val old = File(dir, "old-link")
        Files.createLink(old.toPath(), File(dir, accountHash).toPath())
        val oldBytes = old.readBytes()

        store().save(account, credentials(access = "access-token-2"))

        assertArrayEquals(oldBytes, old.readBytes())
        assertEquals("access-token-2", store().load(account)!!.tokens.access)
    }

    @Test
    fun `a failed save throws and keeps the old envelope`() {
        store().save(account, credentials(access = "access-token-1"))
        val failing = object : SecretBox by box {
            override fun seal(alias: String, plaintext: ByteArray): ByteArray = throw SecretBoxException()
        }

        assertThrows(CredentialStoreException::class.java) { store(failing).save(account, credentials(access = "access-token-2")) }

        assertEquals("access-token-1", store().load(account)!!.tokens.access)
        assertEquals(listOf(accountHash), dir.walk().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun `a save that can't write throws`() {
        // A regular file where the directory goes (unlike a read-only directory, this holds when run as root).
        val notADirectory = File(dir, "credentials").apply { writeText("") }

        val e = assertThrows(CredentialStoreException::class.java) {
            FileCredentialStore(notADirectory, "test.app", box).save(account, credentials())
        }
        // The cause can name the path; the exception itself carries no message.
        assertNull(e.message)
    }

    @Test
    fun `a save whose rename fails throws and leaves no temporary file`() {
        // A non-empty directory where the envelope goes: the rename can't replace it.
        File(dir, "$accountHash/occupied").apply { parentFile!!.mkdirs() }.writeText("")

        assertThrows(CredentialStoreException::class.java) { store().save(account, credentials()) }

        assertEquals(listOf("occupied"), dir.walk().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun `save makes the directory`() {
        val nested = FileCredentialStore(File(dir, "credentials"), "test.app", box)

        nested.save(account, credentials())

        assertNotNull(nested.load(account))
    }

    @Test
    fun `the file is named by the account's hash and sealed under the account's alias`() {
        store().save(account, credentials())

        assertTrue(File(dir, accountHash).isFile)
        assertEquals(setOf(alias), box.aliases())
    }

    @Test
    fun `accounts are kept apart`() {
        val other = AccountId("other")
        store().save(account, credentials(access = "access-token-1"))
        store().save(other, credentials(access = "access-token-2"))

        assertEquals("access-token-1", store().load(account)!!.tokens.access)
        assertEquals("access-token-2", store().load(other)!!.tokens.access)
        assertEquals(2, box.aliases().size)
    }

    @Test
    fun `no secret reaches the file in plaintext`() {
        store().save(account, credentials())

        val onDisk = File(dir, accountHash).readBytes().toString(Charsets.ISO_8859_1)
        listOf("session-uid-1", "access-token-1", "refresh-token-1", "salt-value-1", "armored-key-text-1", "passphrase-bytes-1", "key-id-1")
            .forEach { assertFalse(onDisk.contains(it), it) }
    }

    @Test
    fun `a missing envelope loads as null`() {
        assertNull(store().load(account))
    }

    @Test
    fun `a corrupt file loads as null`() {
        store().save(account, credentials())
        val file = File(dir, accountHash)
        val good = file.readBytes()

        val corruptions = listOf(
            byteArrayOf(),
            good.copyOf(good.size - 1),
            good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() },
            ByteArray(good.size).also { SecureRandom().nextBytes(it) },
        )
        corruptions.forEach { bytes ->
            file.writeBytes(bytes)
            assertNull(store().load(account))
        }
    }

    @Test
    fun `another account's envelope doesn't open`() {
        val other = AccountId("other")
        store().save(account, credentials())
        store().save(other, credentials())
        dir.listFiles()!!.single { it.name != accountHash }.copyTo(File(dir, accountHash), overwrite = true)

        assertNull(store().load(account))
    }

    @Test
    fun `a missing key loads as null`() {
        store().save(account, credentials())
        box.delete(alias)

        assertNull(store().load(account))
    }

    @Test
    fun `an envelope whose content is malformed loads as null`() {
        val valid = envelope()
        val malformed = listOf(
            byteArrayOf(),
            byteArrayOf(2) + valid.copyOfRange(1, valid.size), // an unknown version
            valid.copyOf(valid.size - 1), // truncated
            valid + 0, // trailing bytes
            envelope(passwordMode = 3),
            envelope(saltCount = -1),
            envelope(saltCount = Int.MAX_VALUE),
            envelope(keyCount = Int.MAX_VALUE), // must fail before anything that size is allocated
            envelope(passphraseCount = -1), // with nothing after it: a negative count isn't an empty list
            envelope(passphraseLength = -1),
            envelope(passphraseLength = 1_000_000),
            envelope(duplicateKeyId = true),
            envelope(saltFlag = 2),
            envelope(duplicateSaltId = true),
            envelope(duplicatePassphraseId = true),
            envelope(uid = byteArrayOf(0xc3.toByte(), 0x28)), // invalid UTF-8
        )
        malformed.forEachIndexed { index, plaintext ->
            File(dir, accountHash).writeBytes(box.seal(alias, plaintext))
            assertNull(store().load(account), "case $index")
        }
        File(dir, accountHash).writeBytes(box.seal(alias, valid))
        assertNotNull(store().load(account))
    }

    @Test
    fun `the plaintext buffers are zeroed`() {
        store().save(account, credentials())
        assertTrue(box.lastSealed!!.all { it == 0.toByte() })

        store().load(account)
        assertTrue(box.lastOpened!!.all { it == 0.toByte() })
    }

    @Test
    fun `load doesn't cache`() {
        val one = store()
        one.save(account, credentials(access = "access-token-1"))
        assertEquals("access-token-1", one.load(account)!!.tokens.access)
        store().save(account, credentials(access = "access-token-2"))

        assertEquals("access-token-2", one.load(account)!!.tokens.access)
    }

    @Test
    fun `delete removes the file and the key`() {
        store().save(account, credentials())

        store().delete(account)

        assertNull(store().load(account))
        assertFalse(File(dir, accountHash).exists())
        assertEquals(emptySet<String>(), box.aliases())
    }

    @Test
    fun `a delete that can't remove the file throws and keeps the key`() {
        // A non-empty directory where the envelope goes can't be deleted (also when run as root).
        File(dir, "$accountHash/occupied").apply { parentFile!!.mkdirs() }.writeText("")
        box.seal(alias, byteArrayOf(1))

        assertThrows(CredentialStoreException::class.java) { store().delete(account) }

        assertEquals(setOf(alias), box.aliases())
    }

    @Test
    fun `a key that can't be deleted after the file is gone isn't a failure`() {
        store().save(account, credentials())
        val failing = object : SecretBox by box {
            override fun delete(alias: String) = throw SecretBoxException()
        }

        store(failing).delete(account)

        assertNull(store().load(account))
        assertFalse(File(dir, accountHash).exists())
    }

    @Test
    fun `delete without an envelope is fine`() {
        store().delete(account)

        assertNull(store().load(account))
    }

    @Test
    fun `updateTokens replaces only the tokens`() {
        val saved = credentials()
        store().save(account, saved)
        val tokens = Tokens(uid = "session-uid-1", access = "access-token-2", refresh = "refresh-token-2")

        store().updateTokens(account, tokens)

        val loaded = checkNotNull(store().load(account))
        assertEquals(listOf("session-uid-1", "access-token-2", "refresh-token-2"), listOf(loaded.tokens.uid, loaded.tokens.access, loaded.tokens.refresh))
        assertSameContent(Credentials(tokens, saved.passwordMode, saved.keySalts, saved.keys, credentials().passphrases), loaded)
        assertTrue(box.lastSealed!!.all { it == 0.toByte() })
        assertTrue(box.lastOpened!!.all { it == 0.toByte() })
    }

    @Test
    fun `updateTokens without an envelope throws and makes none`() {
        val tokens = Tokens(uid = "session-uid-1", access = "access-token-2", refresh = "refresh-token-2")

        assertThrows(CredentialStoreException::class.java) { store().updateTokens(account, tokens) }

        assertNull(store().load(account))
        assertEquals(emptyList<File>(), dir.listFiles()!!.toList())
        assertEquals(emptySet<String>(), box.aliases())
    }

    @Test
    fun `updateTokens with an unreadable envelope throws and leaves it`() {
        store().save(account, credentials())
        val file = File(dir, accountHash)
        file.writeBytes(byteArrayOf(1, 2, 3))
        val tokens = Tokens(uid = "session-uid-1", access = "access-token-2", refresh = "refresh-token-2")

        assertThrows(CredentialStoreException::class.java) { store().updateTokens(account, tokens) }

        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(setOf(alias), box.aliases())
    }

    @Test
    fun `updateTokens for another session throws and leaves the envelope`() {
        // A late refresh of an old session (session-uid-1) after a new login (session-uid-2) stored its own tokens.
        store().save(account, credentials())
        store().save(account, Credentials(Tokens("session-uid-2", "access-token-2", "refresh-token-2"), PasswordMode.ONE, emptyMap(), LockedKeyBundle(emptyList()), emptyMap()))
        val before = File(dir, accountHash).readBytes()

        assertThrows(CredentialStoreException::class.java) {
            store().updateTokens(account, Tokens(uid = "session-uid-1", access = "access-token-3", refresh = "refresh-token-3"))
        }

        assertArrayEquals(before, File(dir, accountHash).readBytes())
        assertEquals("access-token-2", store().load(account)!!.tokens.access)
    }

    private fun duringUpdateTokens(other: () -> Unit): Boolean = duringSeal(
        { slow -> store(slow).updateTokens(account, Tokens(uid = "session-uid-1", access = "access-token-2", refresh = "refresh-token-2")) },
        other,
    )

    /**
     * Starts [first] with a SecretBox that stops inside its seal, runs [other] on another store instance (as another
     * component would), and returns whether [other] was still waiting 300 ms later; then lets both finish.
     */
    private fun duringSeal(first: (SecretBox) -> Unit, other: () -> Unit): Boolean {
        val sealing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : SecretBox by box {
            override fun seal(alias: String, plaintext: ByteArray): ByteArray {
                sealing.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return box.seal(alias, plaintext)
            }
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        fun worker(block: () -> Unit) = thread { runCatching(block).onFailure { failures += it } }
        val update = worker { first(slow) }
        assertTrue(sealing.await(10, TimeUnit.SECONDS))
        val second = worker(other)
        second.join(300)
        val waited = second.isAlive
        release.countDown()
        update.join(10_000)
        second.join(10_000)
        assertFalse(update.isAlive || second.isAlive)
        assertEquals(emptyList<Throwable>(), failures.toList())
        return waited
    }

    @Test
    fun `a delete waits for an updateTokens in progress, so it can't bring the envelope back`() {
        store().save(account, credentials())

        assertTrue(duringUpdateTokens { store().delete(account) })

        assertFalse(File(dir, accountHash).exists())
        assertNull(store().load(account))
    }

    @Test
    fun `a save waits for an updateTokens in progress, so the update can't overwrite it`() {
        store().save(account, credentials(access = "access-token-1"))

        assertTrue(duringUpdateTokens { store().save(account, credentials(access = "access-token-3")) })

        assertEquals("access-token-3", store().load(account)!!.tokens.access)
    }

    private val fetchedKeys = LockedKeyBundle(
        listOf(
            LockedUserKey("key-id-3", "armored-key-text-3", active = true, primary = true),
            LockedUserKey("key-id-1", "armored-key-text-1b", active = true, primary = false),
        ),
    )

    @Test
    fun `updateKeys replaces the keys and keeps only the passphrases of keys still there`() {
        // Passphrases carry over by key ID (docs/protocol.md § Keys): key-id-2 is gone, and key-id-3 has none, since
        // only a login derives them.
        val saved = credentials()
        store().save(account, saved)

        store().updateKeys(account, "session-uid-1", fetchedKeys)

        val loaded = checkNotNull(store().load(account))
        assertSameContent(
            Credentials(saved.tokens, saved.passwordMode, saved.keySalts, fetchedKeys, mapOf("key-id-1" to "passphrase-bytes-1".toByteArray())),
            loaded,
        )
        assertTrue(box.lastSealed!!.all { it == 0.toByte() })
        assertTrue(box.lastOpened!!.all { it == 0.toByte() })
    }

    @Test
    fun `updateKeys without an envelope throws and makes none`() {
        assertThrows(CredentialStoreException::class.java) { store().updateKeys(account, "session-uid-1", fetchedKeys) }

        assertNull(store().load(account))
        assertEquals(emptyList<File>(), dir.listFiles()!!.toList())
        assertEquals(emptySet<String>(), box.aliases())
    }

    @Test
    fun `updateKeys with an unreadable envelope throws and leaves it`() {
        store().save(account, credentials())
        val file = File(dir, accountHash)
        file.writeBytes(byteArrayOf(1, 2, 3))

        assertThrows(CredentialStoreException::class.java) { store().updateKeys(account, "session-uid-1", fetchedKeys) }

        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    @Test
    fun `updateKeys for another session throws and leaves the envelope`() {
        // Keys fetched by an old session (session-uid-1) after a new login (session-uid-2) stored its own, which may
        // need other passphrases.
        store().save(account, Credentials(Tokens("session-uid-2", "access-token-2", "refresh-token-2"), PasswordMode.ONE, emptyMap(), LockedKeyBundle(emptyList()), emptyMap()))
        val before = File(dir, accountHash).readBytes()

        assertThrows(CredentialStoreException::class.java) { store().updateKeys(account, "session-uid-1", fetchedKeys) }

        assertArrayEquals(before, File(dir, accountHash).readBytes())
    }

    @Test
    fun `updateKeys with a repeated key ID throws and leaves the envelope`() {
        // load would refuse such an envelope, so writing it would sign the account out.
        store().save(account, credentials())
        val before = File(dir, accountHash).readBytes()
        val repeated = LockedKeyBundle(
            listOf(
                LockedUserKey("key-id-1", "armored-key-text-1", active = true, primary = true),
                LockedUserKey("key-id-1", "armored-key-text-1b", active = true, primary = false),
            ),
        )

        assertThrows(CredentialStoreException::class.java) { store().updateKeys(account, "session-uid-1", repeated) }

        assertArrayEquals(before, File(dir, accountHash).readBytes())
    }

    @Test
    fun `updateKeys with text that has no UTF-8 form throws and leaves the envelope`() {
        // Two IDs differing only in a lone surrogate would both be written as U+FFFD, which load refuses as repeated.
        store().save(account, credentials())
        val before = File(dir, accountHash).readBytes()
        val lone = LockedKeyBundle(
            listOf(
                LockedUserKey("key-id-\uD800", "armored-key-text-1", active = true, primary = true),
                LockedUserKey("key-id-\uDC00", "armored-key-text-1b", active = true, primary = false),
            ),
        )

        assertThrows(CredentialStoreException::class.java) { store().updateKeys(account, "session-uid-1", lone) }

        assertArrayEquals(before, File(dir, accountHash).readBytes())
        assertEquals(listOf(accountHash), dir.list()!!.toList(), "no temporary file left")
    }

    @Test
    fun `a token update waits for an updateKeys in progress, so neither change is lost`() {
        store().save(account, credentials())
        val tokens = Tokens(uid = "session-uid-1", access = "access-token-2", refresh = "refresh-token-2")

        assertTrue(duringSeal({ slow -> store(slow).updateKeys(account, "session-uid-1", fetchedKeys) }, { store().updateTokens(account, tokens) }))

        val loaded = checkNotNull(store().load(account))
        assertEquals("access-token-2", loaded.tokens.access)
        assertEquals(listOf("key-id-3", "key-id-1"), loaded.keys.userKeys.map { it.id })
    }

    @Test
    fun `deleteSession deletes the session it names`() {
        store().save(account, credentials())

        store().deleteSession(account, "session-uid-1")

        assertFalse(File(dir, accountHash).exists())
        assertEquals(emptySet<String>(), box.aliases())
    }

    @Test
    fun `deleteSession of another session keeps the envelope`() {
        // A late rejection of an old session (session-uid-0) after a new login stored session-uid-1.
        store().save(account, credentials())

        store().deleteSession(account, "session-uid-0")

        assertEquals("access-token-1", store().load(account)!!.tokens.access)
    }

    @Test
    fun `deleteSession removes an unreadable envelope`() {
        store().save(account, credentials())
        File(dir, accountHash).writeBytes(byteArrayOf(1, 2, 3))

        store().deleteSession(account, "session-uid-0")

        assertFalse(File(dir, accountHash).exists())
        assertEquals(emptySet<String>(), box.aliases())
    }

    @Test
    fun `deleteSession without an envelope is fine`() {
        store().deleteSession(account, "session-uid-1")

        assertNull(store().load(account))
    }

    @Test
    fun `deleteSession waits for an updateTokens in progress`() {
        store().save(account, credentials())

        assertTrue(duringUpdateTokens { store().deleteSession(account, "session-uid-1") })

        assertFalse(File(dir, accountHash).exists())
    }

    @Test
    fun `deleteSession waits for a save in progress`() {
        store().save(account, credentials())
        val newLogin = Credentials(Tokens("session-uid-2", "access-token-2", "refresh-token-2"), PasswordMode.ONE, emptyMap(), LockedKeyBundle(emptyList()), emptyMap())

        assertTrue(duringSeal({ slow -> store(slow).save(account, newLogin) }, { store().deleteSession(account, "session-uid-1") }))

        assertEquals("access-token-2", store().load(account)!!.tokens.access)
    }

    @Test
    fun `close zeroes the passphrases`() {
        val passphrase = "passphrase-bytes-1".toByteArray()
        val credentials = Credentials(credentials().tokens, PasswordMode.ONE, emptyMap(), LockedKeyBundle(emptyList()), mapOf("key-id-1" to passphrase))

        credentials.close()

        assertTrue(passphrase.all { it == 0.toByte() })
    }

    @Test
    fun `secret-holding types redact toString`() {
        val text = credentials().toString()
        listOf("session-uid-1", "access-token-1", "refresh-token-1", "salt-value-1", "armored-key-text-1", "key-id-1", "passphrase")
            .forEach { assertFalse(text.contains(it), it) }
    }

    /**
     * The envelope's plaintext, written by hand: version 1, then the tokens, password mode, salts, keys, and
     * passphrases (each string and byte field as a 4-byte length and its bytes).
     */
    private fun envelope(
        passwordMode: Int = 1,
        saltCount: Int = 1,
        passphraseLength: Int? = null,
        duplicateKeyId: Boolean = false,
        keyCount: Int? = null,
        passphraseCount: Int? = null,
        saltFlag: Int = 1,
        duplicateSaltId: Boolean = false,
        duplicatePassphraseId: Boolean = false,
        uid: ByteArray = "session-uid-1".encodeToByteArray(),
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            fun field(value: ByteArray, length: Int = value.size) {
                writeInt(length)
                write(value)
            }
            fun string(value: String) = field(value.encodeToByteArray())
            writeByte(1)
            field(uid)
            string("access-token-1")
            string("refresh-token-1")
            writeInt(passwordMode)
            writeInt(if (duplicateSaltId) 2 else saltCount)
            repeat(if (duplicateSaltId) 2 else 1) {
                string("key-id-1")
                writeByte(saltFlag)
                string("salt-value-1")
            }
            writeInt(keyCount ?: if (duplicateKeyId) 2 else 1)
            repeat(if (duplicateKeyId) 2 else 1) {
                string("key-id-1")
                string("armored-key-text-1")
                writeBoolean(true)
                writeBoolean(true)
            }
            writeInt(passphraseCount ?: if (duplicatePassphraseId) 2 else 1)
            repeat(passphraseCount?.coerceAtLeast(0) ?: if (duplicatePassphraseId) 2 else 1) {
                string("key-id-1")
                field("passphrase-bytes-1".toByteArray(), passphraseLength ?: "passphrase-bytes-1".length)
            }
        }
        return bytes.toByteArray()
    }
}
