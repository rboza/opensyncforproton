package dev.rboza.android.opensyncforproton.crypto

import me.proton.core.crypto.common.pgp.UnlockedKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * What `ItemCrypto` decides for each card type (docs/protocol.md § Contact cards; WebClients `545d4d5`
 * packages/shared/lib/contacts/decrypt.ts, `2bc4e70` encrypt.ts), with stand-ins for the library's decrypt, verify,
 * encrypt, and sign. Every card must pass, or the whole contact is rejected; writing is all or nothing too.
 */
class ItemCryptoTest {
    private class Key(override val value: ByteArray) : UnlockedKey {
        override fun close() = value.fill(0)
    }

    private fun userKey(id: String, byte: Int, active: Boolean = true, primary: Boolean = false) =
        UnlockedUserKey(id, Key(byteArrayOf(byte.toByte())), "public-$id", active, primary)

    private val keyA = userKey("a", 1, primary = true)
    private val keyB = userKey("b", 2)
    private val keys = GoUnlockedKeys(listOf(keyA, keyB))

    /** "enc(k:text)" decrypts to text with key k only; anything else fails like the library. */
    private val decryptCalls = mutableListOf<Pair<String, Byte>>()

    /** Verifies when the signature is "sig(public:text)" over the text the library would check. */
    private val verifyCalls = mutableListOf<List<Any>>()

    /** A session key packet, then integrity-protected encrypted data, as an encrypted card holds. */
    private val encryptedPackets = byteArrayOf(0xc1.toByte(), 1, 3, 0xd2.toByte(), 1, 1)

    /** Literal data only: nothing encrypted. */
    private val literalPackets = byteArrayOf(0xcb.toByte(), 1, 0x62)

    private val unarmored = mutableListOf<String>()

    /** "enc-to(public:text)"; texts containing "FAIL-ENC" fail like the library. */
    private val encryptCalls = mutableListOf<Pair<String, String>>()

    /** "sig-by(k,trim:text)" for key byte k; texts containing "FAIL-SIGN" fail like the library. */
    private val signCalls = mutableListOf<List<Any>>()

    private val crypto = GoItemCrypto(
        unarmor = { message ->
            unarmored += message
            when {
                message.startsWith("enc(") -> encryptedPackets
                message.startsWith("literal(") -> literalPackets
                else -> throw RuntimeException("SECRET library message")
            }
        },
        decryptText = { message, key ->
            decryptCalls += message to key[0]
            val match = Regex("enc\\((\\d):(.*)\\)", RegexOption.DOT_MATCHES_ALL).matchEntire(message)
            if (match == null || match.groupValues[1].toInt() != key[0].toInt()) throw RuntimeException("SECRET library message")
            match.groupValues[2]
        },
        verifyText = { text, signature, publicKey, trimTrailingSpaces ->
            verifyCalls += listOf(text, signature, publicKey, trimTrailingSpaces)
            val checked = if (trimTrailingSpaces) text.lines().joinToString("\n") { it.trimEnd(' ') } else text
            if (signature == "throws") throw RuntimeException("SECRET library message")
            signature == "sig($publicKey:$checked)"
        },
        encryptText = { text, publicKey ->
            encryptCalls += text to publicKey
            if ("FAIL-ENC" in text) throw RuntimeException("SECRET library message")
            "enc-to($publicKey:$text)"
        },
        signText = { text, key, trimTrailingSpaces ->
            signCalls += listOf(text, key[0], trimTrailingSpaces)
            if ("FAIL-SIGN" in text) throw RuntimeException("SECRET library message")
            "sig-by(${key[0]},$trimTrailingSpaces:$text)"
        },
    )

    private fun decrypt(vararg cards: EncryptedCard) = crypto.decryptContact(keys, cards.toList())

    private fun success(result: CryptoResult<List<PlainCard>>) = result as? CryptoResult.Success ?: error("expected success: $result")

