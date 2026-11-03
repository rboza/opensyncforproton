package dev.rboza.android.opensyncforproton.mapping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ContactMapperTest {
    private companion object {
        /** A JPEG's first bytes: enough to tell bytes apart, not a whole image (decoding is `:sync`'s job). */
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        const val JPEG_BASE64 = "/9j/4AAQ"

        /** A PNG's signature. */
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val PNG_BASE64 = "iVBORw0KGgo="
    }

    private val mapper: ContactMapper = EzvcardContactMapper()

    @Test
    fun `merge reads name, uid, and work email`() {
        val vcard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:urn:uuid:ada-1
            FN:Ada Lovelace
            N:Lovelace;Ada;;;
            EMAIL;TYPE=work:ada@example.org
            END:VCARD
        """.trimIndent().replace("\n", "\r\n")

        val result = mapper.merge(listOf(CardFragment(CardPlacement.SIGNED, vcard)))
        val graph = assertInstanceOf(KeyedGraph::class.java, assertInstanceOf(MappingResult.Success::class.java, result).value).graph

        assertEquals("Ada Lovelace", graph.formattedName)
        assertEquals("Ada", graph.name?.given)
        assertEquals("Lovelace", graph.name?.family)
        assertEquals("urn:uuid:ada-1", graph.uid)
        assertEquals(listOf(TypedEmail("ada@example.org", EmailType.Work)), graph.emails)
        assertTrue(graph.phones.isEmpty())
    }

    @Test
    fun `clear, signed, and encrypted fragments all contribute`() {
        val graph = success(
            fragment(CardPlacement.CLEAR, "EMAIL:clear@example.org"),
            fragment(CardPlacement.SIGNED, "UID:urn:uuid:ada-1", "FN:Ada Lovelace", "EMAIL;TYPE=work:ada@example.org"),
            fragment(CardPlacement.ENCRYPTED, "UID:urn:uuid:ada-1", "N:Lovelace;Ada;;;", "EMAIL:secret@example.org"),
        )

        assertEquals(
            ContactGraph(
                uid = "urn:uuid:ada-1",
                formattedName = "Ada Lovelace",
                name = StructuredName(given = "Ada", family = "Lovelace"),
                emails = listOf(
                    TypedEmail("clear@example.org", EmailType.Other),
                    TypedEmail("ada@example.org", EmailType.Work),
                    TypedEmail("secret@example.org", EmailType.Other),
                ),
            ),
            graph,
        )
    }

    @Test
    fun `no FN or N gives no name`() {
        val graph = success(fragment(CardPlacement.SIGNED, "EMAIL:ada@example.org"))

        assertNull(graph.formattedName)
        assertNull(graph.name)
    }

    @Test
    fun `blank FN and N give no name`() {
        val graph = success(fragment(CardPlacement.SIGNED, "FN:", "N:;;;;", "EMAIL:ada@example.org"))

        assertNull(graph.formattedName)
        assertNull(graph.name)
    }

    @Test
    fun `a card with only VERSION, UID, and PRODID is invalid`() {
        assertInvalid(
            MappingIssueCode.NO_CONTENT,
            fragment(CardPlacement.SIGNED, "UID:urn:uuid:ada-1", "PRODID:-//Example//Test//EN"),
            fragment(CardPlacement.ENCRYPTED, "UID:urn:uuid:ada-1"),
        )
    }

    @Test
    fun `a fragment with only UID doesn't empty a contact that has content elsewhere`() {
        val graph = success(
            fragment(CardPlacement.SIGNED, "UID:urn:uuid:ada-1", "FN:Ada Lovelace"),
            fragment(CardPlacement.ENCRYPTED, "UID:urn:uuid:ada-1", "PRODID:-//Example//Test//EN"),
        )

        assertEquals("Ada Lovelace", graph.formattedName)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "not a vcard",
            "",
            // Truncated: no END:VCARD.
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada\r\n",
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada\r\na line without a colon\r\nEND:VCARD\r\n",
            "BEGIN:VCARD\r\nFN:Ada\r\nEND:VCARD\r\n",
            "BEGIN:VCARD\r\nVERSION:9.9\r\nFN:Ada\r\nEND:VCARD\r\n",
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada\r\nNOTE;ENCODING=QUOTED-PRINTABLE:=ZZ\r\nEND:VCARD\r\n",
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada\r\nNOTE;ENCODING=QUOTED-PRINTABLE;CHARSET=no-such-charset:abc\r\nEND:VCARD\r\n",
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada\r\nEND:VCARD\r\nBEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bob\r\nEND:VCARD\r\n",
            "junk\r\nBEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada\r\nEND:VCARD\r\n",
            // ez-vcard stops at the first END and drops the rest without a warning.
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada\r\nEND:VCARD\r\nEMAIL:ada@example.org\r\nEND:VCARD\r\n",
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada\r\nEND:VCARD\r\nFN:Bob\r\nEND:VCARD\r\n",
            // A nested card whose outer END is missing: truncated.
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada\r\nBEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bob\r\nEND:VCARD\r\n",
        ],
    )
    fun `a malformed vcard is invalid`(vcard: String) {
        assertInvalid(MappingIssueCode.MALFORMED_INPUT, CardFragment(CardPlacement.SIGNED, vcard))
    }

    @Test
    fun `one bad fragment invalidates the whole contact`() {
        assertInvalid(
            MappingIssueCode.MALFORMED_INPUT,
            fragment(CardPlacement.SIGNED, "UID:urn:uuid:ada-1", "FN:Ada Lovelace"),
            CardFragment(CardPlacement.ENCRYPTED, "BEGIN:VCARD\r\nVERSION:4.0\r\nNOTE:cut off"),
        )
    }

    @Test
    fun `a value that can't be parsed doesn't invalidate the contact`() {
        val graph = success(fragment(CardPlacement.SIGNED, "FN:Ada Lovelace", "BDAY:not a date"))

        assertEquals("Ada Lovelace", graph.formattedName)
    }

    @Test
    fun `fragments with different UIDs are invalid`() {
        assertInvalid(
            MappingIssueCode.CONFLICTING_UID,
            fragment(CardPlacement.SIGNED, "UID:urn:uuid:ada-1", "FN:Ada Lovelace"),
            fragment(CardPlacement.CLEAR, "UID:urn:uuid:ada-2", "EMAIL:ada@example.org"),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "UID:urn:uuid:ada-2",
            "uid:urn:uuid:ada-2",
            "item1.UID:urn:uuid:ada-2",
            "UID;VALUE=text:urn:uuid:ada-2",
            "UID:urn:uuid:ada-2\r\nUID:urn:uuid:ada-3",
        ],
    )
    fun `a UID in an encrypted fragment is ignored`(uidLines: String) {
        val graph = success(
            fragment(CardPlacement.SIGNED, "UID:urn:uuid:ada-1", "FN:Ada Lovelace"),
            fragment(CardPlacement.ENCRYPTED, uidLines, "EMAIL:ada@example.org"),
        )

        assertEquals(
            ContactGraph(uid = "urn:uuid:ada-1", formattedName = "Ada Lovelace", emails = listOf(TypedEmail("ada@example.org", EmailType.Other))),
            graph,
        )
    }

    @Test
    fun `an encrypted fragment's UID isn't the contact's UID`() {
        val graph = success(
            fragment(CardPlacement.SIGNED, "FN:Ada Lovelace"),
            fragment(CardPlacement.ENCRYPTED, "UID:urn:uuid:ada-1", "EMAIL:ada@example.org"),
        )

        assertNull(graph.uid)
    }

    @Test
    fun `a blank UID doesn't conflict`() {
        val graph = success(
            fragment(CardPlacement.SIGNED, "UID:urn:uuid:ada-1", "FN:Ada Lovelace"),
            fragment(CardPlacement.CLEAR, "UID:", "EMAIL:ada@example.org"),
        )

        assertEquals("urn:uuid:ada-1", graph.uid)
    }

    @Test
    fun `UIDs are compared without surrounding spaces`() {
        val graph = success(
            fragment(CardPlacement.SIGNED, "UID:urn:uuid:ada-1", "FN:Ada Lovelace"),
            fragment(CardPlacement.CLEAR, "UID: urn:uuid:ada-1 ", "EMAIL:ada@example.org"),
        )

        assertEquals("urn:uuid:ada-1", graph.uid)
    }

    // The EMAIL row of docs/mapping.md § Types. Each case is the text before the colon.
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "EMAIL                         | Other  |",
            "EMAIL;TYPE=                   | Other  |",
            "'EMAIL;TYPE= '                | Other  |",
            "EMAIL;TYPE=home               | Home   |",
            "EMAIL;TYPE=HOME               | Home   |",
            "EMAIL;TYPE=work               | Work   |",
            "EMAIL;TYPE=other              | Other  |",
            "EMAIL;TYPE=pref               | Other  |",
            "EMAIL;TYPE=internet           | Other  |",
            "EMAIL;TYPE=PREF,INTERNET      | Other  |",
            "EMAIL;TYPE=internet,work      | Work   |",
            "EMAIL;TYPE=pref;TYPE=home     | Home   |",
            "EMAIL;TYPE=work,home          | Work   |",
            "EMAIL;TYPE=home;TYPE=work     | Home   |",
            "EMAIL;TYPE=x-private          | Custom | x-private",
            "EMAIL;TYPE=Private            | Custom | Private",
            "EMAIL;TYPE=x-private,home     | Home   |",
            "item1.EMAIL;TYPE=work         | Work   |",
            // X-ANDROID-LABEL is the label while TYPE's values still begin with it.
            "EMAIL;TYPE=Home;X-ANDROID-LABEL=Home                 | Custom | Home",
            "EMAIL;TYPE=work,pref;X-ANDROID-LABEL=work            | Custom | work",
            "EMAIL;TYPE=pref;X-ANDROID-LABEL=pref                 | Custom | pref",
            "EMAIL;TYPE=internet,pref;X-ANDROID-LABEL=internet    | Custom | internet",
            "EMAIL;type=Other;x-android-label=Other               | Custom | Other",
            "EMAIL;TYPE=\"Home, Gym\";X-ANDROID-LABEL=\"Home, Gym\" | Custom | 'Home, Gym'",
            "EMAIL;TYPE=a,,b;X-ANDROID-LABEL=\"a,,b\"             | Custom | 'a,,b'",
            // Unquoted, the label's comma splits it, so its values are joined again.
            "EMAIL;TYPE=Home,Gym;X-ANDROID-LABEL=Home,Gym         | Custom | 'Home,Gym'",
            // TYPE changed by another app (Proton's type picker), so the TYPE rules apply.
            "EMAIL;TYPE=work;X-ANDROID-LABEL=Home                 | Work   |",
            "EMAIL;TYPE=home;X-ANDROID-LABEL=Home                 | Home   |",
            "EMAIL;TYPE=Homely;X-ANDROID-LABEL=Home               | Custom | Homely",
            "EMAIL;TYPE=pref,Home;X-ANDROID-LABEL=Home            | Home   |",
            "EMAIL;X-ANDROID-LABEL=Home                           | Other  |",
            "'EMAIL;TYPE= ;X-ANDROID-LABEL= '                     | Other  |",
        ],
    )
    fun `email TYPE parameters`(property: String, type: EmailType, label: String?) {
        val graph = success(fragment(CardPlacement.SIGNED, "FN:Ada", "$property:ada@example.org"))

        assertEquals(listOf(TypedEmail("ada@example.org", type, label)), graph.emails)
    }

    // The TEL row of docs/mapping.md § Types.
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "TEL                           | Other   |",
            "TEL;TYPE=                     | Other   |",
            "'TEL;TYPE= '                  | Other   |",
            "TEL;TYPE=home                 | Home    |",
            "TEL;TYPE=cell                 | Mobile  |",
            "TEL;TYPE=CELL                 | Mobile  |",
            "TEL;TYPE=work                 | Work    |",
            "TEL;TYPE=fax,home             | FaxHome |",
            "TEL;TYPE=home,fax             | FaxHome |",
            "TEL;TYPE=FAX,HOME             | FaxHome |",
            "TEL;TYPE=fax                  | FaxWork |",
            "TEL;TYPE=fax,work             | FaxWork |",
            "TEL;TYPE=work,fax             | FaxWork |",
            "TEL;TYPE=cell,fax             | FaxWork |",
            "TEL;TYPE=pager                | Pager   |",
            "TEL;TYPE=main                 | Main    |",
            "TEL;TYPE=other                | Other   |",
            "TEL;TYPE=voice                | Other   |",
            "TEL;TYPE=pref                 | Other   |",
            "TEL;TYPE=internet             | Other   |",
            "TEL;TYPE=voice,cell           | Mobile  |",
            "TEL;TYPE=VOICE                | Other   |",
            "TEL;TYPE=cell,home            | Mobile  |",
            "TEL;TYPE=text                 | Custom  | text",
            "TEL;TYPE=x-car                | Custom  | x-car",
            "TEL;TYPE=x-a,x-b              | Custom  | x-a",
            "TEL;TYPE=pref,x-car           | Custom  | x-car",
            "TEL;TYPE=x-car,work           | Work    |",
            "TEL;TYPE=voice;X-ANDROID-LABEL=voice         | Custom  | voice",
            "TEL;TYPE=fax,voice;X-ANDROID-LABEL=fax       | Custom  | fax",
            "TEL;TYPE=Cell;X-ANDROID-LABEL=Cell           | Custom  | Cell",
            "TEL;TYPE=cell;X-ANDROID-LABEL=Gym            | Mobile  |",
        ],
    )
    fun `phone TYPE parameters`(property: String, type: PhoneType, label: String?) {
        val graph = success(fragment(CardPlacement.SIGNED, "FN:Ada", "$property:+41 79 000 00 00"))

        assertEquals(listOf(TypedPhone("+41 79 000 00 00", type, label)), graph.phones)
    }

    @Test
    fun `phones from every fragment are kept in order`() {
        val graph = success(
            fragment(CardPlacement.SIGNED, "FN:Ada", "TEL;TYPE=cell:+41 79 000 00 01"),
            fragment(CardPlacement.ENCRYPTED, "TEL;TYPE=home:+41 44 000 00 02"),
        )

        assertEquals(
            listOf(TypedPhone("+41 79 000 00 01", PhoneType.Mobile), TypedPhone("+41 44 000 00 02", PhoneType.Home)),
            graph.phones,
        )
    }

    // A tel URI gives its number and extension only.
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "tel:+41-44-000-00-00                                          | +41-44-000-00-00",
            "tel:+41-44-000-00-00;ext=12                                   | +41-44-000-00-00;ext=12",
            "tel:+41-44-000-00-00;isub=3;phone-context=example.org;x-a=b   | +41-44-000-00-00",
            "tel:+41-44-000-00-00;x-a=b;ext=12                             | +41-44-000-00-00;ext=12",
            "tel:+41%2044%20000                                            | +41 44 000",
            "tel:+41%FF44                                                  | +41%FF44",
            "tel:+41%zz44                                                  | +41%zz44",
            "TEL:+41-44-000-00-00                                          | +41-44-000-00-00",
        ],
    )
    fun `a tel URI keeps its number and extension`(uri: String, number: String) {
        val graph = success(fragment(CardPlacement.SIGNED, "FN:Ada", "TEL;VALUE=uri;TYPE=work:$uri"))

        assertEquals(listOf(TypedPhone(number, PhoneType.Work)), graph.phones)
    }

    @ParameterizedTest
    @ValueSource(strings = ["tel:;ext=1", "tel:", "tel:%20"])
    fun `a tel URI without a number is dropped`(uri: String) {
        val graph = success(fragment(CardPlacement.SIGNED, "FN:Ada", "TEL;VALUE=uri:$uri"))

        assertTrue(graph.phones.isEmpty())
    }

    @Test
    fun `blank email and phone values are dropped`() {
        val graph = success(fragment(CardPlacement.SIGNED, "FN:Ada", "EMAIL;TYPE=work: ", "TEL;TYPE=cell: "))

        assertTrue(graph.emails.isEmpty())
        assertTrue(graph.phones.isEmpty())
    }

    @Test
    fun `every part of N is read`() {
        val graph = success(fragment(CardPlacement.SIGNED, "N:Lovelace;Ada;Augusta;Hon.;FRS"))

        assertEquals(
            StructuredName(given = "Ada", family = "Lovelace", prefix = "Hon.", middle = "Augusta", suffix = "FRS"),
            graph.name,
        )
    }

    @Test
    fun `extra middle names, prefixes, and suffixes are joined with a space`() {
        val graph = success(fragment(CardPlacement.SIGNED, "N:Lovelace;Ada;Augusta,King;Hon.,Dr.;FRS,PhD"))

        assertEquals(
            StructuredName(given = "Ada", family = "Lovelace", prefix = "Hon. Dr.", middle = "Augusta King", suffix = "FRS PhD"),
            graph.name,
        )
    }

    @Test
    fun `extra family and given names are joined with a space`() {
        val graph = success(fragment(CardPlacement.SIGNED, "N:Byron,Lovelace;Ada,Augusta;;;"))

        assertEquals(StructuredName(given = "Ada Augusta", family = "Byron Lovelace"), graph.name)
    }

    @Test
    fun `an escaped comma in N is text, not a second value`() {
        val graph = success(fragment(CardPlacement.SIGNED, "N:Lovelace\\, King;Ada;;;"))

        assertEquals(StructuredName(given = "Ada", family = "Lovelace, King"), graph.name)
    }

    @Test
    fun `a vCard 2_1 N has no value lists`() {
        val card = "BEGIN:VCARD\r\nVERSION:2.1\r\nN:Lovelace, King;Ada\r\nEND:VCARD\r\n"

        assertEquals(StructuredName(given = "Ada", family = "Lovelace, King"), success(CardFragment(CardPlacement.SIGNED, card)).name)
    }

    @Test
    fun `phonetic names and SIP addresses are unescaped`() {
        val graph = success(
            fragment(CardPlacement.ENCRYPTED, "FN:Ada", "X-PHONETIC-LAST-NAME:LUV\\, lace", "X-SIP:sip:ada\\;home@example.org"),
        )

        assertEquals("LUV, lace", graph.name?.phoneticFamily)
        assertEquals(listOf("ada;home@example.org"), graph.sipAddresses)
    }

    @Test
    fun `a name with only a prefix is kept`() {
        val graph = success(fragment(CardPlacement.SIGNED, "N:;;;Dr.;", "EMAIL:ada@example.org"))

        assertEquals(StructuredName(given = null, family = null, prefix = "Dr."), graph.name)
    }

    @Test
    fun `phonetic names are part of the structured name`() {
        val graph = success(
            fragment(
                CardPlacement.ENCRYPTED,
                "N:Lovelace;Ada;;;",
                "X-PHONETIC-FIRST-NAME:AY-duh",
                "X-PHONETIC-MIDDLE-NAME:aw-GUS-tuh",
                "X-PHONETIC-LAST-NAME:LUV-lace",
            ),
        )

        assertEquals(
            StructuredName(
                given = "Ada",
                family = "Lovelace",
                phoneticGiven = "AY-duh",
                phoneticMiddle = "aw-GUS-tuh",
                phoneticFamily = "LUV-lace",
            ),
            graph.name,
        )
    }

    // A phonetic name alone never makes a name.
    @Test
    fun `phonetic names need FN or N`() {
        assertEquals(
            StructuredName(given = null, family = null, phoneticFamily = "LUV-lace"),
            success(fragment(CardPlacement.SIGNED, "FN:Ada Lovelace", "X-PHONETIC-LAST-NAME:LUV-lace")).name,
        )
        assertNull(success(fragment(CardPlacement.SIGNED, "EMAIL:ada@example.org", "X-PHONETIC-LAST-NAME:LUV-lace")).name)
        assertNull(success(fragment(CardPlacement.SIGNED, "FN: ", "EMAIL:ada@example.org", "X-PHONETIC-LAST-NAME:LUV-lace")).name)
        assertNull(success(fragment(CardPlacement.SIGNED, "FN:Ada Lovelace", "X-PHONETIC-LAST-NAME: ")).name)
    }

    @Test
    fun `organization takes the first ORG, TITLE, and ROLE`() {
        val graph = success(
            fragment(
                CardPlacement.ENCRYPTED,
                "ORG:Analytical Engines;Research;Difference",
                "ORG:Second Org",
                "TITLE:Countess",
                "TITLE:Second title",
                "ROLE:Mathematician",
                "ROLE:Second role",
            ),
        )

        assertEquals(
            Organization(
                company = "Analytical Engines",
                units = listOf("Research", "Difference"),
                title = "Countess",
                role = "Mathematician",
            ),
            graph.organization,
        )
    }

    @Test
    fun `organization parts can be missing`() {
        assertEquals(
            Organization(company = null, units = listOf("Research")),
            success(fragment(CardPlacement.SIGNED, "ORG:;Research")).organization,
        )
        assertEquals(
            Organization(company = null, title = "Countess"),
            success(fragment(CardPlacement.SIGNED, "TITLE:Countess")).organization,
        )
        assertNull(success(fragment(CardPlacement.SIGNED, "FN:Ada", "ORG:;", "TITLE: ", "ROLE:")).organization)
    }

    @Test
    fun `note, nickname, and URLs`() {
        val graph = success(
            fragment(
                CardPlacement.ENCRYPTED,
                "NOTE: ",
                "NOTE:First line\\nsecond line\\, with a comma",
                "NOTE:Second note",
                "NICKNAME:Enchantress,Ada",
                "NICKNAME:Other",
                "URL:https://example.org/ada",
                "URL: ",
                "URL:https://example.org/engines",
            ),
        )

        assertEquals("First line\nsecond line, with a comma", graph.note)
        assertEquals("Enchantress", graph.nickname)
        assertEquals(listOf("https://example.org/ada", "https://example.org/engines"), graph.urls)
    }

    @Test
    fun `SIP addresses drop a sip scheme`() {
        val graph = success(
            fragment(
                CardPlacement.ENCRYPTED,
                "X-SIP;TYPE=work:sip:ada@example.org",
                "X-SIP:SIP:ada.home@example.org",
                "X-SIP:ada.other@example.org",
                "X-SIP: ",
                "X-SIP:sip:",
            ),
        )

        assertEquals(listOf("ada@example.org", "ada.home@example.org", "ada.other@example.org"), graph.sipAddresses)
    }

    // The "first non-blank" and "blank dropped" rules of docs/mapping.md § Field table: a blank always comes first
    // here.
    @Test
    fun `blank values before real ones are skipped`() {
        val graph = success(
            fragment(
                CardPlacement.ENCRYPTED,
                "N:;;;;",
                "N:Lovelace,;Ada;;;",
                "X-PHONETIC-LAST-NAME: ",
                "X-PHONETIC-LAST-NAME:LUV-lace",
                "ORG:;",
                "ORG:Analytical Engines;;Difference",
                "TITLE: ",
                "TITLE:Countess",
                "ROLE: ",
                "ROLE:Mathematician",
                "NICKNAME:,",
                "NICKNAME:Enchantress",
            ),
        )

        assertEquals(StructuredName(given = "Ada", family = "Lovelace", phoneticFamily = "LUV-lace"), graph.name)
        assertEquals(
            Organization(company = "Analytical Engines", units = listOf("Difference"), title = "Countess", role = "Mathematician"),
            graph.organization,
        )
        assertEquals("Enchantress", graph.nickname)
    }

    @Test
    fun `SIP addresses are trimmed after the scheme is dropped`() {
        val graph = success(fragment(CardPlacement.SIGNED, "FN:Ada", "X-SIP: sip: ada@example.org "))

        assertEquals(listOf("ada@example.org"), graph.sipAddresses)
    }

    @Test
    fun `blank note and nickname give none`() {
        val graph = success(fragment(CardPlacement.SIGNED, "FN:Ada", "NOTE: ", "NICKNAME:"))

        assertNull(graph.note)
        assertNull(graph.nickname)
    }

    @Test
    fun `properties Android can't show are ignored`() {
        val graph = success(
            fragment(
                CardPlacement.ENCRYPTED,
                "FN:Ada",
                "IMPP:xmpp:ada@example.org",
                "CATEGORIES:Friends,Mathematicians",
                "KEY;VALUE=uri:https://example.org/ada.asc",
                "item1.EMAIL:ada@example.org",
                "item1.X-PM-ENCRYPT:true",
                "item1.X-PM-SIGN:true",
                "GENDER:F",
                "REV:20260930T120000Z",
            ),
        )

        assertEquals(ContactGraph(uid = null, formattedName = "Ada", emails = listOf(TypedEmail("ada@example.org", EmailType.Other))), graph)
    }

    // BDAY: every date form with a month and a day.
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "BDAY:18151210                                 | 12 | 10 | 1815",
            "BDAY:1815-12-10                               | 12 | 10 | 1815",
            "BDAY:18151210T103000Z                         | 12 | 10 | 1815",
            "BDAY:1815-12-10T10:30:00+02:00                | 12 | 10 | 1815",
            // A time with an offset gives its date at that offset, not in UTC.
            "BDAY:18151210T233000-0500                     | 12 | 10 | 1815",
            "BDAY:18151210T003000+0200                     | 12 | 10 | 1815",
            "BDAY:--0229                                   | 2  | 29 |",
            // Year 0 is a "no year" placeholder, never a real year.
            "BDAY:00001210                                 | 12 | 10 |",
            "BDAY:--1210                                   | 12 | 10 |",
            "BDAY:--12-10                                  | 12 | 10 |",
            "BDAY;X-APPLE-OMIT-YEAR=1604:1604-12-10        | 12 | 10 |",
            "BDAY;X-APPLE-OMIT-YEAR=1604:1815-12-10        | 12 | 10 | 1815",
        ],
    )
    fun `birthday dates`(property: String, month: Int, day: Int, year: Int?) {
        val graph = success(fragment(CardPlacement.ENCRYPTED, "FN:Ada", property))

        assertEquals(PartialDate(month, day, year), graph.birthday)
    }

    // Text, or a date Android can't hold (no month or no day).
    @ParameterizedTest
    @ValueSource(
        strings = [
            "BDAY;VALUE=text:circa 1815",
            "BDAY:not a date",
            "BDAY:18151310",
            "BDAY:1815",
            "BDAY:1815-12",
            "BDAY:---10",
            // Dates that don't exist.
            "BDAY:18150230",
            "BDAY:1815-02-30",
            "BDAY:--0230",
            "BDAY:18150229",
        ],
    )
    fun `a birthday without a month and a day isn't mapped`(property: String) {
        assertNull(success(fragment(CardPlacement.ENCRYPTED, "FN:Ada", property)).birthday)
    }

    @Test
    fun `the first birthday that maps wins`() {
        val graph = success(
            fragment(CardPlacement.SIGNED, "FN:Ada", "BDAY;VALUE=text:circa 1815"),
            fragment(CardPlacement.ENCRYPTED, "BDAY:18151210", "BDAY:18160101"),
        )

        assertEquals(PartialDate(12, 10, 1815), graph.birthday)
    }

    @Test
    fun `anniversary reads like a birthday`() {
        assertEquals(
            PartialDate(7, 8, 1835),
            success(fragment(CardPlacement.ENCRYPTED, "FN:Ada", "ANNIVERSARY:18350708")).anniversary,
        )
        assertEquals(
            PartialDate(7, 8, null),
            success(fragment(CardPlacement.ENCRYPTED, "FN:Ada", "ANNIVERSARY:--0708")).anniversary,
        )
        assertNull(success(fragment(CardPlacement.ENCRYPTED, "FN:Ada", "ANNIVERSARY;VALUE=text:June")).anniversary)
    }

    // X-ABDATE + X-ABLabel: Apple's other dates, labelled by the property in the same group.
    @Test
    fun `other dates take the label from their group`() {
        val graph = success(
            CardFragment(
                CardPlacement.ENCRYPTED,
                listOf(
                    "BEGIN:VCARD",
                    "VERSION:3.0",
                    "FN:Ada",
                    "item1.X-ABDATE;type=pref:1842-01-01",
                    // The group's case differs from its date's: groups match in any case.
                    "ITEM1.X-ABLabel:Translation published",
                    "item2.X-ABDATE:1604-11-27",
                    "ITEM2.X-ABLabel:_\$!<Other>!\$_",
                    "X-ABDATE;X-APPLE-OMIT-YEAR=1604:1604-06-05",
                    "item3.X-ABDATE:not a date",
                    "item3.X-ABLabel:Dropped",
                    "X-ABLabel:Ungrouped labels label nothing",
                    "item4.X-ABDATE:1843-02-03",
                    "item4.X-ABLabel:Notes\\, published",
                    "item4.X-ABLabel:Second label",
                    "item5.X-ABDATE:1843-02-04",
                    "item5.X-ABLabel:_\$!<>!\$_",
                    "item6.X-ABDATE:1835-07-08",
                    "item6.X-ABLabel:_\$!<Anniversary>!\$_",
                    "END:VCARD",
                ).joinToString("\r\n", postfix = "\r\n"),
            ),
        )

        assertEquals(
            listOf(
                LabeledDate(PartialDate(1, 1, 1842), "Translation published"),
                // Apple's built-in "Other" is no label (OTHER 2 on Android); its "Anniversary" stays a label.
                LabeledDate(PartialDate(11, 27, 1604), null),
                LabeledDate(PartialDate(6, 5, null), null),
                LabeledDate(PartialDate(2, 3, 1843), "Notes, published"),
                LabeledDate(PartialDate(2, 4, 1843), null),
                LabeledDate(PartialDate(7, 8, 1835), "Anniversary"),
            ),
            graph.customDates,
        )
    }

    @Test
    fun `an other date that is text isn't mapped`() {
        val graph = success(fragment(CardPlacement.ENCRYPTED, "FN:Ada", "item1.X-ABDATE;VALUE=text:someday", "item1.X-ABLabel:Wedding"))

        assertTrue(graph.customDates.isEmpty())
    }

    // ADR: the extended-address part isn't shown.
    @Test
    fun `address parts`() {
        val graph = success(
            fragment(CardPlacement.ENCRYPTED, "FN:Ada", "ADR;TYPE=home:PO 7;Flat 2;12 Main St;Zurich;ZH;8000;Switzerland"),
        )

        assertEquals(
            listOf(TypedAddress("12 Main St", "PO 7", "Zurich", "ZH", "8000", "Switzerland", AddressType.Home)),
            graph.addresses,
        )
    }

    @Test
    fun `address lists, escapes, and blank addresses`() {
        val graph = success(
            fragment(
                CardPlacement.ENCRYPTED,
                "FN:Ada",
                "ADR:PO 7,PO 8;;12 Main St,Building B;Zurich,Oerlikon;ZH,Canton;8000,8050;Switzerland,CH",
                "ADR:;;1 Lake Rd\\, Unit 4;;;;",
                "ADR:;;;;;;",
                "ADR:;Flat 2;;;;;",
                "ADR: ; ; ;;;;",
            ),
            fragment(CardPlacement.SIGNED, "ADR;TYPE=work:;;2 Hill St;;;;"),
        )

        assertEquals(
            listOf(
                TypedAddress("12 Main St\nBuilding B", "PO 7, PO 8", "Zurich, Oerlikon", "ZH, Canton", "8000, 8050", "Switzerland, CH", AddressType.Other),
                TypedAddress("1 Lake Rd, Unit 4", null, null, null, null, null, AddressType.Other),
                TypedAddress("2 Hill St", null, null, null, null, null, AddressType.Work),
            ),
            graph.addresses,
        )
    }

    // The ADR row of docs/mapping.md § Types.
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "ADR                     | Other",
            "ADR;TYPE=               | Other",
            "ADR;TYPE=home           | Home",
            "ADR;TYPE=HOME           | Home",
            "ADR;TYPE=work           | Work",
            "ADR;TYPE=other          | Other",
            "ADR;TYPE=postal         | Other",
            "ADR;TYPE=pref,work      | Work",
            "ADR;TYPE=x-shop,home    | Home",
            "ADR;TYPE=work,home      | Work",
        ],
    )
    fun `address TYPE parameters`(property: String, type: AddressType) {
        val graph = success(fragment(CardPlacement.ENCRYPTED, "FN:Ada", "$property:;;12 Main St;;;;"))

        assertEquals(listOf(type), graph.addresses.map { it.type })
    }

    // The RELATED row of docs/mapping.md § Types; X-ANDROID-TYPE keeps Android's type.
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "RELATED;X-ANDROID-TYPE=assistant                 | Assistant       |",
            "RELATED;X-ANDROID-TYPE=domestic_partner          | DomesticPartner |",
            "RELATED;X-ANDROID-TYPE=REFERRED_BY               | ReferredBy      |",
            "RELATED;TYPE=friend;X-ANDROID-TYPE=manager       | Manager         |",
            "RELATED;TYPE=friend;X-ANDROID-TYPE=x-unknown     | Friend          |",
            "RELATED;TYPE=spouse                              | Spouse          |",
            "RELATED;TYPE=CHILD                               | Child           |",
            "RELATED;TYPE=parent                              | Parent          |",
            "RELATED;TYPE=brother                             | Brother         |",
            "RELATED;TYPE=father                              | Father          |",
            "RELATED;TYPE=mother                              | Mother          |",
            "RELATED;TYPE=sister                              | Sister          |",
            "RELATED;TYPE=partner                             | Partner         |",
            "RELATED;TYPE=relative                            | Relative        |",
            "RELATED;TYPE=internet                            | Custom          |",
            "RELATED;TYPE=sibling                             | Custom          | sibling",
            "RELATED;TYPE=co-worker                           | Custom          | co-worker",
            "RELATED;TYPE=pref                                | Custom          |",
            "RELATED;TYPE=co-worker,friend                    | Friend          |",
            "RELATED                                          | Custom          |",
            // X-ANDROID-TYPE=custom: the TYPE value is a label, even one that names an Android type.
            "RELATED;TYPE=Friend;X-ANDROID-TYPE=custom        | Custom          | Friend",
            "RELATED;TYPE=pref,Godparent;X-ANDROID-TYPE=CUSTOM | Custom         | Godparent",
            "RELATED;X-ANDROID-TYPE=custom                    | Custom          |",
            // X-ANDROID-LABEL is the whole label while TYPE's values still begin with it; X-ANDROID-TYPE naming a type
            // wins.
            "RELATED;TYPE=\"Home, Gym\";X-ANDROID-TYPE=custom;X-ANDROID-LABEL=\"Home, Gym\" | Custom | 'Home, Gym'",
            "RELATED;TYPE=Friend;X-ANDROID-LABEL=Friend       | Custom          | Friend",
            "RELATED;TYPE=friend;X-ANDROID-TYPE=friend;X-ANDROID-LABEL=friend | Friend |",
            "RELATED;TYPE=spouse;X-ANDROID-TYPE=custom;X-ANDROID-LABEL=Coach | Custom | spouse",
            "RELATED;TYPE=spouse;X-ANDROID-LABEL=Coach        | Spouse          |",
        ],
    )
    fun `relation types`(property: String, type: RelationType, label: String?) {
        val graph = success(fragment(CardPlacement.ENCRYPTED, "FN:Ada", "$property;VALUE=text:Charles Babbage"))

        assertEquals(listOf(TypedRelation("Charles Babbage", type, label)), graph.relations)
    }

    @Test
    fun `relation values`() {
        val graph = success(
            fragment(
                CardPlacement.ENCRYPTED,
                "FN:Ada",
                "RELATED;TYPE=spouse;VALUE=text:William King\\, Earl",
                "RELATED;TYPE=friend:urn:uuid:babbage-1",
                "RELATED;VALUE=text: ",
            ),
            fragment(CardPlacement.SIGNED, "RELATED;TYPE=child;VALUE=text:Byron King"),
        )

        assertEquals(
            listOf(
                TypedRelation("William King, Earl", RelationType.Spouse),
                TypedRelation("urn:uuid:babbage-1", RelationType.Friend),
                TypedRelation("Byron King", RelationType.Child),
            ),
            graph.relations,
        )
    }

    // Embedded photos in every vCard version.
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "4.0 | PHOTO:data:image/jpeg;base64,$JPEG_BASE64                 | image/jpeg",
            "4.0 | PHOTO:data:;base64,$JPEG_BASE64                           |",
            // A 3.0 photo imported into Proton keeps ENCODING=b in its 4.0 card; the media type is only a hint.
            "4.0 | PHOTO;ENCODING=b;TYPE=JPEG:$JPEG_BASE64                   |",
            "4.0 | PHOTO;ENCODING=BASE64;TYPE=image/jpeg:$JPEG_BASE64        |",
            "3.0 | PHOTO;ENCODING=b;TYPE=JPEG:$JPEG_BASE64                   | image/jpeg",
            "3.0 | PHOTO;ENCODING=b:$JPEG_BASE64                             |",
            "2.1 | PHOTO;ENCODING=BASE64;JPEG:$JPEG_BASE64                   | image/jpeg",
            "2.1 | PHOTO;BASE64;JPEG:$JPEG_BASE64                            | image/jpeg",
        ],
    )
    fun `an embedded photo gives its bytes and media type`(version: String, property: String, mediaType: String?) {
        val card = listOf("BEGIN:VCARD", "VERSION:$version", "FN:Ada", property, "END:VCARD").joinToString("\r\n", postfix = "\r\n")

        val photo = success(CardFragment(CardPlacement.ENCRYPTED, card)).photo

        assertEquals(ContactPhoto(JPEG, mediaType), photo)
        assertTrue(JPEG.contentEquals(photo?.bytes))
        assertEquals(mediaType, photo?.mediaType)
    }

    @Test
    fun `the first embedded photo wins, across fragments, and URLs are skipped`() {
        val graph = success(
            fragment(
                CardPlacement.SIGNED,
                "FN:Ada",
                "PHOTO:https://example.org/ada.jpg",
                "PHOTO:data:image/png;base64,",
                "PHOTO:data:image/png;base64,$PNG_BASE64",
            ),
            fragment(CardPlacement.ENCRYPTED, "PHOTO:data:image/jpeg;base64,$JPEG_BASE64"),
        )

        assertEquals(ContactPhoto(PNG, "image/png"), graph.photo)
    }

    // A value that isn't embedded base64 isn't mapped: in 3.0 and 2.1 ez-vcard base64-decodes any value that doesn't
    // start with http.
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "3.0 | PHOTO:file:///sdcard/ada.jpg",
            "3.0 | PHOTO:ftp://example.org/ada.jpg",
            "3.0 | PHOTO:www.example.org/ada.jpg",
            "3.0 | PHOTO:urn:uuid:ada-photo",
            "3.0 | PHOTO:cid:ada-photo",
            "3.0 | PHOTO;VALUE=uri:https://example.org/ada.jpg",
            "2.1 | PHOTO;VALUE=CID:ada-photo",
            "2.1 | PHOTO;QUOTED-PRINTABLE:ada",
            // ENCODING=b in 4.0 is decoded strictly: corrupt or empty base64 isn't a photo.
            "4.0 | PHOTO;ENCODING=b;TYPE=JPEG:!!!not base64!!!",
            "4.0 | PHOTO;ENCODING=b;TYPE=JPEG:/9j/*4AAQ",
            "4.0 | PHOTO;ENCODING=b:",
        ],
    )
    fun `a photo that isn't embedded base64 isn't mapped`(version: String, property: String) {
        val card = listOf("BEGIN:VCARD", "VERSION:$version", "FN:Ada", property, "END:VCARD").joinToString("\r\n", postfix = "\r\n")

        assertNull(success(CardFragment(CardPlacement.ENCRYPTED, card)).photo)
    }

    // The mapper has no network access, so a URL is never fetched; it just isn't mapped.
    @ParameterizedTest
    @ValueSource(
        strings = [
            "PHOTO:https://example.org/ada.jpg",
            "PHOTO;MEDIATYPE=image/jpeg:https://example.org/ada.jpg",
            "PHOTO:",
            "PHOTO:data:image/jpeg;base64,",
            "PHOTO:data:image/png,%89PNG",
            "PHOTO:file:///sdcard/ada.jpg",
            "PHOTO:cid:ada-photo",
        ],
    )
    fun `a photo without embedded data isn't mapped`(property: String) {
        assertNull(success(fragment(CardPlacement.ENCRYPTED, "FN:Ada", property)).photo)
    }

    @Test
    fun `a photo keeps its own copy of the bytes and shows only its size`() {
        val source = JPEG.copyOf()
        val photo = ContactPhoto(source, "image/jpeg")
        source[0] = 0
        photo.bytes[1] = 0

        assertTrue(JPEG.contentEquals(photo.bytes))
        assertEquals("ContactPhoto(size=${JPEG.size})", photo.toString())
        assertEquals(ContactPhoto(JPEG.copyOf(), "image/jpeg").hashCode(), photo.hashCode())
        assertTrue(photo != ContactPhoto(JPEG, "image/png"))
        assertTrue(photo != ContactPhoto(PNG, "image/jpeg"))
    }

    // docs/architecture.md § Diagnostics: no toString in :mapping shows contact content, so none can reach an
    // exception message or a crash report. Every mapped field carries a SECRET marker, and dates a year no
    // test value matches.
    @Test
    fun `no toString shows contact content`() {
        val vcard = listOf(
            "BEGIN:VCARD",
            "VERSION:4.0",
            "UID:urn:uuid:SECRET-uid",
            "FN:SECRET-fn",
            "N:SECRET-family;SECRET-given;SECRET-middle;SECRET-prefix;SECRET-suffix",
            "X-PHONETIC-LAST-NAME:SECRET-phonetic",
            "EMAIL;TYPE=x-SECRET-label:SECRET-email@example.org",
            "TEL;TYPE=cell:SECRET-tel",
            "ADR;TYPE=home:SECRET-pobox;;SECRET-street;SECRET-city;SECRET-region;SECRET-postcode;SECRET-country",
            "ORG:SECRET-company;SECRET-unit",
            "TITLE:SECRET-title",
            "ROLE:SECRET-role",
            "PHOTO:data:image/jpeg;base64,$JPEG_BASE64",
            "BDAY:17430101",
            "ANNIVERSARY:17430202",
            "item1.X-ABDATE:17430303",
            "item1.X-ABLabel:SECRET-date-label",
            "NOTE:SECRET-note",
            "URL:https://example.org/SECRET-url",
            "NICKNAME:SECRET-nickname",
            "RELATED;TYPE=x-SECRET-relation-label;VALUE=text:SECRET-relation",
            "X-SIP:sip:SECRET-sip@example.org",
            "END:VCARD",
        ).joinToString("\r\n", postfix = "\r\n")
        val fragment = CardFragment(CardPlacement.SIGNED, vcard)
        val result = mapper.merge(listOf(fragment))
        val graph = assertInstanceOf(KeyedGraph::class.java, assertInstanceOf(MappingResult.Success::class.java, result).value).graph

        val shown = listOfNotNull(
            fragment, result, graph, graph.name, graph.organization, graph.photo, graph.birthday, graph.anniversary,
        ) + graph.emails + graph.phones + graph.addresses + graph.customDates + graph.relations +
            graph.customDates.map { it.date }
        // Every field of the graph is present, so nothing above went unchecked for lack of a value.
        assertEquals(16, graph.toString().split(", ").size, graph.toString())
        for (value in shown) {
            val text = value.toString()
            assertTrue("SECRET" !in text && "1743" !in text && "example.org" !in text, text)
        }
        assertEquals("CardFragment(placement=SIGNED, length=${vcard.length})", fragment.toString())
        assertTrue("emails=1" in graph.toString() && "photo" in graph.toString(), graph.toString())
        assertTrue("type=Custom" in graph.emails.single().toString(), graph.emails.single().toString())
    }

    // fixtures/vcard/full.vcf, shaped like a Proton contact (a signed card, then an encrypted one), with every field in
    // docs/mapping.md § Field table and the unmapped properties it lists.
    @Test
    fun `the full fixture maps every field`() {
        val text = checkNotNull(javaClass.getResource("/full.vcf")) { "fixtures/vcard/full.vcf is missing" }.readText()
        val cards = Regex("BEGIN:VCARD.*?END:VCARD\\r?\\n?", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.value }.toList()
        assertEquals(2, cards.size)

        val graph = success(CardFragment(CardPlacement.SIGNED, cards[0]), CardFragment(CardPlacement.ENCRYPTED, cards[1]))

        assertEquals("urn:uuid:5f0c1b1e-0000-4000-8000-000000000001", graph.uid)
        assertEquals("Ada Lovelace", graph.formattedName)
        assertEquals(
            StructuredName(
                given = "Ada", family = "Lovelace", prefix = "Hon.", middle = "Augusta",
                phoneticGiven = "AY-duh", phoneticFamily = "LUV-lace",
            ),
            graph.name,
        )
        assertEquals(
            listOf(TypedEmail("ada@example.org", EmailType.Work), TypedEmail("ada.home@example.org", EmailType.Home)),
            graph.emails,
        )
        assertEquals(
            listOf(
                TypedPhone("+44 20 7946 0000", PhoneType.Mobile),
                TypedPhone("+44 20 7946 0001", PhoneType.FaxHome),
                TypedPhone("+44 20 7946 0002", PhoneType.Custom, "x-lab"),
            ),
            graph.phones,
        )
        assertEquals(
            listOf(TypedAddress("1 Example Row", null, "London", null, "EX1 1AA", "United Kingdom", AddressType.Home)),
            graph.addresses,
        )
        assertEquals(Organization("Analytical Engines", listOf("Research"), "Countess", "Mathematician"), graph.organization)
        assertEquals(ContactPhoto(JPEG, "image/jpeg"), graph.photo)
        assertEquals(PartialDate(12, 10, 1815), graph.birthday)
        assertEquals(PartialDate(7, 8, 1835), graph.anniversary)
        assertEquals(listOf(LabeledDate(PartialDate(1, 1, 1843), "Notes published")), graph.customDates)
        assertEquals("Wrote the first published algorithm.", graph.note)
        assertEquals(listOf("https://example.org/ada"), graph.urls)
        assertEquals("Enchantress of Numbers", graph.nickname)
        assertEquals(
            listOf(
                TypedRelation("William King", RelationType.Spouse),
                TypedRelation("Charles Babbage", RelationType.Assistant),
            ),
            graph.relations,
        )
        assertEquals(listOf("ada@example.org"), graph.sipAddresses)
        // Every field is set, so the fixture really covers the whole table.
        assertEquals(16, graph.toString().split(", ").size, graph.toString())
    }

    private fun fragment(placement: CardPlacement, vararg lines: String) =
        CardFragment(placement, (listOf("BEGIN:VCARD", "VERSION:4.0") + lines + "END:VCARD").joinToString("\r\n", postfix = "\r\n"))

    private fun success(vararg fragments: CardFragment): ContactGraph {
        val result = mapper.merge(fragments.toList())
        return assertInstanceOf(KeyedGraph::class.java, assertInstanceOf(MappingResult.Success::class.java, result).value).graph
    }

    private fun assertInvalid(code: MappingIssueCode, vararg fragments: CardFragment) {
        assertEquals(MappingResult.Invalid(listOf(MappingIssue(code))), mapper.merge(fragments.toList()))
    }
}
