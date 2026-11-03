package dev.rboza.android.opensyncforproton.mapping

import dev.rboza.android.opensyncforproton.mapping.CardPlacement.CLEAR
import dev.rboza.android.opensyncforproton.mapping.CardPlacement.ENCRYPTED
import dev.rboza.android.opensyncforproton.mapping.CardPlacement.SIGNED
import dev.rboza.android.opensyncforproton.mapping.ContactPatchTest.Companion.vcard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Random

// Cards and new contacts (docs/mapping.md § Writing step 4, § Creating a contact, and § `FN` on upload;
// docs/protocol.md § Contact cards). A card shows as its placement and its text. Every case reads its cards back with
// the mapper's merge. Every value is made up for the test.
class UploadCardsTest {
    private val mapper = EzvcardContactMapper()

    @Test
    fun `new lines go where the card table puts them, and every other line stays in its card`() {
        val cards = listOf(
            CLEAR to listOf("PRODID:-//Other//EN", "CATEGORIES:friends"),
            SIGNED to listOf("PRODID:-//Other//EN", "FN:Ann Lee", EMAIL, KEY, PM_ENCRYPT, "UID:urn:uuid:ann-1"),
            ENCRYPTED to listOf("N:Lee;Ann;;;", "TEL;TYPE=cell:+1 555 0100", "NOTE:hello", "X-CUSTOM:kept"),
        )
        val remote = merge(cards)
        val phone = KeyedGraph(
            remote.graph.copy(
                formattedName = "Ann B. Lee",
                emails = remote.graph.emails + TypedEmail("b@example.test", EmailType.Home),
                phones = remote.graph.phones.map { it.copy(value = "+1 555 0199") } + TypedPhone("+1 555 0200", PhoneType.Work),
                nickname = "Annie",
            ),
            remote.keys.copy(emails = remote.keys.emails + null, phones = remote.keys.phones + null),
        )

        assertCards(
            listOf(
                card(CLEAR, "PRODID:-//Other//EN", "CATEGORIES:friends"),
                card(SIGNED, "PRODID:-//Other//EN", "FN:Ann B. Lee", EMAIL, KEY, PM_ENCRYPT, "UID:urn:uuid:ann-1", "item2.EMAIL;TYPE=home:b@example.test"),
                card(ENCRYPTED, "N:Lee;Ann;;;", "TEL;TYPE=cell:+1 555 0199", "NOTE:hello", "X-CUSTOM:kept", "TEL;TYPE=work:+1 555 0200", "NICKNAME:Annie"),
            ),
            patch(phone, cards),
        )
    }

    @Test
    fun `a card the contact doesn't have is added as vCard 4_0`() {
        val signedOnly = listOf(CardFragment(SIGNED, vcard("3.0", listOf("FN:Ann", "EMAIL:a@example.test", "UID:ann-1"))))
        val phones = listOf(TypedPhone("+1 555 0100", PhoneType.Mobile), TypedPhone("+1 555 0101", PhoneType.Home))
        val withPhones = merge(signedOnly).let { KeyedGraph(it.graph.copy(phones = phones), it.keys.copy(phones = listOf(null, null))) }

        assertCards(
            listOf(SIGNED to vcard("3.0", listOf("FN:Ann", "EMAIL:a@example.test", "UID:ann-1")), card(ENCRYPTED, "TEL;TYPE=cell:+1 555 0100", "TEL;TYPE=home:+1 555 0101")),
            patchFragments(withPhones, signedOnly),
        )
        // One new card, however many lines it gets.
        assertEquals(listOf(SIGNED, ENCRYPTED), success(mapper.patch(withPhones, signedOnly)).cards.map { it.placement })

        val encryptedOnly = listOf(CardFragment(ENCRYPTED, vcard("3.0", listOf("FN:Ann", "TEL:+1 555 0100"))))
        val withEmail = merge(encryptedOnly).let { KeyedGraph(it.graph.copy(emails = listOf(TypedEmail("a@example.test", EmailType.Work))), it.keys.copy(emails = listOf(null))) }

        assertCards(
            listOf(ENCRYPTED to vcard("3.0", listOf("FN:Ann", "TEL:+1 555 0100")), card(SIGNED, "item1.EMAIL;TYPE=work:a@example.test")),
            patchFragments(withEmail, encryptedOnly),
        )
    }

