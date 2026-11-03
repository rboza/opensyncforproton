package dev.rboza.android.opensyncforproton.mapping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

// Patching the remote vCard (docs/mapping.md § Writing). A card's lines show as they are, `*` before an edited one; new
// lines go last in the card the card table puts them in. Every case also reads its patch's cards back and expects the
// phone's graph. Every value is made up for the test.
class ContactPatchTest {
    private val mapper = EzvcardContactMapper()

    @Test
    fun `the phone's graph equals the remote's`() {
        val remote = merge(REMOTE)

        assertPatch(REMOTE, remote)
        // Rows without keys pair by value.
        assertPatch(REMOTE, KeyedGraph(remote.graph))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("edits")
    fun `one shown field edited changes only its property's shown parts`(edit: Edit) {
        val remote = merge(REMOTE)

        assertPatch(REMOTE, KeyedGraph(edit.change(remote.graph), remote.keys), edit.lines, edit.added)
    }

    @Test
    fun `a removed e-mail goes with its group, a changed address loses its group's KEY and X-PM-*`() {
        val remote = merge(REMOTE)
        val group = mapOf(KEY to emptyList<String>(), PM_ENCRYPT to emptyList(), EMAIL_LABEL to emptyList())

        assertPatch(REMOTE, KeyedGraph(remote.graph.copy(emails = emptyList()), remote.keys.copy(emails = emptyList())), group + (EMAIL to emptyList()))
    }

    @Test
    fun `a removed row takes its group's X-ABLabel, never a mapped property in the group`() {
        val cards = listOf(
            CardPlacement.SIGNED to listOf("FN:Ann", "item1.EMAIL:a@example.test", "item1.TEL:+1 555 0100", "ITEM1.X-ABLabel:Club"),
            // The same group name in another card is another group.
            CardPlacement.ENCRYPTED to listOf("item1.X-ABLabel:Other card"),
        )
        val remote = merge(cards)

        assertPatch(
            cards,
            KeyedGraph(remote.graph.copy(emails = emptyList()), remote.keys.copy(emails = emptyList())),
            mapOf("item1.EMAIL:a@example.test" to emptyList(), "ITEM1.X-ABLabel:Club" to emptyList()),
        )
    }

    @Test
    fun `removed rows of each list kind`() {
        val remote = merge(REMOTE)
        val graph = remote.graph.copy(phones = emptyList(), addresses = emptyList(), customDates = emptyList(), urls = emptyList(), relations = emptyList(), sipAddresses = emptyList())
        val keys = remote.keys.copy(phones = emptyList(), addresses = emptyList(), customDates = emptyList(), urls = emptyList(), relations = emptyList(), sipAddresses = emptyList())

        assertPatch(
            REMOTE,
            KeyedGraph(graph, keys),
            listOf(TEL, ADR, ADR_LABEL, ABDATE, ABDATE_LABEL, URL, RELATED, SIP).associateWith { emptyList() },
        )
    }

    @Test
    fun `a removed row of a field Android shows once takes every property that would give its value`() {
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", "NOTE:", "NOTE;LANGUAGE=en:hello", "NOTE:second", "TITLE:Boss", "TITLE:Chief", "ORG:Acme"))
        val remote = merge(cards)
        val graph = remote.graph.copy(note = null, organization = remote.graph.organization!!.copy(title = null))

        assertPatch(
            cards,
            KeyedGraph(graph, remote.keys.copy(note = null)),
            listOf("NOTE;LANGUAGE=en:hello", "NOTE:second", "TITLE:Boss", "TITLE:Chief").associateWith { emptyList() },
        )
    }

    @Test
    fun `the name and the organization removed`() {
        val remote = merge(REMOTE)

        val graph = remote.graph.copy(formattedName = null, name = null, organization = null)

        // FN on upload: the first e-mail takes the name's place.
        assertPatch(
            REMOTE,
            KeyedGraph(graph, remote.keys.copy(name = null, organization = null)),
            listOf(N, "X-PHONETIC-FIRST-NAME:an", ORG, "TITLE:Engineer", "ROLE:Lead").associateWith { emptyList<String>() } + ("FN:Ann Lee" to listOf("FN:ann@example.test")),
            uploaded = graph.copy(formattedName = "ann@example.test"),
        )
    }

    @Test
    fun `two NOTEs, the first edited, keep the second`() {
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", "NOTE;LANGUAGE=en:hello", "NOTE:second"))
        val remote = merge(cards)
        val edited = remote.graph.copy(note = "hi")

        // By key: the paired property keeps its hidden parts, the later NOTE among them.
        assertPatch(cards, KeyedGraph(edited, remote.keys), mapOf("NOTE;LANGUAGE=en:hello" to listOf("NOTE;LANGUAGE=en:hi")))
        // Without a key: a new property in the first one's place, so it still reads first; the later NOTE stays.
        assertPatch(cards, KeyedGraph(edited), mapOf("NOTE;LANGUAGE=en:hello" to listOf("NOTE:hi")))
    }

    @Test
    fun `a new property in place of a grouped one takes the old one's group with it`() {
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", "item1.NOTE:hello", "item1.X-ABLabel:Memo"))
        val remote = merge(cards)

        assertPatch(cards, KeyedGraph(remote.graph.copy(note = "hi")), mapOf("item1.NOTE:hello" to listOf("NOTE:hi"), "item1.X-ABLabel:Memo" to emptyList()))
    }

    @Test
    fun `the one-value fields removed`() {
        val remote = merge(REMOTE)
        val graph = remote.graph.copy(birthday = null, anniversary = null, note = null, nickname = null)

        assertPatch(
            REMOTE,
            KeyedGraph(graph, remote.keys.copy(birthday = null, anniversary = null, note = null, nickname = null)),
            listOf(BDAY, ANNIVERSARY, NOTE, "NOTE:second", NICKNAME).associateWith { emptyList() },
        )
    }

    @Test
    fun `a relation's name is escaped only as text, and an X-SIP without a scheme gets none`() {
        val uri = "RELATED;TYPE=spouse:Bob"
        val text = "RELATED;VALUE=text;TYPE=friend:Al"
        val sip = "X-SIP:ann@example.test"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", uri, text, sip))
        val remote = merge(cards)
        val graph = remote.graph.copy(
            relations = remote.graph.relations.map { it.copy(name = it.name + ", Jr") },
            sipAddresses = listOf("bob@example.test"),
        )

        assertPatch(
            cards,
            KeyedGraph(graph, remote.keys),
            mapOf(uri to listOf("RELATED;TYPE=spouse:Bob, Jr"), text to listOf("RELATED;VALUE=text;TYPE=friend:Al\\, Jr"), sip to listOf("X-SIP:bob@example.test")),
        )
    }

    @Test
    fun `a row without a key pairs by equal shown value, else it's a new property`() {
        val remote = merge(REMOTE)
        val changed = remote.graph.copy(emails = listOf(TypedEmail("ann@work.example", EmailType.Work)))

        assertPatch(
            REMOTE,
            KeyedGraph(changed),
            mapOf(EMAIL to emptyList(), KEY to emptyList(), PM_ENCRYPT to emptyList(), EMAIL_LABEL to emptyList()),
            listOf("item4.EMAIL;TYPE=work:ann@work.example"),
        )
    }

    @Test
    fun `a key naming no remote property counts as none`() {
        val remote = merge(REMOTE)
        val stale = SourceKey(listOf("EMAIL.0123456789abcdef"))

        // Same value: paired by value, hidden parts kept.
        assertPatch(REMOTE, KeyedGraph(remote.graph, remote.keys.copy(emails = listOf(stale))))
        // A key of the wrong kind names no e-mail either.
        assertPatch(REMOTE, KeyedGraph(remote.graph, remote.keys.copy(emails = remote.keys.phones)))
    }

    @Test
    fun `two unpaired properties with the row's shown value - the first pairs`() {
        val first = "EMAIL;TYPE=home;X-SOURCE=one:a@example.test"
        val second = "EMAIL;TYPE=home;X-SOURCE=two:a@example.test"
        val cards = listOf(CardPlacement.SIGNED to listOf("FN:Ann", first, second))
        val remote = merge(cards)
        val one = remote.graph.copy(emails = remote.graph.emails.take(1))

        assertPatch(cards, KeyedGraph(one), mapOf(second to emptyList()))
        // A key beats the order.
        assertPatch(cards, KeyedGraph(one, remote.keys.copy(emails = remote.keys.emails.drop(1))), mapOf(first to emptyList()))
    }

    @Test
    fun `an ORG unit holding an escaped semicolon`() {
        val org = "ORG:Acme;R\\;D;Lab"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", org))
        val remote = merge(cards)
        // Read back from the phone's rows, the department splits at each ';'.
        val fromRows = remote.graph.copy(organization = Organization("Acme", listOf("R", "D", "Lab")))

        assertPatch(cards, KeyedGraph(fromRows, remote.keys))
        assertPatch(cards, KeyedGraph(fromRows))
        assertPatch(
            cards,
            KeyedGraph(fromRows.copy(organization = Organization("Acme", listOf("R", "D", "Labs"))), remote.keys),
            mapOf(org to listOf("ORG:Acme;R;D;Labs")),
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "BDAY:19900517                     | 6 | 17 | 1990 | BDAY:19900617",
            "BDAY:1990-05-17T10:30:00Z         | 5 | 18 | 1990 | BDAY:1990-05-18T10:30:00Z",
            "BDAY:19900517T103000-0500         | 5 | 17 | 1991 | BDAY:19910517T103000-0500",
            "BDAY:19900517                     | 5 | 17 |      | BDAY:--0517",
            "BDAY:1990-05-17T10:30:00+02:00    | 5 | 17 |      | BDAY:--05-17",
            "BDAY:--0517                       | 5 | 18 |      | BDAY:--0518",
            "BDAY:--05-17                      | 5 | 17 | 1999 | BDAY:1999-05-17",
            "BDAY:--0517                       | 5 | 17 | 1999 | BDAY:19990517",
            "BDAY:00000517                     | 5 | 18 |      | BDAY:00000518",
            "BDAY:0000-05-17T10:00:00Z         | 5 | 18 |      | BDAY:0000-05-18T10:00:00Z",
            "BDAY;X-APPLE-OMIT-YEAR=1604:1604-05-17 | 6 | 1 |  | BDAY;X-APPLE-OMIT-YEAR=1604:1604-06-01",
            "BDAY;X-APPLE-OMIT-YEAR=1604:1604-05-17 | 5 | 17 | 1604 | BDAY:1604-05-17",
            "BDAY;VALUE=date-and-or-time:1990-05-17 | 5 | 18 | 1990 | BDAY;VALUE=date-and-or-time:1990-05-18",
            "BDAY:1990-05-17T10:30             | 5 | 18 | 1990 | BDAY:1990-05-18T10:30",
            "BDAY:19900517T1030                | 6 | 18 |      | BDAY:--0618",
            // A form the rewrite doesn't know (ez-vcard reads one-digit months and days) is written as a new date.
            "BDAY:1990-5-7                     | 5 | 8  | 1990 | BDAY:19900508",
            "BDAY:1990-5-7T10:30:00Z           | 5 | 8  |      | BDAY:--0508",
        ],
    )
    fun `an edited date keeps its form, its time, and a placeholder year`(line: String, month: Int, day: Int, year: Int?, edited: String) {
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", line))
        val remote = merge(cards)

        assertPatch(cards, KeyedGraph(remote.graph.copy(birthday = PartialDate(month, day, year)), remote.keys), mapOf(line to listOf(edited)))
    }

    @Test
    fun `an edited tel URI is written as text, a new type alone keeps it`() {
        val tel = "TEL;VALUE=uri;TYPE=cell:tel:+1-555-0100;ext=12"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", tel))
        val remote = merge(cards)
        val phone = remote.graph.phones.single()

        assertPatch(cards, KeyedGraph(remote.graph.copy(phones = listOf(phone.copy(value = "+1 555 0199"))), remote.keys), mapOf(tel to listOf("TEL;TYPE=cell:+1 555 0199")))
        assertPatch(
            cards,
            KeyedGraph(remote.graph.copy(phones = listOf(phone.copy(type = PhoneType.Work))), remote.keys),
            mapOf(tel to listOf("TEL;VALUE=uri;TYPE=work:tel:+1-555-0100;ext=12")),
        )
    }

    @Test
    fun `a type left without values goes`() {
        val email = "EMAIL;TYPE=home:a@example.test"
        val cards = listOf(CardPlacement.SIGNED to listOf("FN:Ann", email))
        val remote = merge(cards)

        assertPatch(
            cards,
            KeyedGraph(remote.graph.copy(emails = listOf(TypedEmail("a@example.test", EmailType.Other))), remote.keys),
            mapOf(email to listOf("EMAIL:a@example.test")),
        )
    }

    @Test
    fun `a label added to a custom date goes in its group`() {
        val grouped = "item7.X-ABDATE:2001-07-02"
        val ungrouped = "X-ABDATE:2002-08-03"
        val cards = listOf(
            CardPlacement.SIGNED to listOf("FN:Ann", "item2.EMAIL:a@example.test"),
            CardPlacement.ENCRYPTED to listOf(grouped, ungrouped, "ITEM9.X-ABLabel:other"),
        )
        val remote = merge(cards)
        val labelled = remote.graph.customDates.map { it.copy(label = "Met") }

        assertPatch(
            cards,
            KeyedGraph(remote.graph.copy(customDates = labelled), remote.keys),
            mapOf(
                grouped to listOf(grouped, "item7.X-ABLabel:Met"),
                // A new group past every item group in the contact, whatever its case.
                ungrouped to listOf("item10.X-ABDATE:2002-08-03", "item10.X-ABLabel:Met"),
            ),
        )
    }

    @Test
    fun `new values of each kind`() {
        val cards = listOf(CardPlacement.SIGNED to listOf("FN:Ann", "item3.EMAIL:z@example.test"))
        val remote = merge(cards)
        val graph = remote.graph.copy(
            name = StructuredName(given = "Ann", family = "Lee", phoneticGiven = "an"),
            emails = remote.graph.emails + listOf(TypedEmail("a@example.test", EmailType.Work), TypedEmail("b@example.test", EmailType.Other)),
            phones = listOf(
                TypedPhone("+1 555 0100", PhoneType.Mobile),
                TypedPhone("+1 555 0101", PhoneType.FaxWork),
                TypedPhone("+1 555 0102", PhoneType.Custom, "Boat"),
                TypedPhone("+1 555 0103", PhoneType.Home),
                TypedPhone("+1 555 0104", PhoneType.Pager),
                TypedPhone("+1 555 0105", PhoneType.Main),
            ),
            addresses = listOf(
                TypedAddress("1 Main St\nUnit 2", "PO 9", "Town", null, "12345", "Land", AddressType.Work),
                TypedAddress("2 Side St", null, null, null, null, null, AddressType.Home),
            ),
            organization = Organization("Acme", listOf("R&D", "Lab"), "Engineer", "Lead"),
            birthday = PartialDate(5, 17, 1990),
            anniversary = PartialDate(6, 1, null),
            customDates = listOf(LabeledDate(PartialDate(7, 2, 2001), "Met"), LabeledDate(PartialDate(8, 3, null), null)),
            note = "a, b; c",
            urls = listOf("https://example.test/x,y"),
            nickname = "Annie, Jr",
            relations = listOf(TypedRelation("Bob", RelationType.Spouse), TypedRelation("Al", RelationType.Custom), TypedRelation("Cy", RelationType.Custom, "Coach")),
            sipAddresses = listOf("ann@example.test"),
        )
        val keys = GraphKeys.none(graph).copy(name = remote.keys.name, emails = remote.keys.emails + listOf(null, null))

        assertPatch(
            cards,
            KeyedGraph(graph, keys),
            added = listOf(
                "N:Lee;Ann;;;",
                "X-PHONETIC-FIRST-NAME:an",
                "item4.EMAIL;TYPE=work:a@example.test",
                "item5.EMAIL:b@example.test",
                "TEL;TYPE=cell:+1 555 0100",
                "TEL;TYPE=fax:+1 555 0101",
                "TEL;TYPE=Boat;X-ANDROID-LABEL=Boat:+1 555 0102",
                "TEL;TYPE=home:+1 555 0103",
                "TEL;TYPE=pager:+1 555 0104",
                "TEL;TYPE=main:+1 555 0105",
                "ADR;TYPE=work:PO 9;;1 Main St\\nUnit 2;Town;;12345;Land",
                "ADR;TYPE=home:;;2 Side St;;;;",
                "ORG:Acme;R&D;Lab",
                "TITLE:Engineer",
                "ROLE:Lead",
                "BDAY:19900517",
                "ANNIVERSARY:--0601",
                "item6.X-ABDATE:20010702",
                "item6.X-ABLabel:Met",
                "X-ABDATE:--0803",
                "NOTE:a\\, b\\; c",
                "URL:https://example.test/x,y",
                "NICKNAME:Annie\\, Jr",
                "RELATED;VALUE=text;TYPE=spouse;X-ANDROID-TYPE=spouse:Bob",
                "RELATED;VALUE=text;X-ANDROID-TYPE=custom:Al",
                "RELATED;VALUE=text;TYPE=Coach;X-ANDROID-TYPE=custom;X-ANDROID-LABEL=Coach:Cy",
                "X-SIP:ann@example.test",
            ),
        )
    }

    @Test
    fun `a value the phone changed whose property has no key fails closed`() {
        // vCard 3.0: ez-vcard keeps an unparseable BDAY as raw text, so that card's BDAY lines have no keys.
        val cards = listOf(CardFragment(CardPlacement.ENCRYPTED, "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ann\r\nBDAY:soon\r\nBDAY:1990-05-17\r\nEND:VCARD\r\n"))
        val remote = mergeFragments(cards)

        assertFalse(success(mapper.patch(remote, cards)).changed, "an unchanged value doesn't need its line")
        for (birthday in listOf(PartialDate(5, 18, 1990), null)) {
            val result = mapper.patch(KeyedGraph(remote.graph.copy(birthday = birthday), remote.keys), cards)
            assertEquals(MappingResult.Invalid(listOf(MappingIssue(MappingIssueCode.UNKEYED_PROPERTY))), result)
        }
    }

    @Test
    fun `the photo stays until the photo baseline decides`() {
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", "PHOTO:data:image/jpeg;base64,/9j/4AAQ"))
        val remote = merge(cards)

        for (photo in listOf(null, ContactPhoto(byteArrayOf(1, 2, 3), null))) {
            val patch = success(mapper.patch(KeyedGraph(remote.graph.copy(photo = photo), remote.keys.copy(photo = null)), cards.map { fragment(it) }))
            assertFalse(patch.changed)
        }
    }

    @Test
    fun `untouched lines stay exactly as written, in their original card`() {
        val cards = listOf(
            CardPlacement.CLEAR to listOf("categories:friends"),
            CardPlacement.SIGNED to listOf("FN:Ann Lee", "item1.email;type=WORK:ann@example.test", "item1.X-PM-SIGN:true"),
            CardPlacement.ENCRYPTED to listOf(
                "N:Lee,Smith;Ann;Ruth,May;;",
                "item2.X-ABDATE;X-APPLE-OMIT-YEAR=1604:1604-07-02",
                "item2.X-ABLabel:_\$!<Other>!\$_",
                "NOTE:first",
                "title;x-source=one:Boss",
                "IMPP:xmpp:ann@example.test",
            ),
        )
        val remote = merge(cards)

        assertPatch(cards, KeyedGraph(remote.graph.copy(note = "edited"), remote.keys), mapOf("NOTE:first" to listOf("NOTE:edited")))
    }

    @Test
    fun `a folded line stays as its unfolded line`() {
        val folded = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ann\r\nNOTE:hel\r\n lo there\r\nEND:VCARD\r\n"
        val cards = listOf(CardFragment(CardPlacement.ENCRYPTED, folded))

        val patch = success(mapper.patch(mergeFragments(cards), cards))

        assertEquals(listOf("FN:Ann", "NOTE:hello there"), patch.cards.single().lines.map { it.text })
    }

    @Test
    fun `phonetic names without a name go once FN on upload gives one, and equal properties the phone keeps stay`() {
        // Phonetic names alone give no name, so the phone shows none of them; the upload's FN is the first e-mail.
        val phonetic = "X-PHONETIC-FIRST-NAME:an"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("EMAIL:a@example.test", "EMAIL:a@example.test", phonetic))
        val remote = merge(cards)
        val uploaded = remote.graph.copy(formattedName = "a@example.test")

        assertPatch(cards, remote, mapOf(phonetic to emptyList()), listOf("FN:a@example.test"), uploaded)
        assertPatch(cards, KeyedGraph(remote.graph), mapOf(phonetic to emptyList()), listOf("FN:a@example.test"), uploaded)
    }

