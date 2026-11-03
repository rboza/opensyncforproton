package dev.rboza.android.opensyncforproton.sync.provider

import dev.rboza.android.opensyncforproton.mapping.AddressType
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.ContactPhoto
import dev.rboza.android.opensyncforproton.mapping.EmailType
import dev.rboza.android.opensyncforproton.mapping.GraphKeys
import dev.rboza.android.opensyncforproton.mapping.KeyedGraph
import dev.rboza.android.opensyncforproton.mapping.LabeledDate
import dev.rboza.android.opensyncforproton.mapping.Organization
import dev.rboza.android.opensyncforproton.mapping.PartialDate
import dev.rboza.android.opensyncforproton.mapping.PhoneType
import dev.rboza.android.opensyncforproton.mapping.RelationType
import dev.rboza.android.opensyncforproton.mapping.SourceKey
import dev.rboza.android.opensyncforproton.mapping.StructuredName
import dev.rboza.android.opensyncforproton.mapping.TypedAddress
import dev.rboza.android.opensyncforproton.mapping.TypedEmail
import dev.rboza.android.opensyncforproton.mapping.TypedPhone
import dev.rboza.android.opensyncforproton.mapping.TypedRelation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

// The column mapper: one case per data kind in docs/mapping.md § Field table, contact to rows, and the way back with
// Data.SYNC1 keys. Android's values are written out here, not taken from ContactsContract, so the test pins them. Every
// value is made up for the test.
class ColumnMapperTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    fun `each data kind becomes its provider rows`(case: String, graph: ContactGraph, expected: List<Row>) {
        assertEquals(expected, dataRows(graph, photo = null).map(::plain), case)
    }

    @Test
    fun `a contact without a name writes no structured name row`() {
        val rows = dataRows(ContactGraph(uid = "uid-1", formattedName = null, emails = listOf(TypedEmail("a@example.test", EmailType.Home))), photo = null)

        assertEquals(listOf(EMAIL), rows.map { it.mimeType })
    }

    @Test
    fun `an empty contact has no rows`() {
        assertEquals(emptyList<DataRow>(), dataRows(ContactGraph(uid = "uid-1", formattedName = null), photo = null))
    }

    @Test
    fun `the photo row holds the bytes it is given, already scaled`() {
        val graph = ContactGraph(uid = "uid-1", formattedName = "Ann", photo = ContactPhoto(byteArrayOf(1, 2, 3), "image/jpeg"))

        val rows = dataRows(graph, photo = byteArrayOf(9, 8))

        assertEquals(listOf(Row(PHOTO, mapOf("data15" to listOf<Byte>(9, 8)))), rows.filter { it.mimeType == PHOTO }.map(::plain))
    }

    @Test
    fun `no photo bytes, no photo row`() {
        val graph = ContactGraph(uid = "uid-1", formattedName = "Ann", photo = ContactPhoto(byteArrayOf(1, 2, 3), "image/jpeg"))

        assertEquals(listOf(NAME), dataRows(graph, photo = null).map { it.mimeType })
    }

    @Test
    fun `the photo bytes are copied`() {
        val bytes = byteArrayOf(9, 8)
        val rows = dataRows(ContactGraph(uid = "uid-1", formattedName = null), photo = bytes)

        bytes[0] = 0
        (rows.single().values.getValue("data15") as ColumnValue.Blob).bytes[1] = 0

        assertEquals(listOf(Row(PHOTO, mapOf("data15" to listOf<Byte>(9, 8)))), rows.map(::plain), "copied in, and handed out as copies")
    }

    @Test
    fun `every kind in one contact, name first`() {
        val rows = dataRows(everything, photo = byteArrayOf(1))

        assertEquals(
            listOf(NAME, EMAIL, PHONE, POSTAL, ORGANIZATION, PHOTO, EVENT, EVENT, EVENT, NOTE, WEBSITE, NICKNAME, RELATION, SIP),
            rows.map { it.mimeType },
        )
    }

    @Test
    fun `dates use ASCII digits whatever the phone's locale`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"))
        val rows = try {
            dataRows(ContactGraph(uid = "uid-1", formattedName = null, birthday = PartialDate(5, 7, 1990), anniversary = PartialDate(6, 8, null)), photo = null)
        } finally {
            Locale.setDefault(saved)
        }

        assertEquals(listOf("1990-05-07", "--06-08"), rows.map { (it.values.getValue("data1") as ColumnValue.Text).value })
    }

    @Test
    fun `toString shows no content`() {
        val rows = dataRows(everything, photo = byteArrayOf(1))
        val text = rows.toString() + rows.flatMap { it.values.values }.joinToString()

        assertEquals(14, rows.size)

        listOf("Ann", "Lee", "a@example.test", "+1 555 0100", "Main St", "Acme", "Engineer", "1990", "hello", "example.test/ann", "Annie", "Bob", "sip-ann")
            .forEach { assertFalse(text.contains(it), it) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    fun `the way back gives the same values and keys`(case: String, graph: ContactGraph, expected: List<Row>) {
        val rows = dataRows(graph, photo = null, keys = keysFor(graph))

        assertEquals(RowsRead(KeyedGraph(graph.copy(uid = null), keysFor(graph))), keyedGraph(rows), case)
        assertEquals(expected.size, rows.size, case)
    }

    @Test
    fun `each row the app writes carries its value's key in Data SYNC1`() {
        val keys = keysFor(everything)

        val rows = dataRows(everything, photo = byteArrayOf(1), keys = keys)

        val expected = listOf(
            keys.name, keys.emails[0], keys.phones[0], keys.addresses[0], keys.organization, keys.photo, keys.birthday, keys.anniversary,
            keys.customDates[0], keys.note, keys.urls[0], keys.nickname, keys.relations[0], keys.sipAddresses[0],
        ).map { checkNotNull(it).text }
        assertEquals(expected, rows.map { (it.values[SYNC1] as ColumnValue.Text).value })
        assertEquals(expected.size, expected.toSet().size)
    }

    @Test
    fun `a value without a key writes no SYNC1, and reads back without one`() {
        val graph = ContactGraph(uid = null, formattedName = "Ann", emails = listOf(TypedEmail("a@example.test", EmailType.Home), TypedEmail("b@example.test", EmailType.Work)))
        val keys = GraphKeys(emails = listOf(SourceKey(listOf("EMAIL.0123456789abcdef")), null))

        val rows = dataRows(graph, photo = null, keys = keys)

        assertEquals(listOf(false, true, false), rows.map { SYNC1 in it.values })
        assertEquals(RowsRead(KeyedGraph(graph, keys)), keyedGraph(rows))
    }

    @Test
    fun `a row whose SYNC1 isn't a key reads as a value with no key`() {
        val rows = listOf("EMAIL.0123456789abcdef", "not a key", "").map { row(EMAIL, "data1" to "a@example.test", "data2" to 1L, SYNC1 to it) }

        val keys = keyedGraph(rows.map(::dataRow)).contact.keys.emails

        assertEquals(listOf(SourceKey(listOf("EMAIL.0123456789abcdef")), null, null), keys)
    }

    @Test
    fun `the photo row reads back as its bytes, with its key`() {
        val rows = listOf(DataRow(PHOTO, mapOf("data15" to ColumnValue.Blob(byteArrayOf(4, 5)), SYNC1 to ColumnValue.Text("PHOTO.0123456789abcdef"))))

        val read = keyedGraph(rows).contact

        assertEquals(ContactPhoto(byteArrayOf(4, 5), null), read.graph.photo)
        assertEquals(SourceKey(listOf("PHOTO.0123456789abcdef")), read.keys.photo)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("typesBack")
    fun `types and labels read back as mapping md says`(case: String, row: Row, expected: ContactGraph) {
        assertEquals(expected, keyedGraph(listOf(dataRow(row))).contact.graph, case)
    }

    @Test
    fun `type columns the provider gives back as text read as numbers`() {
        val rows = listOf(row(EMAIL, "data1" to "a@example.test", "data2" to "2"), row(EVENT, "data1" to "--05-07", "data2" to "3"))

        val graph = keyedGraph(rows.map(::dataRow)).contact.graph

        assertEquals(listOf(TypedEmail("a@example.test", EmailType.Work)), graph.emails)
        assertEquals(PartialDate(5, 7, null), graph.birthday)
    }

    @Test
    fun `a name the provider split from FN reads as that FN alone, not as an edit`() {
        val split = row(NAME, "data1" to "Ann B Lee", "data2" to "Ann", "data5" to "B", "data3" to "Lee")
        val phonetic = split.copy(values = split.values + ("data7" to "an"))

        fun read(row: Row, key: String?) = keyedGraph(listOf(dataRow(if (key == null) row else row.copy(values = row.values + (SYNC1 to key))))).contact.graph

        assertEquals(ContactGraph(uid = null, formattedName = "Ann B Lee"), read(split, "FN.0123456789abcdef"))
        assertEquals(
            ContactGraph(uid = null, formattedName = "Ann B Lee", name = StructuredName(null, null, phoneticGiven = "an")),
            read(phonetic, "FN.0123456789abcdef X-PHONETIC-FIRST-NAME.0123456789abcdef"),
        )
        assertEquals(
            ContactGraph(uid = null, formattedName = "Ann B Lee", name = StructuredName(null, null, phoneticGiven = "an")),
            read(phonetic, "FN.0123456789abcdef"),
            "a phonetic name added on the phone is read",
        )
        // The provider builds the display name from N when there's no FN.
        assertEquals(ContactGraph(uid = null, formattedName = null, name = StructuredName("Ann", "Lee", middle = "B")), read(split, "N.0123456789abcdef"))
        val everyPart = ContactGraph(uid = null, formattedName = "Ann B Lee", name = StructuredName("Ann", "Lee", middle = "B"))
        assertEquals(everyPart, read(split, "FN.0123456789abcdef N.fedcba9876543210"))
        assertEquals(everyPart, read(split, null), "a row added on the phone has no key")
        assertEquals(everyPart, read(split, "X-PHONETIC-FIRST-NAME.0123456789abcdef"), "a key naming neither FN nor N hides nothing")
        assertEquals(everyPart, read(split, "EMAIL.0123456789abcdef"), "nor does another app's key")
    }

    @Test
    fun `a second row of a kind that holds one value isn't mapped, and is counted`() {
        val rows = listOf(
            row(NAME, "data1" to "Ann"), row(NAME, "data1" to "Bea"),
            row(ORGANIZATION, "data1" to "Acme"), row(ORGANIZATION, "data1" to "Other Co"),
            row(NOTE, "data1" to "first"), row(NOTE, "data1" to "second"),
            row(NICKNAME, "data1" to "Annie"), row(NICKNAME, "data1" to "Bee"),
            Row(PHOTO, mapOf("data15" to listOf<Byte>(1))), Row(PHOTO, mapOf("data15" to listOf<Byte>(2))),
            row(EVENT, "data1" to "--05-07", "data2" to 3L), row(EVENT, "data1" to "--06-08", "data2" to 3L),
            row(EVENT, "data1" to "--07-09", "data2" to 1L), row(EVENT, "data1" to "--08-10", "data2" to 1L),
        )

        val read = keyedGraph(rows.map(::dataRow))

        assertEquals(
            ContactGraph(
                uid = null, formattedName = "Ann", organization = Organization("Acme"), note = "first", nickname = "Annie",
                photo = ContactPhoto(byteArrayOf(1), null), birthday = PartialDate(5, 7, null), anniversary = PartialDate(7, 9, null),
            ),
            read.contact.graph,
        )
        assertEquals(7, read.unmappedRows)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1990-02-30", "--02-30", "May 7, 1990", "19900507", "1990-05-07T00:00:00Z", "1990-5-7", "--5-7", "x1990-05-07", "1990-05-07 "])
    fun `an event whose date doesn't read isn't mapped, and is counted`(date: String) {
        val read = keyedGraph(listOf(dataRow(row(EVENT, "data1" to date, "data2" to 2L))))

        assertEquals(ContactGraph(uid = null, formattedName = null), read.contact.graph)
        assertEquals(1, read.unmappedRows)
    }

    @Test
    fun `dates read back with or without a year`() {
        val rows = listOf("1990-05-07", "--12-31", "0005-01-02", "2000-02-29", "--02-29", "0000-05-07").map { row(EVENT, "data1" to it, "data2" to 2L) }

        val dates = keyedGraph(rows.map(::dataRow)).contact.graph.customDates.map { it.date }

        assertEquals(
            listOf(PartialDate(5, 7, 1990), PartialDate(12, 31, null), PartialDate(1, 2, 5), PartialDate(2, 29, 2000), PartialDate(2, 29, null), PartialDate(5, 7, null)),
            dates,
        )
    }

    @Test
    fun `blank values and other columns aren't mapped or counted`() {
        val rows = listOf(
            row(EMAIL, "data1" to "", "data2" to 1L), row(PHONE, "data2" to 2L), row(NOTE, "data1" to " "), row(WEBSITE, "data1" to ""),
            row(EVENT, "data1" to "", "data2" to 3L), Row(PHOTO, mapOf("data15" to emptyList<Byte>())),
            row(POSTAL, "data2" to 1L, "data1" to "Formatted, by the provider", "data6" to "Neighbourhood"),
            row(NAME, "data10" to "1", "data11" to "0"),
            // A blank row doesn't take the place of the kind's one value.
            row(NAME, "data1" to "Ann"), row(NOTE, "data1" to "hello"),
        )

        val read = keyedGraph(rows.map(::dataRow))

        assertEquals(RowsRead(KeyedGraph(ContactGraph(uid = null, formattedName = "Ann", note = "hello"))), read)
    }

    @Test
    fun `rows of kinds the field table doesn't name aren't mapped, but are counted as unmapped`() {
        // A write or an upload would lose them, whatever they hold.
        val rows = listOf(
            row(NAME, "data1" to "Ann"), row("vnd.android.cursor.item/group_membership", "data1" to "7"),
            row("vnd.android.cursor.item/im", "data1" to "ann", "data5" to 3L), row("vnd.example.test/custom", "data1" to " "),
        )

        assertEquals(RowsRead(KeyedGraph(ContactGraph(uid = null, formattedName = "Ann")), unmappedRows = 3), keyedGraph(rows.map(::dataRow)))
    }

    @Test
    fun `organization units split at the semicolon they were joined with`() {
        val graph = keyedGraph(listOf(dataRow(row(ORGANIZATION, "data1" to "Acme", "data5" to "R&D;;Lab", "data4" to "Engineer", "data6" to "Lead", "data2" to 1L)))).contact.graph

        assertEquals(Organization("Acme", units = listOf("R&D", "Lab"), title = "Engineer", role = "Lead"), graph.organization)
    }

    @Test
    fun `a unit holding a semicolon reads back split there, the one lossy round trip`() {
        val graph = ContactGraph(uid = null, formattedName = null, organization = Organization("Acme", units = listOf("R;D", "Lab")))

        val read = keyedGraph(dataRows(graph, photo = null)).contact.graph

        assertEquals(Organization("Acme", units = listOf("R", "D", "Lab")), read.organization)
        assertEquals(graph.organization?.units?.joinToString(";"), read.organization?.units?.joinToString(";"), "the department text is the same")
    }

    @Test
    fun `a read shows no content in its toString`() {
        val read = keyedGraph(dataRows(everything, photo = byteArrayOf(1), keys = keysFor(everything)))

        listOf("Ann", "Lee", "a@example.test", "hello", "0000000000000001").forEach { assertFalse(read.toString().contains(it), it) }
    }

    /** A row as plain values: Text → String, Integer → Long, Blob → a list of its bytes (equality compares content). */
    data class Row(val mimeType: String, val values: Map<String, Any>)

    companion object {
        const val NAME = "vnd.android.cursor.item/name"
        const val EMAIL = "vnd.android.cursor.item/email_v2"
        const val PHONE = "vnd.android.cursor.item/phone_v2"
        const val POSTAL = "vnd.android.cursor.item/postal-address_v2"
        const val ORGANIZATION = "vnd.android.cursor.item/organization"
        const val PHOTO = "vnd.android.cursor.item/photo"
        const val EVENT = "vnd.android.cursor.item/contact_event"
        const val NOTE = "vnd.android.cursor.item/note"
        const val WEBSITE = "vnd.android.cursor.item/website"
        const val NICKNAME = "vnd.android.cursor.item/nickname"
        const val RELATION = "vnd.android.cursor.item/relation"
        const val SIP = "vnd.android.cursor.item/sip_address"

        fun plain(row: DataRow) = Row(
            row.mimeType,
            row.values.mapValues { (_, value) ->
                when (value) {
                    is ColumnValue.Text -> value.value
                    is ColumnValue.Integer -> value.value
                    is ColumnValue.Blob -> value.bytes.toList()
                }
            },
        )

        const val SYNC1 = "data_sync1"

        /** A [Row] as the provider gives it back: lists of bytes become a blob. */
        fun dataRow(row: Row) = DataRow(
            row.mimeType,
            row.values.mapValues { (_, value) ->
                when (value) {
                    is String -> ColumnValue.Text(value)
                    is Long -> ColumnValue.Integer(value)
                    is List<*> -> ColumnValue.Blob(value.map { it as Byte }.toByteArray())
                    else -> error("not a column value")
                }
            },
        )

        /** A made-up key for each value of [graph], all different; the name's and organization's list what's shown. */
        fun keysFor(graph: ContactGraph): GraphKeys {
            var n = 0L
            fun key(vararg names: String?) = SourceKey(names.filterNotNull().map { "$it.%016x".format(Locale.ROOT, ++n) })
            val name = graph.name
            val hasN = name != null && listOf(name.given, name.family, name.prefix, name.middle, name.suffix).any { it != null }
            val org = graph.organization
            return GraphKeys(
                name = if (graph.formattedName != null || name != null) {
                    key(
                        "FN".takeIf { graph.formattedName != null }, "N".takeIf { hasN }, "X-PHONETIC-FIRST-NAME".takeIf { name?.phoneticGiven != null },
                        "X-PHONETIC-MIDDLE-NAME".takeIf { name?.phoneticMiddle != null }, "X-PHONETIC-LAST-NAME".takeIf { name?.phoneticFamily != null },
                    )
                } else {
                    null
                },
                emails = graph.emails.map { key("EMAIL") },
                phones = graph.phones.map { key("TEL") },
                addresses = graph.addresses.map { key("ADR") },
                organization = org?.let { key("ORG".takeIf { org.company != null || org.units.isNotEmpty() }, "TITLE".takeIf { org.title != null }, "ROLE".takeIf { org.role != null }) },
                photo = graph.photo?.let { key("PHOTO") },
                birthday = graph.birthday?.let { key("BDAY") },
                anniversary = graph.anniversary?.let { key("ANNIVERSARY") },
                customDates = graph.customDates.map { key("X-ABDATE") },
                note = graph.note?.let { key("NOTE") },
                urls = graph.urls.map { key("URL") },
                nickname = graph.nickname?.let { key("NICKNAME") },
                relations = graph.relations.map { key("RELATED") },
                sipAddresses = graph.sipAddresses.map { key("X-SIP") },
            )
        }

        private fun graph(
            formattedName: String? = null,
            name: StructuredName? = null,
            emails: List<TypedEmail> = emptyList(),
            phones: List<TypedPhone> = emptyList(),
            addresses: List<TypedAddress> = emptyList(),
            organization: Organization? = null,
            birthday: PartialDate? = null,
            anniversary: PartialDate? = null,
            customDates: List<LabeledDate> = emptyList(),
            note: String? = null,
            urls: List<String> = emptyList(),
            nickname: String? = null,
            relations: List<TypedRelation> = emptyList(),
            sipAddresses: List<String> = emptyList(),
        ) = ContactGraph(
            uid = "uid-1", formattedName = formattedName, name = name, emails = emails, phones = phones, addresses = addresses,
            organization = organization, birthday = birthday, anniversary = anniversary, customDates = customDates, note = note,
            urls = urls, nickname = nickname, relations = relations, sipAddresses = sipAddresses,
        )

        private fun case(name: String, graph: ContactGraph, vararg rows: Row) = Arguments.of(name, graph, rows.toList())

        fun row(mimeType: String, vararg values: Pair<String, Any>) = Row(mimeType, mapOf(*values))

        val everything = ContactGraph(
            uid = "uid-1",
            formattedName = "Ann Lee",
            name = StructuredName(given = "Ann", family = "Lee"),
            emails = listOf(TypedEmail("a@example.test", EmailType.Home)),
            phones = listOf(TypedPhone("+1 555 0100", PhoneType.Mobile)),
            addresses = listOf(TypedAddress("1 Main St", null, null, null, null, null, AddressType.Home)),
            organization = Organization("Acme", title = "Engineer"),
            photo = ContactPhoto(byteArrayOf(1), "image/jpeg"),
            birthday = PartialDate(5, 17, 1990),
            anniversary = PartialDate(6, 1, null),
            customDates = listOf(LabeledDate(PartialDate(7, 2, null), "Met")),
            note = "hello",
            urls = listOf("https://example.test/ann"),
            nickname = "Annie",
            relations = listOf(TypedRelation("Bob", RelationType.Spouse)),
            sipAddresses = listOf("sip-ann@example.test"),
        )

        private fun emptyGraph() = ContactGraph(uid = null, formattedName = null)

        /**
         * docs/mapping.md § Types, from Android's side. The vCard has no type for phone types 8–11 and 13–20, or for
         * e-mail MOBILE.
         */
        @JvmStatic
        fun typesBack(): List<Arguments> = buildList {
            fun email(type: Long?, label: String?, expected: TypedEmail) =
                add(Arguments.of("email $type $label", row(EMAIL, *listOfNotNull("data1" to "a@example.test", type?.let { "data2" to it }, label?.let { "data3" to it }).toTypedArray()), emptyGraph().copy(emails = listOf(expected))))
            fun email(type: Long?, label: String?, expected: EmailType, expectedLabel: String? = null) = email(type, label, TypedEmail("a@example.test", expected, expectedLabel))
            email(1, null, EmailType.Home)
            email(2, null, EmailType.Work)
            email(3, null, EmailType.Other)
            email(4, null, EmailType.Other)
            email(0, "School", EmailType.Custom, "School")
            email(0, null, EmailType.Other)
            email(0, " ", EmailType.Other)
            email(null, null, EmailType.Other)
            email(99, "Odd", EmailType.Other)
            email(1, "Ignored", EmailType.Home)

            fun phone(type: Long?, label: String?, expected: PhoneType, expectedLabel: String? = null) =
                add(Arguments.of("phone $type $label", row(PHONE, *listOfNotNull("data1" to "1", type?.let { "data2" to it }, label?.let { "data3" to it }).toTypedArray()), emptyGraph().copy(phones = listOf(TypedPhone("1", expected, expectedLabel)))))
            listOf(1L to PhoneType.Home, 2L to PhoneType.Mobile, 3L to PhoneType.Work, 4L to PhoneType.FaxWork, 5L to PhoneType.FaxHome, 6L to PhoneType.Pager, 7L to PhoneType.Other, 12L to PhoneType.Main)
                .forEach { (type, expected) -> phone(type, null, expected) }
            ((8L..11L) + (13L..20L)).forEach { phone(it, null, PhoneType.Other) }
            phone(3, "Ignored", PhoneType.Work)
            phone(0, "Boat", PhoneType.Custom, "Boat")
            phone(0, null, PhoneType.Other)
            phone(null, null, PhoneType.Other)

            fun postal(type: Long?, label: String?, expected: AddressType) =
                add(Arguments.of("postal $type $label", row(POSTAL, *listOfNotNull("data7" to "Town", type?.let { "data2" to it }, label?.let { "data3" to it }).toTypedArray()), emptyGraph().copy(addresses = listOf(TypedAddress(null, null, "Town", null, null, null, expected)))))
            postal(1, null, AddressType.Home)
            postal(2, null, AddressType.Work)
            postal(3, null, AddressType.Other)
            postal(0, "Cabin", AddressType.Other)
            postal(null, null, AddressType.Other)

            fun relation(type: Long?, label: String?, expected: RelationType, expectedLabel: String? = null) =
                add(Arguments.of("relation $type $label", row(RELATION, *listOfNotNull("data1" to "Sam", type?.let { "data2" to it }, label?.let { "data3" to it }).toTypedArray()), emptyGraph().copy(relations = listOf(TypedRelation("Sam", expected, expectedLabel)))))
            RelationType.entries.filter { it != RelationType.Custom }.forEachIndexed { i, type -> relation(i + 1L, null, type) }
            relation(0, "Coach", RelationType.Custom, "Coach")
            relation(0, null, RelationType.Custom)
            relation(null, null, RelationType.Custom)
            relation(99, "Odd", RelationType.Custom, "Odd")

            fun event(type: Long?, label: String?, expected: ContactGraph) =
                add(Arguments.of("event $type $label", row(EVENT, *listOfNotNull("data1" to "--05-07", type?.let { "data2" to it }, label?.let { "data3" to it }).toTypedArray()), expected))
            val date = PartialDate(5, 7, null)
            event(3, null, emptyGraph().copy(birthday = date))
            event(1, null, emptyGraph().copy(anniversary = date))
            event(2, null, emptyGraph().copy(customDates = listOf(LabeledDate(date, null))))
            event(2, "Ignored", emptyGraph().copy(customDates = listOf(LabeledDate(date, null))))
            event(0, "Met", emptyGraph().copy(customDates = listOf(LabeledDate(date, "Met"))))
            event(0, null, emptyGraph().copy(customDates = listOf(LabeledDate(date, null))))
            event(null, null, emptyGraph().copy(customDates = listOf(LabeledDate(date, null))))

            add(Arguments.of("website", row(WEBSITE, "data1" to "https://example.test", "data2" to 1L), emptyGraph().copy(urls = listOf("https://example.test"))))
            add(Arguments.of("sip", row(SIP, "data1" to "ann@sip.example.test", "data2" to 1L), emptyGraph().copy(sipAddresses = listOf("ann@sip.example.test"))))
            add(Arguments.of("nickname", row(NICKNAME, "data1" to "Annie", "data2" to 2L), emptyGraph().copy(nickname = "Annie")))
        }

        @JvmStatic
        fun kinds() = listOf(
            // FN, N, and phonetic names.
            case(
                "full name",
                graph(
                    formattedName = "Dr Ann B Lee Jr",
                    name = StructuredName("Ann", "Lee", prefix = "Dr", middle = "B", suffix = "Jr", phoneticGiven = "an", phoneticMiddle = "bee", phoneticFamily = "lee"),
                ),
                row(
                    NAME, "data1" to "Dr Ann B Lee Jr", "data2" to "Ann", "data3" to "Lee", "data4" to "Dr", "data5" to "B", "data6" to "Jr",
                    "data7" to "an", "data8" to "bee", "data9" to "lee",
                ),
            ),
            case("FN only", graph(formattedName = "Ann Lee"), row(NAME, "data1" to "Ann Lee")),
            case("N only", graph(name = StructuredName("Ann", null)), row(NAME, "data2" to "Ann")),
            case("N with only a prefix", graph(name = StructuredName(null, null, prefix = "Dr")), row(NAME, "data4" to "Dr")),
            // EMAIL (docs/mapping.md § Types).
            case(
                "emails",
                graph(
                    emails = listOf(
                        TypedEmail("h@example.test", EmailType.Home),
                        TypedEmail("w@example.test", EmailType.Work),
                        TypedEmail("o@example.test", EmailType.Other),
                        TypedEmail("c@example.test", EmailType.Custom, "School"),
                    ),
                ),
                row(EMAIL, "data1" to "h@example.test", "data2" to 1L),
                row(EMAIL, "data1" to "w@example.test", "data2" to 2L),
                row(EMAIL, "data1" to "o@example.test", "data2" to 3L),
                row(EMAIL, "data1" to "c@example.test", "data2" to 0L, "data3" to "School"),
            ),
            // TEL: phone types 1–7 and 12, and custom.
            case(
                "phones",
                graph(
                    phones = listOf(
                        TypedPhone("1", PhoneType.Home),
                        TypedPhone("2", PhoneType.Mobile),
                        TypedPhone("3", PhoneType.Work),
                        TypedPhone("4", PhoneType.FaxWork),
                        TypedPhone("5", PhoneType.FaxHome),
                        TypedPhone("6", PhoneType.Pager),
                        TypedPhone("7", PhoneType.Other),
                        TypedPhone("12", PhoneType.Main),
                        TypedPhone("0", PhoneType.Custom, "Boat"),
                    ),
                ),
                row(PHONE, "data1" to "1", "data2" to 1L),
                row(PHONE, "data1" to "2", "data2" to 2L),
                row(PHONE, "data1" to "3", "data2" to 3L),
                row(PHONE, "data1" to "4", "data2" to 4L),
                row(PHONE, "data1" to "5", "data2" to 5L),
                row(PHONE, "data1" to "6", "data2" to 6L),
                row(PHONE, "data1" to "7", "data2" to 7L),
                row(PHONE, "data1" to "12", "data2" to 12L),
                row(PHONE, "data1" to "0", "data2" to 0L, "data3" to "Boat"),
            ),
            // ADR: no FORMATTED_ADDRESS (the provider composes it).
            case(
                "addresses",
                graph(
                    addresses = listOf(
                        TypedAddress("1 Main St\nFlat 2", "PO 9", "Town", "Region", "12345", "Country", AddressType.Home),
                        TypedAddress(null, null, "Work Town", null, null, null, AddressType.Work),
                        TypedAddress(null, null, null, null, "999", null, AddressType.Other),
                    ),
                ),
                row(
                    POSTAL, "data4" to "1 Main St\nFlat 2", "data5" to "PO 9", "data7" to "Town", "data8" to "Region", "data9" to "12345",
                    "data10" to "Country", "data2" to 1L,
                ),
                row(POSTAL, "data7" to "Work Town", "data2" to 2L),
                row(POSTAL, "data9" to "999", "data2" to 3L),
            ),
            // ORG, TITLE, ROLE: units joined with ';'; TYPE_WORK.
            case(
                "organization",
                graph(organization = Organization("Acme", units = listOf("R&D", "Lab"), title = "Engineer", role = "Lead")),
                row(ORGANIZATION, "data1" to "Acme", "data5" to "R&D;Lab", "data4" to "Engineer", "data6" to "Lead", "data2" to 1L),
            ),
            case("title only", graph(organization = Organization(null, title = "Engineer")), row(ORGANIZATION, "data4" to "Engineer", "data2" to 1L)),
            // BDAY, ANNIVERSARY, X-ABDATE: YYYY-MM-DD or --MM-DD.
            case(
                "dates",
                graph(
                    birthday = PartialDate(5, 7, 1990),
                    anniversary = PartialDate(12, 31, null),
                    customDates = listOf(LabeledDate(PartialDate(1, 2, 5), "Met"), LabeledDate(PartialDate(3, 4, null), null)),
                ),
                row(EVENT, "data1" to "1990-05-07", "data2" to 3L),
                row(EVENT, "data1" to "--12-31", "data2" to 1L),
                row(EVENT, "data1" to "0005-01-02", "data2" to 0L, "data3" to "Met"),
                row(EVENT, "data1" to "--03-04", "data2" to 2L),
            ),
            case("note", graph(note = "line 1\nline 2"), row(NOTE, "data1" to "line 1\nline 2")),
            case(
                "websites",
                graph(urls = listOf("https://example.test", "ftp://example.test")),
                row(WEBSITE, "data1" to "https://example.test", "data2" to 7L),
                row(WEBSITE, "data1" to "ftp://example.test", "data2" to 7L),
            ),
            // TYPE_DEFAULT.
            case("nickname", graph(nickname = "Annie"), row(NICKNAME, "data1" to "Annie", "data2" to 1L)),
            // RELATED: every Android relation type, and custom with and without a label.
            case(
                "relations",
                graph(relations = RelationType.entries.map { TypedRelation(it.name, it) } + TypedRelation("Sam", RelationType.Custom, "Coach")),
                *(
                    listOf(
                        "Assistant" to 1L, "Brother" to 2L, "Child" to 3L, "DomesticPartner" to 4L, "Father" to 5L, "Friend" to 6L,
                        "Manager" to 7L, "Mother" to 8L, "Parent" to 9L, "Partner" to 10L, "ReferredBy" to 11L, "Relative" to 12L,
                        "Sister" to 13L, "Spouse" to 14L, "Custom" to 0L,
                    ).map { (name, type) -> row(RELATION, "data1" to name, "data2" to type) } +
                        row(RELATION, "data1" to "Sam", "data2" to 0L, "data3" to "Coach")
                    ).toTypedArray(),
            ),
            // X-SIP: TYPE_OTHER.
            case(
                "SIP addresses",
                graph(sipAddresses = listOf("ann@sip.example.test", "lee@sip.example.test")),
                row(SIP, "data1" to "ann@sip.example.test", "data2" to 3L),
                row(SIP, "data1" to "lee@sip.example.test", "data2" to 3L),
            ),
        )
    }
}