    @Test
    fun `with two cards of a placement, new lines go last in the first`() {
        val cards = listOf(
            ENCRYPTED to listOf("TEL:+1 555 0100"),
            SIGNED to listOf("FN:Ann", "UID:ann-1"),
            ENCRYPTED to listOf("NOTE:hello"),
            SIGNED to listOf("EMAIL:a@example.test"),
        )
        val remote = merge(cards)
        val phone = KeyedGraph(
            remote.graph.copy(emails = remote.graph.emails + TypedEmail("b@example.test", EmailType.Work), phones = remote.graph.phones + TypedPhone("+1 555 0101", PhoneType.Work)),
            remote.keys.copy(emails = remote.keys.emails + null, phones = remote.keys.phones + null),
        )

        assertCards(
            listOf(
                card(ENCRYPTED, "TEL:+1 555 0100", "TEL;TYPE=work:+1 555 0101"),
                card(SIGNED, "FN:Ann", "UID:ann-1", "item1.EMAIL;TYPE=work:b@example.test"),
                card(ENCRYPTED, "NOTE:hello"),
                card(SIGNED, "EMAIL:a@example.test"),
            ),
            // Read back card by card, so the new e-mail comes first; a list's order doesn't count.
            patch(phone, cards, phone.graph.copy(emails = phone.graph.emails.reversed())),
        )
    }

    @Test
    fun `an edited line stays in its card, wherever the table would put it`() {
        val cards = listOf(
            CLEAR to listOf("CATEGORIES:friends"),
            SIGNED to listOf("FN:Ann", "NOTE:first", "NOTE:second", "TEL:+1 555 0100", "UID:ann-1"),
            ENCRYPTED to listOf("EMAIL:a@example.test", "TITLE:Boss"),
        )
        val remote = merge(cards)
        val edited = remote.graph.copy(
            phones = remote.graph.phones.map { it.copy(value = "+1 555 0199") },
            note = "edited",
            emails = remote.graph.emails.map { it.copy(value = "b@example.test") },
        )

        assertCards(
            listOf(
                card(CLEAR, "CATEGORIES:friends"),
                card(SIGNED, "FN:Ann", "NOTE:edited", "NOTE:second", "TEL:+1 555 0199", "UID:ann-1"),
                card(ENCRYPTED, "EMAIL:b@example.test", "TITLE:Boss"),
            ),
            patch(KeyedGraph(edited, remote.keys), cards),
        )
        // A value without a key is a new property; in a field Android shows once it takes the first property's place.
        assertCards(
            listOf(
                card(CLEAR, "CATEGORIES:friends"),
                card(SIGNED, "FN:Ann", "NOTE:edited", "NOTE:second", "TEL:+1 555 0100", "UID:ann-1"),
                card(ENCRYPTED, "EMAIL:a@example.test", "TITLE:Boss"),
            ),
            patch(KeyedGraph(remote.graph.copy(note = "edited")), cards),
        )
    }