    @Test
    fun `list values in another order are no change`() {
        val cards = listOf(CardPlacement.SIGNED to listOf("FN:Ann", "EMAIL:a@example.test", "EMAIL;TYPE=work:b@example.test"))
        val remote = merge(cards)
        val reordered = KeyedGraph(remote.graph.copy(emails = remote.graph.emails.reversed()), remote.keys.copy(emails = remote.keys.emails.reversed()))

        for (phone in listOf(reordered, KeyedGraph(reordered.graph))) {
            val patch = success(mapper.patch(phone, cards.map { fragment(it) }))
            assertFalse(patch.changed)
            assertEquals(cards.single().second, patch.cards.single().lines.map { it.text })
        }
    }

    @Test
    fun `every property that would give a one-value field its value goes with the row`() {
        val values = listOf(
            "FN:Ann", "FN:Annie", "N:Lee;Ann;;;", "N:Lee;Annie;;;", "X-PHONETIC-FIRST-NAME:an", "X-PHONETIC-FIRST-NAME:ani",
            "ORG:Acme", "ORG:Beta", "TITLE:Boss", "TITLE:Chief", "ROLE:Lead", "ROLE:Head", "BDAY:19900517", "BDAY:19910517",
            "ANNIVERSARY:20000101", "ANNIVERSARY:20010101", "NOTE:a", "NOTE:b", "NICKNAME:x", "NICKNAME:y",
        )
        val cards = listOf(CardPlacement.ENCRYPTED to values + "EMAIL:a@example.test")
        val remote = merge(cards)
        val graph = remote.graph.copy(formattedName = null, name = null, organization = null, birthday = null, anniversary = null, note = null, nickname = null)
        val keys = remote.keys.copy(name = null, organization = null, birthday = null, anniversary = null, note = null, nickname = null)

        // Every FN goes with the name; FN on upload then writes the e-mail as a new FN in the first one's place.
        assertPatch(
            cards,
            KeyedGraph(graph, keys),
            values.associateWith { emptyList<String>() } + ("FN:Ann" to listOf("FN:a@example.test")),
            uploaded = graph.copy(formattedName = "a@example.test"),
        )
    }

