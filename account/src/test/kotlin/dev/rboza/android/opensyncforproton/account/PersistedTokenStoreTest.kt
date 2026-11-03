package dev.rboza.android.opensyncforproton.account

import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.proton.Tokens
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

// Every value is generated for the test.
class PersistedTokenStoreTest {
    @TempDir
    lateinit var dir: File

    private val account = AccountId("abc")
    private val box = JvmSecretBox()
    private val credentialStore by lazy { FileCredentialStore(dir, "test.app", box) }

    private fun credentials(passphrase: ByteArray = "passphrase-bytes-1".toByteArray()) = Credentials(
        Tokens(uid = "session-uid-1", access = "access-token-1", refresh = "refresh-token-1"),
        PasswordMode.ONE,
        mapOf("key-id-1" to "salt-value-1"),
        LockedKeyBundle(emptyList()),
        mapOf("key-id-1" to passphrase),
    )

    @Test
    fun `current reads the stored tokens`() {
        credentialStore.save(account, credentials())

        val tokens = PersistedTokenStore(account, credentialStore).current()!!

        assertEquals(listOf("session-uid-1", "access-token-1", "refresh-token-1"), listOf(tokens.uid, tokens.access, tokens.refresh))
    }

    @Test
    fun `current without credentials is null`() {
        assertNull(PersistedTokenStore(account, credentialStore).current())
    }

    @Test
    fun `current closes what it loaded`() {
        val passphrase = "passphrase-bytes-1".toByteArray()
        val fake = object : CredentialStore by credentialStore {
            override fun load(account: AccountId) = credentials(passphrase)
        }

        PersistedTokenStore(account, fake).current()

        assertTrue(passphrase.all { it == 0.toByte() })
    }

    @Test
    fun `current doesn't cache`() {
        credentialStore.save(account, credentials())
        val store = PersistedTokenStore(account, credentialStore)
        store.current()

        credentialStore.updateTokens(account, Tokens(uid = "session-uid-1", access = "access-token-2", refresh = "refresh-token-2"))

        assertEquals("access-token-2", store.current()!!.access)
    }

    @Test
    fun `replace stores the new tokens durably before returning`() {
        credentialStore.save(account, credentials())

        PersistedTokenStore(account, credentialStore).replace(Tokens(uid = "session-uid-1", access = "access-token-2", refresh = "refresh-token-2"))

        // A new instance reads them from disk.
        val reread = FileCredentialStore(dir, "test.app", box)
        assertEquals("refresh-token-2", PersistedTokenStore(account, reread).current()!!.refresh)
    }

    @Test
    fun `replace without credentials throws and makes none`() {
        assertThrows(CredentialStoreException::class.java) {
            PersistedTokenStore(account, credentialStore).replace(Tokens(uid = "session-uid-1", access = "access-token-2", refresh = "refresh-token-2"))
        }

        assertNull(credentialStore.load(account))
    }

    @Test
    fun `a rejected session deletes the credentials`() {
        credentialStore.save(account, credentials())

        PersistedTokenStore(account, credentialStore).onSessionRejected("session-uid-1")

        assertNull(credentialStore.load(account))
        assertEquals(emptyList<File>(), dir.listFiles()!!.toList())
    }

    @Test
    fun `a rejection of an older session keeps a newer login's credentials`() {
        credentialStore.save(account, credentials())

        PersistedTokenStore(account, credentialStore).onSessionRejected("session-uid-0")

        assertEquals("access-token-1", credentialStore.load(account)!!.tokens.access)
    }
}