    @Test
    fun `a phone value is never written in the clear card`() {
        val cards = listOf(
            CLEAR to listOf("CATEGORIES:friends", "TEL:+1 555 0100", "NOTE:first", "item1.X-ABDATE:20010702", "item1.X-ABLabel:Met"),
            SIGNED to listOf("FN:Ann", "UID:ann-1"),
        )
        val remote = merge(cards)
        val refused = invalid(MappingIssueCode.UNENCRYPTED_PROPERTY)

        // Edited by key, written new in a field's first property's place, or a label changed in its group: refused.
        assertEquals(refused, mapper.patch(KeyedGraph(remote.graph.copy(phones = remote.graph.phones.map { it.copy(value = "+1 555 0199") }), remote.keys), fragments(cards)))
        assertEquals(refused, mapper.patch(KeyedGraph(remote.graph.copy(note = "edited")), fragments(cards)))
        assertEquals(refused, mapper.patch(KeyedGraph(remote.graph.copy(customDates = remote.graph.customDates.map { it.copy(label = "Wed") }), remote.keys), fragments(cards)))
        // Removed is fine: nothing of the phone's is written; untouched clear lines stay while other values change.
        val removed = KeyedGraph(remote.graph.copy(phones = emptyList(), note = null), remote.keys.copy(phones = emptyList(), note = null))
        assertCards(
            listOf(card(CLEAR, "CATEGORIES:friends", "item1.X-ABDATE:20010702", "item1.X-ABLabel:Met"), card(SIGNED, "FN:Ann", "UID:ann-1", "item2.EMAIL:a@example.test")),
            patch(KeyedGraph(removed.graph.copy(emails = listOf(TypedEmail("a@example.test", EmailType.Other))), removed.keys.copy(emails = listOf(null))), cards),
        )
        // A custom date's label removed: its date line stays as it was, so only a line goes.
        val unlabelled = remote.graph.copy(customDates = remote.graph.customDates.map { it.copy(label = null) })
        assertCards(
            listOf(card(CLEAR, "CATEGORIES:friends", "TEL:+1 555 0100", "NOTE:first", "item1.X-ABDATE:20010702"), card(SIGNED, "FN:Ann", "UID:ann-1")),
            patch(KeyedGraph(unlabelled, remote.keys), cards),
        )
    }

    @Test
    fun `new lines are written in their card's vCard version`() {
        val cards = listOf(
            CardFragment(SIGNED, vcard("2.1", listOf("FN:Ann", "UID:ann-1"))),
            CardFragment(ENCRYPTED, vcard("2.1", listOf("TEL;CELL:+1 555 0100"))),
        )
        val remote = merge(cards)
        val phone = KeyedGraph(
            remote.graph.copy(
                emails = listOf(TypedEmail("a@example.test", EmailType.Custom, "Home, Gym")),
                phones = remote.graph.phones + TypedPhone("+1 555 0101", PhoneType.Custom, "Boat, red"),
                addresses = listOf(TypedAddress("1 Main St\nUnit 2", null, "Town", null, null, null, AddressType.Home)),
                customDates = listOf(LabeledDate(PartialDate(8, 3, null), "a\nb")),
                note = "a, b;\nc",
            ),
            remote.keys.copy(emails = listOf(null), phones = remote.keys.phones + null, addresses = listOf(null), customDates = listOf(null)),
        )

        assertCards(
            listOf(
                SIGNED to vcard("2.1", listOf("FN:Ann", "UID:ann-1", "item1.EMAIL;TYPE=Home, Gym;X-ANDROID-LABEL=Home, Gym:a@example.test")),
                ENCRYPTED to vcard(
                    "2.1",
                    listOf(
                        "TEL;CELL:+1 555 0100",
                        "TEL;TYPE=Boat, red;X-ANDROID-LABEL=Boat, red:+1 555 0101",
                        "ADR;TYPE=home;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:;;1 Main St=0AUnit 2;Town;;;",
                        "item2.X-ABDATE:--0803",
                        "item2.X-ABLabel;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:a=0Ab",
                        "NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:a\\, b\\;=0Ac",
                    ),
                ),
            ),
            patchFragments(phone, cards),
        )
        // A new property in place of a field's first property is written in that card's version too.
        val notes = listOf(CardFragment(ENCRYPTED, vcard("2.1", listOf("FN:Ann", "NOTE:old", "NOTE:second"))))
        assertCards(
            listOf(ENCRYPTED to vcard("2.1", listOf("FN:Ann", "NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:a=0Ab", "NOTE:second"))),
            patchFragments(KeyedGraph(merge(notes).graph.copy(note = "a\nb")), notes),
        )
        // A label vCard 2.1 can't hold fails closed in a 2.1 card.
        val colon = phone.graph.copy(emails = listOf(TypedEmail("a@example.test", EmailType.Custom, "Gym: back")))
        assertEquals(invalid(MappingIssueCode.UNWRITABLE_LABEL), mapper.patch(KeyedGraph(colon, phone.keys), cards))
    }

