package dev.rboza.android.opensyncforproton.mapping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest

// Each value's key, which pairs a row with its property (D28; docs/mapping.md § Keys). The expected keys are worked out
// here from the formula, apart from the mapper's code. Every value is made up for the test.
class PropertyKeyTest {
    private val mapper: ContactMapper = EzvcardContactMapper()

    @Test
    fun `every value knows the properties it was read from`() {
        val signed = listOf("FN:Ann Lee", "EMAIL;TYPE=work:a@example.test", "UID:urn:uuid:ann-1")
        val encrypted = listOf(
            "N:Lee;Ann;;;",
            "X-PHONETIC-FIRST-NAME:an",
            "X-PHONETIC-LAST-NAME:lee",
            "EMAIL:b@example.test",
            "TEL;TYPE=cell:+1 555 0100",
            "ADR;TYPE=home:;;1 Main St;Town;;12345;",
            "ORG:Acme;R&D",
            "TITLE:Engineer",
            "ROLE:Lead",
            "PHOTO:data:image/jpeg;base64,/9j/4AAQ",
            "BDAY:19900517",
            "ANNIVERSARY:--0601",
            "item1.X-ABDATE:2001-07-02",
            "item1.X-ABLabel:Met",
            "NOTE:hello",
            "URL:https://example.test/ann",
            "NICKNAME:Annie",
            "RELATED;TYPE=spouse:Bob",
            "X-SIP:sip:ann@example.test",
        )

        val keys = keyed(card(CardPlacement.SIGNED, signed), card(CardPlacement.ENCRYPTED, encrypted)).keys

        fun s(line: String) = key(CardPlacement.SIGNED, line)
        fun e(line: String) = key(CardPlacement.ENCRYPTED, line)
        assertEquals(
            GraphKeys(
                name = SourceKey(listOf(s("FN:Ann Lee"), e("N:Lee;Ann;;;"), e("X-PHONETIC-FIRST-NAME:an"), e("X-PHONETIC-LAST-NAME:lee"))),
                emails = listOf(SourceKey(listOf(s("EMAIL;TYPE=work:a@example.test"))), SourceKey(listOf(e("EMAIL:b@example.test")))),
                phones = listOf(SourceKey(listOf(e("TEL;TYPE=cell:+1 555 0100")))),
                addresses = listOf(SourceKey(listOf(e("ADR;TYPE=home:;;1 Main St;Town;;12345;")))),
                organization = SourceKey(listOf(e("ORG:Acme;R&D"), e("TITLE:Engineer"), e("ROLE:Lead"))),
                photo = SourceKey(listOf(e("PHOTO:data:image/jpeg;base64,/9j/4AAQ"))),
                birthday = SourceKey(listOf(e("BDAY:19900517"))),
                anniversary = SourceKey(listOf(e("ANNIVERSARY:--0601"))),
                customDates = listOf(SourceKey(listOf(e("item1.X-ABDATE:2001-07-02")))),
                note = SourceKey(listOf(e("NOTE:hello"))),
                urls = listOf(SourceKey(listOf(e("URL:https://example.test/ann")))),
                nickname = SourceKey(listOf(e("NICKNAME:Annie"))),
                relations = listOf(SourceKey(listOf(e("RELATED;TYPE=spouse:Bob")))),
                sipAddresses = listOf(SourceKey(listOf(e("X-SIP:sip:ann@example.test")))),
            ),
            keys,
        )
    }

    @Test
    fun `a value's key names the property it was read from, not the first of its kind`() {
        val keys = keyed(
            card(
                CardPlacement.SIGNED,
                listOf(
                    "FN:", "FN:Ann", "N:;;;;", "N:Lee;Ann;;;", "X-PHONETIC-MIDDLE-NAME:", "X-PHONETIC-MIDDLE-NAME:bee",
                    "ORG:;", "ORG:Acme", "TITLE:", "TITLE:Engineer", "NOTE:", "NOTE:hello", "NICKNAME:,", "NICKNAME:,Annie",
                    "BDAY;VALUE=text:soon", "BDAY:--0230", "BDAY:--0517", "PHOTO:https://example.test/ann.jpg", "PHOTO:data:image/jpeg;base64,/9j/4AAQ",
                    "EMAIL:", "EMAIL:a@example.test",
                ),
            ),
        ).keys

        fun s(line: String) = SourceKey(listOf(key(CardPlacement.SIGNED, line)))
        assertEquals(SourceKey(listOf(key(CardPlacement.SIGNED, "FN:Ann"), key(CardPlacement.SIGNED, "N:Lee;Ann;;;"), key(CardPlacement.SIGNED, "X-PHONETIC-MIDDLE-NAME:bee"))), keys.name)
        assertEquals(SourceKey(listOf(key(CardPlacement.SIGNED, "ORG:Acme"), key(CardPlacement.SIGNED, "TITLE:Engineer"))), keys.organization)
        assertEquals(s("NOTE:hello"), keys.note)
        assertEquals(s("NICKNAME:,Annie"), keys.nickname)
        assertEquals(s("BDAY:--0517"), keys.birthday)
        assertEquals(s("PHOTO:data:image/jpeg;base64,/9j/4AAQ"), keys.photo)
        assertEquals(listOf(s("EMAIL:a@example.test")), keys.emails)
    }

