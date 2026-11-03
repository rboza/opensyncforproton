package dev.rboza.android.opensyncforproton.proton

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** docs/protocol.md § Content fingerprints. */
class ContactFingerprintTest {
    private val id = ProtonContactId("id-1")
    private val cards = listOf(CardDto(type = 2, data = "ab", signature = "sig"), CardDto(type = 0, data = "c", signature = null))

    private fun fingerprint(cards: List<CardDto>, id: ProtonContactId = this.id) = contactFingerprint(id, cards)

    @Test
    fun `protocol md's known answer`() {
        assertEquals(
            "contact-fp-v1:ebf802db1d0e3db7e30ae959c445c8944aa9246556f89562f0aced6c5844f2f6",
            fingerprint(cards).value,
        )
    }

    @Test
    fun `the same cards give the same fingerprint`() {
        assertEquals(fingerprint(cards), fingerprint(cards.map { it.copy() }))
    }

    @Test
    fun `any change to the ID, a Type, a Data, a Signature, or the order changes it`() {
        val original = fingerprint(cards)
        val changed = listOf(
            fingerprint(cards, ProtonContactId("id-2")),
            fingerprint(listOf(cards[0].copy(type = 3), cards[1])),
            fingerprint(listOf(cards[0].copy(data = "ac"), cards[1])),
            fingerprint(listOf(cards[0].copy(signature = "sih"), cards[1])),
            fingerprint(listOf(cards[0].copy(signature = null), cards[1])),
            fingerprint(listOf(cards[0], cards[1].copy(signature = ""))),
            fingerprint(cards.reversed()),
            fingerprint(cards.take(1)),
            fingerprint(emptyList()),
        )
        changed.forEachIndexed { index, it -> assertNotEquals(original, it, "case $index") }
        assertEquals(changed.size, changed.toSet().size, "every change gives its own fingerprint")
    }

    @Test
    fun `strings are length-framed, so moving a boundary changes it`() {
        assertNotEquals(
            fingerprint(listOf(CardDto(0, "ab", null), CardDto(0, "c", null))),
            fingerprint(listOf(CardDto(0, "a", null), CardDto(0, "bc", null))),
        )
        assertNotEquals(fingerprint(listOf(CardDto(0, "ab", "c"))), fingerprint(listOf(CardDto(0, "a", "bc"))))
        assertNotEquals(fingerprint(cards, ProtonContactId("id-1a")), fingerprint(cards, ProtonContactId("id-1")))
    }

    @Test
    fun `JSON key order and a missing or null Signature don't matter`() {
        val cardList = ListSerializer(CardDto.serializer())
        val one = protonJson.decodeFromString(cardList, """[{"Type":2,"Data":"ab","Signature":"sig"},{"Type":0,"Data":"c"}]""")
        val other = protonJson.decodeFromString(cardList, """[{"Signature":"sig","Data":"ab","Type":2},{"Data":"c","Signature":null,"Type":0}]""")

        assertEquals(fingerprint(cards), fingerprint(one))
        assertEquals(fingerprint(cards), fingerprint(other))
    }

    @Test
    fun `the captured export and detail cards parse and fingerprint`() {
        val export = body("contacts-export").getValue("Contacts").jsonArray.map { it.jsonObject }
        val detail = body("contact").getValue("Contact").jsonObject
        for (contact in export + detail) {
            val parsed = protonJson.decodeFromJsonElement(ListSerializer(CardDto.serializer()), contact.getValue("Cards"))
            assertTrue(parsed.isNotEmpty())
            val value = fingerprint(parsed, ProtonContactId(contact.getValue("ID").jsonPrimitive.content)).value
            assertTrue(Regex("contact-fp-v1:[0-9a-f]{64}").matches(value), value)
        }
    }

    @Test
    fun `a card shows none of its content`() {
        assertEquals("CardDto(redacted)", CardDto(2, "BEGIN:VCARD", "sig").toString())
    }

    private fun body(fixture: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/$fixture.json")).readText()).jsonObject.getValue("body").jsonObject
}
