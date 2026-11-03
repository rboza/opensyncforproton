package dev.rboza.android.opensyncforproton.sync.provider

import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordEntity
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.EMAIL
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.EVENT
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.NAME
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.NICKNAME
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.NOTE
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.ORGANIZATION
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.PHONE
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.PHOTO
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.POSTAL
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.RELATION
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.SIP
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.SYNC1
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.WEBSITE
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.dataRow
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.row
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

// The import list (docs/sync.md § Import from this phone): matching, the marks, and the imported originals. Rows are
// given as readRows gives them; every value is made up for the test.
class ImportListTest {
    // Matching: each rule alone makes a pair.

    @Test
    fun `the same contact Android shows makes a pair`() {
        val list = importList(local(1, contactId = 77), synced(10, contactId = 77))

        assertEquals(listOf(pair(1, 10, MatchReason.SAME_CONTACT)), list.pairs.map(::shape))
        assertEquals(emptyList<ImportCandidate>(), list.candidates)
    }

    @Test
    fun `two rows with no contact read aren't the same contact`() {
        val list = importList(local(1, contactId = null), synced(10, contactId = null))

        assertEquals(listOf(1L to ImportMark.NONE), list.candidates.map(::mark))
    }

    @Test
    fun `an e-mail address equal after trimming, ignoring case, makes a pair, and one differing in a letter doesn't`() {
        val list = importList(
            listOf(
                local(1, row(EMAIL, "data1" to "A@X.example", "data2" to 1L)),
                local(2, row(EMAIL, "data1" to "b@x.example", "data2" to 1L)),
                // Lower case in the root locale: a Turkish one would make the I a dotless ı.
                local(3, row(EMAIL, "data1" to "KIM@X.example", "data2" to 1L)),
            ),
            listOf(
                synced(10, row(EMAIL, "data1" to " a@x.example", "data2" to 2L)),
                synced(11, row(EMAIL, "data1" to "c@x.example", "data2" to 1L)),
                synced(12, row(EMAIL, "data1" to "kim@x.example", "data2" to 1L)),
            ),
        )

        assertEquals(listOf(pair(1, 10, MatchReason.EMAIL), pair(3, 12, MatchReason.EMAIL)), list.pairs.map(::shape))
        assertEquals(listOf(2L to ImportMark.NONE), list.candidates.map(::mark))
    }

    @Test
    fun `phone numbers are equal by the provider's normalized number when both have one`() {
        val list = importList(
            listOf(
                local(1, phone("(650) 555-0101", normalized = "+16505550101")),
                local(2, phone("650 555 0102", normalized = "+16505550102")),
                // The provider's normalized form leaves out an extension (API 37).
                local(3, phone("+1 650 555 0103 ext. 9", normalized = "+16505550103")),
            ),
            listOf(
                synced(10, phone("+1 650 555 0101", normalized = "+16505550101")),
                // The same digits, but another normalized number: not a match.
                synced(11, phone("650 555 0102", normalized = "+446505550102")),
                synced(12, phone("+1 650 555 0103", normalized = "+16505550103")),
            ),
        )

        assertEquals(listOf(pair(1, 10, MatchReason.PHONE), pair(3, 12, MatchReason.PHONE)), list.pairs.map(::shape))
        assertEquals(listOf(2L to ImportMark.NONE), list.candidates.map(::mark))
    }

    @Test
    fun `phone numbers without a normalized number on both are equal by their digits`() {
        val list = importList(
            listOf(local(1, phone("+1 (555) 0199")), local(2, phone("555-0199")), local(3, phone("ext. --")), local(4, phone("1-555-0177", normalized = "+15550177"))),
            listOf(synced(10, phone("1-555-0199")), synced(11, phone("+1 555 0199-9")), synced(12, phone("(no digits)")), synced(13, phone("1 555 0177"))),
        )

        assertEquals(listOf(pair(1, 10, MatchReason.PHONE), pair(4, 13, MatchReason.PHONE)), list.pairs.map(::shape))
        assertEquals(listOf(2L to ImportMark.NONE, 3L to ImportMark.NONE), list.candidates.map(::mark))
    }

    @Test
    fun `a number without a normalized one doesn't match the same number with a country code`() {
        val list = importList(listOf(local(1, phone("555-0199"))), listOf(synced(10, phone("+1 555 0199"))))

        assertEquals(listOf(1L to ImportMark.NONE), list.candidates.map(::mark))
    }

    @Test
    fun `a name equal after trimming, whitespace runs, and case makes a pair`() {
        val list = importList(local(1, name = " Ann \t LEE "), synced(10, name = "ann lee"))

        assertEquals(listOf(pair(1, 10, MatchReason.NAME)), list.pairs.map(::shape))
    }

