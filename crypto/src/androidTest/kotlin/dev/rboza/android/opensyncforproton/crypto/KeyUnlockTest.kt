package dev.rboza.android.opensyncforproton.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.proton.core.crypto.android.pgp.GOpenPGPCrypto
import me.proton.core.crypto.common.pgp.PGPHeader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real library unlocking `fixtures/crypto/keys.json`'s user keys (tools/fixture-gen), with each key's passphrase
 * derived from its own salt as docs/protocol.md § Keys says, and refusing `unsupported-keys.json`.
 */
@RunWith(AndroidJUnit4::class)
class KeyUnlockTest {
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name)
        .bufferedReader().use { JSONObject(it.readText()) }

    private val keys = fixture("keys.json")
    private val hierarchy = GoKeyHierarchy()

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private val userKeys = keys.getJSONArray("userKeys").objects().map {
        LockedUserKey(it.getString("id"), it.getString("armoredPrivateKey"), it.getBoolean("active"), it.getBoolean("primary"))
    }
    private val primary = userKeys.single { it.primary }
    private val inactive = userKeys.single { !it.active }

    /** Each user key's passphrase from the login password and the salt under the key's own ID. */
    private fun passphrases(): Map<String, ByteArray> {
        val salts = keys.getJSONArray("keySalts").objects().associate { it.getString("id") to it.getString("keySalt") }
        val client = GoSrpClient()
        return userKeys.associate { it.id to client.keyPassphrase(keys.getString("loginPassword").toCharArray(), salts.getValue(it.id)) }
    }

    private fun unlock(userKeys: List<LockedUserKey> = this.userKeys, passphrases: Map<String, ByteArray> = passphrases()) =
        hierarchy.unlock(LockedKeyBundle(userKeys), passphrases)

    private fun success(result: CryptoResult<UnlockedKeys>) =
        result as? CryptoResult.Success ?: throw AssertionError("expected success: $result")

    @Test
    fun unlock_user_keys() {
        val result = success(unlock())

        result.value.use { keys -> assertEquals(listOf(primary.id, inactive.id), (keys as GoUnlockedKeys).keys().map { it.id }) }
        assertEquals(emptyList<CryptoWarning>(), result.warnings)
    }

    @Test
    fun wrong_passphrase_on_the_primary_rejects() {
        assertEquals(CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE), unlock(passphrases = passphrases() + (primary.id to "wrong".encodeToByteArray())))
    }

    @Test
    fun wrong_passphrase_on_another_key_leaves_it_out() {
        val result = success(unlock(passphrases = passphrases() + (inactive.id to "wrong".encodeToByteArray())))

        result.value.use { keys -> assertEquals(listOf(primary.id), (keys as GoUnlockedKeys).keys().map { it.id }) }
        assertEquals(listOf(CryptoWarning.KeyLeftOut(inactive.id, RejectReason.WRONG_PASSPHRASE)), result.warnings)
    }

    @Test
    fun pq_key_refused() {
        val expected = mapOf(
            "v6_ed25519_rfc9580" to RejectReason.V6_KEY,
            "v4_eddsa_mlkem768_pqc_draft" to RejectReason.POST_QUANTUM_KEY,
            "v6_mldsa65_mlkem768_pqc_draft" to RejectReason.POST_QUANTUM_KEY,
        )
        val refused = fixture("unsupported-keys.json").getJSONArray("keys").objects()
        assertEquals(expected.keys, refused.map { it.getString("name") }.toSet())
        for (key in refused) {
            val bundle = listOf(LockedUserKey("refused", key.getString("armoredPrivateKey"), active = true, primary = true))
            assertEquals(key.getString("name"), CryptoResult.Rejected(expected.getValue(key.getString("name"))), unlock(bundle, mapOf("refused" to byteArrayOf(1))))
        }
    }

    // A key the library can't parse is "unsupported", never a wrong passphrase.
    @Test
    fun unparseable_key_is_unsupported() {
        // Unarmored and armored again by the library itself, so only the algorithm octet differs.
        val crypto = GOpenPGPCrypto()
        val bytes = crypto.getUnarmored(primary.armoredPrivateKey)
        // The fixture's first packet has a one-octet length, so its algorithm octet is at 2 + 5.
        assertEquals(0xc5, bytes[0].toInt() and 0xff)
        assertTrue((bytes[1].toInt() and 0xff) < 192)
        bytes[7] = 99
        val armored = crypto.getArmored(bytes, PGPHeader.PrivateKey)

        assertEquals(CryptoResult.Rejected(RejectReason.UNSUPPORTED_KEY), unlock(listOf(LockedUserKey(primary.id, armored, active = true, primary = true))))
    }

    @Test
    fun close_wipes_the_unlocked_keys() {
        val keys = success(unlock()).value as GoUnlockedKeys
        val values = keys.keys().map { it.key.value }
        assertTrue(values.all { value -> value.any { it != 0.toByte() } })

        keys.close()

        assertTrue(values.all { value -> value.all { it == 0.toByte() } })
    }
}
