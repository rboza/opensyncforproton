package dev.rboza.android.opensyncforproton.crypto

import java.util.Base64
import me.proton.core.crypto.common.pgp.UnlockedKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * What `KeyHierarchy` decides around Proton's library (docs/protocol.md § Keys: user keys only; the unlock needs the
 * active primary key, and any other key that fails is left out). Stand-ins replace the library's unarmor, public key,
 * and unlock; the key bytes are the committed fixtures', so the packet checks see real keys.
 */
class KeyHierarchyTest {
    private val keys = fixture("keys.json")
    private val unsupported = fixture("unsupported-keys.json").obj("keys").list()
        .associate { it.obj("name") as String to it.obj("armoredPrivateKey") as String }
    private val primary = userKey(0)
    private val inactive = userKey(1)

    private fun fixture(name: String) = parseJson(java.io.File(System.getProperty("crypto.fixtures"), name).readText())

    private fun userKey(index: Int) = keys.obj("userKeys").list()[index].let {
        LockedUserKey(it.obj("id") as String, it.obj("armoredPrivateKey") as String, it.obj("active") as Boolean, it.obj("primary") as Boolean)
    }

    private val passphrases = mapOf(primary.id to "pp-1".encodeToByteArray(), inactive.id to "pp-2".encodeToByteArray())

    /** A stand-in for the library's unlocked key: closing it zeroes its bytes, as GOpenPGPUnlockedKey does. */
    private class StandInKey(override val value: ByteArray) : UnlockedKey {
        var closes = 0
        override fun close() {
            closes++
            value.fill(0)
        }
    }

    private val unlocked = mutableListOf<Pair<String, StandInKey>>()

    /** Armored keys whose unlock throws, as the library does for a wrong passphrase. */
    private val failing = mutableSetOf<String>()

    /** Armored keys the library can't parse, before any passphrase is involved. */
    private val unparseable = mutableSetOf<String>()

    private val hierarchy = GoKeyHierarchy(
        unarmor = ::unarmor,
        publicKey = { armored -> if (armored in unparseable) throw RuntimeException("SECRET library message") else "public of $armored" },
        unlock = { armored, passphrase ->
            if (armored in failing) throw RuntimeException("SECRET library message")
            StandInKey(passphrase.copyOf()).also { unlocked += armored to it }
        },
    )

    /** OpenPGP ASCII armor: the Base64 between the blank line and the checksum or footer. */
    private fun unarmor(armored: String): ByteArray {
        // The library throws on text that isn't armor.
        require(armored.startsWith("-----BEGIN PGP")) { "not armor" }
        val body = armored.lines().dropWhile { it.isNotBlank() }.drop(1).takeWhile { !it.startsWith("=") && !it.startsWith("-----") }
        return Base64.getDecoder().decode(body.joinToString(""))
    }

    private fun armor(bytes: ByteArray) =
        "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\n${Base64.getEncoder().encodeToString(bytes)}\n-----END PGP PRIVATE KEY BLOCK-----\n"

    private fun unlock(vararg userKeys: LockedUserKey, passphrases: Map<String, ByteArray> = this.passphrases) =
        hierarchy.unlock(LockedKeyBundle(userKeys.toList()), passphrases)

    private fun <T> success(result: CryptoResult<T>) = (result as? CryptoResult.Success) ?: error("expected success: $result")

    private fun UnlockedKeys.ids() = (this as GoUnlockedKeys).keys().map { it.id }

    @Test
    fun `every user key unlocks, the primary first`() {
        val result = success(unlock(inactive, primary))

        assertEquals(listOf(primary.id, inactive.id), result.value.ids())
        assertEquals(emptyList<CryptoWarning>(), result.warnings)
        assertEquals(listOf(primary.armoredPrivateKey, inactive.armoredPrivateKey), unlocked.map { it.first })
    }

    @Test
    fun `each unlocked key keeps its public part`() {
        val keys = success(unlock(primary, inactive)).value as GoUnlockedKeys

        assertEquals(listOf(primary, inactive).map { "public of ${it.armoredPrivateKey}" }, keys.keys().map { it.publicKey })
    }

    // Writes sign with the active primary key, found by these flags, never by its place in the list.
    @Test
    fun `each unlocked key keeps whether it's active and primary`() {
        val activeOther = LockedUserKey(inactive.id, inactive.armoredPrivateKey, active = true, primary = false)

        assertEquals(listOf(true to true, false to false), (success(unlock(inactive, primary)).value as GoUnlockedKeys).keys().map { it.active to it.primary })
        assertEquals(listOf(true to true, true to false), (success(unlock(activeOther, primary)).value as GoUnlockedKeys).keys().map { it.active to it.primary })
    }

