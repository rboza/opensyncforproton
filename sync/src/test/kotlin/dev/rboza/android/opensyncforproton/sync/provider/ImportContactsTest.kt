package dev.rboza.android.opensyncforproton.sync.provider

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordDao
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordEntity
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.NAME
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.NOTE
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.PHOTO
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.SYNC1
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.dataRow
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.plain
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.row
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Row
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

// Importing local contacts: docs/sync.md § Import from this phone, Import. Every value is made up for the test.
class ImportContactsTest {
    private val account = PhoneAccount(AccountId("test-only-user-id"), "test-only@example.test")

    /** Every call to the fake provider's writes and to the records, in the order made. */
    private val calls = mutableListOf<String>()
    private val phone = FakeContacts()
    private val records = FakeRecords()

    @Test
    fun `each contact in order, its record, then its copy, then the record given the copy`() = runTest {
        val first = original(1, version = 4, starred = true)
        val second = original(2, version = 9)

        val result = importContacts(phone, records, account, listOf(first, second))

        assertEquals(ActionResult(done = 2, notDone = 0), result)
        assertEquals(
            listOf(
                "save ${record(1, version = 4)}", "insertImport 1 at 4", "save ${record(1, version = 4, copy = 101)}",
                "save ${record(2, version = 9)}", "insertImport 2 at 9", "save ${record(2, version = 9, copy = 102)}",
            ),
            calls,
        )
        assertEquals(listOf(record(1, version = 4, copy = 101), record(2, version = 9, copy = 102)), records.records(account.id.value))
    }

    @Test
    fun `insertImport gets the copy's rows, the star, and the original at its VERSION, for the app's account`() = runTest {
        val read = listOf(
            row(NAME, "data1" to "Ann Lee", "data10" to 1L),
            row(IM, "data1" to "ann"),
            row(NOTE, "data1" to "a note", SYNC1 to "NOTE.0123456789abcdef"),
        )
        val starred = ReadContact(contact(1, version = 4, starred = true), read.map(::dataRow))
        val plain = ReadContact(contact(2, version = 5), read.map(::dataRow))

        importContacts(phone, records, account, listOf(starred, plain))

        val copy = listOf(row(NAME, "data1" to "Ann Lee"), row(NOTE, "data1" to "a note"))
        assertEquals(listOf(Inserted(account, ExistingRow(1, 4), copy, true), Inserted(account, ExistingRow(2, 5), copy, false)), phone.inserted)
    }

    @Test
    fun `the copy holds the display photo, and the record says the photo was copied`() = runTest {
        phone.photos[1] = { byteArrayOf(7, 7) }

        importContacts(phone, records, account, listOf(original(1, photo = true)))

        assertEquals(row(PHOTO, "data15" to listOf<Byte>(7, 7)), phone.inserted.single().rows.last())
        assertEquals(record(1, copy = 101, photoCopied = true), records.records(account.id.value).single())
    }

    @Test
    fun `a contact whose provider keeps no display photo gets its thumbnail, and the record says the photo was copied`() = runTest {
        // The photo row's thumbnail is all the provider keeps of a photo of 96 px or less.
        val result = importContacts(phone, records, account, listOf(original(1, photo = true)))

        assertEquals(ActionResult(1, 0), result)
        assertEquals(row(PHOTO, "data15" to listOf<Byte>(1, 2)), phone.inserted.single().rows.last())
        assertEquals(record(1, copy = 101, photoCopied = true), records.records(account.id.value).single())
    }

    @Test
    fun `a photo that can't be read gives the thumbnail, and the record says no photo was copied`() = runTest {
        phone.photos[1] = { throw IOException("test-only") }

        val result = importContacts(phone, records, account, listOf(original(1, photo = true)))

        assertEquals(ActionResult(1, 0), result)
        assertEquals(row(PHOTO, "data15" to listOf<Byte>(1, 2)), phone.inserted.single().rows.last())
        assertEquals(record(1, copy = 101, photoCopied = false), records.records(account.id.value).single())
    }

    @Test
    fun `a display photo the provider no longer has leaves the thumbnail as the photo, and the record says the photo was copied`() = runTest {
        // The provider's photo clean-up deleted it while the row still names it.
        phone.photos[1] = { throw PhotoFileGoneException() }

        val result = importContacts(phone, records, account, listOf(original(1, photo = true)))

        assertEquals(ActionResult(1, 0), result)
        assertEquals(row(PHOTO, "data15" to listOf<Byte>(1, 2)), phone.inserted.single().rows.last())
        assertEquals(record(1, copy = 101, photoCopied = true), records.records(account.id.value).single())
    }

    @Test
    fun `a contact without a photo copies none`() = runTest {
        phone.photos[1] = { byteArrayOf(7, 7) }

        importContacts(phone, records, account, listOf(original(1), original(2, photo = true)))

        assertEquals(listOf(listOf(NAME), listOf(NAME, PHOTO)), phone.inserted.map { it.rows.map(Row::mimeType) })
        assertEquals(listOf(record(1, copy = 101), record(2, copy = 102, photoCopied = true)), records.records(account.id.value))
    }