    @Test
    fun `a label changed alone`() {
        val email = "EMAIL;TYPE=Club:a@example.test"
        val tel = "TEL;TYPE=Boat:+1 555 0100"
        val related = "RELATED;TYPE=Coach;X-ANDROID-TYPE=custom:Bob"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", email, tel, related))
        val remote = merge(cards)
        val graph = remote.graph.copy(
            emails = remote.graph.emails.map { it.copy(customLabel = "Golf") },
            phones = remote.graph.phones.map { it.copy(customLabel = "Ship") },
            relations = remote.graph.relations.map { it.copy(customLabel = "Mentor") },
        )

        assertPatch(
            cards,
            KeyedGraph(graph, remote.keys),
            mapOf(
                email to listOf("EMAIL;TYPE=Golf;X-ANDROID-LABEL=Golf:a@example.test"),
                tel to listOf("TEL;TYPE=Ship;X-ANDROID-LABEL=Ship:+1 555 0100"),
                related to listOf("RELATED;TYPE=Mentor;X-ANDROID-TYPE=custom;X-ANDROID-LABEL=Mentor:Bob"),
            ),
        )
    }

    @Test
    fun `a line with fewer components than Android shows gets the ones it needs`() {
        val n = "N:Lee;Ann"
        val adr = "ADR:;;1 Main St"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", n, adr))
        val remote = merge(cards)
        val graph = remote.graph.copy(name = remote.graph.name!!.copy(suffix = "Jr."), addresses = remote.graph.addresses.map { it.copy(country = "Land") })

        assertPatch(cards, KeyedGraph(graph, remote.keys), mapOf(n to listOf("N:Lee;Ann;;;Jr."), adr to listOf("ADR:;;1 Main St;;;;Land")))
    }