    @Test
    fun `a name from FN alone, or from N alone, names only that property`() {
        assertEquals(SourceKey(listOf(key(CardPlacement.SIGNED, "FN:Ann"))), keyed(card(CardPlacement.SIGNED, listOf("FN:Ann"))).keys.name)
        assertEquals(SourceKey(listOf(key(CardPlacement.SIGNED, "N:Lee;Ann;;;"))), keyed(card(CardPlacement.SIGNED, listOf("N:Lee;Ann;;;"))).keys.name)
    }

    @Test
    fun `the keys line up with the graph's values`() {
        val contact = keyed(card(CardPlacement.SIGNED, listOf("EMAIL:", "EMAIL:a@example.test", "EMAIL:b@example.test", "TEL:", "TEL:1")))

        assertEquals(2, contact.keys.emails.size)
        assertEquals(contact.graph.emails.size, contact.keys.emails.size)
        assertEquals(1, contact.keys.phones.size)
        assertNull(contact.keys.name)
        assertNull(contact.keys.organization)
        assertNull(contact.keys.note)
    }

    @Test
    fun `keys that don't line up with the graph are refused`() {
        val graph = ContactGraph(uid = null, formattedName = null, emails = listOf(TypedEmail("a@example.test", EmailType.Home)))

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { KeyedGraph(graph, GraphKeys()) }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { KeyedGraph(ContactGraph(uid = null, formattedName = null), GraphKeys(note = SourceKey(listOf("NOTE.0123456789abcdef")))) }
        assertEquals(listOf(null), KeyedGraph(graph).keys.emails)
    }

    @Test
    fun `the same property gives the same key every time`() {
        val first = keyed(
            card(CardPlacement.SIGNED, listOf("FN:Ann", "EMAIL:a@example.test")),
            card(CardPlacement.ENCRYPTED, listOf("TEL:1", "NOTE:hello")),
        ).keys
        // Proton gives the cards in no fixed order, and other properties change around them.
        val again = keyed(
            card(CardPlacement.ENCRYPTED, listOf("URL:https://example.test", "NOTE:hello", "TEL:2", "TEL:1")),
            card(CardPlacement.SIGNED, listOf("EMAIL:a@example.test", "FN:Ann Lee")),
        ).keys

        assertEquals(first.emails.single(), again.emails.single())
        assertEquals(first.phones.single(), again.phones[1])
        assertEquals(first.note, again.note)
        assertNotEquals(first.name, again.name, "an edited FN is another property")
    }

    @Test
    fun `any change to a property's line changes its key`() {
        val keys = listOf("EMAIL:a@example.test", "EMAIL;TYPE=home:a@example.test", "item1.EMAIL:a@example.test", "email:a@example.test", "EMAIL:b@example.test")
            .map { keyed(card(CardPlacement.SIGNED, listOf(it))).keys.emails.single() }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(SourceKey(listOf(key(CardPlacement.SIGNED, "email:a@example.test"))), keys[3], "the name part is in upper case")
    }

    @Test
    fun `the card's placement is part of the key`() {
        val keys = keyed(card(CardPlacement.SIGNED, listOf("NOTE:hello", "EMAIL:a@example.test")), card(CardPlacement.ENCRYPTED, listOf("EMAIL:a@example.test"))).keys

        assertEquals(
            listOf(SourceKey(listOf(key(CardPlacement.SIGNED, "EMAIL:a@example.test"))), SourceKey(listOf(key(CardPlacement.ENCRYPTED, "EMAIL:a@example.test")))),
            keys.emails,
        )
        assertEquals(
            SourceKey(listOf(key(CardPlacement.CLEAR, "NOTE:hello"))),
            keyed(card(CardPlacement.CLEAR, listOf("NOTE:hello"))).keys.note,
        )
    }

    @Test
    fun `two identical properties get different keys`() {
        val keys = keyed(
            card(CardPlacement.ENCRYPTED, listOf("EMAIL:a@example.test", "TEL:1", "EMAIL:a@example.test")),
            card(CardPlacement.ENCRYPTED, listOf("EMAIL:a@example.test")),
        ).keys
        val base = key(CardPlacement.ENCRYPTED, "EMAIL:a@example.test")

        assertEquals(listOf(base, "$base~2", "$base~3").map { SourceKey(listOf(it)) }, keys.emails)
    }