    @Test
    fun `names compare in the root locale, with their accents`() {
        val list = importList(
            listOf(local(1, name = "IŞIK"), local(2, name = "Zoë Lee")),
            listOf(synced(10, name = "ışık"), synced(11, name = "Zoe Lee")),
        )

        assertEquals(emptyList<MergePair>(), list.pairs)
        assertEquals(listOf(1L to ImportMark.NONE, 2L to ImportMark.NONE), list.candidates.map(::mark))
    }

    @Test
    fun `a name Android took from something other than a name row matches no one, nor do blank names`() {
        val list = importList(
            listOf(
                local(1, row(ORGANIZATION, "data1" to "Acme"), name = "Acme", fromNameRow = false),
                local(2, name = "a@x.example", fromNameRow = false),
                local(3, name = "Bea Lee", fromNameRow = false),
                local(4, name = null),
                local(5, name = " "),
            ),
            listOf(
                synced(10, row(ORGANIZATION, "data1" to "Acme", "data2" to 1L), name = "Acme", fromNameRow = false),
                synced(11, name = "a@x.example", fromNameRow = false),
                synced(12, name = "Bea Lee"),
                synced(13, name = null),
                synced(14, name = "\t"),
            ),
        )

        assertEquals(emptyList<MergePair>(), list.pairs)
        assertEquals((1L..5L).map { it to ImportMark.NONE }, list.candidates.map(::mark))
    }

    @Test
    fun `the reason given is the first that holds`() {
        val email = row(EMAIL, "data1" to "a@x.example", "data2" to 1L)
        val list = importList(
            listOf(
                local(1, email, phone("555-0101"), name = "Ann Lee", contactId = 77),
                local(2, row(EMAIL, "data1" to "b@x.example", "data2" to 1L), phone("555-0102"), name = "Bea Lee"),
                local(3, phone("555-0103"), name = "Cy Lee"),
            ),
            listOf(
                synced(10, email, phone("555-0101"), name = "Ann Lee", contactId = 77),
                synced(11, row(EMAIL, "data1" to "b@x.example", "data2" to 1L), phone("555-0102"), name = "Bea Lee"),
                synced(12, phone("555-0103"), name = "Cy Lee"),
            ),
        )

        assertEquals(
            listOf(pair(1, 10, MatchReason.SAME_CONTACT), pair(2, 11, MatchReason.EMAIL), pair(3, 12, MatchReason.PHONE)),
            list.pairs.map(::shape),
        )
    }

    @Test
    fun `only live rows of the app's account holding a Proton ID are in Proton`() {
        val list = importList(
            listOf(local(1, name = "Ann Lee"), local(2, name = "Bea Lee"), local(3, name = "Cy Lee"), local(4, name = "Di Lee")),
            listOf(
                synced(10, name = "Ann Lee", protonId = null),
                synced(11, name = "Bea Lee", protonId = " \t"),
                synced(12, name = "Cy Lee", deleted = true),
                synced(13, name = "Di Lee"),
            ),
        )

        assertEquals(listOf(pair(4, 13, MatchReason.NAME)), list.pairs.map(::shape))
        assertEquals((1L..3L).map { it to ImportMark.NONE }, list.candidates.map(::mark))
    }

    // The marks.

    @Test
    fun `a phone contact matching two contacts in Proton looks like it's already there`() {
        val list = importList(
            listOf(local(1, phone("555-0101"), name = "Ann Lee")),
            listOf(synced(10, name = "Ann Lee"), synced(11, phone("555-0101"))),
        )

        assertEquals(emptyList<MergePair>(), list.pairs)
        assertEquals(listOf(1L to ImportMark.LOOKS_IN_PROTON), list.candidates.map(::mark))
    }

    @Test
    fun `two phone contacts matching one contact in Proton both look like they're already there`() {
        val list = importList(
            listOf(local(1, name = "Ann Lee"), local(2, phone("555-0101"))),
            listOf(synced(10, phone("555-0101"), name = "Ann Lee")),
        )

        assertEquals(emptyList<MergePair>(), list.pairs)
        assertEquals(listOf(1L to ImportMark.LOOKS_IN_PROTON, 2L to ImportMark.LOOKS_IN_PROTON), list.candidates.map(::mark))
    }