    @Test
    fun `an organization's untouched parts stay as written`() {
        val trailing = "ORG:Acme;;Diff;"
        val noCompany = "ORG: ;Lab"
        fun patched(line: String, change: (Organization) -> Organization, edited: String) {
            val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", line))
            val remote = merge(cards)
            assertPatch(cards, KeyedGraph(remote.graph.copy(organization = change(remote.graph.organization!!)), remote.keys), mapOf(line to listOf(edited)))
        }

        patched(trailing, { it.copy(company = "Acme Inc") }, "ORG:Acme Inc;;Diff;")
        patched(noCompany, { it.copy(units = listOf("Labs")) }, "ORG: ;Labs")
    }

    @Test
    fun `a new organization without a company`() {
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann"))
        val remote = merge(cards)

        assertPatch(cards, KeyedGraph(remote.graph.copy(organization = Organization(null, listOf("Lab"))), remote.keys), added = listOf("ORG:;Lab"))
    }

    @Test
    fun `without keys, a name or organization with one part changed is new, every part written again`() {
        val fn = "FN;LANGUAGE=en:Ann Lee"
        val n = "N:Lee,Smith;Ann;;;"
        val org = "ORG;SORT-AS=acme:Acme;R&D"
        val title = "TITLE:Engineer"
        val cards = listOf(CardPlacement.SIGNED to listOf(fn), CardPlacement.ENCRYPTED to listOf(n, org, title))
        val remote = merge(cards)
        val graph = remote.graph.copy(formattedName = "Ann B. Lee", organization = remote.graph.organization!!.copy(title = "Boss"))

        assertPatch(
            cards,
            KeyedGraph(graph),
            mapOf(fn to listOf("FN:Ann B. Lee"), n to listOf("N:Lee Smith;Ann;;;"), org to listOf("ORG:Acme;R&D"), title to listOf("TITLE:Boss")),
        )
    }