    @Test
    fun `a card left with no lines isn't sent`() {
        val cards = listOf(SIGNED to listOf("FN:Ann", "UID:ann-1"), ENCRYPTED to listOf("TEL:+1 555 0100"))
        val remote = merge(cards)

        assertCards(
            listOf(card(SIGNED, "FN:Ann", "UID:ann-1")),
            patch(KeyedGraph(remote.graph.copy(phones = emptyList()), remote.keys.copy(phones = emptyList())), cards),
        )
    }

    @Test
    fun `a new e-mail gets its own item group, past every item group in the contact`() {
        // Created: each e-mail its own group, and a labelled date the next one.
        val created = ContactGraph(
            uid = null,
            formattedName = "Ann",
            emails = listOf(TypedEmail("a@example.test", EmailType.Work), TypedEmail("b@example.test", EmailType.Other)),
            customDates = listOf(LabeledDate(PartialDate(7, 2, 2001), "Met")),
        )
        val cards = success(mapper.create(created, UID, "0.2.0"))
        assertCards(
            listOf(
                card(SIGNED, "PRODID:-//OpenSync for Proton//Contacts 0.2.0//EN", "UID:$UID", "FN:Ann", "item1.EMAIL;TYPE=work:a@example.test", "item2.EMAIL:b@example.test"),
                card(ENCRYPTED, "item3.X-ABDATE:20010702", "item3.X-ABLabel:Met"),
            ),
            cards,
        )
        assertReadsBack(created.copy(uid = UID), cards)

        // Added on the phone: past every item group, in any card and any case; an edited e-mail keeps its own group.
        val remoteCards = listOf(
            SIGNED to listOf("FN:Ann", "item2.EMAIL:a@example.test", "UID:ann-1"),
            ENCRYPTED to listOf("ITEM7.X-ABDATE:20010702", "ITEM7.X-ABLabel:Met", "other.TEL:+1 555 0100"),
        )
        val remote = merge(remoteCards)
        val added = KeyedGraph(
            remote.graph.copy(emails = remote.graph.emails.map { it.copy(value = "c@example.test") } + TypedEmail("b@example.test", EmailType.Home)),
            remote.keys.copy(emails = remote.keys.emails + null),
        )
        assertCards(
            listOf(
                card(SIGNED, "FN:Ann", "item2.EMAIL:c@example.test", "UID:ann-1", "item8.EMAIL;TYPE=home:b@example.test"),
                card(ENCRYPTED, "ITEM7.X-ABDATE:20010702", "ITEM7.X-ABLabel:Met", "other.TEL:+1 555 0100"),
            ),
            patch(added, remoteCards),
        )

        // In a card of vCard 2.1, the group comes first there too.
        val old = listOf(CardFragment(SIGNED, vcard("2.1", listOf("FN:Ann", "UID:ann-1"))))
        val oldAdded = merge(old).let { KeyedGraph(it.graph.copy(emails = listOf(TypedEmail("a@example.test", EmailType.Custom, "Gym"))), it.keys.copy(emails = listOf(null))) }
        assertCards(
            listOf(SIGNED to vcard("2.1", listOf("FN:Ann", "UID:ann-1", "item1.EMAIL;TYPE=Gym;X-ANDROID-LABEL=Gym:a@example.test"))),
            patchFragments(oldAdded, old),
        )
    }

