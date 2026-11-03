package dev.rboza.android.opensyncforproton.crypto

import me.proton.core.crypto.android.pgp.GOpenPGPCrypto
import me.proton.core.crypto.common.pgp.VerificationTime

/** A contact card as Proton stores it (docs/protocol.md § Contact cards); plain data, never a DTO. */
data class EncryptedCard(val type: Int, val data: String, val signature: String?) {
    override fun toString() = "EncryptedCard(type=$type)"
}

/** A card's vCard text: decrypted for types 1 and 3, as stored for 0 and 2. */
data class PlainCard(val type: Int, val text: String) {
    override fun toString() = "PlainCard(type=$type)"
}

interface ItemCrypto {
    /** All or nothing: any failure rejects the whole contact. */
    fun decryptContact(keys: UnlockedKeys, cards: List<EncryptedCard>): CryptoResult<List<PlainCard>>

    /**
     * All or nothing, with the active primary user key. A type other than 0, 2, or 3 throws [IllegalArgumentException].
     * Rejected with [RejectReason.NO_PRIMARY_KEY] or [RejectReason.ENCRYPT_OR_SIGN_FAILED], making no cards.
     */
    fun encryptContact(keys: UnlockedKeys, cards: List<PlainCard>): CryptoResult<List<EncryptedCard>>
}

/**
 * Reads cards as WebClients `545d4d5` (packages/shared/lib/contacts/decrypt.ts) does, except that a signature that
 * doesn't verify rejects the contact instead of showing it with a warning (D8). Signatures are checked against the
 * unlocked user keys only, ignoring time: a card signed by a key that has since expired still verifies. Cards are
 * written as WebClients `2bc4e70` (packages/shared/lib/contacts/encrypt.ts) writes them.
 */
class GoItemCrypto internal constructor(
    private val unarmor: (armored: String) -> ByteArray,
    private val decryptText: (message: String, unlockedKey: ByteArray) -> String,
    private val verifyText: (text: String, signature: String, publicKey: String, trimTrailingSpaces: Boolean) -> Boolean,
    private val encryptText: (text: String, publicKey: String) -> String,
    private val signText: (text: String, unlockedKey: ByteArray, trimTrailingSpaces: Boolean) -> String,
) : ItemCrypto {
    private constructor(crypto: GOpenPGPCrypto) : this(
        crypto::getUnarmored,
        crypto::decryptText,
        { text, signature, publicKey, trim -> crypto.verifyText(text, signature, publicKey, VerificationTime.Ignore, trim, null) },
        crypto::encryptText,
        { text, key, trim -> crypto.signText(text, key, trim, null) },
    )

    constructor() : this(GOpenPGPCrypto())

    override fun decryptContact(keys: UnlockedKeys, cards: List<EncryptedCard>): CryptoResult<List<PlainCard>> {
        val userKeys = (keys as GoUnlockedKeys).keys()
        val plain = mutableListOf<PlainCard>()
        val warnings = mutableListOf<CryptoWarning>()
        for (card in cards) {
            val text = when (card.type) {
                CLEAR -> card.data
                ENCRYPTED -> decrypt(card.data, userKeys)?.also { warnings += CryptoWarning.LegacyEncryptedCard }
                    ?: return CryptoResult.Rejected(RejectReason.CORRUPT_DATA)
                // Verified with trailing spaces stripped from each line; Data keeps them.
                SIGNED -> card.data.takeIf { verified(it, card.signature, userKeys, trimTrailingSpaces = true) }
                    ?: return CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)
                ENCRYPTED_AND_SIGNED -> {
                    val decrypted = decrypt(card.data, userKeys) ?: return CryptoResult.Rejected(RejectReason.CORRUPT_DATA)
                    // Over the plaintext, no stripping; verified as text, which absorbs the library's LF endings.
                    decrypted.takeIf { verified(it, card.signature, userKeys, trimTrailingSpaces = false) }
                        ?: return CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)
                }
                else -> return CryptoResult.Rejected(RejectReason.CORRUPT_DATA)
            }
            plain += PlainCard(card.type, text)
        }
        return CryptoResult.Success(plain, warnings.distinct())
    }

    /**
     * Type 0 as given, unsigned; type 2 as given, with a detached text-mode signature over the text with trailing
     * spaces stripped from each line; type 3 encrypted, with a detached text-mode signature over the plaintext. Both
     * are made with the active primary user key, found by its flags and never by its place in the list. All or nothing:
     * any failure makes no cards, so there's never a cleartext fallback. The app writes no other type (a new contact
     * gets types 2 and 3), so any other is the caller's mistake.
     */
    override fun encryptContact(keys: UnlockedKeys, cards: List<PlainCard>): CryptoResult<List<EncryptedCard>> {
        val userKeys = (keys as GoUnlockedKeys).keys()
        require(cards.all { it.type == CLEAR || it.type == SIGNED || it.type == ENCRYPTED_AND_SIGNED }) { "the app writes types 0, 2, and 3 only" }
        val primary = userKeys.filter { it.active && it.primary }.singleOrNull() ?: return CryptoResult.Rejected(RejectReason.NO_PRIMARY_KEY)
        val written = try {
            cards.map { card ->
                when (card.type) {
                    CLEAR -> EncryptedCard(CLEAR, card.text, null)
                    SIGNED -> EncryptedCard(SIGNED, card.text, signText(card.text, primary.key.value, true))
                    else -> EncryptedCard(ENCRYPTED_AND_SIGNED, encryptText(card.text, primary.publicKey), signText(card.text, primary.key.value, false))
                }
            }
        } catch (_: Exception) {
            // The library's message is dropped (D23).
            return CryptoResult.Rejected(RejectReason.ENCRYPT_OR_SIGN_FAILED)
        }
        return CryptoResult.Success(written)
    }

    /**
     * The text from the first user key that decrypts [message], or null if it isn't a message encrypted to keys or no
     * key opens it; the library's messages are dropped (D23).
     */
    private fun decrypt(message: String, userKeys: List<UnlockedUserKey>): String? {
        val packets = try {
            unarmor(message)
        } catch (_: Exception) {
            return null
        }
        if (!isEncryptedToKeys(packets)) return null
        return userKeys.firstNotNullOfOrNull { key ->
            try {
                decryptText(message, key.key.value)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun verified(text: String, signature: String?, userKeys: List<UnlockedUserKey>, trimTrailingSpaces: Boolean): Boolean {
        // An empty one fails like any other that doesn't verify.
        if (signature == null) return false
        return userKeys.any { key ->
            try {
                verifyText(text, signature, key.publicKey, trimTrailingSpaces)
            } catch (_: Exception) {
                false
            }
        }
    }

    private companion object {
        /** `Type` is a bit field: encrypted = 1, signed = 2. */
        const val CLEAR = 0
        const val ENCRYPTED = 1
        const val SIGNED = 2
        const val ENCRYPTED_AND_SIGNED = 3
    }
}