    @Test
    fun `a nickname's first value with text is the one edited`() {
        val nickname = "NICKNAME:,Annie,Lee"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", nickname))
        val remote = merge(cards)

        assertPatch(cards, KeyedGraph(remote.graph.copy(nickname = "Ann"), remote.keys), mapOf(nickname to listOf("NICKNAME:,Ann,Lee")))
    }

    @Test
    fun `an edit that writes the same line is no change`() {
        // A custom type without a label writes no type, as OTHER does (the column mapper reads such a row as OTHER).
        val cards = listOf(CardPlacement.SIGNED to listOf("FN:Ann", "EMAIL:a@example.test"))
        val remote = merge(cards)
        val labelled = remote.graph.copy(emails = listOf(TypedEmail("a@example.test", EmailType.Custom, null)))

        val patch = success(mapper.patch(KeyedGraph(labelled, remote.keys), cards.map { fragment(it) }))

        assertFalse(patch.changed)
        assertEquals(listOf(PatchedLine("FN:Ann", false), PatchedLine("EMAIL:a@example.test", false)), patch.cards.single().lines)
    }

    @Test
    fun `a label with a quote or a line break is written with caret encoding`() {
        val email = "EMAIL;TYPE=home:a@example.test"
        val cards = listOf(CardPlacement.SIGNED to listOf("FN:Ann", email))
        val remote = merge(cards)
        val labelled = remote.graph.copy(emails = listOf(TypedEmail("a@example.test", EmailType.Custom, "say \"hi\"\nnow")))

        assertPatch(cards, KeyedGraph(labelled, remote.keys), mapOf(email to listOf("EMAIL;TYPE=say ^'hi^'^nnow;X-ANDROID-LABEL=say ^'hi^'^nnow:a@example.test")))
    }

