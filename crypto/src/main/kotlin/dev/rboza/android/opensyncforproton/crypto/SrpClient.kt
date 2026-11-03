package dev.rboza.android.opensyncforproton.crypto

import com.proton.gopenpgp.srp.Srp
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import me.proton.core.crypto.android.pgp.GOpenPGPCrypto

/**
 * Login's SRP proofs and each key's passphrase (docs/protocol.md § Login, § Keys), through Proton's own SRP code (D6).
 * The caller owns the password arrays and zeroes them; the client zeroes every copy it makes. Copies inside Go can't be
 * wiped. Every call throws [SrpException] on any failure.
 */
interface SrpClient {
    /** Verifies the signed modulus first: a bad signature throws before any proof exists. */
    fun proofs(version: Int, username: String, password: CharArray, salt: String, signedModulus: String, serverEphemeral: String): SrpProofs

    fun keyPassphrase(password: CharArray, keySalt: String): ByteArray
}

/** Standard Base64, as A2 sends them and compares `ServerProof`. */
data class SrpProofs(val clientEphemeral: String, val clientProof: String, val expectedServerProof: String) {
    override fun toString() = "SrpProofs(redacted)"
}

/** Any SRP failure. The library's message is dropped (D23): it can describe the inputs. */
class SrpException : Exception()

internal class RawSrpProofs(val clientEphemeral: ByteArray, val clientProof: ByteArray, val expectedServerProof: ByteArray)

private const val SRP_BITS = 2048L

class GoSrpClient internal constructor(
    private val goProofs: (
        version: Long, username: String, password: ByteArray, salt: String, signedModulus: String, serverEphemeral: String,
    ) -> RawSrpProofs,
    private val goKeyPassphrase: (password: ByteArray, keySalt: String) -> ByteArray,
) : SrpClient {
    constructor() : this(::goProofs, ::goKeyPassphrase)

    override fun proofs(
        version: Int, username: String, password: CharArray, salt: String, signedModulus: String, serverEphemeral: String,
    ): SrpProofs {
        // go-srp v0.0.7 panics on an empty signed modulus (a nil block), and a Go panic ends the process.
        if (signedModulus.isEmpty()) throw SrpException()
        val raw = withUtf8(password) { goProofs(version.toLong(), username, it, salt, signedModulus, serverEphemeral) }
        if (raw.clientEphemeral.isEmpty() || raw.clientProof.isEmpty() || raw.expectedServerProof.isEmpty()) throw SrpException()
        val base64 = Base64.getEncoder()
        return SrpProofs(
            base64.encodeToString(raw.clientEphemeral),
            base64.encodeToString(raw.clientProof),
            base64.encodeToString(raw.expectedServerProof),
        )
    }

    override fun keyPassphrase(password: CharArray, keySalt: String): ByteArray =
        withUtf8(password) { goKeyPassphrase(it, keySalt) }.also { if (it.isEmpty()) throw SrpException() }
}

/** Runs [block] on a UTF-8 copy of [password] (go-srp takes Go's `[]byte(password)`), zeroing every copy after. */
private inline fun <T> withUtf8(password: CharArray, block: (ByteArray) -> T): T {
    val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    // Sized for the worst case (3 bytes per UTF-16 unit), so the encoder never grows it and leaves a partial copy.
    val buffer = ByteBuffer.allocate(password.size * 3)
    var bytes = ByteArray(0)
    try {
        val input = CharBuffer.wrap(password)
        val encoded = encoder.encode(input, buffer, true)
        val flushed = encoder.flush(buffer)
        if (encoded.isError || encoded.isOverflow || flushed.isError || flushed.isOverflow || input.hasRemaining()) {
            throw SrpException()
        }
        bytes = buffer.array().copyOf(buffer.position())
        return block(bytes)
    } catch (e: SrpException) {
        throw e
    } catch (e: Exception) {
        throw SrpException()
    } finally {
        buffer.array().fill(0)
        bytes.fill(0)
    }
}

private fun goProofs(
    version: Long, username: String, password: ByteArray, salt: String, signedModulus: String, serverEphemeral: String,
): RawSrpProofs {
    val proofs = Srp.newAuth(version, username, password, salt, signedModulus, serverEphemeral).generateProofs(SRP_BITS)
    return RawSrpProofs(proofs.clientEphemeral, proofs.clientProof, proofs.expectedServerProof)
}

/** Proton's derivation: go-srp `MailboxPassword`, keeping the hash after bcrypt's `$2y$10$<salt>` prefix. */
private fun goKeyPassphrase(password: ByteArray, keySalt: String): ByteArray = GOpenPGPCrypto().getPassphrase(password, keySalt)
