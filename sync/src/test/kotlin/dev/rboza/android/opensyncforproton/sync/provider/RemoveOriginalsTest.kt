package dev.rboza.android.opensyncforproton.sync.provider

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordDao
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

// Removing originals: docs/sync.md § Import from this phone, Remove originals. Every value is made up for the test.
class RemoveOriginalsTest {
    private val account = PhoneAccount(AccountId("test-only-user-id"), "test-only@example.test")

    /** Every call to the fake provider's writes and to the records, in the order made. */
    private val calls = mutableListOf<String>()
    private val phone = FakeContacts()
    private val records = FakeRecords()

    @Test
    fun `each removable original in order is removed at its record's VERSION with its copy at the VERSION read, then its record deleted`() = runTest {
        saveRecords(1, 2)
        val first = imported(1, recordVersion = 4, copyVersion = 40)
        val second = imported(2, recordVersion = 9, copyVersion = 90)

        val result = removeOriginals(phone, records, account, listOf(first, second))

        assertEquals(ActionResult(done = 2, notDone = 0), result)
        assertEquals(
            listOf("removeOriginal 1 at 4, copy proton-id-1 row 11 at 40", "delete 1", "removeOriginal 2 at 9, copy proton-id-2 row 12 at 90", "delete 2"),
            calls,
        )
        assertEquals(emptyList<ImportRecordEntity>(), records.records(account.id.value))
        assertEquals(List(2) { account }, phone.removed.map { it.account })
    }

    @Test
    fun `the copy's star as read goes with it`() = runTest {
        saveRecords(1, 2)

        removeOriginals(phone, records, account, listOf(imported(1, copyStarred = true), imported(2)))

        assertEquals(listOf(true, false), phone.removed.map { it.copyStarred })
    }

    @Test
    fun `changed originals and those holding more aren't passed, and don't count`() = runTest {
        saveRecords(1, 2, 3)
        val originals = listOf(imported(1, OriginalState.CHANGED), imported(2), imported(3, OriginalState.HOLDS_MORE))

        val result = removeOriginals(phone, records, account, originals)

        assertEquals(ActionResult(done = 1, notDone = 0), result)
        assertEquals(listOf(2L), phone.removed.map { it.original.rawContactId })
        assertEquals(listOf(1L, 3L), records.records(account.id.value).map { it.originalRawContactId })
    }

    @Test
    fun `a stale or refused removal keeps its record and counts as not removed, and the rest go on`() = runTest {
        saveRecords(1, 2, 3, 4, 5)
        phone.results += listOf(
            ContactWriteResult.Stale,
            ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED),
            ContactWriteResult.Failed(FailureReason.REJECTED_VALUE),
            ContactWriteResult.Failed(FailureReason.TOO_LARGE),
        )

        val result = removeOriginals(phone, records, account, (1L..5L).map { imported(it) })