    @Test
    fun `a backslash in a URI is doubled, since ez-vcard reads it as an escape`() {
        val url = "URL:https://example.test/"
        val related = "RELATED;TYPE=spouse:urn:uuid:1"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", url, related))
        val remote = merge(cards)
        val graph = remote.graph.copy(
            urls = listOf("https://example.test/a\\b;c"),
            relations = remote.graph.relations.map { it.copy(name = "urn:uuid:\\2") },
        )

        assertPatch(
            cards,
            KeyedGraph(graph.copy(urls = graph.urls + "https://example.test/new\\"), remote.keys.copy(urls = remote.keys.urls + null)),
            mapOf(url to listOf("URL:https://example.test/a\\\\b;c"), related to listOf("RELATED;TYPE=spouse:urn:uuid:\\\\2")),
            listOf("URL:https://example.test/new\\\\"),
        )
    }

    @Test
    fun `a parameter without a name is a TYPE value, and a 2_1 line is written as 2_1`() {
        // vCard 2.1 writes types bare; ez-vcard reads them as TYPE values. An edited 2.1 line is written in 2.1's
        // syntax, which has no lists of parameter values.
        val cards = listOf(CardFragment(CardPlacement.ENCRYPTED, vcard("2.1", listOf("FN:Ann", "TEL;CELL;VOICE;X-SOURCE=one;TYPE=pref:+1 555 0100"))))
        val remote = mergeFragments(cards)
        val graph = remote.graph.copy(phones = remote.graph.phones.map { it.copy(type = PhoneType.Home) })

        val patch = success(mapper.patch(KeyedGraph(graph, remote.keys), cards))

        assertEquals(listOf("FN:Ann", "TEL;TYPE=home;TYPE=VOICE;TYPE=pref;X-SOURCE=one:+1 555 0100"), patch.cards.single().lines.map { it.text })
        assertEquals(comparable(graph), comparable(mergeFragments(patch.fragments()).graph))
    }

    @Test
    fun `a 2_1 note edited with a line break, a comma, and a semicolon reads back`() {
        val cards = listOf(CardFragment(CardPlacement.ENCRYPTED, vcard("2.1", listOf("FN:Ann", "NOTE:hello"))))
        val remote = mergeFragments(cards)
        val graph = remote.graph.copy(note = "a, b;\nc")

        val patch = success(mapper.patch(KeyedGraph(graph, remote.keys), cards))

        // 2.1 holds a line break only as quoted-printable.
        assertEquals(listOf("FN:Ann", "NOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:a\\, b\\;=0Ac"), patch.cards.single().lines.map { it.text })
        assertEquals(comparable(graph), comparable(mergeFragments(patch.fragments()).graph))
    }

    @Test
    fun `phonetic names the phone doesn't show go once the phone has a name`() {
        val phonetic = "X-PHONETIC-FIRST-NAME:an"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("EMAIL:a@example.test", phonetic))
        val remote = merge(cards)

