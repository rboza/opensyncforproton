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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

// An import's copy holds only what sync uploads (docs/sync.md § Import from this phone). Rows are given as readRows
// gives them; every value is made up for the test.
class ImportRowsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    fun `each kind of the field table is copied as the column mapper reads it and writes it again, without its key`(case: String, read: List<Row>, copied: List<Row>) {
        assertEquals(copied, importRows(read.map(::dataRow), photo = null).map(::plain), case)
    }

    @Test
    fun `a kind the field table doesn't name and a second value of a one-value kind are gone`() {
        val read = listOf(
            row(GROUP_MEMBERSHIP, "data1" to 7L),
            row(NAME, "data1" to "Ann Lee"),
            row(IM, "data1" to "ann", "data2" to 3L, "data5" to 1L),
            row(NOTE, "data1" to "first note"),
            row(NOTE, "data1" to "second note"),
            row(NICKNAME, "data1" to "Annie", "data2" to 1L),
            row(NICKNAME, "data1" to "Nan", "data2" to 1L),
            row(UNKNOWN, "data1" to "test-only"),
        )

        assertEquals(
            listOf(row(NAME, "data1" to "Ann Lee"), row(NOTE, "data1" to "first note"), row(NICKNAME, "data1" to "Annie", "data2" to 1L)),
            importRows(read.map(::dataRow), photo = null).map(::plain),
        )
    }

    @Test
    fun `the photo given is in the photo row`() {
        val read = listOf(row(NAME, "data1" to "Ann Lee"), row(PHOTO, "data15" to listOf<Byte>(1, 2), SYNC1 to "PHOTO.0123456789abcdef"))

        assertEquals(
            listOf(row(NAME, "data1" to "Ann Lee"), row(PHOTO, "data15" to listOf<Byte>(9, 8, 7))),
            importRows(read.map(::dataRow), photo = byteArrayOf(9, 8, 7)).map(::plain),
        )
    }

    @Test
    fun `no photo keeps the row's thumbnail, and none without one`() {
        val thumbnail = listOf(row(NAME, "data1" to "Ann Lee"), row(PHOTO, "data15" to listOf<Byte>(1, 2)))
        val empty = listOf(row(NAME, "data1" to "Ann Lee"), row(PHOTO, SYNC1 to "PHOTO.0123456789abcdef"))

        assertEquals(thumbnail, importRows(thumbnail.map(::dataRow), photo = null).map(::plain))
        assertEquals(listOf(row(NAME, "data1" to "Ann Lee")), importRows(empty.map(::dataRow), photo = null).map(::plain))
    }

    @Test
    fun `no rows give none`() {
        assertEquals(emptyList<DataRow>(), importRows(emptyList(), photo = null))
        assertEquals(emptyList<DataRow>(), importRows(emptyList(), photo = byteArrayOf(9)))
        assertEquals(emptyList<DataRow>(), importRows(listOf(dataRow(row(IM, "data1" to "ann"))), photo = byteArrayOf(9)))
    }

    private companion object {
        const val GROUP_MEMBERSHIP = "vnd.android.cursor.item/group_membership"
        const val IM = "vnd.android.cursor.item/im"
        const val UNKNOWN = "vnd.test-only.cursor.item/unknown"
        const val KEY = "test-only.0123456789abcdef"

        fun case(name: String, read: List<Row>, copied: List<Row>): Arguments = Arguments.of(name, read, copied)

        /**
         * Each kind with every column readRows reads that it uses, the provider-filled ones and the ones the field
         * table doesn't read included, and what the copy holds: the column mapper's own types, in the order it writes
         * them.
         */
        @JvmStatic
        fun kinds(): List<Arguments> = listOf(
            case(
                "name, its full-name style dropped",
                listOf(
                    row(
                        NAME, "data1" to "Ann B Lee", "data2" to "Ann", "data3" to "Lee", "data4" to "Dr", "data5" to "B", "data6" to "Jr",
                        "data7" to "an", "data8" to "bee", "data9" to "lee", "data10" to 1L,
                    ),
                ),
                listOf(
                    row(
                        NAME, "data1" to "Ann B Lee", "data2" to "Ann", "data3" to "Lee", "data4" to "Dr", "data5" to "B", "data6" to "Jr",
                        "data7" to "an", "data8" to "bee", "data9" to "lee",
                    ),
                ),
            ),
            case(
                "e-mails: MOBILE and a custom type without a label become OTHER, a label off a custom type is dropped",
                listOf(
                    row(EMAIL, "data1" to "home@example.test", "data2" to 1L, "data3" to "Ignored", SYNC1 to KEY),
                    row(EMAIL, "data1" to "work@example.test", "data2" to 2L),
                    row(EMAIL, "data1" to "other@example.test", "data2" to 3L),
                    row(EMAIL, "data1" to "mobile@example.test", "data2" to 4L),
                    row(EMAIL, "data1" to "school@example.test", "data2" to 0L, "data3" to "School"),
                    row(EMAIL, "data1" to "bare@example.test", "data2" to 0L),
                ),
                listOf(
                    row(EMAIL, "data1" to "home@example.test", "data2" to 1L),
                    row(EMAIL, "data1" to "work@example.test", "data2" to 2L),
                    row(EMAIL, "data1" to "other@example.test", "data2" to 3L),
                    row(EMAIL, "data1" to "mobile@example.test", "data2" to 3L),
                    row(EMAIL, "data1" to "school@example.test", "data2" to 0L, "data3" to "School"),
                    row(EMAIL, "data1" to "bare@example.test", "data2" to 3L),
                ),
            ),
            case(
                "phones: the types the upload loses become OTHER, the normalized number dropped",
                (0L..20L).map { type -> row(PHONE, "data1" to "+1 555 01${"%02d".format(type)}", "data2" to type, "data4" to "+1555010$type") } +
                    row(PHONE, "data1" to "+1 555 0199", "data2" to 0L, "data3" to "Boat", SYNC1 to KEY),
                (0L..20L).map { type ->
                    row(PHONE, "data1" to "+1 555 01${"%02d".format(type)}", "data2" to if (type in setOf(1L, 2L, 3L, 4L, 5L, 6L, 12L)) type else 7L)
                } + row(PHONE, "data1" to "+1 555 0199", "data2" to 0L, "data3" to "Boat"),
            ),
            case(
                "address: its parts, the formatted line and neighbourhood dropped, a custom type OTHER",
                listOf(
                    row(
                        POSTAL, "data1" to "1 Main St\nTown", "data2" to 0L, "data3" to "Cabin", "data4" to "1 Main St", "data5" to "PO 9",
                        "data6" to "Old Town", "data7" to "Town", "data8" to "Region", "data9" to "12345", "data10" to "Country", SYNC1 to KEY,
                    ),
                    row(POSTAL, "data4" to "2 Side St", "data2" to 1L),
                    row(POSTAL, "data7" to "City", "data2" to 2L),
                ),
                listOf(
                    row(POSTAL, "data4" to "1 Main St", "data5" to "PO 9", "data7" to "Town", "data8" to "Region", "data9" to "12345", "data10" to "Country", "data2" to 3L),
                    row(POSTAL, "data4" to "2 Side St", "data2" to 1L),
                    row(POSTAL, "data7" to "City", "data2" to 2L),
                ),
            ),
            case(
                "organization: WORK, its symbol, phonetic name, and office dropped",
                listOf(
                    row(
                        ORGANIZATION, "data1" to "Acme", "data2" to 2L, "data3" to "Label", "data4" to "Engineer", "data5" to "R&D;Lab", "data6" to "Lead",
                        "data7" to "AC", "data8" to "ak-me", "data9" to "Here", "data10" to 0L, SYNC1 to KEY,
                    ),
                ),
                listOf(row(ORGANIZATION, "data1" to "Acme", "data5" to "R&D;Lab", "data4" to "Engineer", "data6" to "Lead", "data2" to 1L)),
            ),
            case("photo, its thumbnail", listOf(row(PHOTO, "data15" to listOf<Byte>(1, 2, 3), SYNC1 to KEY)), listOf(row(PHOTO, "data15" to listOf<Byte>(1, 2, 3)))),
            case(
                "events: birthday, anniversary, then the others in order; one without a type OTHER",
                listOf(
                    row(EVENT, "data1" to "0005-01-02", "data2" to 0L, "data3" to "Met", SYNC1 to KEY),
                    row(EVENT, "data1" to "--12-31", "data2" to 1L),
                    row(EVENT, "data1" to "--03-04", "data2" to 2L, "data3" to "Ignored"),
                    row(EVENT, "data1" to "1990-05-07", "data2" to 3L),
                    row(EVENT, "data1" to "--06-07"),
                ),
                listOf(
                    row(EVENT, "data1" to "1990-05-07", "data2" to 3L),
                    row(EVENT, "data1" to "--12-31", "data2" to 1L),
                    row(EVENT, "data1" to "0005-01-02", "data2" to 0L, "data3" to "Met"),
                    row(EVENT, "data1" to "--03-04", "data2" to 2L),
                    row(EVENT, "data1" to "--06-07", "data2" to 2L),
                ),
            ),
            case("note, its data2 dropped", listOf(row(NOTE, "data1" to "line 1\nline 2", "data2" to "aside", SYNC1 to KEY)), listOf(row(NOTE, "data1" to "line 1\nline 2"))),
            case(
                "website: OTHER, its label dropped",
                listOf(row(WEBSITE, "data1" to "https://example.test", "data2" to 0L, "data3" to "Blog", SYNC1 to KEY)),
                listOf(row(WEBSITE, "data1" to "https://example.test", "data2" to 7L)),
            ),
            case("nickname: DEFAULT", listOf(row(NICKNAME, "data1" to "Annie", "data2" to 2L, SYNC1 to KEY)), listOf(row(NICKNAME, "data1" to "Annie", "data2" to 1L))),
            case(
                "relations: their types, a custom one's label",
                listOf(
                    row(RELATION, "data1" to "Bob", "data2" to 14L, "data3" to "Ignored", SYNC1 to KEY),
                    row(RELATION, "data1" to "Sam", "data2" to 0L, "data3" to "Coach"),
                ),
                listOf(row(RELATION, "data1" to "Bob", "data2" to 14L), row(RELATION, "data1" to "Sam", "data2" to 0L, "data3" to "Coach")),
            ),
            case(
                "SIP address: OTHER, its label dropped",
                listOf(row(SIP, "data1" to "ann@sip.example.test", "data2" to 0L, "data3" to "Desk", SYNC1 to KEY)),
                listOf(row(SIP, "data1" to "ann@sip.example.test", "data2" to 3L)),
            ),
        )
    }
}