        assertEquals(ActionResult(done = 1, notDone = 4), result)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), phone.removed.map { it.original.rawContactId })
        assertEquals(listOf(1L, 2L, 3L, 4L), records.records(account.id.value).map { it.originalRawContactId })
        assertEquals(listOf("delete 5"), calls.filter { it.startsWith("delete") })
    }

    @Test
    fun `an original passed twice is removed once`() = runTest {
        saveRecords(1, 2)

        val result = removeOriginals(phone, records, account, listOf(imported(1), imported(2), imported(1)))

        assertEquals(ActionResult(done = 2, notDone = 0), result)
        assertEquals(listOf(1L, 2L), phone.removed.map { it.original.rawContactId })
    }

    @Test
    fun `nothing passed does nothing`() = runTest {
        assertEquals(ActionResult(0, 0), removeOriginals(phone, records, account, emptyList()))
        assertEquals(emptyList<String>(), calls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["security", "provider"])
    fun `a missing permission or a provider that stops answering reaches the caller, with the records of those removed deleted`(what: String) = runTest {
        saveRecords(1, 2, 3)
        val thrown = if (what == "security") SecurityException() else ProviderUnavailableException()
        phone.failing = 2L to thrown

        val caught = runCatching { removeOriginals(phone, records, account, (1L..3L).map { imported(it) }) }.exceptionOrNull()

        assertSame(thrown, caught)
        assertEquals(listOf(1L), phone.removed.map { it.original.rawContactId })
        assertEquals(listOf(2L, 3L), records.records(account.id.value).map { it.originalRawContactId })
    }

    private suspend fun saveRecords(vararg ids: Long) {
        ids.forEach { records.save(ImportRecordEntity(account.id.value, it, 1, copyRawContactId = 10 + it)) }
        calls.clear()
    }

    /**
     * An imported original [id] whose copy is row 10 + [id] holding Proton ID "proton-id-[id]". The row as read has
     * another VERSION than its record's (a removable one never does), so the one passed is told apart.
     */
    private fun imported(id: Long, state: OriginalState = OriginalState.REMOVABLE, recordVersion: Long = 1, copyVersion: Long = 3, copyStarred: Boolean = false) =
        ImportedOriginal(
            DeviceContact(id, sourceId = null, dirty = true, deleted = false, version = recordVersion + 100, displayName = "Original $id"),
            recordVersion,
            DeviceContact(10 + id, sourceId = "proton-id-$id", dirty = false, deleted = false, version = copyVersion, displayName = "Copy $id", starred = copyStarred),
            state,
        )

    private data class Removed(val account: PhoneAccount, val original: ExistingRow, val protonId: String, val copy: ExistingRow, val copyStarred: Boolean)

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
     * [removeOriginal], which records each removal and answers from [results] (then `Written`). Any other write fails
     * the test.
     */
    private inner class FakeContacts : DeviceContacts {
        val removed = mutableListOf<Removed>()
        val results = ArrayDeque<ContactWriteResult>()

        /** An original whose removal throws the exception given. */
        var failing: Pair<Long, Exception>? = null

        override fun removeOriginal(account: PhoneAccount, original: ExistingRow, protonId: String, copy: ExistingRow, copyStarred: Boolean): ContactWriteResult {
            failing?.let { (id, thrown) -> if (id == original.rawContactId) throw thrown }
            calls += "removeOriginal ${original.rawContactId} at ${original.version}, copy $protonId row ${copy.rawContactId} at ${copy.version}"
            removed += Removed(account, original, protonId, copy, copyStarred)
            return results.removeFirstOrNull() ?: ContactWriteResult.Written(original.rawContactId)
        }

        override fun query(account: PhoneAccount) = error("the originals are read already")
        override fun queryLocal() = error("the originals are read already")
        override fun readRows(rawContactId: Long) = error("the originals are read already")
        override fun photo(rawContactId: Long) = error("the originals are read already")
        override fun apply(account: PhoneAccount, writes: List<ContactWrite>) = error("Remove originals deletes only originals")
        override fun insertImport(account: PhoneAccount, original: ExistingRow, rows: List<DataRow>, starred: Boolean) = error("Remove originals deletes only originals")
        override fun rewrite(account: PhoneAccount, protonId: String, existing: ExistingRow, original: ExistingRow, rows: List<DataRow>, keepPhotoRow: Boolean): ContactWriteResult = error("Remove originals deletes only originals")
        override fun clearDirty(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Remove originals deletes only originals")
        override fun bind(account: PhoneAccount, protonId: String, rawContactId: Long) = error("Remove originals deletes only originals")
        override fun removeTombstone(account: PhoneAccount, protonId: String, rawContactId: Long) = error("Remove originals deletes only originals")
        override fun removeRow(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Remove originals deletes only originals")
        override fun detach(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Remove originals deletes only originals")
        override fun deleteAccountRows(accountName: String) = error("Remove originals deletes only originals")
        override fun insertLocal(rows: List<DataRow>, starred: Boolean) = error("Remove originals deletes only originals")
        override fun maxPhotoDimension() = error("not used by Remove originals")
        override fun photoHash(rawContactId: Long) = error("not used by Remove originals")
        override fun thumbnailHash(rawContactId: Long) = error("not used by Remove originals")
        override fun untouched(rawContactId: Long) = error("not used by Remove originals")
        override fun accountNamesWithRows() = error("not used by Remove originals")
    }
}