    // Reading.
    @Test
    fun `type 0 is the text as it is, with any signature ignored`() {
        val result = success(decrypt(EncryptedCard(0, "CATEGORIES:x", "anything")))

        assertEquals(listOf(PlainCard(0, "CATEGORIES:x")), result.value)
        assertEquals(emptyList<List<Any>>(), verifyCalls.toList())
    }

    @Test
    fun `type 1 is decrypted with whichever user key opens it, with a legacy warning`() {
        val result = success(decrypt(EncryptedCard(1, "enc(2:NICKNAME:x)", "ignored")))

        assertEquals(listOf(PlainCard(1, "NICKNAME:x")), result.value)
        assertEquals(listOf(CryptoWarning.LegacyEncryptedCard), result.warnings)
        assertEquals(listOf("enc(2:NICKNAME:x)" to 1.toByte(), "enc(2:NICKNAME:x)" to 2.toByte()), decryptCalls.toList())
        assertEquals(emptyList<List<Any>>(), verifyCalls.toList())
    }

    @Test
    fun `type 2 verifies with trailing spaces stripped, against any user key, and keeps Data as it is`() {
        val data = "FN:Spaces  \nEMAIL:x"
        val result = success(decrypt(EncryptedCard(2, data, "sig(public-b:FN:Spaces\nEMAIL:x)")))

        assertEquals(listOf(PlainCard(2, data)), result.value)
        assertEquals(listOf(true, true), verifyCalls.map { it[3] })
        assertEquals(listOf("public-a", "public-b"), verifyCalls.map { it[2] })
    }

    @Test
    fun `type 3 is decrypted, then its plaintext verified without stripping`() {
        val result = success(decrypt(EncryptedCard(3, "enc(1:N:x  )", "sig(public-a:N:x  )")))

        assertEquals(listOf(PlainCard(3, "N:x  ")), result.value)
        assertEquals(listOf(listOf("N:x  ", "sig(public-a:N:x  )", "public-a", false)), verifyCalls.toList())
    }

    @Test
    fun `cards come back in their order`() {
        val result = success(
            decrypt(
                EncryptedCard(3, "enc(1:three)", "sig(public-a:three)"),
                EncryptedCard(0, "zero", null),
                EncryptedCard(2, "two", "sig(public-a:two)"),
            ),
        )

        assertEquals(listOf(3, 0, 2), result.value.map { it.type })
    }

    @ParameterizedTest
    @ValueSource(strings = ["bad", "throws", ""])
    fun `a type 2 signature that doesn't verify, can't be checked, or is empty rejects the whole contact`(signature: String) {
        val result = decrypt(EncryptedCard(0, "zero", null), EncryptedCard(2, "two", signature), EncryptedCard(3, "enc(1:three)", "sig(public-a:three)"))

        assertEquals(CryptoResult.Rejected(RejectReason.BAD_SIGNATURE), result)
    }

    @Test
    fun `a missing signature on type 2 or 3 rejects`() {
        assertEquals(CryptoResult.Rejected(RejectReason.BAD_SIGNATURE), decrypt(EncryptedCard(2, "two", null)))
        assertEquals(CryptoResult.Rejected(RejectReason.BAD_SIGNATURE), decrypt(EncryptedCard(3, "enc(1:three)", null)))
    }