    @Test
    fun `a new contact has a signed card with FN, its e-mails, UID, VERSION and PRODID, and the rest encrypted`() {
        val cards = success(mapper.create(FULL, UID, "0.2.0"))

        assertCards(
            listOf(
                card(
                    SIGNED,
                    "PRODID:-//OpenSync for Proton//Contacts 0.2.0//EN",
                    "UID:$UID",
                    "FN:Ann Lee",
                    "item1.EMAIL;TYPE=work:a@example.test",
                    "item2.EMAIL;TYPE=Gym;X-ANDROID-LABEL=Gym:b@example.test",
                ),
                card(
                    ENCRYPTED,
                    "N:Lee;Ann;B;Dr;Jr",
                    "X-PHONETIC-FIRST-NAME:an",
                    "TEL;TYPE=cell:+1 555 0100",
                    "TEL;TYPE=fax,home:+1 555 0101",
                    "TEL;TYPE=Boat;X-ANDROID-LABEL=Boat:+1 555 0102",
                    "ADR;TYPE=home:PO 9;;1 Main St\\nUnit 2;Town;North;12345;Land",
                    "ORG:Acme;R&D;Lab",
                    "TITLE:Engineer",
                    "ROLE:Lead",
                    "BDAY:19900517",
                    "ANNIVERSARY:--0601",
                    "item3.X-ABDATE:20010702",
                    "item3.X-ABLabel:Met",
                    "X-ABDATE:--0803",
                    "NOTE:a\\, b\\; c\\nd",
                    "URL:https://example.test/x,y",
                    "NICKNAME:Annie\\, Jr",
                    "RELATED;VALUE=text;TYPE=spouse;X-ANDROID-TYPE=spouse:Bob",
                    "RELATED;VALUE=text;TYPE=Coach;X-ANDROID-TYPE=custom;X-ANDROID-LABEL=Coach:Cy",
                    "X-SIP:ann@example.test",
                ),
            ),
            cards,
        )
        assertReadsBack(FULL.copy(uid = UID), cards)
    }

    @Test
    fun `a new contact with only a name and no e-mail has only a signed card`() {
        val cards = success(mapper.create(ContactGraph(uid = null, formattedName = "Ann"), UID, "0.2.0"))

        assertCards(listOf(card(SIGNED, "PRODID:-//OpenSync for Proton//Contacts 0.2.0//EN", "UID:$UID", "FN:Ann")), cards)
    }

    @Test
    fun `a new contact's photo is the JPEG given, last in the encrypted card, never the graph's own`() {
        val jpeg = byteArrayOf(9, 8, 7)
        val cards = success(mapper.create(FULL, UID, "0.2.0", photo = jpeg))

        val encrypted = cardLines(cards.single { it.placement == ENCRYPTED }.vcard).lines
        assertEquals("PHOTO:data:image/jpeg;base64,CQgH", encrypted.last().line)
        assertEquals(1, encrypted.count { it.name == "PHOTO" })
        assertEquals(ContactPhoto(jpeg, "image/jpeg"), merge(cards).graph.photo)
        assertReadsBack(FULL.copy(uid = UID), cards)

        // A name and a photo: the photo alone in the encrypted card.
        assertCards(
            listOf(
                card(SIGNED, "PRODID:-//OpenSync for Proton//Contacts 0.2.0//EN", "UID:$UID", "FN:Ann"),
                card(ENCRYPTED, "PHOTO:data:image/jpeg;base64,CQgH"),
            ),
            success(mapper.create(ContactGraph(uid = null, formattedName = "Ann"), UID, "0.2.0", photo = jpeg)),
        )
    }

