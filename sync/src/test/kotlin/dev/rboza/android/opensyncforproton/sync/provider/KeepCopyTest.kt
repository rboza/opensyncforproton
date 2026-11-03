package dev.rboza.android.opensyncforproton.sync.provider

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.NAME
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.NOTE
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.PHOTO
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.SYNC1
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.dataRow
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.plain
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.row
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

// Keep a copy: docs/sync.md § Keep a copy. Every value is made up for the test.
class KeepCopyTest {
    private val account = PhoneAccount(AccountId("test-only-user-id"), "test-only@example.test")
    private val phone = FakeContacts()

    @Test
    fun `each live row is copied once with its star, and deleted rows are not`() {
        phone.add(contact(1, starred = true), rows(1))
        phone.add(contact(2, deleted = true), rows(2))
        phone.add(contact(3, deleted = true, starred = true), rows(3))
        phone.add(contact(4), rows(4))

        val result = keepCopy(phone, account)

        assertEquals(CopyResult(copied = 2, notCopied = 0), result)
        assertEquals(listOf(listOf(name(1)) to true, listOf(name(4)) to false), phone.inserted.map { (rows, starred) -> rows.map(::plain) to starred })
        assertEquals(listOf(account), phone.queried)
    }

    @Test
    fun `insertLocal gets the copy's rows, the display photo in`() {
        val read = listOf(
            row(NAME, "data1" to "Ann Lee", SYNC1 to "FN.0123456789abcdef"),
            row(IM, "data1" to "ann"),
            row(PHOTO, "data15" to listOf<Byte>(1, 2), SYNC1 to "PHOTO.0123456789abcdef"),
            row(NOTE, "data1" to "a note", SYNC1 to "NOTE.0123456789abcdef"),
        ).map(::dataRow)
        phone.add(contact(1), read, photo = { byteArrayOf(7, 7, 7) })

        keepCopy(phone, account)

        assertEquals(
            listOf(row(NAME, "data1" to "Ann Lee"), row(PHOTO, "data15" to listOf<Byte>(7, 7, 7)), row(NOTE, "data1" to "a note")),
            phone.inserted.single().first.map(::plain),
        )
    }

    @Test
    fun `a photo that can't be read gives the copy the row's thumbnail`() {
        val read = listOf(row(NAME, "data1" to "Ann Lee"), row(PHOTO, "data15" to listOf<Byte>(1, 2), SYNC1 to "PHOTO.0123456789abcdef")).map(::dataRow)
        phone.add(contact(1), read, photo = { throw IOException("test-only") })

        val result = keepCopy(phone, account)

        assertEquals(CopyResult(1, 0), result)
        assertEquals(listOf(row(NAME, "data1" to "Ann Lee"), row(PHOTO, "data15" to listOf<Byte>(1, 2))), phone.inserted.single().first.map(::plain))
    }

    @Test
    fun `a contact whose copy has no rows is not written and counts as not copied`() {
        // Gone after the count (the read finds no rows), only rows of other kinds, or only a photo row with no picture.
        phone.add(contact(1), emptyList())
        phone.add(contact(2), listOf(dataRow(row(IM, "data1" to "ann"))), photo = { byteArrayOf(7) })
        phone.add(contact(3), rows(3))
        phone.add(contact(4), listOf(dataRow(row(PHOTO, SYNC1 to "PHOTO.0123456789abcdef"))))

        val result = keepCopy(phone, account)

        assertEquals(CopyResult(copied = 1, notCopied = 3), result)
        assertEquals(listOf(listOf(name(3))), phone.inserted.map { (rows, _) -> rows.map(::plain) })
    }

    @Test
    fun `only a written copy counts as copied, and the rest go on after one that isn't`() {
        (1L..5L).forEach { phone.add(contact(it), rows(it)) }
        phone.results += listOf(
            ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED),
            ContactWriteResult.Written(101),
            ContactWriteResult.Failed(FailureReason.REJECTED_VALUE),
            ContactWriteResult.Failed(FailureReason.TOO_LARGE),
            ContactWriteResult.Written(105),
        )

        val result = keepCopy(phone, account)

