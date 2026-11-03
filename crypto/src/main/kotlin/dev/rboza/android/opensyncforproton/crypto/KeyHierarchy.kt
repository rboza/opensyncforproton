package dev.rboza.android.opensyncforproton.crypto

import me.proton.core.crypto.android.pgp.GOpenPGPCrypto
import me.proton.core.crypto.common.pgp.UnlockedKey

/** A user key as U1 lists it; plain data owned by `:crypto`, never a DTO. */
class LockedUserKey(val id: String, val armoredPrivateKey: String, val active: Boolean, val primary: Boolean) {
    override fun toString() = "LockedUserKey(redacted, active=$active, primary=$primary)"
}

/** The account's locked user keys. Address keys aren't unlocked: contact cards use user keys only. */
class LockedKeyBundle(val userKeys: List<LockedUserKey>) {
    override fun toString() = "LockedKeyBundle(userKeys=${userKeys.size})"
}

/** Why `:crypto` refused something; a fixed set, never the library's text (D23). */
enum class RejectReason {
    BAD_SIGNATURE,
    CORRUPT_DATA,
    WRONG_PASSPHRASE,
    NO_PASSPHRASE,

    /** Not exactly one active primary user key. */
    NO_PRIMARY_KEY,

    /** A key the app can't use for another reason: a version other than 4 or 6, or one the library can't parse. */
    UNSUPPORTED_KEY,

    /** A version 6 key, told apart from [UNSUPPORTED_KEY] so the login can say the key is newer than the app. */
    V6_KEY,

    /** An ML-DSA, SLH-DSA, or ML-KEM key or subkey, positively identified. */
    POST_QUANTUM_KEY,

    /** Writing a contact's cards: the library couldn't encrypt or sign one. */
    ENCRYPT_OR_SIGN_FAILED,
}

sealed interface CryptoWarning {
    data object LegacyEncryptedCard : CryptoWarning

    /** A user key other than the active primary didn't unlock and was left out. */
    data class KeyLeftOut(val keyId: String, val reason: RejectReason) : CryptoWarning {
        override fun toString() = "KeyLeftOut(reason=$reason)"
    }
}

sealed interface CryptoResult<out T> {
    data class Success<T>(val value: T, val warnings: List<CryptoWarning> = emptyList()) : CryptoResult<T>
    data class Rejected(val reason: RejectReason) : CryptoResult<Nothing>
}

/**
 * The account's unlocked user keys, for one sync run. `close()` zeroes the Java key bytes and clears Go's keys; the
 * key bytes Go receives or makes on each decryption or signature can't be wiped. Passing it to [ItemCrypto] after
 * `close()` throws [IllegalStateException]; closing it again does nothing. If a key fails to close, the others are
 * still closed and the first failure is thrown.
 */
interface UnlockedKeys : AutoCloseable

interface KeyHierarchy {
    /**
     * Unlocks each user key with its passphrase (docs/protocol.md § Keys). The active primary key must unlock; any
     * other key that doesn't is left out with a [CryptoWarning.KeyLeftOut]. The caller owns the passphrases and zeroes
     * them; the library copies them into Go's heap, where they can't be wiped.
     */
    fun unlock(bundle: LockedKeyBundle, passphrasesByKeyId: Map<String, ByteArray>): CryptoResult<UnlockedKeys>
}

/**
 * An unlocked user key and its armored public part, which verifies card signatures. Writes encrypt to and sign with the
 * one key that is [active] and [primary].
 */
internal class UnlockedUserKey(val id: String, val key: UnlockedKey, val publicKey: String, val active: Boolean, val primary: Boolean)

internal class GoUnlockedKeys(private val keys: List<UnlockedUserKey>) : UnlockedKeys {
    @Volatile private var closed = false