    @Test
    fun `a match with an edit waiting, or held, isn't a pair`() {
        val list = importList(
            listOf(local(1, name = "Ann Lee"), local(2, name = "Bea Lee"), local(3, name = "Cy Lee")),
            listOf(synced(10, name = "Ann Lee", dirty = true), synced(11, name = "Bea Lee", protonId = "proton-held"), synced(12, name = "Cy Lee")),
            held = setOf("proton-held"),
        )

        assertEquals(listOf(pair(3, 12, MatchReason.NAME)), list.pairs.map(::shape))
        assertEquals(listOf(1L to ImportMark.LOOKS_IN_PROTON, 2L to ImportMark.LOOKS_IN_PROTON), list.candidates.map(::mark))
    }

    @Test
    fun `a contact an import record names is imported before, whatever it matches, and never a pair`() {
        val list = importList(
            listOf(local(1, name = "Ann Lee"), local(2, name = "Bea Lee"), local(3, name = "Cy Lee")),
            listOf(synced(10, name = "Ann Lee"), synced(11, name = "Bea Lee"), synced(12, name = "Bea Lee")),
            records = listOf(record(1, copy = 10), record(2, copy = null), record(3, copy = 99)),
        )

        assertEquals(emptyList<MergePair>(), list.pairs)
        assertEquals((1L..3L).map { it to ImportMark.IMPORTED_BEFORE }, list.candidates.map(::mark))
    }

    @Test
    fun `contacts imported before don't make another phone contact's match ambiguous`() {
        val list = importList(
            listOf(local(1, name = "Ann Lee"), local(2, name = "Ann Lee")),
            listOf(synced(10, name = "Ann Lee")),
            records = listOf(record(1, copy = 10)),
        )

        assertEquals(listOf(pair(2, 10, MatchReason.NAME)), list.pairs.map(::shape))
        assertEquals(listOf(1L to ImportMark.IMPORTED_BEFORE), list.candidates.map(::mark))
    }

    @Test
    fun `deleted local rows aren't listed`() {
        val list = importList(listOf(local(1, name = "Ann Lee", deleted = true), local(2, name = "Ann Lee", deleted = true)), listOf(synced(10, name = "Ann Lee")))

        assertEquals(ImportList(emptyList(), emptyList(), emptyList()), list)
    }