        assertEquals(CopyResult(copied = 2, notCopied = 3), result)
        assertEquals((1L..5L).map { listOf(name(it)) }, phone.inserted.map { (rows, _) -> rows.map(::plain) })
    }

    @Test
    fun `a stale result counts as not copied`() {
        phone.add(contact(1), rows(1))
        phone.results += ContactWriteResult.Stale

        assertEquals(CopyResult(copied = 0, notCopied = 1), keepCopy(phone, account))
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "readRows", "photo", "insertLocal"])
    fun `a missing permission reaches the caller`(where: String) {
        phone.add(contact(1), rows(1))
        val thrown = SecurityException()
        phone.failing = where to thrown

        assertSame(thrown, assertThrows(SecurityException::class.java) { keepCopy(phone, account) })
    }

    @ParameterizedTest
    @ValueSource(strings = ["query", "readRows", "photo", "insertLocal"])
    fun `a provider that stops answering reaches the caller`(where: String) {
        phone.add(contact(1), rows(1))
        val thrown = ProviderUnavailableException()
        phone.failing = where to thrown

        assertSame(thrown, assertThrows(ProviderUnavailableException::class.java) { keepCopy(phone, account) })
    }

    @Test
    fun `an account with no live rows copies nothing`() {
        assertEquals(CopyResult(0, 0), keepCopy(phone, account))

        phone.add(contact(1, deleted = true), rows(1))
        assertEquals(CopyResult(0, 0), keepCopy(phone, account))

        assertEquals(emptyList<Pair<List<DataRow>, Boolean>>(), phone.inserted)
    }

    private fun contact(id: Long, deleted: Boolean = false, starred: Boolean = false) =
        DeviceContact(id, sourceId = "proton-id-$id", dirty = false, deleted = deleted, version = 1, displayName = "Contact $id", starred = starred)

    private fun name(id: Long) = row(NAME, "data1" to "Contact $id")

    private fun rows(id: Long) = listOf(dataRow(row(NAME, "data1" to "Contact $id", SYNC1 to "FN.0123456789abcdef")))

    /**
     * The phone's rows of [account] alone: reads give what was added, and [insertLocal] records each copy and answers
     * from [results] (then `Written`). Any write to the app's account fails the test.
     */
    private inner class FakeContacts : DeviceContacts {
        private val contacts = mutableListOf<DeviceContact>()
        private val rows = mutableMapOf<Long, List<DataRow>>()
        private val photos = mutableMapOf<Long, () -> ByteArray?>()
        val queried = mutableListOf<PhoneAccount>()
        val inserted = mutableListOf<Pair<List<DataRow>, Boolean>>()
        val results = ArrayDeque<ContactWriteResult>()

        /** A call that throws the exception given. */
        var failing: Pair<String, Exception>? = null

        fun add(contact: DeviceContact, rows: List<DataRow>, photo: () -> ByteArray? = { null }) {
            contacts += contact
            this.rows[contact.rawContactId] = rows
            photos[contact.rawContactId] = photo
        }

        private fun fail(call: String) {
            failing?.let { (where, thrown) -> if (where == call) throw thrown }
        }

        override fun query(account: PhoneAccount): List<DeviceContact> {
            fail("query")
            queried += account
            return contacts.toList()
        }

        override fun readRows(rawContactId: Long): List<DataRow> {
            fail("readRows")
            check(contacts.single { it.rawContactId == rawContactId }.deleted.not()) { "a deleted row is read" }
            return checkNotNull(rows[rawContactId])
        }

        override fun photo(rawContactId: Long): ByteArray? {
            fail("photo")
            return checkNotNull(photos[rawContactId])()
        }

        override fun insertLocal(rows: List<DataRow>, starred: Boolean): ContactWriteResult {
            fail("insertLocal")
            inserted += rows to starred
            return results.removeFirstOrNull() ?: ContactWriteResult.Written(100L + inserted.size)
        }

        override fun queryLocal(): List<DeviceContact> = error("Keep a copy reads the app's account")
        override fun insertImport(account: PhoneAccount, original: ExistingRow, rows: List<DataRow>, starred: Boolean): ContactWriteResult =
            error("Keep a copy writes to the app's account")
        override fun rewrite(account: PhoneAccount, protonId: String, existing: ExistingRow, original: ExistingRow, rows: List<DataRow>, keepPhotoRow: Boolean): ContactWriteResult = error("Keep a copy writes to the app's account")
        override fun removeOriginal(account: PhoneAccount, original: ExistingRow, protonId: String, copy: ExistingRow, copyStarred: Boolean): ContactWriteResult = error("Keep a copy writes to the app's account")
        override fun apply(account: PhoneAccount, writes: List<ContactWrite>) = error("Keep a copy writes to the app's account")
        override fun clearDirty(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Keep a copy writes to the app's account")
        override fun bind(account: PhoneAccount, protonId: String, rawContactId: Long) = error("Keep a copy writes to the app's account")
        override fun removeTombstone(account: PhoneAccount, protonId: String, rawContactId: Long) = error("Keep a copy writes to the app's account")
        override fun removeRow(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Keep a copy writes to the app's account")
        override fun detach(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Keep a copy writes to the app's account")
        override fun deleteAccountRows(accountName: String) = error("Keep a copy writes to the app's account")
        override fun maxPhotoDimension() = error("not used by Keep a copy")
        override fun photoHash(rawContactId: Long) = error("not used by Keep a copy")
        override fun thumbnailHash(rawContactId: Long) = error("not used by Keep a copy")
        override fun untouched(rawContactId: Long) = error("not used by Keep a copy")
        override fun accountNamesWithRows() = error("not used by Keep a copy")
    }

    private companion object {
        const val IM = "vnd.android.cursor.item/im"
    }
}