        assertPatch(cards, KeyedGraph(remote.graph.copy(formattedName = "Ann"), remote.keys), mapOf(phonetic to emptyList()), listOf("FN:Ann"))
    }

    @Test
    fun `a new group past the largest item number there can be`() {
        val date = "X-ABDATE:2002-08-03"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", "item2147483647.X-FOO:x", date))
        val remote = merge(cards)
        val labelled = remote.graph.customDates.map { it.copy(label = "Met") }

        assertPatch(
            cards,
            KeyedGraph(remote.graph.copy(customDates = labelled), remote.keys),
            mapOf(date to listOf("item2147483648.X-ABDATE:2002-08-03", "item2147483648.X-ABLabel:Met")),
        )
    }

    @Test
    fun `no toString shows content`() {
        val remote = merge(REMOTE)
        val patch = success(mapper.patch(KeyedGraph(remote.graph.copy(note = "secret note"), remote.keys), REMOTE.map { fragment(it) }))
        val texts = listOf(patch.toString()) + patch.cards.map { it.toString() } + patch.cards.flatMap { card -> card.lines.map { it.toString() } }

        for (text in texts) {
            for (word in listOf("Ann", "Lee", "example", "secret", "hello", "Acme", "EMAIL", "NOTE", "4.0")) assertFalse(word in text, text)
        }
        assertTrue("changed" in patch.toString() && "SIGNED" in patch.cards.first().toString() && "edited" in texts.last(), texts.toString())
    }

    /** One field edited on the phone, and the lines that change: an empty list removes the line. */
    class Edit(private val name: String, val change: (ContactGraph) -> ContactGraph, val lines: Map<String, List<String>>, val added: List<String> = emptyList()) {
        override fun toString() = name
    }

    /** [uploaded] is the phone's graph with the `FN` an upload gives it (docs/mapping.md § `FN` on upload). */
    private fun assertPatch(
        cards: List<Pair<CardPlacement, List<String>>>,
        phone: KeyedGraph,
        lines: Map<String, List<String>> = emptyMap(),
        added: List<String> = emptyList(),
        uploaded: ContactGraph = phone.graph,
    ) {
        val patch = success(mapper.patch(phone, cards.map { fragment(it) }))
        // A replacement that is the original line itself stays unedited (a label inserted after its date).
        val expected = cards.map { (placement, original) ->
            placement to original.flatMap { line -> lines[line]?.map { if (it == line) it else "*$it" } ?: listOf(line) }.toMutableList()
        }.toMutableList()
        // A new FN or EMAIL is signed, anything else encrypted; a card the contact doesn't have comes last. This
        // mirrors the card table only to place the expected lines; UploadCardsTest proves the placement with literal
        // cards.
        for (line in added) {
            val placement = if (line.substringBefore(':').substringBefore(';').substringAfter('.') in setOf("FN", "EMAIL")) CardPlacement.SIGNED else CardPlacement.ENCRYPTED
            (expected.firstOrNull { it.first == placement } ?: (placement to mutableListOf<String>()).also { expected += it }).second += "*$line"
        }

        assertEquals(expected, patch.cards.map { card -> card.placement to card.lines.map { (if (it.edited) "*" else "") + it.text } })
        assertEquals(lines.isNotEmpty() || added.isNotEmpty(), patch.changed)
        assertTrue(patch.cards.all { it.version == "4.0" })
        assertEquals(comparable(uploaded), comparable(mergeFragments(patch.fragments()).graph), "read back")
    }

    /** Photo and `UID` aside; an organization compares by its department text (the one lossy round trip). */
    private fun comparable(graph: ContactGraph) = graph.copy(
        uid = null,
        photo = null,
        organization = graph.organization?.let { it.copy(units = listOf(it.units.joinToString(";"))) },
    )

    private fun merge(cards: List<Pair<CardPlacement, List<String>>>): KeyedGraph = mergeFragments(cards.map(::fragment))

    private fun mergeFragments(cards: List<CardFragment>): KeyedGraph = success(mapper.merge(cards))

    private fun <T> success(result: MappingResult<T>): T = assertInstanceOf(MappingResult.Success::class.java, result).value.let {
        @Suppress("UNCHECKED_CAST")
        it as T
    }

    companion object {
        const val N = "N:Lee,Smith;Ann;;Dr;"
        const val EMAIL = "item1.EMAIL;TYPE=WORK,pref:ann@example.test"
        const val KEY = "item1.KEY;PREF=1:data:application/pgp-keys;base64,AAEC"
        const val PM_ENCRYPT = "item1.X-PM-ENCRYPT:true"
        const val EMAIL_LABEL = "item1.X-ABLabel:Club"
        const val TEL = "TEL;TYPE=cell,voice:+1 555 0100"
        const val ADR = "item2.ADR;TYPE=HOME;LANGUAGE=en:PO 9;Apt 4;1 Main St,Unit 2;Town;North;12345;Land"
        const val ADR_LABEL = "item2.X-ABLabel:Holiday home"
        const val ORG = "ORG;SORT-AS=acme:Acme;R&D"
        const val BDAY = "BDAY:1990-05-17T10:30:00+02:00"
        const val ANNIVERSARY = "ANNIVERSARY;X-APPLE-OMIT-YEAR=1604:1604-06-01"
        const val ABDATE = "item3.X-ABDATE:2001-07-02"
        const val ABDATE_LABEL = "item3.X-ABLabel:_\$!<Anniversary>!\$_"
        const val NOTE = "NOTE;LANGUAGE=en:hello"
        const val URL = "URL;TYPE=home:https://example.test/a,b"
        const val NICKNAME = "NICKNAME:Annie,Lee"
        const val RELATED = "RELATED;TYPE=spouse:Bob"
        const val SIP = "X-SIP;TYPE=work:sip:ann@example.test"

        val REMOTE = listOf(
            CardPlacement.SIGNED to listOf("FN:Ann Lee", EMAIL, KEY, PM_ENCRYPT, EMAIL_LABEL, "UID:urn:uuid:ann-1"),
            CardPlacement.ENCRYPTED to listOf(
                N, "X-PHONETIC-FIRST-NAME:an", TEL, ADR, ADR_LABEL, ORG, "TITLE:Engineer", "ROLE:Lead", BDAY, ANNIVERSARY, ABDATE,
                ABDATE_LABEL, NOTE, "NOTE:second", URL, NICKNAME, RELATED, SIP, "CATEGORIES:friends,family", "IMPP:xmpp:ann@example.test",
                "GENDER:F", "REV:20200101T000000Z",
            ),
        )

        fun fragment(card: Pair<CardPlacement, List<String>>) = CardFragment(card.first, vcard("4.0", card.second))

        fun vcard(version: String, lines: List<String>) =
            (listOf("BEGIN:VCARD", "VERSION:$version") + lines + "END:VCARD").joinToString("\r\n", postfix = "\r\n")

        private fun name(change: (StructuredName) -> StructuredName) = { g: ContactGraph -> g.copy(name = change(g.name!!)) }
        private fun email(change: (TypedEmail) -> TypedEmail) = { g: ContactGraph -> g.copy(emails = g.emails.map(change)) }
        private fun phone(change: (TypedPhone) -> TypedPhone) = { g: ContactGraph -> g.copy(phones = g.phones.map(change)) }
        private fun address(change: (TypedAddress) -> TypedAddress) = { g: ContactGraph -> g.copy(addresses = g.addresses.map(change)) }
        private fun organization(change: (Organization) -> Organization) = { g: ContactGraph -> g.copy(organization = change(g.organization!!)) }
        private fun customDate(change: (LabeledDate) -> LabeledDate) = { g: ContactGraph -> g.copy(customDates = g.customDates.map(change)) }
        private fun relation(change: (TypedRelation) -> TypedRelation) = { g: ContactGraph -> g.copy(relations = g.relations.map(change)) }

        @JvmStatic
        fun edits() = listOf(
            Edit("FN", { it.copy(formattedName = "Ann B. Lee") }, mapOf("FN:Ann Lee" to listOf("FN:Ann B. Lee"))),
            Edit("N given, the family's two values kept", name { it.copy(given = "Anna") }, mapOf(N to listOf("N:Lee,Smith;Anna;;Dr;"))),
            Edit("N family of two values, as one", name { it.copy(family = "Lee") }, mapOf(N to listOf("N:Lee;Ann;;Dr;"))),
            Edit("N suffix added", name { it.copy(suffix = "Jr.") }, mapOf(N to listOf("N:Lee,Smith;Ann;;Dr;Jr."))),
            Edit("phonetic name", name { it.copy(phoneticGiven = "ann") }, mapOf("X-PHONETIC-FIRST-NAME:an" to listOf("X-PHONETIC-FIRST-NAME:ann"))),
            Edit("phonetic name added", name { it.copy(phoneticMiddle = "b") }, emptyMap(), listOf("X-PHONETIC-MIDDLE-NAME:b")),
            Edit("phonetic name removed", name { it.copy(phoneticGiven = null) }, mapOf("X-PHONETIC-FIRST-NAME:an" to emptyList())),
            Edit(
                "e-mail address, losing its KEY and X-PM-*",
                email { it.copy(value = "ann,lee@work.example") },
                mapOf(EMAIL to listOf("item1.EMAIL;TYPE=WORK,pref:ann\\,lee@work.example"), KEY to emptyList(), PM_ENCRYPT to emptyList()),
            ),
            Edit("e-mail type", email { it.copy(type = EmailType.Home) }, mapOf(EMAIL to listOf("item1.EMAIL;TYPE=home,pref:ann@example.test"))),
            Edit("e-mail label", email { it.copy(type = EmailType.Custom, customLabel = "Club") }, mapOf(EMAIL to listOf("item1.EMAIL;TYPE=Club,pref;X-ANDROID-LABEL=Club:ann@example.test"))),
            Edit("e-mail OTHER", email { it.copy(type = EmailType.Other) }, mapOf(EMAIL to listOf("item1.EMAIL;TYPE=pref:ann@example.test"))),
            Edit("phone number", phone { it.copy(value = "+1 555 0199") }, mapOf(TEL to listOf("TEL;TYPE=cell,voice:+1 555 0199"))),
            Edit("phone type", phone { it.copy(type = PhoneType.FaxHome) }, mapOf(TEL to listOf("TEL;TYPE=fax,home,voice:+1 555 0100"))),
            Edit("phone OTHER", phone { it.copy(type = PhoneType.Other) }, mapOf(TEL to listOf("TEL;TYPE=voice:+1 555 0100"))),
            Edit("phone label", phone { it.copy(type = PhoneType.Custom, customLabel = "Boat") }, mapOf(TEL to listOf("TEL;TYPE=Boat,voice;X-ANDROID-LABEL=Boat:+1 555 0100"))),
            Edit(
                "address street of two values, as one",
                address { it.copy(street = "2 Main St") },
                mapOf(ADR to listOf("item2.ADR;TYPE=HOME;LANGUAGE=en:PO 9;Apt 4;2 Main St;Town;North;12345;Land")),
            ),
            Edit(
                "address city, the street's two values kept",
                address { it.copy(city = "City") },
                mapOf(ADR to listOf("item2.ADR;TYPE=HOME;LANGUAGE=en:PO 9;Apt 4;1 Main St,Unit 2;City;North;12345;Land")),
            ),
            Edit("address region removed", address { it.copy(region = null) }, mapOf(ADR to listOf("item2.ADR;TYPE=HOME;LANGUAGE=en:PO 9;Apt 4;1 Main St,Unit 2;Town;;12345;Land"))),
            Edit("address type", address { it.copy(type = AddressType.Work) }, mapOf(ADR to listOf("item2.ADR;TYPE=work;LANGUAGE=en:PO 9;Apt 4;1 Main St,Unit 2;Town;North;12345;Land"))),
            Edit("company", organization { it.copy(company = "Acme Inc") }, mapOf(ORG to listOf("ORG;SORT-AS=acme:Acme Inc;R&D"))),
            Edit("department", organization { it.copy(units = listOf("Labs")) }, mapOf(ORG to listOf("ORG;SORT-AS=acme:Acme;Labs"))),
            Edit("title", organization { it.copy(title = "Boss") }, mapOf("TITLE:Engineer" to listOf("TITLE:Boss"))),
            Edit("role", organization { it.copy(role = "Head") }, mapOf("ROLE:Lead" to listOf("ROLE:Head"))),
            Edit("birthday, its time and offset kept", { it.copy(birthday = PartialDate(6, 17, 1990)) }, mapOf(BDAY to listOf("BDAY:1990-06-17T10:30:00+02:00"))),
            Edit(
                "anniversary, its placeholder year kept",
                { it.copy(anniversary = PartialDate(7, 1, null)) },
                mapOf(ANNIVERSARY to listOf("ANNIVERSARY;X-APPLE-OMIT-YEAR=1604:1604-07-01")),
            ),
            Edit("custom date", customDate { it.copy(date = PartialDate(8, 2, 2001)) }, mapOf(ABDATE to listOf("item3.X-ABDATE:2001-08-02"))),
            Edit("custom date label", customDate { it.copy(label = "Met") }, mapOf(ABDATE_LABEL to listOf("item3.X-ABLabel:Met"))),
            Edit("custom date label removed", customDate { it.copy(label = null) }, mapOf(ABDATE_LABEL to emptyList())),
            Edit(
                "note, the later NOTE kept, a long line not folded",
                { it.copy(note = "hi, there. " + "x".repeat(80)) },
                mapOf(NOTE to listOf("NOTE;LANGUAGE=en:hi\\, there. " + "x".repeat(80))),
            ),
            Edit("URL, written as it is", { it.copy(urls = listOf("https://example.test/c,d")) }, mapOf(URL to listOf("URL;TYPE=home:https://example.test/c,d"))),
            Edit("nickname, its further values kept", { it.copy(nickname = "Ann") }, mapOf(NICKNAME to listOf("NICKNAME:Ann,Lee"))),
            Edit("relation name", relation { it.copy(name = "Rob") }, mapOf(RELATED to listOf("RELATED;TYPE=spouse:Rob"))),
            Edit("relation type", relation { it.copy(type = RelationType.Friend) }, mapOf(RELATED to listOf("RELATED;TYPE=friend;X-ANDROID-TYPE=friend:Bob"))),
            Edit(
                "relation label",
                relation { it.copy(type = RelationType.Custom, customLabel = "Coach") },
                mapOf(RELATED to listOf("RELATED;TYPE=Coach;X-ANDROID-TYPE=custom;X-ANDROID-LABEL=Coach:Bob")),
            ),
            Edit("SIP address, its scheme kept", { it.copy(sipAddresses = listOf("ann2@example.test")) }, mapOf(SIP to listOf("X-SIP;TYPE=work:sip:ann2@example.test"))),
        )
    }
}