    fun keys(): List<UnlockedUserKey> {
        check(!closed) { "keys are closed" }
        return keys
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // GOpenPGPUnlockedKey.close zeroes its bytes and clears the Go key's private parameters. One key failing to close
        // doesn't keep the others open.
        var failure: Throwable? = null
        for (key in keys) {
            try {
                key.key.close()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    override fun toString() = "UnlockedKeys(${keys.size})"
}

class GoKeyHierarchy internal constructor(
    private val unarmor: (armored: String) -> ByteArray,
    /** The key's public part: parsed without the passphrase, it also shows whether the library can read the key. */
    private val publicKey: (armored: String) -> String,
    private val unlock: (armored: String, passphrase: ByteArray) -> UnlockedKey,
) : KeyHierarchy {
    private constructor(crypto: GOpenPGPCrypto) : this(crypto::getUnarmored, crypto::getPublicKey, crypto::unlock)

    constructor() : this(GOpenPGPCrypto())

    override fun unlock(bundle: LockedKeyBundle, passphrasesByKeyId: Map<String, ByteArray>): CryptoResult<UnlockedKeys> {
        val primary = bundle.userKeys.filter { it.active && it.primary }.singleOrNull()
            ?: return CryptoResult.Rejected(RejectReason.NO_PRIMARY_KEY)
        val unlocked = mutableListOf<UnlockedUserKey>()
        val warnings = mutableListOf<CryptoWarning>()
        try {
            // The primary first: if it fails, nothing else is unlocked.
            for (key in listOf(primary) + (bundle.userKeys - primary)) {
                when (val result = unlockOne(key, passphrasesByKeyId[key.id])) {
                    is CryptoResult.Success -> unlocked += result.value
                    is CryptoResult.Rejected -> if (key === primary) return result else warnings += CryptoWarning.KeyLeftOut(key.id, result.reason)
                }
            }
        } catch (unexpected: Throwable) {
            // Nothing unlocked outlives a failed unlock; a close failure doesn't hide the original one.
            runCatching { GoUnlockedKeys(unlocked).close() }.exceptionOrNull()?.let(unexpected::addSuppressed)
            throw unexpected
        }
        return CryptoResult.Success(GoUnlockedKeys(unlocked), warnings)
    }

    private fun unlockOne(key: LockedUserKey, passphrase: ByteArray?): CryptoResult<UnlockedUserKey> {
        if (passphrase == null) return CryptoResult.Rejected(RejectReason.NO_PASSPHRASE)
        // The library parses v6 and post-quantum keys and doesn't say which they are, so the packets are read first.
        val bytes = try {
            unarmor(key.armoredPrivateKey)
        } catch (_: Exception) {
            return CryptoResult.Rejected(RejectReason.CORRUPT_DATA)
        }
        val check = checkKeyPackets(bytes)
        if (check is KeyCheck.Refused) return CryptoResult.Rejected(check.reason)
        val public = try {
            publicKey(key.armoredPrivateKey)
        } catch (_: Exception) {
            // A key the library can't parse is "unsupported", never a wrong passphrase.
            return CryptoResult.Rejected(RejectReason.UNSUPPORTED_KEY)
        }
        return try {
            CryptoResult.Success(UnlockedUserKey(key.id, unlock(key.armoredPrivateKey, passphrase), public, key.active, key.primary))
        } catch (_: Exception) {
            // The key parsed, so the library's refusal is the passphrase (or a damaged secret part, which looks the same);
            // its message is dropped (D23).
            CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE)
        }
    }
}

internal sealed interface KeyCheck {
    data object Ok : KeyCheck
    data class Refused(val reason: RejectReason) : KeyCheck
}

/**
 * Reads the version and algorithm octets of every key and subkey packet, secret or public (RFC 9580 § 5.5.2: version, a
 * four-octet creation time, then the algorithm). The first packet must be a secret key. Post-quantum wins over v6, as
 * it names the more specific refusal.
 */
internal fun checkKeyPackets(bytes: ByteArray): KeyCheck {
    var i = 0
    var first = true
    var v6 = false
    var postQuantum = false
    var otherVersion = false
    while (i < bytes.size) {
        // Partial and indeterminate lengths are never used for key packets.
        val header = readPacketHeader(bytes, i) ?: return corrupt()
        val tag = header.tag
        val at = header.bodyStart
        val length = header.length ?: return corrupt()
        if (first && tag != SECRET_KEY) return corrupt()
        // go-crypto also reads public key and subkey packets inside a private key, so those are checked too.
        if (tag in KEY_PACKETS) {
            if (length < 6) return corrupt()
            when (bytes[at].toInt() and 0xff) {
                4 -> Unit
                6 -> v6 = true
                else -> otherVersion = true
            }
            if ((bytes[at + 5].toInt() and 0xff) in POST_QUANTUM_ALGORITHMS) postQuantum = true
        }
        first = false
        i = at + length
    }
    return when {
        first -> corrupt()
        postQuantum -> KeyCheck.Refused(RejectReason.POST_QUANTUM_KEY)
        v6 -> KeyCheck.Refused(RejectReason.V6_KEY)
        otherVersion -> KeyCheck.Refused(RejectReason.UNSUPPORTED_KEY)
        else -> KeyCheck.Ok
    }
}

private fun corrupt() = KeyCheck.Refused(RejectReason.CORRUPT_DATA)

private const val SECRET_KEY = 5

/** Secret key (5), public key (6), secret subkey (7), and public subkey (14) packets. */
private val KEY_PACKETS = setOf(SECRET_KEY, 6, 7, 14)

/**
 * ML-DSA (30, 31), SLH-DSA (32–34), and ML-KEM (35, 36): draft-ietf-openpgp-pqc's algorithm IDs, as
 * github.com/ProtonMail/go-crypto v1.5.2 openpgp/packet/packet.go defines them.
 */
private val POST_QUANTUM_ALGORITHMS = 30..36
