package dev.rboza.android.opensyncforproton.proton

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** `<tag>:<64 hex digits>`, as `contact_map` stores it. */
@JvmInline value class Fingerprint(val value: String)

/**
 * docs/protocol.md § Content fingerprints, `contact-fp-v1`: SHA-256 over the tag, the contact ID, and each card in
 * response order (its `Type`, `Data`, and `Signature` with a presence byte). Strings are length-framed, so no two
 * inputs run together. Only the cards are hashed, never re-serialized JSON or decrypted text. Pure, so any thread;
 * it doesn't fail.
 */
fun contactFingerprint(id: ProtonContactId, cards: List<CardDto>): Fingerprint {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        fun string(value: String) {
            val utf8 = value.toByteArray(Charsets.UTF_8)
            out.writeLong(utf8.size.toLong())
            out.write(utf8)
        }
        string(FINGERPRINT_TAG)
        string(id.value)
        for (card in cards) {
            out.writeLong(card.type.toLong())
            string(card.data)
            if (card.signature == null) {
                out.writeByte(0)
            } else {
                out.writeByte(1)
                string(card.signature)
            }
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    return Fingerprint("$FINGERPRINT_TAG:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
}

/** Changing what's hashed means a new tag and a one-time re-baseline. */
private const val FINGERPRINT_TAG = "contact-fp-v1"