    @Test
    fun `only Written counts as done, a stale or refused copy deletes its record, and one whose reply was lost keeps it with no copy`() = runTest {
        phone.results += listOf(
            ContactWriteResult.Stale,
            ContactWriteResult.Failed(FailureReason.REJECTED_VALUE),
            ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED),
            ContactWriteResult.Failed(FailureReason.TOO_LARGE),
            ContactWriteResult.Written(205),
        )

        val result = importContacts(phone, records, account, (1L..5L).map { original(it) })

        assertEquals(ActionResult(done = 1, notDone = 4), result)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), phone.inserted.map { it.original.rawContactId })
        assertEquals(listOf(record(4), record(5, copy = 205)), records.records(account.id.value))
        assertEquals(listOf("delete 1", "delete 2", "delete 3"), calls.filter { it.startsWith("delete") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["STALE", "REJECTED_VALUE", "OPERATION_REFUSED", "TOO_LARGE"])
    fun `a contact imported before keeps its record, untouched, when its new copy is refused or lost`(answer: String) = runTest {
        // A marked contact can be ticked by hand: its record already marks it, so it isn't saved again before the
        // write, and its first copy's record is never lost.
        val earlier = record(1, version = 2, copy = 55, photoCopied = true)
        records.save(earlier)
        calls.clear()
        phone.results += if (answer == "STALE") ContactWriteResult.Stale else ContactWriteResult.Failed(FailureReason.valueOf(answer))

        val result = importContacts(phone, records, account, listOf(original(1, version = 3)))

        assertEquals(ActionResult(done = 0, notDone = 1), result)
        assertEquals(listOf("insertImport 1 at 3"), calls)
        assertEquals(listOf(earlier), records.records(account.id.value))
    }

    @Test
    fun `a contact imported before whose new copy is written has its record name the new copy`() = runTest {
        records.save(record(1, version = 2, copy = 55, photoCopied = true))
        records.save(record(2, version = 7, copy = 66))

        val result = importContacts(phone, records, account, listOf(original(1, version = 3)))

        assertEquals(ActionResult(1, 0), result)
        assertEquals(listOf(record(1, version = 3, copy = 101), record(2, version = 7, copy = 66)), records.records(account.id.value))
    }

    @Test
    fun `an import that stops at a contact imported before leaves its record as it was`() = runTest {
        val earlier = record(2, version = 2, copy = 55, photoCopied = true)
        records.save(earlier)
        phone.failing = Triple("insertImport", 2L, SecurityException())

        runCatching { importContacts(phone, records, account, (1L..3L).map { original(it) }) }

        assertEquals(listOf(record(1, copy = 101), earlier), records.records(account.id.value))
    }

    @Test
    fun `a contact chosen twice is imported once`() = runTest {
        val result = importContacts(phone, records, account, listOf(original(1), original(2), original(1)))

        assertEquals(ActionResult(done = 2, notDone = 0), result)
        assertEquals(listOf(1L, 2L), phone.inserted.map { it.original.rawContactId })
        assertEquals(listOf(record(1, copy = 101), record(2, copy = 102)), records.records(account.id.value))
    }

    @Test
    fun `nothing chosen does nothing`() = runTest {
        assertEquals(ActionResult(0, 0), importContacts(phone, records, account, emptyList()))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a contact whose copy has no rows isn't written, saves no record, and counts as not done`() = runTest {
        // The screen never offers one (it has no name or e-mail address), but nothing makes an empty contact.
        val empty = ReadContact(contact(1), listOf(dataRow(row(IM, "data1" to "ann"))))

        val result = importContacts(phone, records, account, listOf(empty, original(2)))

        assertEquals(ActionResult(done = 1, notDone = 1), result)
        assertEquals(listOf(2L), phone.inserted.map { it.original.rawContactId })
        assertEquals(listOf(record(2, copy = 102)), records.records(account.id.value))
    }

    @ParameterizedTest
    @ValueSource(strings = ["photo", "insertImport"])
    fun `a missing permission reaches the caller, with the records made so far kept`(where: String) = runTest {
        stopsAt(where, SecurityException())
    }

    @ParameterizedTest
    @ValueSource(strings = ["photo", "insertImport"])
    fun `a provider that stops answering reaches the caller, with the records made so far kept`(where: String) = runTest {
        stopsAt(where, ProviderUnavailableException())
    }

    /**
     * The second of three contacts throws [thrown] at [where]: it reaches the caller and the third isn't copied. The
     * photo is read before the record is saved, so a record without a copy is left only by a write that may have run.
     */
    private suspend fun stopsAt(where: String, thrown: Exception) {
        phone.failing = Triple(where, 2L, thrown)

        val caught = runCatching { importContacts(phone, records, account, (1L..3L).map { original(it) }) }.exceptionOrNull()

        assertSame(thrown, caught)
        assertEquals(listOf(1L), phone.inserted.map { it.original.rawContactId })
        val kept = listOf(record(1, copy = 101)) + listOfNotNull(record(2).takeIf { where == "insertImport" })
        assertEquals(kept, records.records(account.id.value))
    }

    private fun contact(id: Long, version: Long = 1, starred: Boolean = false) =
        DeviceContact(id, sourceId = null, dirty = false, deleted = false, version = version, displayName = "Contact $id", starred = starred)

    /** A local contact named after [id], with a photo row holding a thumbnail when [photo]. */
    private fun original(id: Long, version: Long = 1, starred: Boolean = false, photo: Boolean = false) = ReadContact(
        contact(id, version, starred),
        (listOf(row(NAME, "data1" to "Contact $id")) + listOfNotNull(row(PHOTO, "data15" to listOf<Byte>(1, 2)).takeIf { photo })).map(::dataRow),
    )

    private fun record(id: Long, version: Long = 1, copy: Long? = null, photoCopied: Boolean = false) =
        ImportRecordEntity(account.id.value, id, version, copy, photoCopied)

    private data class Inserted(val account: PhoneAccount, val original: ExistingRow, val rows: List<Row>, val starred: Boolean)

    private inner class FakeRecords : ImportRecordDao {
        private val saved = mutableMapOf<Pair<String, Long>, ImportRecordEntity>()

        override suspend fun records(accountId: String) = saved.values.filter { it.accountId == accountId }.sortedBy { it.originalRawContactId }

        override suspend fun save(record: ImportRecordEntity) {
            calls += "save $record"
            saved[record.accountId to record.originalRawContactId] = record
        }

        override suspend fun delete(accountId: String, originalRawContactId: Long) {
            calls += "delete $originalRawContactId"
            saved.remove(accountId to originalRawContactId)
        }
    }

    /**
     * The local contacts' photos ([photo] gives none unless set), and [insertImport], which records each copy and
     * answers from [results] (then `Written`, numbered from 101). Any other write fails the test.
     */
    private inner class FakeContacts : DeviceContacts {
        val photos = mutableMapOf<Long, () -> ByteArray?>()
        val inserted = mutableListOf<Inserted>()
        val results = ArrayDeque<ContactWriteResult>()

        /** A call, for one original, that throws the exception given. */
        var failing: Triple<String, Long, Exception>? = null

        private fun fail(call: String, rawContactId: Long) {
            failing?.let { (where, id, thrown) -> if (where == call && id == rawContactId) throw thrown }
        }

        override fun photo(rawContactId: Long): ByteArray? {
            fail("photo", rawContactId)
            return photos[rawContactId]?.invoke()
        }

        override fun insertImport(account: PhoneAccount, original: ExistingRow, rows: List<DataRow>, starred: Boolean): ContactWriteResult {
            fail("insertImport", original.rawContactId)
            calls += "insertImport ${original.rawContactId} at ${original.version}"
            inserted += Inserted(account, original, rows.map(::plain), starred)
            return results.removeFirstOrNull() ?: ContactWriteResult.Written(100L + original.rawContactId)
        }

        override fun query(account: PhoneAccount) = error("the chosen contacts are read already")
        override fun queryLocal() = error("the chosen contacts are read already")
        override fun readRows(rawContactId: Long) = error("the chosen contacts are read already")
        override fun apply(account: PhoneAccount, writes: List<ContactWrite>) = error("Import writes only its copies")
        override fun rewrite(account: PhoneAccount, protonId: String, existing: ExistingRow, original: ExistingRow, rows: List<DataRow>, keepPhotoRow: Boolean): ContactWriteResult = error("Import writes only its copies")
        override fun removeOriginal(account: PhoneAccount, original: ExistingRow, protonId: String, copy: ExistingRow, copyStarred: Boolean): ContactWriteResult = error("Import writes only its copies")
        override fun clearDirty(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Import writes only its copies")
        override fun bind(account: PhoneAccount, protonId: String, rawContactId: Long) = error("Import writes only its copies")
        override fun removeTombstone(account: PhoneAccount, protonId: String, rawContactId: Long) = error("Import writes only its copies")
        override fun removeRow(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Import writes only its copies")
        override fun detach(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Import writes only its copies")
        override fun deleteAccountRows(accountName: String) = error("Import writes only its copies")
        override fun insertLocal(rows: List<DataRow>, starred: Boolean) = error("Import writes only its copies")
        override fun maxPhotoDimension() = error("not used by Import")
        override fun photoHash(rawContactId: Long) = error("not used by Import")
        override fun thumbnailHash(rawContactId: Long) = error("not used by Import")
        override fun untouched(rawContactId: Long) = error("not used by Import")
        override fun accountNamesWithRows() = error("not used by Import")
    }

    private companion object {
        const val IM = "vnd.android.cursor.item/im"
    }
}