    @Test
    fun `a new UID is a version 4 UUID URN made from the random source, fresh each time`() {
        assertEquals("urn:uuid:00000000-0000-4000-8000-000000000000", newContactUid(Constant(0x00)))
        assertEquals("urn:uuid:ffffffff-ffff-4fff-bfff-ffffffffffff", newContactUid(Constant(0xff)))
        val counting = Counting()
        assertEquals("urn:uuid:00010203-0405-4607-8809-0a0b0c0d0e0f", newContactUid(counting))
        assertEquals("urn:uuid:10111213-1415-4617-9819-1a1b1c1d1e1f", newContactUid(counting))

        val (a, b) = newContactUid() to newContactUid()
        assertNotEquals(a, b)
        assertTrue(listOf(a, b).all { URN_V4.matches(it) }, "$a $b")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uploadNames")
    fun `FN is the phone's name, else built from the structured name, else the first e-mail`(case: UploadName) {
        val cards = success(mapper.create(case.phone, UID, "0.2.0"))

        assertEquals("FN:${case.fn}", cardLines(cards.first { it.placement == SIGNED }.vcard).lines.single { it.name == "FN" }.line)
        // A blank e-mail reads back as none.
        assertReadsBack(case.phone.copy(uid = UID, formattedName = case.fn, emails = case.phone.emails.filter { it.value.isNotBlank() }), cards)
    }

    @Test
    fun `no name and no e-mail is held, and a phone number is never the name`() {
        val phones = ContactGraph(uid = null, formattedName = null, phones = listOf(TypedPhone("+1 555 0100", PhoneType.Mobile)))

        assertEquals(invalid(MappingIssueCode.NO_NAME_OR_EMAIL), mapper.create(phones, UID, "0.2.0"))
        assertEquals(invalid(MappingIssueCode.NO_NAME_OR_EMAIL), mapper.create(phones.copy(formattedName = " ", name = StructuredName(" ", null)), UID, "0.2.0"))
        assertEquals(invalid(MappingIssueCode.NO_NAME_OR_EMAIL), mapper.create(phones.copy(emails = listOf(TypedEmail(" ", EmailType.Home))), UID, "0.2.0"))
    }

    @Test
    fun `an update carries an FN too`() {
        // A remote with N but no FN gains one when an edit uploads.
        val nOnly = listOf(SIGNED to listOf("UID:ann-1"), ENCRYPTED to listOf("N:Lee;Ann;;;", "TEL:+1 555 0100"))
        val remote = merge(nOnly)
        val edited = remote.graph.copy(phones = remote.graph.phones.map { it.copy(value = "+1 555 0199") })

        assertCards(
            listOf(card(SIGNED, "UID:ann-1", "FN:Ann Lee"), card(ENCRYPTED, "N:Lee;Ann;;;", "TEL:+1 555 0199")),
            patch(KeyedGraph(edited, remote.keys), nOnly, edited.copy(formattedName = "Ann Lee")),
        )

        // The phone removed the name: the first e-mail takes FN's place.
        val named = listOf(SIGNED to listOf("FN:Ann", "EMAIL:a@example.test", "UID:ann-1"), ENCRYPTED to listOf("N:Lee;Ann;;;"))
        val withName = merge(named)
        val nameless = withName.graph.copy(formattedName = null, name = null)

        assertCards(
            listOf(card(SIGNED, "FN:a@example.test", "EMAIL:a@example.test", "UID:ann-1")),
            patch(KeyedGraph(nameless, withName.keys.copy(name = null)), named, nameless.copy(formattedName = "a@example.test")),
        )

        // The phone removed the name: every FN goes, as for any field removed on the phone, a later one too.
        val twoNames = listOf(SIGNED to listOf("FN:Ann", "FN:Annie", "EMAIL:a@example.test", "UID:ann-1"), ENCRYPTED to listOf("N:Lee;Ann;;;", "NOTE:x"))
        val both = merge(twoNames)
        for (blank in listOf(null, " ")) {
            val removed = both.graph.copy(formattedName = blank, name = null)
            assertCards(
                listOf(card(SIGNED, "FN:a@example.test", "EMAIL:a@example.test", "UID:ann-1"), card(ENCRYPTED, "NOTE:x")),
                patch(KeyedGraph(removed, both.keys.copy(name = null)), twoNames, removed.copy(formattedName = "a@example.test")),
            )
        }
        // With a structured name but no display name, the built FN is an edit of the first; the later one stays.
        val noDisplayName = both.graph.copy(formattedName = null)
        assertCards(
            listOf(card(SIGNED, "FN:Ann Lee", "FN:Annie", "EMAIL:a@example.test", "UID:ann-1"), card(ENCRYPTED, "N:Lee;Ann;;;", "NOTE:x")),
            patch(KeyedGraph(noDisplayName, both.keys), twoNames, noDisplayName.copy(formattedName = "Ann Lee")),
        )

        // No name and no e-mail left: held, nothing written.
        val bare = nameless.copy(emails = emptyList())
        assertEquals(invalid(MappingIssueCode.NO_NAME_OR_EMAIL), mapper.patch(KeyedGraph(bare, GraphKeys.none(bare)), named.map(ContactPatchTest::fragment)))
    }

    @ParameterizedTest(name = "vCard {0}")
    @ValueSource(strings = ["2.1", "3.0", "4.0"])
    fun `every new value read back from the cards, in a card of each version`(version: String) {
        val cards = listOf(CardFragment(SIGNED, vcard(version, listOf("FN:Ann Lee", "UID:ann-1"))), CardFragment(ENCRYPTED, vcard(version, listOf("NOTE:old"))))
        val remote = merge(cards)
        val phone = FULL.copy(uid = remote.graph.uid)

        assertReadsBack(phone, patchFragments(KeyedGraph(phone, GraphKeys.none(phone).copy(name = remote.keys.name)), cards))
    }

    /** The patch's cards, which must read back as [phone]'s graph, or as [readsBack] when FN on upload changed it. */
    private fun patch(phone: KeyedGraph, cards: List<Pair<CardPlacement, List<String>>>, readsBack: ContactGraph = phone.graph) =
        patchFragments(phone, cards.map(ContactPatchTest::fragment), readsBack)

    private fun patchFragments(phone: KeyedGraph, cards: List<CardFragment>, readsBack: ContactGraph = phone.graph): List<CardFragment> {
        val written = success(mapper.patch(phone, cards)).fragments()
        assertReadsBack(readsBack, written)
        return written
    }

    /** Photo aside (`:sync`'s photo baseline decides it); an organization compares by its department text. */
    private fun assertReadsBack(expected: ContactGraph, cards: List<CardFragment>) {
        fun comparable(graph: ContactGraph) = graph.copy(photo = null, organization = graph.organization?.let { it.copy(units = listOf(it.units.joinToString(";"))) })

        assertEquals(comparable(expected), comparable(merge(cards).graph), "read back")
    }

    private fun assertCards(expected: List<Pair<CardPlacement, String>>, cards: List<CardFragment>) =
        assertEquals(expected, cards.map { it.placement to it.vcard })

    private fun fragments(cards: List<Pair<CardPlacement, List<String>>>) = cards.map(ContactPatchTest::fragment)

    private fun merge(cards: List<Pair<CardPlacement, List<String>>>) = merge(fragments(cards))

    @JvmName("mergeFragments")
    private fun merge(cards: List<CardFragment>): KeyedGraph = success(mapper.merge(cards))

    private fun <T> success(result: MappingResult<T>): T = assertInstanceOf(MappingResult.Success::class.java, result).value.let {
        @Suppress("UNCHECKED_CAST")
        it as T
    }

    private class Constant(private val byte: Int) : Random() {
        override fun nextBytes(bytes: ByteArray) = bytes.fill(byte.toByte())
    }

    /** Hands out 0, 1, 2, … across calls. */
    private class Counting : Random() {
        private var next = 0

        override fun nextBytes(bytes: ByteArray) = bytes.indices.forEach { bytes[it] = next++.toByte() }
    }

    class UploadName(private val name: String, val phone: ContactGraph, val fn: String) {
        override fun toString() = name
    }

    companion object {
        const val UID = "urn:uuid:00000000-0000-4000-8000-000000000000"
        const val EMAIL = "item1.EMAIL;TYPE=work:ann@example.test"
        const val KEY = "item1.KEY:data:application/pgp-keys;base64,AAEC"
        const val PM_ENCRYPT = "item1.X-PM-ENCRYPT:true"

        val URN_V4 = Regex("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        /** Every field Android shows, as the phone has it, with a photo the upload leaves to the photo baseline. */
        val FULL = ContactGraph(
            uid = "ignored",
            formattedName = "Ann Lee",
            name = StructuredName(given = "Ann", family = "Lee", prefix = "Dr", middle = "B", suffix = "Jr", phoneticGiven = "an"),
            emails = listOf(TypedEmail("a@example.test", EmailType.Work), TypedEmail("b@example.test", EmailType.Custom, "Gym")),
            phones = listOf(
                TypedPhone("+1 555 0100", PhoneType.Mobile),
                TypedPhone("+1 555 0101", PhoneType.FaxHome),
                TypedPhone("+1 555 0102", PhoneType.Custom, "Boat"),
            ),
            addresses = listOf(TypedAddress("1 Main St\nUnit 2", "PO 9", "Town", "North", "12345", "Land", AddressType.Home)),
            organization = Organization("Acme", listOf("R&D", "Lab"), "Engineer", "Lead"),
            photo = ContactPhoto(byteArrayOf(1, 2, 3), "image/jpeg"),
            birthday = PartialDate(5, 17, 1990),
            anniversary = PartialDate(6, 1, null),
            customDates = listOf(LabeledDate(PartialDate(7, 2, 2001), "Met"), LabeledDate(PartialDate(8, 3, null), null)),
            note = "a, b; c\nd",
            urls = listOf("https://example.test/x,y"),
            nickname = "Annie, Jr",
            relations = listOf(TypedRelation("Bob", RelationType.Spouse), TypedRelation("Cy", RelationType.Custom, "Coach")),
            sipAddresses = listOf("ann@example.test"),
        )

        private val NO_FN = ContactGraph(uid = null, formattedName = null, emails = listOf(TypedEmail("a@example.test", EmailType.Home), TypedEmail("b@example.test", EmailType.Work)))

        @JvmStatic
        fun uploadNames() = listOf(
            UploadName("the phone's name", NO_FN.copy(formattedName = "Ann Lee", name = StructuredName("Anna", "Leigh")), "Ann Lee"),
            UploadName("the structured name", NO_FN.copy(name = StructuredName("Ann", "Lee", prefix = "Dr", middle = "B", suffix = "Jr")), "Dr Ann B Lee Jr"),
            UploadName("a blank name counts as none", NO_FN.copy(formattedName = " ", name = StructuredName("Ann", "Lee")), "Ann Lee"),
            UploadName("the phonetic name when that's all", NO_FN.copy(name = StructuredName(null, null, phoneticGiven = "an", phoneticFamily = "lee")), "an lee"),
            UploadName("the first e-mail", NO_FN, "a@example.test"),
            UploadName("the first e-mail that isn't blank", NO_FN.copy(emails = listOf(TypedEmail(" ", EmailType.Home)) + NO_FN.emails), "a@example.test"),
            UploadName("never a phone number", NO_FN.copy(phones = listOf(TypedPhone("+1 555 0100", PhoneType.Mobile))), "a@example.test"),
        )

        private fun card(placement: CardPlacement, vararg lines: String) = placement to vcard("4.0", lines.toList())

        private fun invalid(code: MappingIssueCode) = MappingResult.Invalid(listOf(MappingIssue(code)))
    }
}
