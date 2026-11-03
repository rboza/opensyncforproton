package dev.rboza.android.opensyncforproton.crypto

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What `SrpClient` does around Proton's SRP library, with stand-ins for the Go calls (the library is native Android
 * code; the instrumented tests run the real one against the fixture vectors).
 */
class SrpClientTest {
    private val password = "test-only pässwörd 1".toCharArray()
    private val passwordUtf8 = "test-only pässwörd 1".encodeToByteArray()

    /** Bytes whose standard Base64 has `+`, `/`, and padding, so another alphabet or no padding shows. */
    private val ephemeral = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte(), 1)
    private val proof = byteArrayOf(0xfa.toByte(), 0xef.toByte())
    private val serverProof = byteArrayOf(0x3e, 0x3f, 0x7f, 0x80.toByte(), 0x81.toByte())

    private class GoCall(val args: List<Any>, val password: ByteArray, val passwordAtCall: ByteArray)

    private val calls = mutableListOf<GoCall>()

    private fun client(
        proofs: () -> RawSrpProofs = { RawSrpProofs(ephemeral, proof, serverProof) },
        passphrase: () -> ByteArray = { "passphrase".encodeToByteArray() },
    ) = GoSrpClient(
        goProofs = { version, username, pw, salt, modulus, serverEphemeral ->
            calls += GoCall(listOf(version, username, salt, modulus, serverEphemeral), pw, pw.copyOf())
            proofs()
        },
        goKeyPassphrase = { pw, keySalt ->
            calls += GoCall(listOf(keySalt), pw, pw.copyOf())
            passphrase()
        },
    )

    @Test
    fun `proofs hand the library the inputs and the password as UTF-8`() {
        client().proofs(4, "user", password, "c2FsdA==", "signed modulus", "ZXBoZW1lcmFs")

        val call = calls.single()
        assertEquals(listOf(4L, "user", "c2FsdA==", "signed modulus", "ZXBoZW1lcmFs"), call.args)
        assertArrayEquals(passwordUtf8, call.passwordAtCall)
    }

    @Test
    fun `proofs come back as standard Base64`() {
        val proofs = client().proofs(4, "user", password, "c2FsdA==", "signed modulus", "ZXBoZW1lcmFs")

        assertEquals(Base64.getEncoder().encodeToString(ephemeral), proofs.clientEphemeral)
        assertEquals(Base64.getEncoder().encodeToString(proof), proofs.clientProof)
        assertEquals(Base64.getEncoder().encodeToString(serverProof), proofs.expectedServerProof)
        assertEquals("+/+/AQ==", proofs.clientEphemeral)
    }

    @Test
    fun `password_copies_zeroed after proofs, and the caller's array is left alone`() {
        client().proofs(4, "user", password, "c2FsdA==", "signed modulus", "ZXBoZW1lcmFs")

        assertTrue(calls.single().password.all { it == 0.toByte() })
        assertArrayEquals("test-only pässwörd 1".toCharArray(), password)
    }

    @Test
    fun `password_copies_zeroed after a failure too`() {
        assertThrows(SrpException::class.java) {
            client(proofs = { throw Exception("go error naming the modulus") })
                .proofs(4, "user", password, "c2FsdA==", "signed modulus", "ZXBoZW1lcmFs")
        }
        assertThrows(SrpException::class.java) {
            client(passphrase = { throw Exception("go error") }).keyPassphrase(password, "a2V5U2FsdA==")
        }

        calls.forEach { call -> assertTrue(call.password.all { it == 0.toByte() }) }
        assertArrayEquals("test-only pässwörd 1".toCharArray(), password)
    }

    @Test
    fun `a library failure becomes SrpException without its message`() {
        val error = assertThrows(SrpException::class.java) {
            client(proofs = { throw Exception("go error naming the modulus") })
                .proofs(4, "user", password, "c2FsdA==", "tampered modulus", "ZXBoZW1lcmFs")
        }

        assertNull(error.message)
        assertNull(error.cause)
    }

    @Test
    fun `missing proofs are a failure`() {
        for (raw in listOf(
            RawSrpProofs(ByteArray(0), proof, serverProof),
            RawSrpProofs(ephemeral, ByteArray(0), serverProof),
            RawSrpProofs(ephemeral, proof, ByteArray(0)),
        )) {
            assertThrows(SrpException::class.java) {
                client(proofs = { raw }).proofs(4, "user", password, "c2FsdA==", "signed modulus", "ZXBoZW1lcmFs")
            }
        }
    }

    /** go-srp v0.0.7 dereferences a nil block for an empty signed modulus: a Go panic, which kills the app. */
    @Test
    fun `an empty signed modulus fails before the library is called`() {
        assertThrows(SrpException::class.java) {
            client().proofs(4, "user", password, "c2FsdA==", "", "ZXBoZW1lcmFs")
        }
        assertTrue(calls.isEmpty())
    }

    /** A lone surrogate has no UTF-8 form; Proton's clients would send `?` or U+FFFD, so it fails closed. */
    @Test
    fun `a password with a lone surrogate fails before the library is called`() {
        val broken = charArrayOf('a', '\uD800', 'b')
        assertThrows(SrpException::class.java) {
            client().proofs(4, "user", broken, "c2FsdA==", "signed modulus", "ZXBoZW1lcmFs")
        }
        assertThrows(SrpException::class.java) { client().keyPassphrase(broken, "a2V5U2FsdA==") }
        assertTrue(calls.isEmpty())
    }

    /** Characters of three and four UTF-8 bytes, more than an encoder's first estimate holds. */
    @Test
    fun `a password beyond ASCII and Latin-1 reaches the library intact`() {
        val text = "测试密码测试密码🔑🔑"
        client().proofs(4, "user", text.toCharArray(), "c2FsdA==", "signed modulus", "ZXBoZW1lcmFs")

        assertArrayEquals(text.encodeToByteArray(), calls.single().passwordAtCall)
        assertTrue(calls.single().password.all { it == 0.toByte() })
    }

    @Test
    fun `keyPassphrase hands the library the password as UTF-8 and the salt, and returns its passphrase`() {
        val passphrase = client().keyPassphrase(password, "a2V5U2FsdA==")

        val call = calls.single()
        assertEquals(listOf("a2V5U2FsdA=="), call.args)
        assertArrayEquals(passwordUtf8, call.passwordAtCall)
        assertArrayEquals("passphrase".encodeToByteArray(), passphrase)
    }

    @Test
    fun `password_copies_zeroed after keyPassphrase, and the caller's array is left alone`() {
        client().keyPassphrase(password, "a2V5U2FsdA==")

        assertTrue(calls.single().password.all { it == 0.toByte() })
        assertArrayEquals("test-only pässwörd 1".toCharArray(), password)
    }

    @Test
    fun `an empty passphrase is a failure`() {
        assertThrows(SrpException::class.java) { client(passphrase = { ByteArray(0) }).keyPassphrase(password, "a2V5U2FsdA==") }
    }

    @Test
    fun `proofs don't show in toString`() {
        val proofs = client().proofs(4, "user", password, "c2FsdA==", "signed modulus", "ZXBoZW1lcmFs")

        assertFalse(proofs.toString().contains(proofs.clientEphemeral))
        assertFalse(proofs.toString().contains(proofs.clientProof))
        assertFalse(proofs.toString().contains(proofs.expectedServerProof))
    }
}
