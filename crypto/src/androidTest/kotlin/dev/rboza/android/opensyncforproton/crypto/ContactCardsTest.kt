package dev.rboza.android.opensyncforproton.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.proton.core.crypto.android.pgp.GOpenPGPCrypto
import me.proton.core.crypto.common.pgp.VerificationTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real library reading `fixtures/crypto/contacts.json` (tools/fixture-gen) with the fixture account's unlocked
 * user keys (docs/protocol.md § Contact cards), and reading back the cards it writes.
 */
@RunWith(AndroidJUnit4::class)
class ContactCardsTest {
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name)
        .bufferedReader().use { JSONObject(it.readText()) }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private val keysFixture = fixture("keys.json")
    private val contacts = fixture("contacts.json").getJSONArray("contacts").objects().associateBy { it.getString("name") }

    private val salts = keysFixture.getJSONArray("keySalts").objects().associate { it.getString("id") to it.getString("keySalt") }

    private fun unlock(userKeys: List<LockedUserKey>): UnlockedKeys {
        val passphrases = userKeys.associate {
            it.id to GoSrpClient().keyPassphrase(keysFixture.getString("loginPassword").toCharArray(), salts.getValue(it.id))
        }
        return (GoKeyHierarchy().unlock(LockedKeyBundle(userKeys), passphrases) as CryptoResult.Success).value
    }

    private val keys: UnlockedKeys = unlock(
        keysFixture.getJSONArray("userKeys").objects().map {
            LockedUserKey(it.getString("id"), it.getString("armoredPrivateKey"), it.getBoolean("active"), it.getBoolean("primary"))
        },
    )

    private val crypto = GoItemCrypto()

    @After
    fun close() = keys.close()

    private fun cards(name: String) = contacts.getValue(name).getJSONArray("cards").objects().map {
        EncryptedCard(it.getInt("type"), it.getString("data"), it.optString("signature").ifEmpty { null })
    }

    private fun read(name: String) = crypto.decryptContact(keys, cards(name))

    /** The library returns LF line endings; the fixture's plaintexts are CRLF. */
    private fun lf(text: String) = text.replace("\r\n", "\n")

    @Test
    fun contact_types_0_to_3() {
        val result = read("all_types") as CryptoResult.Success
        val expected = contacts.getValue("all_types").getJSONArray("plaintexts").let { array -> (0 until array.length()).map { array.getString(it) } }

        assertEquals(listOf(0, 1, 2, 3), result.value.map { it.type })
        // Types 0 and 2 come back as stored (CRLF); the library returns 1 and 3 with LF endings.
        assertEquals(listOf(expected[0], lf(expected[1]), expected[2], lf(expected[3])), result.value.map { it.text })
        assertEquals(listOf(CryptoWarning.LegacyEncryptedCard), result.warnings)
    }

    @Test
    fun signed_only_verifies() {
        assertTrue(read("signed_only").toString(), read("signed_only") is CryptoResult.Success)
    }

    @Test
    fun type2_trailing_spaces() {
        val card = cards("type2_trailing_spaces").single()
        val publicKey = GOpenPGPCrypto().getPublicKey(keysFixture.getJSONArray("userKeys").getJSONObject(0).getString("armoredPrivateKey"))

        val result = read("type2_trailing_spaces") as CryptoResult.Success

        // Data keeps the spaces; only the stripped text verifies.
        assertEquals(card.data, result.value.single().text)
        assertTrue(card.data.lines().any { it.endsWith(" ") })
        assertFalse(GOpenPGPCrypto().verifyText(card.data, card.signature!!, publicKey, VerificationTime.Ignore, false, null))
    }

    @Test
    fun tampered_or_foreign_cards_rejected() {
        val expected = mapOf(
            "type2_data_changed_after_signing" to RejectReason.BAD_SIGNATURE,
            "type3_signed_by_unrelated_key" to RejectReason.BAD_SIGNATURE,
            "type3_encrypted_to_unrelated_key" to RejectReason.CORRUPT_DATA,
            "type3_corrupt_data" to RejectReason.CORRUPT_DATA,
            // Literal data only: the library would "decrypt" these.
            "type1_not_encrypted" to RejectReason.CORRUPT_DATA,
            "type3_not_encrypted" to RejectReason.CORRUPT_DATA,
        )
        for ((name, reason) in expected) assertEquals(name, CryptoResult.Rejected(reason), read(name))
    }

    // Only user keys verify cards.
    @Test
    fun address_signed_card() {
        assertEquals(CryptoResult.Rejected(RejectReason.BAD_SIGNATURE), read("type3_signed_by_address_key"))
    }

    @Test
    fun bad_signature_rejects_whole_contact() {
        val mixed = cards("all_types") + cards("type2_data_changed_after_signing")

        assertEquals(CryptoResult.Rejected(RejectReason.BAD_SIGNATURE), crypto.decryptContact(keys, mixed))
    }

    // The cards the app writes, read back with the same keys.
    @Test
    fun written_cards_read_back() {
        val clear = "BEGIN:VCARD\r\nVERSION:4.0\r\nCATEGORIES:x\r\nEND:VCARD\r\n"
        val signed = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Trailing \t \r\nEMAIL:a@example.invalid\r\nEND:VCARD\r\n"
        val encrypted = "BEGIN:VCARD\r\nVERSION:4.0\r\nNOTE:kept  \r\nEND:VCARD\r\n"

        val written = (crypto.encryptContact(keys, listOf(PlainCard(0, clear), PlainCard(2, signed), PlainCard(3, encrypted))) as CryptoResult.Success).value
        val read = crypto.decryptContact(keys, written) as CryptoResult.Success

        assertEquals(listOf(0, 2, 3), written.map { it.type })
        assertEquals(listOf(clear, signed), written.take(2).map { it.data })
        // Type 3's plaintext keeps its trailing spaces: its signature verifies over the text as it is.
        assertEquals(listOf(clear, signed, lf(encrypted)), read.value.map { it.text })
        assertEquals(emptyList<CryptoWarning>(), read.warnings)
    }

    @Test
    fun written_type2_verifies_only_stripped() {
        val signed = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Trailing \t \r\nEND:VCARD\r\n"
        val card = (crypto.encryptContact(keys, listOf(PlainCard(2, signed))) as CryptoResult.Success).value.single()
        val primary = keysFixture.getJSONArray("userKeys").objects().single { it.getBoolean("primary") }
        val publicKey = GOpenPGPCrypto().getPublicKey(primary.getString("armoredPrivateKey"))

        assertTrue(GOpenPGPCrypto().verifyText(card.data, card.signature!!, publicKey, VerificationTime.Ignore, true, null))
        assertFalse(GOpenPGPCrypto().verifyText(card.data, card.signature!!, publicKey, VerificationTime.Ignore, false, null))
    }

    @Test
    fun written_type3_does_not_open_with_an_unrelated_key() {
        val card = (crypto.encryptContact(keys, listOf(PlainCard(3, "BEGIN:VCARD\r\nVERSION:4.0\r\nNOTE:x\r\nEND:VCARD\r\n"))) as CryptoResult.Success).value
        // The legacy address key opens with the account's key passphrase, and it isn't the user key cards are encrypted to.
        unlock(
            keysFixture.getJSONArray("addressKeys").objects().filter { it.getString("form") == "legacy" }.map {
                LockedUserKey(it.getString("id"), it.getString("armoredPrivateKey"), true, true)
            },
        ).use { assertEquals(CryptoResult.Rejected(RejectReason.CORRUPT_DATA), crypto.decryptContact(it, card)) }
        // The account's other user key doesn't open it either: it's encrypted to the primary key only.
        unlock(
            keysFixture.getJSONArray("userKeys").objects().filter { !it.getBoolean("primary") }.map {
                LockedUserKey(it.getString("id"), it.getString("armoredPrivateKey"), true, true)
            },
        ).use { assertEquals(CryptoResult.Rejected(RejectReason.CORRUPT_DATA), crypto.decryptContact(it, card)) }
    }
}