    @Test
    fun `a folded line has the key of its unfolded line`() {
        val folded = "BEGIN:VCARD\r\nVERSION:4.0\r\nNOTE:hel\r\n lo there\r\nEND:VCARD\r\n"

        val keys = keyedCards(CardFragment(CardPlacement.SIGNED, folded)).keys

        assertEquals(SourceKey(listOf(key(CardPlacement.SIGNED, "NOTE:hello there"))), keys.note)
    }

    @Test
    fun `a kind ez-vcard couldn't read every line of has no keys in that card`() {
        // vCard 3.0: ez-vcard keeps an unparseable BDAY as raw text, so its BDAY lines no longer line up one to one.
        val v3 = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ann\r\nBDAY:soon\r\nBDAY:1990-05-17\r\nEND:VCARD\r\n"

        val contact = keyedCards(CardFragment(CardPlacement.SIGNED, v3), card(CardPlacement.ENCRYPTED, listOf("ANNIVERSARY:--0601")))

        assertEquals(PartialDate(5, 17, 1990), contact.graph.birthday)
        assertNull(contact.keys.birthday)
        assertEquals(SourceKey(listOf(key(CardPlacement.SIGNED, "FN:Ann"))), contact.keys.name)
        assertEquals(SourceKey(listOf(key(CardPlacement.ENCRYPTED, "ANNIVERSARY:--0601"))), contact.keys.anniversary)
    }

    @Test
    fun `keys are ASCII, short, and read back from their text`() {
        val contact = keyed(
            card(CardPlacement.SIGNED, listOf("FN:Ånn Lée", "N:Lée;Ånn;Bé;Dr;Jr", "X-PHONETIC-FIRST-NAME:ān", "X-PHONETIC-MIDDLE-NAME:bē", "X-PHONETIC-LAST-NAME:lī")),
            card(CardPlacement.ENCRYPTED, listOf("EMAIL:ü@example.test", "EMAIL:ü@example.test")),
        )
        val all = listOfNotNull(contact.keys.name) + contact.keys.emails.filterNotNull()

        all.forEach { key ->
            assertTrue(key.text.all { it in ' '..'~' }, "printable ASCII")
            assertTrue(key.text.length < 256, "fits Data.SYNC1")
            assertEquals(key, SourceKey.parse(key.text))
        }
        assertEquals(5, contact.keys.name?.properties?.size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "EMAIL", "EMAIL.9f86d081884c7d6", "EMAIL.9f86d081884c7d65x", "EMAIL.9F86D081884C7D65", "email.9f86d081884c7d65", "EMAIL.9f86d081884c7d65~", "EMAIL.9f86d081884c7d65  N.9f86d081884c7d65", "Ä.9f86d081884c7d65"])
    fun `text that isn't a key reads as no key`(text: String) {
        assertNull(SourceKey.parse(text))
    }

    @Test
    fun `key text with several properties and repeats reads back`() {
        val text = "FN.9f86d081884c7d65 N.0123456789abcdef~2"

        assertEquals(SourceKey(listOf("FN.9f86d081884c7d65", "N.0123456789abcdef~2")), SourceKey.parse(text))
        assertNull(SourceKey.parse(null))
    }

    @Test
    fun `no toString shows a key`() {
        val contact = keyed(card(CardPlacement.SIGNED, listOf("FN:Ann", "EMAIL:a@example.test", "NOTE:hello")))
        val hex = Regex("[0-9a-f]{16}")

        listOf(contact.toString(), contact.keys.toString(), contact.keys.name.toString(), contact.keys.emails.toString())
            .forEach { assertFalse(hex.containsMatchIn(it), it) }
        assertFalse(contact.toString().contains("Ann"))
    }

    private fun card(placement: CardPlacement, lines: List<String>) =
        CardFragment(placement, (listOf("BEGIN:VCARD", "VERSION:4.0") + lines + "END:VCARD").joinToString("\r\n", postfix = "\r\n"))

    private fun keyed(vararg cards: CardFragment): KeyedGraph = keyedCards(*cards)

    private fun keyedCards(vararg cards: CardFragment): KeyedGraph =
        assertInstanceOf(KeyedGraph::class.java, assertInstanceOf(MappingResult.Success::class.java, mapper.merge(cards.toList())).value)

    private companion object {
        /**
         * The formula of docs/mapping.md § Keys, written out apart from the mapper: the property's name in upper case,
         * a dot, and the first 16 hex digits of SHA-256 over the placement, a line feed, and the line as written.
         */
        fun key(placement: CardPlacement, line: String): String {
            val name = line.substringBefore(':').substringBefore(';').substringAfter('.').uppercase()
            val digest = MessageDigest.getInstance("SHA-256").digest("${placement.name}\n$line".toByteArray())
            return name + "." + digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