    @Test
    fun `candidates and pairs keep the card issue of the contact's graph`() {
        val seen = mutableListOf<ContactGraph>()
        val one = local(1, phone("555-0101"))
        val two = local(2, row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee"), name = "Ann Lee")
        val three = local(3, row(EMAIL, "data1" to "c@x.example", "data2" to 0L, "data3" to "Odd"))
        val list = importList(listOf(one, two, three), listOf(synced(10, name = "Ann Lee")), emptyList(), emptySet()) { graph ->
            seen += graph
            when {
                graph.phones.isNotEmpty() -> MappingIssueCode.NO_NAME_OR_EMAIL
                graph.formattedName != null -> MappingIssueCode.UNWRITABLE_LABEL
                else -> null
            }
        }

        assertEquals(listOf(one, two, three).map { keyedGraph(it.rows).contact.graph }.toSet(), seen.toSet())
        assertEquals(listOf(1L to MappingIssueCode.NO_NAME_OR_EMAIL, 3L to null), list.candidates.map { it.original.row.rawContactId to it.cantImport })
        assertEquals(listOf(MappingIssueCode.UNWRITABLE_LABEL), list.pairs.map { it.cantImport })
    }

    @Test
    fun `candidates and pairs keep no content in toString`() {
        val list = importList(
            listOf(local(1, row(EMAIL, "data1" to "secret-a@x.example", "data2" to 1L), name = "Secret Name"), local(2, phone("555-0199"), name = "Hidden Person")),
            listOf(synced(10, row(EMAIL, "data1" to "secret-a@x.example", "data2" to 1L), name = "Secret Proton", protonId = "secret-proton-id")),
        )

        val text = list.toString()
        listOf("secret", "Secret", "Hidden", "555", "x.example").forEach { assertFalse(it in text, it) }
    }

    // What a copy can't hold: Import's question and Remove originals count it the same way.

    @ParameterizedTest(name = "{0}")
    @MethodSource("losses")
    fun `a contact holding what its copy can't is counted for Import's question`(case: String, rows: List<Row>, loses: Boolean) {
        val list = importList(listOf(local(1, *rows.toTypedArray())), emptyList())

        assertEquals(listOf(loses), list.candidates.map { it.unmappedRows }, case)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("losses")
    fun `an original holding what its copy can't holds more, by the same rule`(case: String, rows: List<Row>, loses: Boolean) {
        val original = local(1, *rows.toTypedArray())
        // The copy an import writes: what the column mapper reads, with the original's photo.
        val graph = keyedGraph(original.rows).contact.graph
        val copy = synced(10, rows = withFormattedLines(dataRows(graph, photo = graph.photo?.bytes), original.rows))

        val list = importList(listOf(original), listOf(copy), records = listOf(record(1, copy = 10, photoCopied = graph.photo != null)))

        assertEquals(listOf(if (loses) OriginalState.HOLDS_MORE else OriginalState.REMOVABLE), list.originals.map { it.state }, case)
    }

    // Imported originals.

    @Test
    fun `an imported original whose copy is on Proton is listed, removable when its copy shows all it shows`() {
        val rows = arrayOf(
            row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee", "data10" to 1L),
            row(EMAIL, "data1" to "a@x.example", "data2" to 1L),
            phone("555-0101", type = 2L),
        )
        val original = local(1, *rows, version = 4)
        val copy = synced(10, *rows, version = 9)

        val list = importList(listOf(original), listOf(copy), records = listOf(record(1, copy = 10, version = 4)))

        assertEquals(listOf(ImportedOriginal(original.row, 4, copy.row, OriginalState.REMOVABLE)), list.originals)
    }

    @Test
    fun `an original whose VERSION moved since the record is changed`() {
        val rows = arrayOf(row(NOTE, "data1" to "a note"), row(IM_KIND, "data1" to "ann"))
        val original = local(1, *rows, version = 5)
        val copy = synced(10, *rows)

        val list = importList(listOf(original), listOf(copy), records = listOf(record(1, copy = 10, version = 4)))

        assertEquals(listOf(ImportedOriginal(original.row, 4, copy.row, OriginalState.CHANGED)), list.originals)
    }

    @Test
    fun `an e-mail address or number matching by the rules with the same type and label is held, one with another label isn't`() {
        val email = { address: String, label: String -> row(EMAIL, "data1" to address, "data2" to 0L, "data3" to label) }
        val phone = { number: String, type: Long -> phone(number, type = type) }
        val list = importList(
            listOf(
                local(1, email("A@X.example", "School")),
                local(2, phone("+1 (555) 0199", 2L)),
                local(3, email("a@x.example", "School")),
                local(4, phone("555-0199", 2L)),
            ),
            listOf(
                synced(10, email(" a@x.example", "School")),
                // Formatting aside, as dialed: the `+` stays.
                synced(11, phone("+1-555-0199", 2L)),
                synced(12, email("a@x.example", "Work stuff")),
                synced(13, phone("555-0199", 3L)),
            ),
            records = (1L..4L).map { record(it, copy = it + 9) },
        )

        assertEquals(
            listOf(OriginalState.REMOVABLE, OriginalState.REMOVABLE, OriginalState.HOLDS_MORE, OriginalState.HOLDS_MORE),
            list.originals.map { it.state },
        )
    }

    @Test
    fun `a one-value field the copy holds differently, as after Combine both, holds more, while the copy's own extra values don't`() {
        val name = row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee")
        val list = importList(
            listOf(local(1, name, row(NOTE, "data1" to "from the phone")), local(2, name, row(NOTE, "data1" to "same")), local(3, name, row(NOTE, "data1" to "same"))),
            listOf(
                synced(10, name, row(NOTE, "data1" to "from Proton")),
                synced(11, name, row(NOTE, "data1" to "same"), row(EMAIL, "data1" to "extra@x.example", "data2" to 1L), row(NICKNAME, "data1" to "Annie")),
                // The same text as another kind doesn't hold it.
                synced(12, name, row(NICKNAME, "data1" to "same")),
            ),
            records = listOf(record(1, copy = 10), record(2, copy = 11), record(3, copy = 12)),
        )

        assertEquals(listOf(OriginalState.HOLDS_MORE, OriginalState.REMOVABLE, OriginalState.HOLDS_MORE), list.originals.map { it.state })
    }

    @Test
    fun `a name the copy holds differently holds more`() {
        val list = importList(
            listOf(local(1, row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee"))),
            listOf(synced(10, row(NAME, "data1" to "Ann B Lee", "data2" to "Ann", "data3" to "Lee", "data5" to "B"))),
            records = listOf(record(1, copy = 10)),
        )

        assertEquals(listOf(OriginalState.HOLDS_MORE), list.originals.map { it.state })
    }

    @Test
    fun `a photo is held exactly when the record says it was copied and the copy has one`() {
        val photo = row(PHOTO, "data15" to listOf<Byte>(1, 2, 3))
        val otherPhoto = row(PHOTO, "data15" to listOf<Byte>(7, 8))
        val note = row(NOTE, "data1" to "a note")
        val list = importList(
            listOf(local(1, note, photo), local(2, note, photo), local(3, note, photo), local(4, note)),
            listOf(synced(10, note, otherPhoto), synced(11, note, otherPhoto), synced(12, note), synced(13, note, otherPhoto)),
            records = listOf(record(1, copy = 10, photoCopied = true), record(2, copy = 11), record(3, copy = 12, photoCopied = true), record(4, copy = 13)),
        )

        assertEquals(
            listOf(OriginalState.REMOVABLE, OriginalState.HOLDS_MORE, OriginalState.HOLDS_MORE, OriginalState.REMOVABLE),
            list.originals.map { it.state },
        )
    }

    @Test
    fun `a number is held only with the same digits, so an extension the normalized form leaves out isn't`() {
        val list = importList(
            listOf(
                local(1, phone("+1 650 555 0101 ext. 12", normalized = "+16505550101", type = 3L)),
                local(2, phone("(650) 555-0101", normalized = "+16505550101", type = 3L)),
                local(3, phone("+1 650 555 0101", normalized = "+16505550101", type = 3L)),
            ),
            (10L..12L).map { synced(it, phone("+1 650 555 0101", normalized = "+16505550101", type = 3L)) },
            records = (1L..3L).map { record(it, copy = it + 9) },
        )

        assertEquals(listOf(OriginalState.HOLDS_MORE, OriginalState.HOLDS_MORE, OriginalState.REMOVABLE), list.originals.map { it.state })
    }

    @Test
    fun `a number is held only as written but for spaces, dashes, brackets, and dots`() {
        val list = importList(
            listOf(
                local(1, phone("+44 20 7946 0958", normalized = "+442079460958")),
                local(2, phone("555-FLOWERS")),
                local(3, phone("650 555 0101,,123")),
                local(4, phone("(650) 555-0101")),
            ),
            listOf(synced(10, phone("44 20 7946 0958")), synced(11, phone("555-CONTACT")), synced(12, phone("650 555 0101;123")), synced(13, phone("650.555.0101"))),
            records = (1L..4L).map { record(it, copy = it + 9) },
        )

        assertEquals(
            listOf(OriginalState.HOLDS_MORE, OriginalState.HOLDS_MORE, OriginalState.HOLDS_MORE, OriginalState.REMOVABLE),
            list.originals.map { it.state },
        )
    }

    @Test
    fun `a star the copy doesn't have holds more`() {
        val note = row(NOTE, "data1" to "a note")
        val list = importList(
            listOf(local(1, note).starred(), local(2, note).starred(), local(3, note)),
            listOf(synced(10, note), synced(11, note).starred(), synced(12, note).starred()),
            records = (1L..3L).map { record(it, copy = it + 9) },
        )

        assertEquals(listOf(OriginalState.HOLDS_MORE, OriginalState.REMOVABLE, OriginalState.REMOVABLE), list.originals.map { it.state })
        assertEquals(listOf(false, false, false), list.candidates.map { it.unmappedRows })
    }

    @Test
    fun `a ringtone, send to voicemail, or a default number or e-mail address, which a copy never takes, is counted and holds more`() {
        val note = row(NOTE, "data1" to "a note")
        val ringtone = { contact: ReadContact -> contact.copy(row = contact.row.copy(customRingtone = "content://test-only/ringtone/1")) }
        val voicemail = { contact: ReadContact -> contact.copy(row = contact.row.copy(sendToVoicemail = true)) }
        val default = { contact: ReadContact -> contact.copy(row = contact.row.copy(hasDefault = true)) }
        val list = importList(
            listOf(ringtone(local(1, note)), voicemail(local(2, note)), default(local(3, note))),
            listOf(ringtone(synced(10, note)), voicemail(synced(11, note)), default(synced(12, note))),
            records = listOf(record(1, copy = 10), record(2, copy = 11), record(3, copy = 12)),
        )

        assertEquals(listOf(true, true, true), list.candidates.map { it.unmappedRows })
        assertEquals(listOf(OriginalState.HOLDS_MORE, OriginalState.HOLDS_MORE, OriginalState.HOLDS_MORE), list.originals.map { it.state })
    }

    @Test
    fun `an address's formatted line the copy's doesn't equal holds more, though Import's question doesn't count it`() {
        val parts = arrayOf("data4" to "10 Test St", "data7" to "Testtown", "data2" to 1L)
        val original = row(POSTAL, "data1" to "Flat 3, 10 Test St, Testtown", *parts)
        val list = importList(
            listOf(local(1, original), local(2, original)),
            listOf(synced(10, row(POSTAL, "data1" to "10 Test St\nTesttown", *parts)), synced(11, row(POSTAL, "data1" to "Flat 3, 10 Test St, Testtown", *parts))),
            records = listOf(record(1, copy = 10), record(2, copy = 11)),
        )

        assertEquals(listOf(OriginalState.HOLDS_MORE, OriginalState.REMOVABLE), list.originals.map { it.state })
        assertEquals(listOf(false, false), list.candidates.map { it.unmappedRows })
    }

    @Test
    fun `a copy's own extra address and its line hold every line the original has`() {
        val original = row(POSTAL, "data1" to "10 Test St\nTesttown", "data4" to "10 Test St", "data7" to "Testtown", "data2" to 1L)
        val proton = row(POSTAL, "data1" to "2 Work Rd\nWorktown", "data4" to "2 Work Rd", "data7" to "Worktown", "data2" to 2L)
        val list = importList(listOf(local(1, original)), listOf(synced(10, original, proton)), records = listOf(record(1, copy = 10)))

        assertEquals(listOf(OriginalState.REMOVABLE), list.originals.map { it.state })
    }

    @Test
    fun `a website, nickname, or SIP address the copy lacks holds more, whatever their types`() {
        val list = importList(
            listOf(
                local(1, row(WEBSITE, "data1" to "https://example.test", "data2" to 7L)),
                local(2, row(NICKNAME, "data1" to "Annie", "data2" to 1L)),
                local(3, row(SIP, "data1" to "ann@sip.example.test")),
                local(4, row(NICKNAME, "data1" to "Annie", "data2" to 2L)),
            ),
            listOf(synced(10), synced(11), synced(12), synced(13, row(NICKNAME, "data1" to "Annie", "data2" to 1L))),
            records = (1L..4L).map { record(it, copy = it + 9) },
        )

        assertEquals(
            listOf(OriginalState.HOLDS_MORE, OriginalState.HOLDS_MORE, OriginalState.HOLDS_MORE, OriginalState.REMOVABLE),
            list.originals.map { it.state },
        )
    }

    @Test
    fun `a copy that has an edit waiting, is held, holds no Proton ID, is deleted, or is gone isn't listed, nor is a record with no copy`() {
        val note = row(NOTE, "data1" to "a note")
        val list = importList(
            (1L..7L).map { local(it, note) },
            listOf(
                synced(10, note, dirty = true),
                synced(11, note, protonId = null),
                synced(12, note, protonId = " "),
                synced(13, note, deleted = true),
                synced(15, note, protonId = "proton-held"),
            ),
            records = listOf(
                record(1, copy = 10), record(2, copy = 11), record(3, copy = 12), record(4, copy = 13), record(5, copy = 14), record(6, copy = null),
                record(7, copy = 15),
            ),
            held = setOf("proton-held"),
        )

        assertEquals(emptyList<ImportedOriginal>(), list.originals)
    }

    @Test
    fun `a record whose original isn't read isn't listed, an empty read included`() {
        val note = row(NOTE, "data1" to "a note")

        val some = importList(listOf(local(2, note)), listOf(synced(10, note)), records = listOf(record(1, copy = 10)))
        val none = importList(emptyList(), listOf(synced(10, note)), records = listOf(record(1, copy = 10)))

        assertEquals(ImportList(listOf(ImportCandidate(local(2, note), ImportMark.NONE, null, false)), emptyList(), emptyList()), some)
        assertEquals(ImportList(emptyList(), emptyList(), emptyList()), none)
    }

    private fun importList(local: ReadContact, synced: ReadContact) = importList(listOf(local), listOf(synced))

    private fun importList(
        local: List<ReadContact>,
        synced: List<ReadContact>,
        records: List<ImportRecordEntity> = emptyList(),
        held: Set<String> = emptySet(),
    ) = importList(local, synced, records, held) { null }

    private companion object {
        const val IM_KIND = "vnd.android.cursor.item/im"
        const val GROUP_KIND = "vnd.android.cursor.item/group_membership"

        fun phone(number: String, normalized: String? = null, type: Long = 2L) =
            row(PHONE, *listOfNotNull("data1" to number, "data2" to type, normalized?.let { "data4" to it }).toTypedArray())

        /** A live row of the phone's local account. */
        fun local(
            id: Long,
            vararg rows: Row,
            name: String? = null,
            fromNameRow: Boolean = name != null,
            contactId: Long? = 1000 + id,
            version: Long = 1,
            deleted: Boolean = false,
        ) = ReadContact(
            DeviceContact(id, sourceId = null, dirty = true, deleted = deleted, version = version, displayName = name, contactId = contactId, nameFromNameRow = fromNameRow),
            rows.map(::dataRow),
        )

        /** A row of the app's account, holding a Proton ID unless [protonId] is null. */
        fun synced(
            id: Long,
            vararg rows: Row,
            name: String? = null,
            fromNameRow: Boolean = name != null,
            contactId: Long? = 2000 + id,
            protonId: String? = "proton-$id",
            dirty: Boolean = false,
            deleted: Boolean = false,
            version: Long = 1,
        ) = ReadContact(
            DeviceContact(id, protonId, dirty = dirty, deleted = deleted, version = version, displayName = name, contactId = contactId, nameFromNameRow = fromNameRow),
            rows.map(::dataRow),
        )

        fun synced(id: Long, rows: List<DataRow>) = ReadContact(DeviceContact(id, "proton-$id", dirty = false, deleted = false, version = 1, displayName = null, contactId = 2000 + id), rows)

        fun ReadContact.starred() = copy(row = row.copy(starred = true))

        /**
         * [copy] as the provider stores it: each address with parts gets the formatted line [original]'s holds, built
         * from the same parts.
         */
        fun withFormattedLines(copy: List<DataRow>, original: List<DataRow>): List<DataRow> {
            val lines = original.filter { it.mimeType == POSTAL && it.values.keys.any { column -> column in POSTAL_PARTS } }.map { it.values["data1"] }.iterator()
            return copy.map { row ->
                if (row.mimeType != POSTAL) return@map row
                val line = if (lines.hasNext()) lines.next() else null
                if (line == null) row else row.copy(values = row.values + ("data1" to line))
            }
        }

        val POSTAL_PARTS = setOf("data4", "data5", "data7", "data8", "data9", "data10")

        fun record(original: Long, copy: Long?, version: Long = 1, photoCopied: Boolean = false) =
            ImportRecordEntity("test-only-account", original, version, copy, photoCopied)

        fun pair(original: Long, proton: Long, reason: MatchReason) = Triple(original, proton, reason)

        fun shape(pair: MergePair) = Triple(pair.original.row.rawContactId, pair.proton.row.rawContactId, pair.reason)

        fun mark(candidate: ImportCandidate) = candidate.original.row.rawContactId to candidate.mark

        private fun case(name: String, loses: Boolean, vararg rows: Row): Arguments = Arguments.of(name, rows.toList(), loses)

        @JvmStatic
        fun losses() = listOf(
            case(
                "every kind of the field table, as the Contacts app writes them", false,
                row(NAME, "data1" to "Dr Ann B Lee Jr", "data2" to "Ann", "data3" to "Lee", "data4" to "Dr", "data5" to "B", "data6" to "Jr", "data7" to "an", "data8" to "bee", "data9" to "lee"),
                row(EMAIL, "data1" to "h@x.example", "data2" to 1L),
                row(EMAIL, "data1" to "w@x.example", "data2" to 2L),
                row(EMAIL, "data1" to "o@x.example", "data2" to 3L),
                row(EMAIL, "data1" to "c@x.example", "data2" to 0L, "data3" to "School"),
                *(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 12L).map { phone("555-01%02d".format(it), type = it) }.toTypedArray()),
                row(PHONE, "data1" to "555-0100", "data2" to 0L, "data3" to "Boat"),
                row(POSTAL, "data4" to "1 Main St", "data5" to "PO 9", "data7" to "Town", "data8" to "Region", "data9" to "12345", "data10" to "Country", "data2" to 1L),
                row(POSTAL, "data7" to "Work Town", "data2" to 2L),
                row(POSTAL, "data7" to "Other Town", "data2" to 3L),
                // The Contacts app gives an organization and a SIP address no type, and shows none for them, a website,
                // or a nickname (AOSP's FallbackAccountType).
                row(ORGANIZATION, "data1" to "Acme", "data4" to "Engineer", "data5" to "R&D;Lab", "data6" to "Lead"),
                row(EVENT, "data1" to "1990-05-07", "data2" to 3L),
                row(EVENT, "data1" to "--12-31", "data2" to 1L),
                row(EVENT, "data1" to "--03-04", "data2" to 2L),
                row(EVENT, "data1" to "0005-01-02", "data2" to 0L, "data3" to "Met"),
                row(NOTE, "data1" to "line 1\nline 2"),
                row(WEBSITE, "data1" to "https://example.test", "data2" to 1L),
                row(NICKNAME, "data1" to "Annie", "data2" to 1L),
                row(RELATION, "data1" to "Sam", "data2" to 14L),
                row(RELATION, "data1" to "Kit", "data2" to 0L, "data3" to "Coach"),
                row(SIP, "data1" to "ann@sip.example.test"),
                row(PHOTO, "data15" to listOf<Byte>(1, 2, 3)),
            ),
            // The columns the provider fills in itself, pinned per kind by the instrumented ProviderDeviceContactsTest.
            case("a name's full-name style", false, row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee", "data10" to 1L)),
            case("a formatted address with parts", false, row(POSTAL, "data1" to "1 Main St\nTown", "data4" to "1 Main St", "data7" to "Town", "data2" to 1L)),
            case("a normalized number", false, phone("+44 20 7946 0123", normalized = "+442079460123")),
            // Types and labels Android doesn't show.
            case("an e-mail address's label on a type that isn't custom", false, row(EMAIL, "data1" to "a@x.example", "data2" to 1L, "data3" to "Ignored")),
            case("a birthday's label", false, row(EVENT, "data1" to "1990-05-07", "data2" to 3L, "data3" to "Ignored")),
            case("a website's type", false, row(WEBSITE, "data1" to "https://example.test", "data2" to 2L)),
            case("a blank e-mail address", false, row(EMAIL, "data1" to " ", "data2" to 1L)),
            case("a blank neighbourhood", false, row(POSTAL, "data6" to " ", "data7" to "Town", "data2" to 1L)),
            case("an empty value in a note's data15", false, row(NOTE, "data1" to "a note", "data15" to listOf<Byte>())),
            case("a key on a row", false, row(NOTE, "data1" to "a note", SYNC1 to "NOTE.0123456789abcdef")),
            // Rows the column mapper can't hold.
            case("an IM", true, row(NOTE, "data1" to "a note"), row(IM_KIND, "data1" to "ann", "data2" to 3L, "data5" to 1L)),
            case("a group", true, row(NOTE, "data1" to "a note"), row(GROUP_KIND, "data1" to 7L)),
            case("a second note", true, row(NOTE, "data1" to "a note"), row(NOTE, "data1" to "another note")),
            case("a second photo", true, row(PHOTO, "data15" to listOf<Byte>(1)), row(PHOTO, "data15" to listOf<Byte>(2))),
            case("a date that doesn't read", true, row(EVENT, "data1" to "May 7", "data2" to 3L)),
            // Values in columns the field table doesn't read.
            case("an address held only as one formatted line", true, row(POSTAL, "data1" to "1 Main St, Town", "data2" to 1L)),
            case("a neighbourhood", true, row(POSTAL, "data6" to "Old Town", "data7" to "Town", "data2" to 1L)),
            case("an organization's symbol", true, row(ORGANIZATION, "data1" to "Acme", "data7" to "ACME")),
            case("an organization's phonetic name", true, row(ORGANIZATION, "data1" to "Acme", "data8" to "akmee")),
            case("an organization's office", true, row(ORGANIZATION, "data1" to "Acme", "data9" to "Floor 2")),
            // The provider doesn't fill it in (API 37), so another app wrote it.
            case("an organization's phonetic name style", true, row(ORGANIZATION, "data1" to "Acme", "data2" to 1L, "data10" to 0L)),
            case("an e-mail address's display name", true, row(EMAIL, "data1" to "a@x.example", "data2" to 1L, "data4" to "Ann")),
            case("a value in a note's data15", true, row(NOTE, "data1" to "a note", "data15" to listOf<Byte>(1))),
            case("a value in a note's data2, which has no type", true, row(NOTE, "data1" to "a note", "data2" to 1L)),
            case("a value in a photo row's data1", true, row(PHOTO, "data1" to "x", "data15" to listOf<Byte>(1))),
            // Types and labels the copy can't keep (docs/mapping.md § Field table).
            case("an e-mail address's MOBILE", true, row(EMAIL, "data1" to "a@x.example", "data2" to 4L)),
            case("a custom e-mail type without a label", true, row(EMAIL, "data1" to "a@x.example", "data2" to 0L)),
            case("a phone type the upload loses", true, phone("555-0101", type = 9L)),
            case("a custom phone type without a label", true, row(PHONE, "data1" to "555-0101", "data2" to 0L)),
            case("a custom address type", true, row(POSTAL, "data7" to "Town", "data2" to 0L, "data3" to "Cabin")),
            case("an event without a type", true, row(EVENT, "data1" to "1990-05-07")),
            case("a relation type Android doesn't name", true, row(RELATION, "data1" to "Sam", "data2" to 99L, "data3" to "Odd")),
            case("a key naming FN alone, so the name parts aren't read", true, row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee", SYNC1 to "FN.0123456789abcdef")),
        )
    }
}
