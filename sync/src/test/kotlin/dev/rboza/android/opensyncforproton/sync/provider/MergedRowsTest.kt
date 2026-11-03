package dev.rboza.android.opensyncforproton.sync.provider

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
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.plain
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.row
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource

// The rows a merge writes for Combine both and Keep the phone's (docs/sync.md § Import from this phone, Merge). Rows
// are given as readRows gives them (the Proton row's with its keys and the columns the provider filled in); every value
// is made up for the test.
class MergedRowsTest {
    /** The rows [mergedRows] gives, as plain rows. */
    private fun rows(merged: MergedRows) = merged.rows.map(::plain)

    private fun merge(proton: List<Row>, phone: List<Row>, choice: MergeChoice, photo: ByteArray? = null) =
        mergedRows(proton.map(::dataRow), phone.map(::dataRow), photo, choice)

    @ParameterizedTest(name = "{0}")
    @MethodSource("listKinds")
    fun `Combine both adds each list value the phone has and Proton lacks, with no key, and keeps Proton's rows as they are`(
        case: String,
        proton: List<Row>,
        phone: List<Row>,
        added: List<Row>,
    ) {
        val merged = merge(proton, phone, MergeChoice.COMBINE)

        assertEquals(proton + added, rows(merged), case)
        assertFalse(merged.unchanged, case)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneValueKinds")
    fun `Combine both keeps a one-value kind Proton has, and adds one only the phone has`(case: String, proton: Row, phone: Row, imported: Row) {
        val kept = merge(listOf(proton), listOf(phone), MergeChoice.COMBINE)
        val added = merge(listOf(EVERYONE), listOf(phone), MergeChoice.COMBINE)

        assertEquals(listOf(proton), rows(kept), case)
        assertTrue(kept.unchanged, case)
        assertEquals(listOf(EVERYONE, imported), rows(added), case)
        assertFalse(added.unchanged, case)
    }

    @Test
    fun `the name, Combine both keeps Proton's and adds the phone's when Proton has none, Keep the phone's gives the phone's with Proton's key`() {
        val email = row(EMAIL, "data1" to "ann@example.test", "data2" to 1L, SYNC1 to key("EMAIL", 1))
        val phone = listOf(row(NAME, "data1" to "Ann B Lee", "data2" to "Ann", "data3" to "Lee", "data5" to "B", "data10" to 1L))
        val imported = row(NAME, "data1" to "Ann B Lee", "data2" to "Ann", "data3" to "Lee", "data5" to "B")

        val kept = merge(listOf(EVERYONE), phone, MergeChoice.COMBINE)

        assertEquals(listOf(EVERYONE), rows(kept))
        assertTrue(kept.unchanged)
        assertEquals(listOf(email, imported), rows(merge(listOf(email), phone, MergeChoice.COMBINE)))
        assertEquals(listOf(imported + (SYNC1 to key("FN", 99))), rows(merge(listOf(EVERYONE), phone, MergeChoice.KEEP_PHONE)))
    }

    @Test
    fun `Combine both keeps the organization as a whole`() {
        val proton = row(ORGANIZATION, "data1" to "Acme", "data2" to 1L, SYNC1 to key("ORG", 1))
        val phone = row(ORGANIZATION, "data1" to "Acme", "data4" to "Engineer", "data2" to 1L)

        assertEquals(listOf(proton), rows(merge(listOf(proton), listOf(phone), MergeChoice.COMBINE)))
    }

    @Test
    fun `Combine both keeps Proton's photo row as it is, and nothing of the phone's photo`() {
        val proton = listOf(EVERYONE, row(PHOTO, "data15" to listOf<Byte>(1, 2), SYNC1 to key("PHOTO", 1)))
        val phone = listOf(row(PHOTO, "data15" to listOf<Byte>(5, 6)))

        val merged = merge(proton, phone, MergeChoice.COMBINE, photo = byteArrayOf(7, 7))

        assertEquals(listOf(EVERYONE), rows(merged))
        assertTrue(merged.keepPhotoRow)
        assertFalse(merged.photoCopied)
        assertTrue(merged.unchanged)
    }

    @Test
    fun `Combine both takes the phone's photo when Proton has none`() {
        // An empty photo row holds no photo: the provider stores an empty thumbnail as none.
        val proton = listOf(EVERYONE, row(PHOTO, SYNC1 to key("PHOTO", 1)))
        val phone = listOf(row(PHOTO, "data15" to listOf<Byte>(5, 6)))

        val display = merge(proton, phone, MergeChoice.COMBINE, photo = byteArrayOf(7, 7))
        val thumbnail = merge(proton.take(1), phone, MergeChoice.COMBINE)

        assertEquals(listOf(EVERYONE, row(PHOTO, "data15" to listOf<Byte>(7, 7))), rows(display))
        assertEquals(listOf(EVERYONE, row(PHOTO, "data15" to listOf<Byte>(5, 6))), rows(thumbnail))
        listOf(display, thumbnail).forEach {
            assertFalse(it.keepPhotoRow)
            assertTrue(it.photoCopied)
            assertFalse(it.unchanged)
        }
    }

    @Test
    fun `Combine both with no photo on either side writes none and copies none`() {
        val merged = merge(listOf(EVERYONE), listOf(EVERYONE_AS_IMPORTED), MergeChoice.COMBINE)

        assertEquals(listOf(EVERYONE), rows(merged))
        assertFalse(merged.keepPhotoRow)
        assertFalse(merged.photoCopied)
        assertTrue(merged.unchanged)
    }

    @Test
    fun `Combine both keeps Proton's photo when the phone has none`() {
        val merged = merge(listOf(EVERYONE, row(PHOTO, "data15" to listOf<Byte>(1, 2))), listOf(EVERYONE_AS_IMPORTED), MergeChoice.COMBINE)

        assertEquals(listOf(EVERYONE), rows(merged))
        assertTrue(merged.keepPhotoRow)
        assertFalse(merged.photoCopied)
        assertTrue(merged.unchanged)
    }

    @Test
    fun `Combine both adds a new value once, however often the phone holds it`() {
        val phone = listOf(row(EMAIL, "data1" to "new@example.test", "data2" to 1L), row(EMAIL, "data1" to "NEW@example.test", "data2" to 2L))

        assertEquals(listOf(EVERYONE, row(EMAIL, "data1" to "new@example.test", "data2" to 1L)), rows(merge(listOf(EVERYONE), phone, MergeChoice.COMBINE)))
    }

    @Test
    fun `Combine both adds a date equal to Proton's birthday as another date, and a value equal to another kind's`() {
        val proton = listOf(
            EVERYONE,
            row(EVENT, "data1" to "1990-05-07", "data2" to 3L, SYNC1 to key("BDAY", 1)),
            row(EMAIL, "data1" to "ann@sip.example.test", "data2" to 1L, SYNC1 to key("EMAIL", 2)),
        )
        val phone = listOf(row(EVENT, "data1" to "1990-05-07", "data2" to 2L), row(SIP, "data1" to "ann@sip.example.test", "data2" to 1L))

        assertEquals(
            proton + listOf(row(EVENT, "data1" to "1990-05-07", "data2" to 2L), row(SIP, "data1" to "ann@sip.example.test", "data2" to 3L)),
            rows(merge(proton, phone, MergeChoice.COMBINE)),
        )
        assertEquals(
            listOf(row(EVENT, "data1" to "1990-05-07", "data2" to 2L), row(SIP, "data1" to "ann@sip.example.test", "data2" to 3L)),
            rows(merge(proton, phone, MergeChoice.KEEP_PHONE)),
        )
    }

    @Test
    fun `Combine both adds a one-value kind whose Proton row holds no value`() {
        val proton = listOf(EVERYONE, row(NOTE, SYNC1 to key("NOTE", 1)))

        assertEquals(proton + row(NOTE, "data1" to "the phone's note"), rows(merge(proton, listOf(row(NOTE, "data1" to "the phone's note")), MergeChoice.COMBINE)))
    }

    @Test
    fun `Keep the phone's gives the phone's values, each one Proton shows alike keeping Proton's row and key, the rest with none`() {
        val proton = listOf(
            row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee", "data10" to 1L, SYNC1 to key("FN", 1)),
            row(EMAIL, "data1" to "ann@example.test", "data2" to 1L, SYNC1 to key("EMAIL", 2)),
            row(EMAIL, "data1" to "gone@example.test", "data2" to 2L, SYNC1 to key("EMAIL", 3)),
            row(PHONE, "data1" to "+1 555 0199", "data2" to 1L, SYNC1 to key("TEL", 4)),
            row(POSTAL, "data1" to "1 Main St\nTown", "data4" to "1 Main St", "data7" to "Town", "data2" to 1L, SYNC1 to key("ADR", 5)),
            row(POSTAL, "data1" to "9 Gone Rd", "data4" to "9 Gone Rd", "data2" to 2L, SYNC1 to key("ADR", 6)),
            row(NOTE, "data1" to "Proton's note", SYNC1 to key("NOTE", 7)),
        )
        val phone = listOf(
            row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee", "data10" to 1L),
            row(EMAIL, "data1" to " Ann@Example.test", "data2" to 1L),
            row(EMAIL, "data1" to "new@example.test", "data2" to 3L),
            row(PHONE, "data1" to "+1 555 0199", "data2" to 1L),
            row(POSTAL, "data1" to "1 Main St\nTown", "data4" to "1 Main St", "data7" to "Town", "data2" to 1L),
            row(POSTAL, "data4" to "2 Side St", "data2" to 2L),
        )

        val merged = merge(proton, phone, MergeChoice.KEEP_PHONE)

        assertEquals(
            listOf(
                proton[0],
                proton[1],
                row(EMAIL, "data1" to "new@example.test", "data2" to 3L),
                proton[3],
                proton[4],
                row(POSTAL, "data4" to "2 Side St", "data2" to 2L),
            ),
            rows(merged),
        )
        assertFalse(merged.keepPhotoRow)
        assertFalse(merged.photoCopied)
        assertFalse(merged.unchanged)
    }

    @Test
    fun `Keep the phone's gives a value Proton holds with another type, label, or spelling the phone's row and Proton's key`() {
        // The key keeps the property's hidden parts, an e-mail address's encryption settings among them: only a value
        // removed loses them.
        val proton = listOf(
            EVERYONE,
            row(EMAIL, "data1" to "ann@example.test", "data2" to 2L, SYNC1 to key("EMAIL", 1)),
            row(PHONE, "data1" to "+1 650 555 0101", "data2" to 2L, "data4" to "+16505550101", SYNC1 to key("TEL", 2)),
            row(PHONE, "data1" to "+1 555 0199", "data2" to 0L, "data3" to "Boat", SYNC1 to key("TEL", 3)),
            row(RELATION, "data1" to "Bob", "data2" to 14L, SYNC1 to key("RELATED", 4)),
        )
        val phone = listOf(
            EVERYONE_AS_IMPORTED,
            row(EMAIL, "data1" to "ann@example.test", "data2" to 1L),
            row(PHONE, "data1" to "650 555 0101", "data2" to 2L, "data4" to "+16505550101"),
            row(PHONE, "data1" to "1-555-0199", "data2" to 0L, "data3" to "Car"),
            row(RELATION, "data1" to "Bob", "data2" to 1L),
        )

        assertEquals(
            listOf(
                EVERYONE,
                row(EMAIL, "data1" to "ann@example.test", "data2" to 1L, SYNC1 to key("EMAIL", 1)),
                row(PHONE, "data1" to "650 555 0101", "data2" to 2L, SYNC1 to key("TEL", 2)),
                row(PHONE, "data1" to "1-555-0199", "data2" to 0L, "data3" to "Car", SYNC1 to key("TEL", 3)),
                row(RELATION, "data1" to "Bob", "data2" to 1L, SYNC1 to key("RELATED", 4)),
            ),
            rows(merge(proton, phone, MergeChoice.KEEP_PHONE)),
        )
    }

    @Test
    fun `Keep the phone's pairs each of Proton's values with one of the phone's at most`() {
        val proton = listOf(EVERYONE, row(EMAIL, "data1" to "ann@example.test", "data2" to 1L, SYNC1 to key("EMAIL", 1)))
        val phone = listOf(EVERYONE_AS_IMPORTED, row(EMAIL, "data1" to "ann@example.test", "data2" to 2L), row(EMAIL, "data1" to "ann@example.test", "data2" to 1L))

        assertEquals(
            listOf(EVERYONE, row(EMAIL, "data1" to "ann@example.test", "data2" to 2L), proton[1]),
            rows(merge(proton, phone, MergeChoice.KEEP_PHONE)),
        )
        val twice = listOf(EVERYONE_AS_IMPORTED, row(EMAIL, "data1" to "ann@example.test", "data2" to 2L), row(EMAIL, "data1" to "ANN@example.test", "data2" to 3L))
        assertEquals(
            listOf(EVERYONE, row(EMAIL, "data1" to "ann@example.test", "data2" to 2L, SYNC1 to key("EMAIL", 1)), row(EMAIL, "data1" to "ANN@example.test", "data2" to 3L)),
            rows(merge(proton, twice, MergeChoice.KEEP_PHONE)),
        )
    }

    @Test
    fun `only a number takes the phone's normalized form, not another kind's value written alike`() {
        // A name row's data4 is its prefix, which a number's normalized form must not fill in.
        val name = row(NAME, "data1" to "+1 650 555 0101", "data10" to 1L, SYNC1 to key("FN", 1))
        val number = row(PHONE, "data1" to "+1 650 555 0101", "data2" to 2L, "data4" to "+16505550101", SYNC1 to key("TEL", 2))
        val phone = listOf(name - SYNC1, number - SYNC1)

        val merged = merge(listOf(name, number), phone, MergeChoice.KEEP_PHONE)

        assertEquals(listOf(name, number), rows(merged))
        assertTrue(merged.unchanged)
    }

    @Test
    fun `Keep the phone's takes no key from a Proton row that holds no value`() {
        val proton = listOf(EVERYONE, row(NOTE, SYNC1 to key("NOTE", 1)))

        assertEquals(
            listOf(EVERYONE, row(NOTE, "data1" to "the phone's note")),
            rows(merge(proton, listOf(EVERYONE_AS_IMPORTED, row(NOTE, "data1" to "the phone's note")), MergeChoice.KEEP_PHONE)),
        )
    }

    @Test
    fun `the rows' order doesn't count for unchanged`() {
        val first = row(EMAIL, "data1" to "first@example.test", "data2" to 1L, SYNC1 to key("EMAIL", 1))
        val second = row(EMAIL, "data1" to "second@example.test", "data2" to 1L, SYNC1 to key("EMAIL", 2))
        val phone = listOf(EVERYONE_AS_IMPORTED, second - SYNC1, first - SYNC1)

        val merged = merge(listOf(EVERYONE, first, second), phone, MergeChoice.KEEP_PHONE)

        assertEquals(listOf(EVERYONE, second, first), rows(merged))
        assertTrue(merged.unchanged)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneValueKinds")
    fun `Keep the phone's gives a one-value kind the phone's value with Proton's key, keeps Proton's row when alike, and drops it when the phone has none`(
        case: String,
        proton: Row,
        phone: Row,
        imported: Row,
    ) {
        val replaced = merge(listOf(EVERYONE, proton), listOf(EVERYONE_AS_IMPORTED, phone), MergeChoice.KEEP_PHONE)
        val alike = merge(listOf(EVERYONE, proton), listOf(EVERYONE_AS_IMPORTED, proton - SYNC1), MergeChoice.KEEP_PHONE)
        val gone = merge(listOf(EVERYONE, proton), listOf(EVERYONE_AS_IMPORTED), MergeChoice.KEEP_PHONE)

        assertEquals(listOf(EVERYONE, imported + (SYNC1 to proton.values.getValue(SYNC1))), rows(replaced), case)
        assertEquals(listOf(EVERYONE, proton), rows(alike), case)
        assertTrue(alike.unchanged, case)
        assertEquals(listOf(EVERYONE), rows(gone), case)
        assertFalse(gone.unchanged, case)
    }

    @Test
    fun `Keep the phone's replaces Proton's photo by the phone's, with its key, and never keeps Proton's photo row`() {
        val proton = listOf(EVERYONE, row(PHOTO, "data15" to listOf<Byte>(1, 2), SYNC1 to key("PHOTO", 1)))
        val phone = listOf(EVERYONE_AS_IMPORTED, row(PHOTO, "data15" to listOf<Byte>(5, 6)))

        val replaced = merge(proton, phone, MergeChoice.KEEP_PHONE, photo = byteArrayOf(7, 7))
        val added = merge(proton.take(1), phone, MergeChoice.KEEP_PHONE, photo = byteArrayOf(7, 7))

        assertEquals(listOf(EVERYONE, row(PHOTO, "data15" to listOf<Byte>(7, 7), SYNC1 to key("PHOTO", 1))), rows(replaced))
        assertEquals(listOf(EVERYONE, row(PHOTO, "data15" to listOf<Byte>(7, 7))), rows(added))
        listOf(replaced, added).forEach {
            assertFalse(it.keepPhotoRow)
            assertTrue(it.photoCopied)
            assertFalse(it.unchanged)
        }
    }

    @Test
    fun `Keep the phone's removes Proton's photo when the phone has none`() {
        val merged = merge(listOf(EVERYONE, row(PHOTO, "data15" to listOf<Byte>(1, 2))), listOf(EVERYONE_AS_IMPORTED), MergeChoice.KEEP_PHONE)

        assertEquals(listOf(EVERYONE), rows(merged))
        assertFalse(merged.keepPhotoRow)
        assertFalse(merged.photoCopied)
        assertFalse(merged.unchanged)
    }

    @ParameterizedTest
    @EnumSource(MergeChoice::class, names = ["COMBINE", "KEEP_PHONE"])
    fun `rows of kinds the field table doesn't name aren't in the rows, on either side`(choice: MergeChoice) {
        val proton = listOf(EVERYONE, row(IM, "data1" to "ann", SYNC1 to key("IMPP", 1)), row(GROUP_MEMBERSHIP, "data1" to 7L))
        val phone = listOf(EVERYONE_AS_IMPORTED, row(IM, "data1" to "bob"), row(GROUP_MEMBERSHIP, "data1" to 8L))

        val merged = merge(proton, phone, choice)

        assertEquals(listOf(EVERYONE), rows(merged))
        assertTrue(merged.unchanged)
    }

    @Test
    fun `a phone contact with nothing new leaves the row unchanged, as the result of a merge merged again does`() {
        val proton = listOf(
            EVERYONE,
            row(PHONE, "data1" to "+1 650 555 0101", "data2" to 2L, "data4" to "+16505550101", SYNC1 to key("TEL", 1)),
            row(NOTE, "data1" to "Proton's note", SYNC1 to key("NOTE", 2)),
        )
        val phone = listOf(
            EVERYONE_AS_IMPORTED,
            row(PHONE, "data1" to "650 555 0101", "data2" to 1L, "data4" to "+16505550101"),
            row(EMAIL, "data1" to "new@example.test", "data2" to 1L),
            row(NOTE, "data1" to "the phone's note"),
        )

        val combined = merge(proton, phone, MergeChoice.COMBINE)
        val replaced = merge(proton, phone, MergeChoice.KEEP_PHONE)

        assertFalse(combined.unchanged)
        assertFalse(replaced.unchanged)
        assertTrue(mergedRows(combined.rows, phone.map(::dataRow), null, MergeChoice.COMBINE).unchanged)
        assertTrue(mergedRows(replaced.rows, phone.map(::dataRow), null, MergeChoice.KEEP_PHONE).unchanged)
        assertTrue(merge(proton, listOf(EVERYONE_AS_IMPORTED, row(PHONE, "data1" to "+1 650 555 0101", "data2" to 7L)), MergeChoice.COMBINE).unchanged)
    }

    @Test
    fun `a merge that took the phone's photo, merged again, keeps Combine both's photo and is never unchanged for Keep the phone's`() {
        val phone = listOf(EVERYONE_AS_IMPORTED, row(PHOTO, "data15" to listOf<Byte>(5, 6)))

        val combined = merge(listOf(EVERYONE), phone, MergeChoice.COMBINE, photo = byteArrayOf(7, 7))
        val again = mergedRows(combined.rows, phone.map(::dataRow), byteArrayOf(7, 7), MergeChoice.COMBINE)
        val replaced = merge(listOf(EVERYONE), phone, MergeChoice.KEEP_PHONE, photo = byteArrayOf(7, 7))
        val replacedAgain = mergedRows(replaced.rows, phone.map(::dataRow), byteArrayOf(7, 7), MergeChoice.KEEP_PHONE)

        assertTrue(again.unchanged)
        assertTrue(again.keepPhotoRow)
        assertFalse(again.photoCopied)
        assertFalse(replacedAgain.unchanged)
        assertTrue(replacedAgain.photoCopied)
    }

    @ParameterizedTest
    @EnumSource(MergeChoice::class, names = ["KEEP_PROTON", "KEEP_BOTH"])
    fun `Keep Proton's and Keep both write no rows, so they aren't accepted`(choice: MergeChoice) {
        assertThrows<IllegalArgumentException> { merge(listOf(EVERYONE), listOf(EVERYONE_AS_IMPORTED), choice) }
    }

    // Apply's question counts the Keep the phone's pairs that lose values only Proton has.
    private fun loses(proton: List<Row>, phone: List<Row>) = losesProtonValues(proton.map(::dataRow), phone.map(::dataRow))

    @ParameterizedTest(name = "{0}")
    @MethodSource("listKindsButNumbers")
    fun `a list value of Proton's loses nothing while the phone holds it with another type, label, or spelling, and is lost without it`(
        case: String,
        proton: List<Row>,
        phone: List<Row>,
    ) {
        assertFalse(loses(proton, phone), case)
        // The phone's last row is its new value, which matches none of Proton's.
        assertTrue(loses(proton, listOf(EVERYONE_AS_IMPORTED, phone.last())), case)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneValueKinds")
    fun `a one-value kind of Proton's is lost when the phone's differs or the phone has none, not when it's alike`(case: String, proton: Row, phone: Row) {
        assertTrue(loses(listOf(EVERYONE, proton), listOf(EVERYONE_AS_IMPORTED, phone)), case)
        assertTrue(loses(listOf(EVERYONE, proton), listOf(EVERYONE_AS_IMPORTED)), case)
        assertFalse(loses(listOf(EVERYONE, proton), listOf(EVERYONE_AS_IMPORTED, proton - SYNC1)), case)
    }

    @Test
    fun `Proton's name is lost when the phone's differs`() {
        assertTrue(loses(listOf(EVERYONE), listOf(row(NAME, "data1" to "Bob Lee", "data2" to "Bob", "data3" to "Lee"))))
        assertFalse(loses(listOf(EVERYONE), listOf(EVERYONE_AS_IMPORTED)))
    }

    @Test
    fun `Proton's photo is lost only when the phone has none, since a photo replaced can't be compared`() {
        val proton = listOf(EVERYONE, row(PHOTO, "data15" to listOf<Byte>(1, 2), SYNC1 to key("PHOTO", 1)))

        assertTrue(loses(proton, listOf(EVERYONE_AS_IMPORTED)))
        assertFalse(loses(proton, listOf(EVERYONE_AS_IMPORTED, row(PHOTO, "data15" to listOf<Byte>(5, 6)))))
        assertFalse(loses(listOf(EVERYONE), listOf(EVERYONE_AS_IMPORTED, row(PHOTO, "data15" to listOf<Byte>(5, 6)))))
        // A photo row with no photo is none.
        assertFalse(loses(listOf(EVERYONE, row(PHOTO, SYNC1 to key("PHOTO", 1))), listOf(EVERYONE_AS_IMPORTED)))
    }

    @Test
    fun `nothing is lost by a value only the phone has, a row Proton keeps of a kind the field table doesn't name, or a Proton row with no value`() {
        val phone = listOf(EVERYONE_AS_IMPORTED, row(EMAIL, "data1" to "new@example.test", "data2" to 1L))

        assertFalse(loses(listOf(EVERYONE), phone))
        assertFalse(loses(listOf(EVERYONE, row(IM, "data1" to "ann-im"), row(GROUP_MEMBERSHIP, "data1" to 4L)), phone))
        assertFalse(loses(listOf(EVERYONE, row(NOTE, SYNC1 to key("NOTE", 1)), row(EMAIL, "data1" to " ", SYNC1 to key("EMAIL", 1))), phone))
    }

    @Test
    fun `a number Proton holds is kept only by one that matches by the rules and is dialed alike, its type aside`() {
        // As Remove originals compares them: the normalized form leaves out an extension.
        fun proton(number: String, normalized: String? = null) =
            listOf(EVERYONE, row(PHONE, *listOfNotNull("data1" to number, "data2" to 2L, normalized?.let { "data4" to it }, SYNC1 to key("TEL", 1)).toTypedArray()))
        fun phone(number: String, normalized: String? = null) =
            listOf(EVERYONE_AS_IMPORTED, row(PHONE, *listOfNotNull("data1" to number, "data2" to 1L, normalized?.let { "data4" to it }).toTypedArray()))

        assertFalse(loses(proton("+1 650 555 0101", "+16505550101"), phone("+1 (650) 555-0101", "+16505550101")))
        assertFalse(loses(proton("555-0199"), phone("555.0199")))
        assertTrue(loses(proton("+1 650 555 0101 ext. 12", "+16505550101"), phone("+1 650 555 0101", "+16505550101")), "an extension")
        assertTrue(loses(proton("+1 650 555 0101", "+16505550101"), phone("650 555 0101", "+16505550101")), "a + and country code")
        assertTrue(loses(proton("+1 555 0199"), phone("1 555 0199")), "a +")
        assertTrue(loses(proton("+1 650 555 0101", "+16505550101"), phone("650 555 0101")), "no match by the rules")
        assertTrue(loses(proton("+1 650 555 0101,1", "+16505550101"), phone("+1 650 555 0101", "+16505550101")), "a pause")
        // A number with a custom label the phone's has as another type isn't lost.
        val boat = listOf(EVERYONE, row(PHONE, "data1" to "1 555 0199", "data2" to 0L, "data3" to "Boat", SYNC1 to key("TEL", 1)))
        assertFalse(loses(boat, phone("1-555-0199")))
    }

    private operator fun Row.plus(value: Pair<String, Any>) = copy(values = values + value)

    private operator fun Row.minus(column: String) = copy(values = values - column)

    private companion object {
        const val GROUP_MEMBERSHIP = "vnd.android.cursor.item/group_membership"
        const val IM = "vnd.android.cursor.item/im"

        fun key(property: String, n: Int) = "$property.%016x".format(n)

        /** A name on both sides, as the Proton row holds it (keyed, with its full-name style) and a copy writes it. */
        val EVERYONE = row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee", "data10" to 1L, SYNC1 to key("FN", 99))
        val EVERYONE_AS_IMPORTED = row(NAME, "data1" to "Ann Lee", "data2" to "Ann", "data3" to "Lee")

        fun case(name: String, vararg rest: Any): Arguments = Arguments.of(name, *rest)

        /** [listKinds] but numbers, which a loss compares as dialed too. */
        @JvmStatic
        fun listKindsButNumbers(): List<Arguments> = listKinds().filterNot { arguments -> (arguments.get()[1] as List<*>).any { (it as Row).mimeType == PHONE } }

        /**
         * Each list kind: Proton's rows, the phone's (a value equal by the matching rules or as Android shows it, with
         * another type or label, and a new one), and the new one as the copy would write it.
         */
        @JvmStatic
        fun listKinds(): List<Arguments> = listOf(
            case(
                "e-mail addresses, trimmed and ignoring case",
                listOf(EVERYONE, row(EMAIL, "data1" to "Ann@Example.test", "data2" to 1L, SYNC1 to key("EMAIL", 1))),
                listOf(EVERYONE_AS_IMPORTED, row(EMAIL, "data1" to " ann@example.test", "data2" to 2L), row(EMAIL, "data1" to "an@example.test", "data2" to 4L)),
                listOf(row(EMAIL, "data1" to "an@example.test", "data2" to 3L)),
            ),
            case(
                "phone numbers, by the provider's normalized form, else their digits",
                listOf(
                    EVERYONE,
                    row(PHONE, "data1" to "+1 650 555 0101", "data2" to 2L, "data4" to "+16505550101", SYNC1 to key("TEL", 1)),
                    row(PHONE, "data1" to "+1 (555) 0199", "data2" to 0L, "data3" to "Boat", SYNC1 to key("TEL", 2)),
                ),
                listOf(
                    EVERYONE_AS_IMPORTED,
                    row(PHONE, "data1" to "650 555 0101", "data2" to 1L, "data4" to "+16505550101"),
                    row(PHONE, "data1" to "1-555-0199", "data2" to 2L),
                    row(PHONE, "data1" to "+44 650 555 0101", "data2" to 2L, "data4" to "+446505550101"),
                ),
                listOf(row(PHONE, "data1" to "+44 650 555 0101", "data2" to 2L)),
            ),
            case(
                "addresses, by their parts",
                listOf(EVERYONE, row(POSTAL, "data1" to "1 Main St\nTown", "data4" to "1 Main St", "data7" to "Town", "data2" to 1L, SYNC1 to key("ADR", 1))),
                listOf(
                    EVERYONE_AS_IMPORTED,
                    row(POSTAL, "data1" to "1 Main St\nTown", "data4" to "1 Main St", "data7" to "Town", "data2" to 2L),
                    row(POSTAL, "data4" to "1 Main St", "data7" to "Other Town", "data2" to 1L),
                ),
                listOf(row(POSTAL, "data4" to "1 Main St", "data7" to "Other Town", "data2" to 1L)),
            ),
            case(
                "dates other than a birthday or an anniversary, by the date",
                listOf(EVERYONE, row(EVENT, "data1" to "--07-02", "data2" to 0L, "data3" to "Met", SYNC1 to key("X-ABDATE", 1))),
                listOf(EVERYONE_AS_IMPORTED, row(EVENT, "data1" to "--07-02", "data2" to 2L), row(EVENT, "data1" to "2001-07-02", "data2" to 0L, "data3" to "Moved")),
                listOf(row(EVENT, "data1" to "2001-07-02", "data2" to 0L, "data3" to "Moved")),
            ),
            case(
                "websites",
                listOf(EVERYONE, row(WEBSITE, "data1" to "https://example.test/ann", "data2" to 7L, SYNC1 to key("URL", 1))),
                listOf(EVERYONE_AS_IMPORTED, row(WEBSITE, "data1" to "https://example.test/ann", "data2" to 1L), row(WEBSITE, "data1" to "https://example.test/Ann", "data2" to 1L)),
                listOf(row(WEBSITE, "data1" to "https://example.test/Ann", "data2" to 7L)),
            ),
            case(
                "relations",
                listOf(EVERYONE, row(RELATION, "data1" to "Bob", "data2" to 14L, SYNC1 to key("RELATED", 1))),
                listOf(EVERYONE_AS_IMPORTED, row(RELATION, "data1" to "Bob", "data2" to 1L), row(RELATION, "data1" to "Sam", "data2" to 0L, "data3" to "Coach")),
                listOf(row(RELATION, "data1" to "Sam", "data2" to 0L, "data3" to "Coach")),
            ),
            case(
                "SIP addresses",
                listOf(EVERYONE, row(SIP, "data1" to "ann@sip.example.test", "data2" to 3L, SYNC1 to key("X-SIP", 1))),
                listOf(EVERYONE_AS_IMPORTED, row(SIP, "data1" to "ann@sip.example.test", "data2" to 1L), row(SIP, "data1" to "bob@sip.example.test", "data2" to 1L)),
                listOf(row(SIP, "data1" to "bob@sip.example.test", "data2" to 3L)),
            ),
        )

        /** Each one-value kind: Proton's row (keyed), another phone value, and that value as a copy writes it. */
        @JvmStatic
        fun oneValueKinds(): List<Arguments> = listOf(
            case(
                "organization",
                row(ORGANIZATION, "data1" to "Acme", "data4" to "Engineer", "data2" to 1L, SYNC1 to key("ORG", 1)),
                row(ORGANIZATION, "data1" to "Beta", "data2" to 2L, "data3" to "Label"),
                row(ORGANIZATION, "data1" to "Beta", "data2" to 1L),
            ),
            case(
                "birthday",
                row(EVENT, "data1" to "1990-05-07", "data2" to 3L, SYNC1 to key("BDAY", 1)),
                row(EVENT, "data1" to "1991-05-07", "data2" to 3L),
                row(EVENT, "data1" to "1991-05-07", "data2" to 3L),
            ),
            case(
                "anniversary",
                row(EVENT, "data1" to "--06-01", "data2" to 1L, SYNC1 to key("ANNIVERSARY", 1)),
                row(EVENT, "data1" to "--06-02", "data2" to 1L),
                row(EVENT, "data1" to "--06-02", "data2" to 1L),
            ),
            case("note", row(NOTE, "data1" to "Proton's note", SYNC1 to key("NOTE", 1)), row(NOTE, "data1" to "the phone's note", "data2" to "aside"), row(NOTE, "data1" to "the phone's note")),
            case(
                "nickname",
                row(NICKNAME, "data1" to "Annie", "data2" to 1L, SYNC1 to key("NICKNAME", 1)),
                row(NICKNAME, "data1" to "Nan", "data2" to 2L),
                row(NICKNAME, "data1" to "Nan", "data2" to 1L),
            ),
        )
    }
}
