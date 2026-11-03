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
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Row
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.SIP
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.SYNC1
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.WEBSITE
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.dataRow
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.plain
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.row
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

// What a kept copy holds (docs/sync.md § Keep a copy). Rows are given as readRows gives them; every value is made up
// for the test.
class LocalCopyTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    fun `each kind of the field table is copied as read, without its key`(case: String, read: List<Row>) {
        val copied = localCopy(read.map(::dataRow), photo = null)

        assertEquals(read.map { Row(it.mimeType, it.values - SYNC1) }, copied.map(::plain), case)
    }

    @Test
    fun `rows keep their order, a second row of a one-value kind included`() {
        val read = listOf(
            row(NOTE, "data1" to "first note"),
            row(EMAIL, "data1" to "a@example.test", "data2" to 1L),
            row(NOTE, "data1" to "second note", SYNC1 to "NOTE.0123456789abcdef"),
            row(NICKNAME, "data1" to "Annie", "data2" to 1L),
            row(NAME, "data1" to "Ann Lee"),
            row(NICKNAME, "data1" to "Nan", "data2" to 1L),
        )

        assertEquals(read.map { Row(it.mimeType, it.values - SYNC1) }, localCopy(read.map(::dataRow), photo = null).map(::plain))
    }

    @Test
    fun `rows of other kinds are dropped`() {
        val read = listOf(
            row(GROUP_MEMBERSHIP, "data1" to 7L),
            row(NAME, "data1" to "Ann Lee"),
            row(IM, "data1" to "ann", "data2" to 3L, "data5" to 1L),
            row(UNKNOWN, "data1" to "test-only"),
            row(NOTE, "data1" to "a note"),
        )

        assertEquals(listOf(row(NAME, "data1" to "Ann Lee"), row(NOTE, "data1" to "a note")), localCopy(read.map(::dataRow), photo = null).map(::plain))
    }

    @Test
    fun `a given photo replaces the first photo row's bytes, and a second photo row stays as read`() {
        val read = listOf(
            row(NAME, "data1" to "Ann Lee"),
            row(PHOTO, "data15" to listOf<Byte>(1, 2), SYNC1 to "PHOTO.0123456789abcdef"),
            row(PHOTO, "data15" to listOf<Byte>(3, 4), SYNC1 to "PHOTO.fedcba9876543210"),
        )

        val copied = localCopy(read.map(::dataRow), photo = byteArrayOf(9, 8, 7))

        assertEquals(
            listOf(row(NAME, "data1" to "Ann Lee"), row(PHOTO, "data15" to listOf<Byte>(9, 8, 7)), row(PHOTO, "data15" to listOf<Byte>(3, 4))),
            copied.map(::plain),
        )
    }

    @Test
    fun `a photo row without a thumbnail gets the given photo`() {
        val copied = localCopy(listOf(dataRow(row(PHOTO, SYNC1 to "PHOTO.0123456789abcdef"))), photo = byteArrayOf(9))

        assertEquals(listOf(row(PHOTO, "data15" to listOf<Byte>(9))), copied.map(::plain))
    }

    @Test
    fun `a photo row left with no picture is dropped`() {
        // No display photo and no thumbnail: such a row alone would make an empty contact.
        val read = listOf(row(NAME, "data1" to "Ann Lee"), row(PHOTO, SYNC1 to "PHOTO.0123456789abcdef"))

        assertEquals(listOf(row(NAME, "data1" to "Ann Lee")), localCopy(read.map(::dataRow), photo = null).map(::plain))
        assertEquals(emptyList<DataRow>(), localCopy(listOf(dataRow(row(PHOTO))), photo = null))
    }

    @Test
    fun `no photo keeps the thumbnail the row holds`() {
        val read = listOf(row(PHOTO, "data15" to listOf<Byte>(1, 2), SYNC1 to "PHOTO.0123456789abcdef"))

        assertEquals(listOf(row(PHOTO, "data15" to listOf<Byte>(1, 2))), localCopy(read.map(::dataRow), photo = null).map(::plain))
    }

    @Test
    fun `a photo is never added to a contact without a photo row`() {
        val read = listOf(row(NAME, "data1" to "Ann Lee"), row(IM, "data1" to "ann"))

        assertEquals(listOf(row(NAME, "data1" to "Ann Lee")), localCopy(read.map(::dataRow), photo = byteArrayOf(9)).map(::plain))
    }

    @Test
    fun `no rows give none`() {
        assertEquals(emptyList<DataRow>(), localCopy(emptyList(), photo = byteArrayOf(9)))
        assertEquals(emptyList<DataRow>(), localCopy(listOf(dataRow(row(GROUP_MEMBERSHIP, "data1" to 7L))), photo = null))
    }

    private companion object {
        const val GROUP_MEMBERSHIP = "vnd.android.cursor.item/group_membership"
        const val IM = "vnd.android.cursor.item/im"
        const val UNKNOWN = "vnd.test-only.cursor.item/unknown"
        const val KEY = "test-only.0123456789abcdef"

        fun case(name: String, vararg rows: Row): Arguments = Arguments.of(name, rows.toList())

        /** Every column readRows reads that the kind uses, with a key in `Data.SYNC1`. */
        @JvmStatic
        fun kinds(): List<Arguments> = listOf(
            case(
                "name",
                row(
                    NAME, "data1" to "Ann B Lee", "data2" to "Ann", "data3" to "Lee", "data4" to "Dr", "data5" to "B", "data6" to "Jr",
                    "data7" to "an", "data8" to "bee", "data9" to "lee", SYNC1 to KEY,
                ),
            ),
            case(
                "e-mails, MOBILE and custom included",
                row(EMAIL, "data1" to "a@example.test", "data2" to 4L, SYNC1 to KEY),
                row(EMAIL, "data1" to "b@example.test", "data2" to 0L, "data3" to "School", SYNC1 to KEY),
            ),
            // Every phone type, the ones the upload loses (8–11 and 13–20) included.
            case(
                "phones, every type",
                *((0L..20L).map { type -> row(PHONE, "data1" to "+1 555 01${"%02d".format(type)}", "data2" to type, SYNC1 to KEY) } +
                    row(PHONE, "data1" to "+1 555 0199", "data2" to 0L, "data3" to "Boat", SYNC1 to KEY)).toTypedArray(),
            ),
            case(
                "address, its formatted address and neighbourhood included",
                row(
                    POSTAL, "data1" to "1 Main St\nTown", "data2" to 0L, "data3" to "Cabin", "data4" to "1 Main St", "data5" to "PO 9",
                    "data6" to "Old Town", "data7" to "Town", "data8" to "Region", "data9" to "12345", "data10" to "Country", SYNC1 to KEY,
                ),
            ),
            case(
                "organization",
                row(
                    ORGANIZATION, "data1" to "Acme", "data2" to 1L, "data4" to "Engineer", "data5" to "R&D;Lab", "data6" to "Lead",
                    "data7" to "AC", "data8" to "ak-me", "data9" to "Here", SYNC1 to KEY,
                ),
            ),
            case("photo", row(PHOTO, "data15" to listOf<Byte>(1, 2, 3), SYNC1 to KEY)),
            case(
                "events",
                row(EVENT, "data1" to "1990-05-07", "data2" to 3L, SYNC1 to KEY),
                row(EVENT, "data1" to "--12-31", "data2" to 1L, SYNC1 to KEY),
                row(EVENT, "data1" to "0005-01-02", "data2" to 0L, "data3" to "Met", SYNC1 to KEY),
                row(EVENT, "data1" to "--03-04", "data2" to 2L, SYNC1 to KEY),
            ),
            case("note", row(NOTE, "data1" to "line 1\nline 2", SYNC1 to KEY)),
            case("website", row(WEBSITE, "data1" to "https://example.test", "data2" to 1L, "data3" to "Blog", SYNC1 to KEY)),
            case("nickname", row(NICKNAME, "data1" to "Annie", "data2" to 2L, SYNC1 to KEY)),
            case(
                "relations",
                row(RELATION, "data1" to "Bob", "data2" to 14L, SYNC1 to KEY),
                row(RELATION, "data1" to "Sam", "data2" to 0L, "data3" to "Coach", SYNC1 to KEY),
            ),
            case("SIP address", row(SIP, "data1" to "ann@sip.example.test", "data2" to 0L, "data3" to "Desk", SYNC1 to KEY)),
        )
    }
}