    @Test
    fun `a type 3 signed by another key rejects`() {
        assertEquals(CryptoResult.Rejected(RejectReason.BAD_SIGNATURE), decrypt(EncryptedCard(3, "enc(1:three)", "sig(public-z:three)")))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3])
    fun `a card no user key decrypts is corrupt data`(type: Int) {
        assertEquals(CryptoResult.Rejected(RejectReason.CORRUPT_DATA), decrypt(EncryptedCard(type, "enc(9:x)", "sig(public-a:x)")))
        assertEquals(CryptoResult.Rejected(RejectReason.CORRUPT_DATA), decrypt(EncryptedCard(type, "garbage", "sig(public-a:x)")))
    }

    // A message that holds no encrypted data "decrypts" in the library, so its packets are checked first.
    @ParameterizedTest
    @ValueSource(ints = [1, 3])
    fun `a card that isn't encrypted to a key is corrupt data, never decrypted`(type: Int) {
        assertEquals(CryptoResult.Rejected(RejectReason.CORRUPT_DATA), decrypt(EncryptedCard(type, "literal(N:x)", "sig(public-a:N:x)")))
        assertEquals(emptyList<Pair<String, Byte>>(), decryptCalls.toList())
        assertEquals(listOf("literal(N:x)"), unarmored.toList())
    }

    @Test
    fun `encrypted messages are session key packets, then integrity-protected data`() {
        val pkesk = byteArrayOf(0xc1.toByte(), 3, 3, 0, 0)
        val legacyPkesk = byteArrayOf(0x84.toByte(), 3, 3, 0, 0)
        val seipd = byteArrayOf(0xd2.toByte(), 2, 1, 0)
        val partialSeipd = byteArrayOf(0xd2.toByte(), 0xe1.toByte(), 1, 0, 2, 0, 0)
        val fiveOctetSeipd = byteArrayOf(0xd2.toByte(), 0xff.toByte(), 0, 0, 0, 2, 1, 0)
        for ((name, bytes) in mapOf(
            "one key" to pkesk + seipd,
            "two keys" to pkesk + pkesk + seipd,
            "legacy header" to legacyPkesk + seipd,
            "partial lengths" to pkesk + partialSeipd,
            "several partial chunks" to pkesk + byteArrayOf(0xd2.toByte(), 0xe0.toByte(), 1, 0xe1.toByte(), 0, 0, 1, 0),
            // gopenpgp's usual shape: a partial chunk, then a two-octet final one (here 192 bytes).
            "a two-octet final chunk" to pkesk + byteArrayOf(0xd2.toByte(), 0xe1.toByte(), 1, 0, 0xc0.toByte(), 0) + ByteArray(192),
            "a 64 KiB partial chunk" to pkesk + byteArrayOf(0xd2.toByte(), 0xf0.toByte()) + ByteArray(65536) + byteArrayOf(0),
            "a five-octet final chunk" to pkesk + byteArrayOf(0xd2.toByte(), 0xe1.toByte(), 1, 0, 0xff.toByte(), 0, 0, 0, 1, 0),
            "five-octet length" to pkesk + fiveOctetSeipd,
        )) {
            assertTrue(isEncryptedToKeys(bytes), name)
        }
        for ((name, bytes) in mapOf(
            "empty" to ByteArray(0),
            "literal only" to literalPackets,
            "no session key" to seipd,
            "symmetric session key" to byteArrayOf(0xc3.toByte(), 2, 4, 9) + seipd,
            "a key, then literal data" to pkesk + literalPackets,
            "without integrity protection (tag 9)" to pkesk + byteArrayOf(0xc9.toByte(), 1, 0),
            "AEAD encrypted data (tag 20)" to pkesk + byteArrayOf(0xd4.toByte(), 1, 0),
            "a marker first" to byteArrayOf(0xca.toByte(), 3, 0x50, 0x47, 0x50) + pkesk + seipd,
            "a packet after the data" to pkesk + seipd + literalPackets,
            // The library decrypts the chunks, then reads on into the plain packet after them.
            "a packet after partial-length data" to pkesk + partialSeipd + literalPackets,
            "partial chunks running past the end" to pkesk + byteArrayOf(0xd2.toByte(), 0xe2.toByte(), 1, 0),
            "a final chunk running past the end" to pkesk + byteArrayOf(0xd2.toByte(), 0xe1.toByte(), 1, 0, 5, 0),
            "partial chunks with no final chunk" to pkesk + byteArrayOf(0xd2.toByte(), 0xe1.toByte(), 1, 0),
            "a truncated final chunk length" to pkesk + byteArrayOf(0xd2.toByte(), 0xe1.toByte(), 1, 0, 0xc5.toByte()),
            "a truncated key packet" to byteArrayOf(0xc1.toByte(), 9, 3),
            // Read as zero-length, the rest would be a valid message; a session key packet never has a partial length.
            "a partial length on the key packet" to byteArrayOf(0xc1.toByte(), 0xe1.toByte()) + seipd,
            "a huge length" to pkesk + byteArrayOf(0xd2.toByte(), 0xff.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 1),
            "not a packet" to byteArrayOf(0x01, 0x02),
        )) {
            assertFalse(isEncryptedToKeys(bytes), name)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [4, -1, 7])
    fun `an unknown card type rejects`(type: Int) {
        assertEquals(CryptoResult.Rejected(RejectReason.CORRUPT_DATA), decrypt(EncryptedCard(0, "zero", null), EncryptedCard(type, "x", "y")))
    }

    @Test
    fun `no cards is an empty contact`() {
        assertEquals(CryptoResult.Success(emptyList<PlainCard>()), decrypt())
    }

    @Test
    fun `closed keys can't decrypt`() {
        keys.close()
        assertThrows(IllegalStateException::class.java) { decrypt(EncryptedCard(0, "zero", null)) }
    }

    @Test
    fun `nothing shows card content or the library's message`() {
        val shown = listOf(
            EncryptedCard(2, "FN:SECRET", "SECRET-sig").toString(),
            PlainCard(2, "FN:SECRET").toString(),
            decrypt(EncryptedCard(2, "FN:SECRET", "throws")).toString(),
            success(decrypt(EncryptedCard(1, "enc(1:FN:SECRET)", null))).toString(),
        )
        for (text in shown) assertFalse("SECRET" in text, text)
    }

    // Writing.
    private fun encrypt(vararg cards: PlainCard, keys: UnlockedKeys = this.keys) = crypto.encryptContact(keys, cards.toList())

    private fun encrypted(result: CryptoResult<List<EncryptedCard>>) = result as? CryptoResult.Success ?: error("expected success: $result")

    @Test
    fun `type 2 keeps its text as Data, with a signature by the primary key over the text stripped of trailing spaces`() {
        val text = "BEGIN:VCARD\r\nFN:Spaces \t\r\nEND:VCARD\r\n"

        val result = encrypted(encrypt(PlainCard(2, text)))

        assertEquals(listOf(EncryptedCard(2, text, "sig-by(1,true:$text)")), result.value)
        // The library strips the trailing spaces and tabs (and the CR before LF) when asked to; Data keeps them.
        assertEquals(listOf(listOf(text, 1.toByte(), true)), signCalls.toList())
        assertEquals(emptyList<Pair<String, String>>(), encryptCalls.toList())
    }

    @Test
    fun `type 3 is encrypted to the primary key, with a signature over the plaintext, not stripped`() {
        val text = "BEGIN:VCARD\r\nNOTE:x  \r\nEND:VCARD\r\n"

        val result = encrypted(encrypt(PlainCard(3, text)))

        assertEquals(listOf(EncryptedCard(3, "enc-to(public-a:$text)", "sig-by(1,false:$text)")), result.value)
        assertEquals(listOf(text to "public-a"), encryptCalls.toList())
        assertEquals(listOf(listOf(text, 1.toByte(), false)), signCalls.toList())
    }

    @Test
    fun `type 0 is written as given, unsigned`() {
        val result = encrypted(encrypt(PlainCard(0, "CATEGORIES:x")))

        assertEquals(listOf(EncryptedCard(0, "CATEGORIES:x", null)), result.value)
        assertEquals(emptyList<List<Any>>(), signCalls.toList())
        assertEquals(emptyList<Pair<String, String>>(), encryptCalls.toList())
    }

    @Test
    fun `written cards keep their order, with no warnings`() {
        val result = encrypted(encrypt(PlainCard(3, "three"), PlainCard(0, "zero"), PlainCard(2, "two")))

        assertEquals(listOf(3, 0, 2), result.value.map { it.type })
        assertEquals(emptyList<CryptoWarning>(), result.warnings)
    }

    @Test
    fun `no cards writes nothing`() {
        assertEquals(CryptoResult.Success(emptyList<EncryptedCard>()), encrypt())
    }

    // Never "first in the list": the unlocked keys come primary first, but writes find the primary by its flags.
    @Test
    fun `the active primary key signs and is encrypted to, wherever it is in the list`() {
        val other = userKey("o", 7)
        val primary = userKey("p", 8, primary = true)
        val inactive = userKey("i", 9, active = false)

        val result = encrypted(encrypt(PlainCard(2, "two"), PlainCard(3, "three"), keys = GoUnlockedKeys(listOf(other, inactive, primary))))

        assertEquals(listOf(EncryptedCard(2, "two", "sig-by(8,true:two)"), EncryptedCard(3, "enc-to(public-p:three)", "sig-by(8,false:three)")), result.value)
    }

    @Test
    fun `no active primary key, or more than one, writes nothing`() {
        val cases = mapOf(
            "no primary" to listOf(userKey("o", 7), userKey("q", 8)),
            // An inactive key never encrypts or signs, primary or not.
            "an inactive primary" to listOf(userKey("p", 8, active = false, primary = true), userKey("o", 7)),
            "two active primaries" to listOf(userKey("p", 8, primary = true), userKey("q", 9, primary = true)),
            "no keys" to emptyList(),
        )
        for ((name, keys) in cases) {
            assertEquals(CryptoResult.Rejected(RejectReason.NO_PRIMARY_KEY), encrypt(PlainCard(0, "zero"), PlainCard(2, "two"), PlainCard(3, "three"), keys = GoUnlockedKeys(keys)), name)
        }
        assertEquals(emptyList<List<Any>>(), signCalls.toList())
        assertEquals(emptyList<Pair<String, String>>(), encryptCalls.toList())
    }

    // Never a cleartext fallback: one card failing leaves no cards at all.
    @ParameterizedTest
    @ValueSource(strings = ["two FAIL-SIGN", "three FAIL-SIGN", "three FAIL-ENC"])
    fun `an encrypt or sign that fails writes no cards`(failing: String) {
        val type = if (failing.startsWith("two")) 2 else 3
        val cards = arrayOf(PlainCard(0, "zero"), PlainCard(2, "two"), PlainCard(3, "three"), PlainCard(type, failing), PlainCard(3, "after"))

        assertEquals(CryptoResult.Rejected(RejectReason.ENCRYPT_OR_SIGN_FAILED), encrypt(*cards))
        // Nothing after the failure is tried.
        assertFalse(signCalls.any { it[0] == "after" } || encryptCalls.any { it.first == "after" })
    }

    // The app writes types 0, 2, and 3 only (a new contact gets 2 and 3); anything else is a caller's mistake.
    @ParameterizedTest
    @ValueSource(ints = [1, 4, -1])
    fun `another card type is refused before anything is made`(type: Int) {
        assertThrows(IllegalArgumentException::class.java) { encrypt(PlainCard(2, "two"), PlainCard(type, "x")) }
        assertEquals(emptyList<List<Any>>(), signCalls.toList())
    }

    @Test
    fun `closed keys can't write`() {
        keys.close()
        assertThrows(IllegalStateException::class.java) { encrypt(PlainCard(0, "zero")) }
    }

    @Test
    fun `nothing written shows card content or the library's message`() {
        val shown = listOf(
            encrypt(PlainCard(2, "FN:SECRET"), PlainCard(3, "NOTE:SECRET")).toString(),
            encrypt(PlainCard(3, "SECRET FAIL-ENC")).toString(),
            encrypt(PlainCard(2, "SECRET FAIL-SIGN")).toString(),
        )
        for (text in shown) assertFalse("SECRET" in text, text)
    }
}