    @Test
    fun `another key that fails is left out with its reason`() {
        failing += inactive.armoredPrivateKey

        val result = success(unlock(primary, inactive))

        assertEquals(listOf(primary.id), result.value.ids())
        assertEquals(listOf(CryptoWarning.KeyLeftOut(inactive.id, RejectReason.WRONG_PASSPHRASE)), result.warnings)
    }

    @Test
    fun `another key without a passphrase is left out, unlocked never tried`() {
        val result = success(unlock(primary, inactive, passphrases = mapOf(primary.id to "pp-1".encodeToByteArray())))

        assertEquals(listOf(CryptoWarning.KeyLeftOut(inactive.id, RejectReason.NO_PASSPHRASE)), result.warnings)
        assertEquals(listOf(primary.armoredPrivateKey), unlocked.map { it.first })
    }

    @Test
    fun `the primary failing rejects the unlock, and nothing else is unlocked`() {
        failing += primary.armoredPrivateKey

        assertEquals(CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE), unlock(primary, inactive))
        assertEquals(emptyList<Pair<String, StandInKey>>(), unlocked.toList())
    }

    @Test
    fun `the primary without a passphrase rejects the unlock`() {
        assertEquals(CryptoResult.Rejected(RejectReason.NO_PASSPHRASE), unlock(primary, inactive, passphrases = emptyMap()))
    }

    @Test
    fun `no single active primary key rejects the unlock`() {
        val notPrimary = LockedUserKey(primary.id, primary.armoredPrivateKey, active = true, primary = false)
        val inactivePrimary = LockedUserKey(primary.id, primary.armoredPrivateKey, active = false, primary = true)
        val secondPrimary = LockedUserKey(inactive.id, inactive.armoredPrivateKey, active = true, primary = true)

        for (bundle in listOf(listOf(notPrimary, inactive), listOf(inactivePrimary, inactive), listOf(primary, secondPrimary), emptyList())) {
            assertEquals(CryptoResult.Rejected(RejectReason.NO_PRIMARY_KEY), unlock(*bundle.toTypedArray()), bundle.toString())
        }
        assertEquals(emptyList<Pair<String, StandInKey>>(), unlocked.toList())
    }

    // Refused from the key's own packets, before the library is asked; "post-quantum" only when an ML-DSA, SLH-DSA,
    // or ML-KEM algorithm is actually there.
    @ParameterizedTest
    @CsvSource(
        "v6_ed25519_rfc9580, V6_KEY",
        "v4_eddsa_mlkem768_pqc_draft, POST_QUANTUM_KEY",
        "v6_mldsa65_mlkem768_pqc_draft, POST_QUANTUM_KEY",
    )
    fun `v6 and post-quantum keys are refused before unlock`(name: String, reason: RejectReason) {
        val refused = LockedUserKey("refused", unsupported.getValue(name), active = true, primary = true)
        val other = LockedUserKey("refused-other", unsupported.getValue(name), active = false, primary = false)
        val withRefused = passphrases + mapOf("refused" to byteArrayOf(1), "refused-other" to byteArrayOf(1))

        assertEquals(CryptoResult.Rejected(reason), unlock(refused, passphrases = withRefused))
        assertEquals(listOf(CryptoWarning.KeyLeftOut("refused-other", reason)), success(unlock(primary, other, passphrases = withRefused)).warnings)
        assertTrue(unlocked.none { it.first == refused.armoredPrivateKey })
    }

    // Each algorithm ID at and around the post-quantum range, in a key and in a subkey.
    @ParameterizedTest
    @CsvSource("29, false", "30, true", "31, true", "32, true", "33, true", "34, true", "35, true", "36, true", "37, false")
    fun `post-quantum is named only for its own algorithm IDs`(algorithm: Int, postQuantum: Boolean) {
        val expected = if (postQuantum) KeyCheck.Refused(RejectReason.POST_QUANTUM_KEY) else KeyCheck.Ok
        val key = byteArrayOf(0xc5.toByte(), 6, 4, 0, 0, 0, 0, algorithm.toByte())
        val subkey = byteArrayOf(0xc5.toByte(), 6, 4, 0, 0, 0, 0, 22, 0xc7.toByte(), 6, 4, 0, 0, 0, 0, algorithm.toByte())

        assertEquals(expected, checkKeyPackets(key))
        assertEquals(expected, checkKeyPackets(subkey))
    }

    // go-crypto reads public subkey packets (tag 14) inside a private key too, so they're checked like secret ones.
    // A later public key packet (tag 6) too: go-crypto can start another key there.
    @ParameterizedTest
    @CsvSource(
        "0xce, 4, 35, POST_QUANTUM_KEY", "0xce, 6, 27, V6_KEY", "0xce, 5, 22, UNSUPPORTED_KEY",
        "0xc6, 4, 35, POST_QUANTUM_KEY", "0xc6, 6, 27, V6_KEY",
    )
    fun `a public subkey or later public key inside a private key is checked too`(header: String, version: Int, algorithm: Int, reason: RejectReason) {
        val tag = header.removePrefix("0x").toInt(16).toByte()
        val key = byteArrayOf(0xc5.toByte(), 6, 4, 0, 0, 0, 0, 22, tag, 6, version.toByte(), 0, 0, 0, 0, algorithm.toByte())
        assertEquals(KeyCheck.Refused(reason), checkKeyPackets(key))
    }

    @Test
    fun `key packets are read in both header formats and every length form`() {
        val v4 = unarmor(primary.armoredPrivateKey)
        assertEquals(KeyCheck.Ok, checkKeyPackets(v4))
        for (octets in listOf(1, 2, 4)) assertEquals(KeyCheck.Ok, checkKeyPackets(oldFormat(v4, octets)), "legacy, $octets-octet lengths")
        assertEquals(KeyCheck.Ok, checkKeyPackets(withFiveOctetLengths(v4)))
    }

    // A length near Int.MAX_VALUE must not wrap the bounds check (new five-octet and legacy four-octet forms).
    @Test
    fun `a huge length is corrupt data, not a crash`() {
        val key = byteArrayOf(0xc5.toByte(), 6, 4, 0, 0, 0, 0, 22)
        val huge = listOf(
            key + byteArrayOf(0xcd.toByte(), 0xff.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 1),
            byteArrayOf(0xc5.toByte(), 0xff.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 4, 0, 0, 0, 0, 22),
            // at = 5, so this length wraps at + length past Int.MAX_VALUE.
            byteArrayOf(0x96.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 4, 0, 0, 0, 0, 22),
            byteArrayOf(0xc5.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 4, 0, 0, 0, 0, 22),
        )
        for ((index, bytes) in huge.withIndex()) assertEquals(KeyCheck.Refused(RejectReason.CORRUPT_DATA), checkKeyPackets(bytes), "case $index")
    }

    @Test
    fun `malformed key bytes are corrupt data`() {
        val v4 = unarmor(primary.armoredPrivateKey)
        val cases = mapOf(
            "empty" to ByteArray(0),
            "truncated" to v4.copyOf(v4.size - 1),
            "header only" to v4.copyOf(1),
            "not a packet" to byteArrayOf(0x05, 0x01, 0x02),
            "partial length" to byteArrayOf(0xc5.toByte(), 0xe1.toByte(), 4, 0, 0, 0, 0, 22),
            "indeterminate old length" to byteArrayOf(0x97.toByte(), 4, 0, 0, 0, 0, 22),
            "public key first" to byteArrayOf(0xc6.toByte(), 6, 4, 0, 0, 0, 0, 22),
            "key packet too short" to byteArrayOf(0xc5.toByte(), 3, 4, 0, 0),
        )
        for ((name, bytes) in cases) assertEquals(KeyCheck.Refused(RejectReason.CORRUPT_DATA), checkKeyPackets(bytes), name)
    }

    @Test
    fun `a version other than 4 or 6 is unsupported`() {
        val v5 = byteArrayOf(0xc5.toByte(), 6, 5, 0, 0, 0, 0, 22)
        assertEquals(KeyCheck.Refused(RejectReason.UNSUPPORTED_KEY), checkKeyPackets(v5))
    }

    @Test
    fun `a key the library can't parse is unsupported, not a wrong passphrase`() {
        unparseable += primary.armoredPrivateKey
        unparseable += inactive.armoredPrivateKey

        assertEquals(CryptoResult.Rejected(RejectReason.UNSUPPORTED_KEY), unlock(primary, inactive))
        unparseable -= primary.armoredPrivateKey
        assertEquals(listOf(CryptoWarning.KeyLeftOut(inactive.id, RejectReason.UNSUPPORTED_KEY)), success(unlock(primary, inactive)).warnings)
    }

    @Test
    fun `an unexpected failure after the primary unlocked closes it before it escapes`() {
        val boom = GoKeyHierarchy(
            unarmor = { armored -> if (armored == inactive.armoredPrivateKey) throw OutOfMemoryError("boom") else unarmor(armored) },
            publicKey = { "public" },
            unlock = { _, passphrase -> StandInKey(passphrase.copyOf()).also { unlocked += "k" to it } },
        )

        assertThrows(OutOfMemoryError::class.java) { boom.unlock(LockedKeyBundle(listOf(primary, inactive)), passphrases) }
        assertEquals(1, unlocked.single().second.closes)
    }

    @Test
    fun `a close failure while handling an unexpected one keeps the original, with the close failure suppressed`() {
        val stuck = object : UnlockedKey {
            override val value = byteArrayOf(1)
            override fun close() = throw IllegalStateException("stuck")
        }
        val boom = GoKeyHierarchy(
            unarmor = { armored -> if (armored == inactive.armoredPrivateKey) throw OutOfMemoryError("boom") else unarmor(armored) },
            publicKey = { "public" },
            unlock = { _, _ -> stuck },
        )

        val error = assertThrows(OutOfMemoryError::class.java) { boom.unlock(LockedKeyBundle(listOf(primary, inactive)), passphrases) }

        assertEquals(listOf(IllegalStateException::class.java), error.suppressed.map { it.javaClass })
    }

    @Test
    fun `a key the library can't unarmor is corrupt data`() {
        val broken = LockedUserKey(primary.id, "not armor", active = true, primary = true)
        assertEquals(CryptoResult.Rejected(RejectReason.CORRUPT_DATA), unlock(broken))
    }

    @Test
    fun `closing wipes every unlocked key once, and the keys can't be used after`() {
        val keys = success(unlock(primary, inactive)).value

        keys.close()
        keys.close()

        for ((_, key) in unlocked) {
            assertEquals(1, key.closes)
            assertTrue(key.value.all { it == 0.toByte() })
        }
        assertThrows(IllegalStateException::class.java) { (keys as GoUnlockedKeys).keys() }
    }

    @Test
    fun `a key that fails to close doesn't keep the others open`() {
        val stuck = object : UnlockedKey {
            override val value = byteArrayOf(1)
            override fun close() = throw IllegalStateException("stuck")
        }
        val other = StandInKey(byteArrayOf(2))
        val keys = GoUnlockedKeys(listOf(UnlockedUserKey("a", stuck, "public", true, false), UnlockedUserKey("b", other, "public", true, false)))

        assertThrows(IllegalStateException::class.java) { keys.close() }
        assertEquals(1, other.closes)
    }

    @Test
    fun `every close failure is kept, the later ones suppressed`() {
        fun stuck(message: String) = object : UnlockedKey {
            override val value = byteArrayOf(1)
            override fun close() = throw IllegalStateException(message)
        }
        val keys = GoUnlockedKeys(listOf(UnlockedUserKey("a", stuck("first"), "public", true, false), UnlockedUserKey("b", stuck("second"), "public", true, false)))

        val error = assertThrows(IllegalStateException::class.java) { keys.close() }

        assertEquals("first", error.message)
        assertEquals(listOf("second"), error.suppressed.map { it.message })
    }

    @Test
    fun `nothing shows a key, a passphrase, an ID, or the library's message`() {
        failing += inactive.armoredPrivateKey
        val result = success(unlock(primary, inactive))
        val shown = listOf(result.toString(), result.value.toString(), primary.toString(), LockedKeyBundle(listOf(primary)).toString())

        for (text in shown) {
            assertFalse("SECRET" in text || "BEGIN PGP" in text || primary.id in text || inactive.id in text || "pp-" in text, text)
        }
    }

    /** [v4]'s packets with legacy headers and [octets]-octet lengths (1, 2, or 4); a packet too long for 1 gets 2. */
    private fun oldFormat(v4: ByteArray, octets: Int) = rewrite(v4) { tag, body ->
        val n = body.size
        val size = if (octets == 1 && n > 255) 2 else octets
        val type = mapOf(1 to 0, 2 to 1, 4 to 2).getValue(size)
        val length = (size - 1 downTo 0).map { (n shr (8 * it)).toByte() }.toByteArray()
        byteArrayOf((0x80 or (tag shl 2) or type).toByte()) + length + body
    }

    /** [v4]'s packets with new-format headers and five-octet lengths. */
    private fun withFiveOctetLengths(v4: ByteArray) = rewrite(v4) { tag, body ->
        val n = body.size
        byteArrayOf((0xc0 or tag).toByte(), 0xff.toByte(), (n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) + body
    }

    /** Splits new-format packets (as the fixture keys are) and writes each again with [header]. */
    private fun rewrite(bytes: ByteArray, header: (tag: Int, body: ByteArray) -> ByteArray): ByteArray {
        var out = ByteArray(0)
        var i = 0
        while (i < bytes.size) {
            val tag = bytes[i].toInt() and 0x3f
            val first = bytes[i + 1].toInt() and 0xff
            val (length, headerSize) = when {
                first < 192 -> first to 2
                first < 224 -> (((first - 192) shl 8) + (bytes[i + 2].toInt() and 0xff) + 192) to 3
                else -> error("unexpected length form in the fixture")
            }
            out += header(tag, bytes.copyOfRange(i + headerSize, i + headerSize + length))
            i += headerSize + length
        }
        return out
    }
}
