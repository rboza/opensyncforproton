package dev.rboza.android.opensyncforproton.sync.provider

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordDao
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordEntity
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.EMAIL
import dev.rboza.android.opensyncforproton.sync.provider.ColumnMapperTest.Companion.NAME
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
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

// Applying merges: docs/sync.md § Import from this phone, Merge. Every value is made up for the test.
class ApplyMergesTest {
    private val account = PhoneAccount(AccountId("test-only-user-id"), "test-only@example.test")

    /** Every call to the fake provider's writes and to the records, in the order made. */
    private val calls = mutableListOf<String>()
    private val phone = FakeContacts()
    private val records = FakeRecords()

    @ParameterizedTest
    @EnumSource(MergeChoice::class, names = ["COMBINE", "KEEP_PHONE"])
    fun `Combine both and Keep the phone's rewrite the Proton row with mergedRows' rows, then save the record naming it`(choice: MergeChoice) = runTest {
        val first = pair(1, version = 4, protonVersion = 40, email = "first@example.test")
        val second = pair(2, version = 9, protonVersion = 90, email = "second@example.test")

        val result = applyMerges(phone, records, account, listOf(first to choice, second to choice))

        assertEquals(applied(choice, 2, wrote = true), result)
        assertEquals(
            listOf(
                "rewrite proton-id-1 row 11 at 40 for 1 at 4", "save ${record(1, version = 4, copy = 11)}",
                "rewrite proton-id-2 row 12 at 90 for 2 at 9", "save ${record(2, version = 9, copy = 12)}",
            ),
            calls,
        )
        val expected = listOf(first, second).map { mergedRows(it.proton.rows, it.original.rows, null, choice) }
        assertEquals(expected.map { it.rows.map(::plain) to it.keepPhotoRow }, phone.rewritten.map { it.rows to it.keepPhotoRow })
        assertEquals(List(2) { account }, phone.rewritten.map { it.account })
    }

    @Test
    fun `the rewrite keeps Proton's photo row for Combine both, and the record says the phone's photo was copied only when it was`() = runTest {
        phone.photos[1] = { byteArrayOf(7, 7) }
        phone.photos[2] = { byteArrayOf(8, 8) }
        phone.photos[3] = { byteArrayOf(9, 9) }
        val protonHasOne = pair(1, email = "one@example.test", photo = true, protonPhoto = true)
        val protonHasNone = pair(2, email = "two@example.test", photo = true)
        val replaced = pair(3, email = "three@example.test", photo = true, protonPhoto = true)

        applyMerges(phone, records, account, listOf(protonHasOne to MergeChoice.COMBINE, protonHasNone to MergeChoice.COMBINE, replaced to MergeChoice.KEEP_PHONE))

        assertEquals(listOf(true, false, false), phone.rewritten.map { it.keepPhotoRow })
        assertEquals(listOf(null, listOf<Byte>(8, 8), listOf<Byte>(9, 9)), phone.rewritten.map { written -> written.rows.singleOrNull { it.mimeType == PHOTO }?.values?.get("data15") })
        assertEquals(listOf(record(1, copy = 11), record(2, copy = 12, photoCopied = true), record(3, copy = 13, photoCopied = true)), records.records(account.id.value))
    }

    @Test
    fun `a photo that can't be read gives the thumbnail, and the record says no photo was copied`() = runTest {
        phone.photos[1] = { throw IOException("test-only") }

        val result = applyMerges(phone, records, account, listOf(pair(1, photo = true) to MergeChoice.KEEP_PHONE))

        assertEquals(MergeResult(keptPhone = 1, wrote = true), result)
        assertEquals(listOf<Byte>(1, 2), phone.rewritten.single().rows.single { it.mimeType == PHOTO }.values["data15"])
        assertEquals(record(1, copy = 11, photoCopied = false), records.records(account.id.value).single())
    }

    @ParameterizedTest
    @EnumSource(MergeChoice::class, names = ["COMBINE", "KEEP_PHONE"])
    fun `a display photo the provider no longer has leaves the thumbnail as the photo, and the record says the photo was copied`(choice: MergeChoice) = runTest {
        // The provider's photo clean-up deleted it while the row still names it.
        phone.photos[1] = { throw PhotoFileGoneException() }

        val result = applyMerges(phone, records, account, listOf(pair(1, photo = true) to choice))

        assertEquals(if (choice == MergeChoice.COMBINE) MergeResult(combined = 1, wrote = true) else MergeResult(keptPhone = 1, wrote = true), result)
        assertEquals(listOf<Byte>(1, 2), phone.rewritten.single().rows.single { it.mimeType == PHOTO }.values["data15"])
        assertEquals(record(1, copy = 11, photoCopied = true), records.records(account.id.value).single())
    }

    @ParameterizedTest
    @EnumSource(MergeChoice::class, names = ["COMBINE", "KEEP_PHONE"])
    fun `a choice that leaves the row as it is writes nothing and only saves the record`(choice: MergeChoice) = runTest {
        // The phone contact shows nothing Proton's lacks: the Proton row's rows are the phone's, with its keys.
        val same = pair(1, version = 4, email = "same@example.test")
        val alike = same.copy(proton = same.proton.copy(rows = same.original.rows.map { DataRow(it.mimeType, it.values + (SYNC1 to ColumnValue.Text("EMAIL.0000000000000001"))) }))

        val result = applyMerges(phone, records, account, listOf(alike to choice))

        assertEquals(applied(choice, 1, wrote = false), result)
        assertEquals(listOf("save ${record(1, version = 4, copy = 11)}"), calls)
    }

    @Test
    fun `Keep Proton's writes nothing and saves the record naming Proton's row`() = runTest {
        phone.photos[1] = { byteArrayOf(7, 7) }

        val result = applyMerges(phone, records, account, listOf(pair(1, version = 4, photo = true) to MergeChoice.KEEP_PROTON))

        assertEquals(MergeResult(keptProton = 1, wrote = false), result)
        assertEquals(listOf("save ${record(1, version = 4, copy = 11)}"), calls)
    }

    @Test
    fun `Keep both imports the phone contact as Import does`() = runTest {
        phone.photos[1] = { byteArrayOf(7, 7) }
        val both = pair(1, version = 4, starred = true, photo = true)

        val result = applyMerges(phone, records, account, listOf(both to MergeChoice.KEEP_BOTH))

        assertEquals(MergeResult(keptBoth = 1, wrote = true), result)
        assertEquals(listOf("save ${record(1, version = 4)}", "insertImport 1 at 4", "save ${record(1, version = 4, copy = 101, photoCopied = true)}"), calls)
        assertEquals(Inserted(ExistingRow(1, 4), importRows(both.original.rows, byteArrayOf(7, 7)).map(::plain), starred = true), phone.inserted.single())
    }

    @Test
    fun `Keep both whose copy isn't written isn't applied`() = runTest {
        phone.insertResults += ContactWriteResult.Stale

        val result = applyMerges(phone, records, account, listOf(pair(1) to MergeChoice.KEEP_BOTH, pair(2) to MergeChoice.KEEP_BOTH))

        assertEquals(MergeResult(keptBoth = 1, notApplied = 1, wrote = true), result)
        assertEquals(listOf(record(2, copy = 102)), records.records(account.id.value))
    }

    @Test
    fun `a Keep both whose copy isn't written writes nothing`() = runTest {
        phone.insertResults += ContactWriteResult.Failed(FailureReason.TOO_LARGE)

        assertEquals(MergeResult(notApplied = 1, wrote = false), applyMerges(phone, records, account, listOf(pair(1) to MergeChoice.KEEP_BOTH)))
    }

    @Test
    fun `each pair in list order, the choices mixed`() = runTest {
        val chosen = listOf(
            pair(3) to MergeChoice.KEEP_PROTON,
            pair(1) to MergeChoice.KEEP_BOTH,
            pair(2, email = "two@example.test") to MergeChoice.COMBINE,
        )

        val result = applyMerges(phone, records, account, chosen)

        assertEquals(MergeResult(combined = 1, keptProton = 1, keptBoth = 1, wrote = true), result)
        assertEquals(
            listOf(
                "save ${record(3, copy = 13)}",
                "save ${record(1)}", "insertImport 1 at 1", "save ${record(1, copy = 101)}",
                "rewrite proton-id-2 row 12 at 1 for 2 at 1", "save ${record(2, copy = 12)}",
            ),
            calls,
        )
    }

    @Test
    fun `a stale or refused rewrite saves no record and isn't applied, and the rest go on`() = runTest {
        phone.results += listOf(
            ContactWriteResult.Stale,
            ContactWriteResult.Failed(FailureReason.REJECTED_VALUE),
            ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED),
            ContactWriteResult.Failed(FailureReason.TOO_LARGE),
        )
        val chosen = (1L..5L).map { pair(it, email = "n$it@example.test") to MergeChoice.COMBINE }

        val result = applyMerges(phone, records, account, chosen)

        assertEquals(MergeResult(combined = 1, notApplied = 4, wrote = true), result)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), phone.rewritten.map { it.original.rawContactId })
        assertEquals(listOf(record(5, copy = 15)), records.records(account.id.value))
    }

    @ParameterizedTest
    @EnumSource(MergeChoice::class, names = ["COMBINE", "KEEP_PHONE"])
    fun `a rewrite not written writes nothing, Keep Proton's beside it included`(choice: MergeChoice) = runTest {
        phone.results += ContactWriteResult.Stale

        val result = applyMerges(phone, records, account, listOf(pair(1, email = "new@example.test") to choice, pair(2) to MergeChoice.KEEP_PROTON))

        assertEquals(MergeResult(keptProton = 1, notApplied = 1, wrote = false), result)
    }

    @Test
    fun `a pair chosen twice is applied once`() = runTest {
        val twice = pair(1, email = "new@example.test")

        val result = applyMerges(phone, records, account, listOf(twice to MergeChoice.COMBINE, pair(2) to MergeChoice.KEEP_PROTON, twice to MergeChoice.KEEP_PHONE))

        assertEquals(MergeResult(combined = 1, keptProton = 1, wrote = true), result)
        assertEquals(listOf("rewrite proton-id-1 row 11 at 1 for 1 at 1", "save ${record(1, copy = 11)}", "save ${record(2, copy = 12)}"), calls)
    }

    @Test
    fun `nothing chosen does nothing`() = runTest {
        assertEquals(MergeResult(), applyMerges(phone, records, account, emptyList()))
        assertEquals(emptyList<String>(), calls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["photo", "rewrite"])
    fun `a missing permission reaches the caller, with the records made so far kept`(where: String) = runTest {
        stopsAt(where, SecurityException())
    }

    @ParameterizedTest
    @ValueSource(strings = ["photo", "rewrite"])
    fun `a provider that stops answering reaches the caller, with the records made so far kept`(where: String) = runTest {
        stopsAt(where, ProviderUnavailableException())
    }

    /** The second of three pairs throws [thrown] at [where]: it reaches the caller, and the third isn't applied. */
    private suspend fun stopsAt(where: String, thrown: Exception) {
        phone.failing = Triple(where, 2L, thrown)
        val chosen = (1L..3L).map { pair(it, email = "n$it@example.test") to MergeChoice.KEEP_PHONE }

        val caught = runCatching { applyMerges(phone, records, account, chosen) }.exceptionOrNull()

        assertSame(thrown, caught)
        assertEquals(listOf(1L), phone.rewritten.map { it.original.rawContactId })
        assertEquals(listOf(record(1, copy = 11)), records.records(account.id.value))
    }

    /**
     * A local contact named after [id] (with an e-mail address when [email] is given, and a photo row holding a
     * thumbnail when [photo]) paired with its Proton row, 10 + [id], holding the same name.
     */
    private fun pair(
        id: Long,
        version: Long = 1,
        protonVersion: Long = 1,
        starred: Boolean = false,
        email: String? = null,
        photo: Boolean = false,
        protonPhoto: Boolean = false,
    ): MergePair {
        val name = row(NAME, "data1" to "Contact $id")
        val original = ReadContact(
            DeviceContact(id, sourceId = null, dirty = false, deleted = false, version = version, displayName = "Contact $id", starred = starred),
            (listOf(name) + listOfNotNull(email?.let { row(EMAIL, "data1" to it, "data2" to 1L) }, row(PHOTO, "data15" to listOf<Byte>(1, 2)).takeIf { photo })).map(::dataRow),
        )
        val proton = ReadContact(
            DeviceContact(10 + id, sourceId = "proton-id-$id", dirty = false, deleted = false, version = protonVersion, displayName = "Contact $id"),
            (listOf(row(NAME, "data1" to "Contact $id", SYNC1 to "FN.0000000000000001")) + listOfNotNull(row(PHOTO, "data15" to listOf<Byte>(3, 4)).takeIf { protonPhoto })).map(::dataRow),
        )
        return MergePair(original, proton, MatchReason.NAME, cantImport = null)
    }

    /** [count] pairs of [choice] applied, and nothing else. */
    private fun applied(choice: MergeChoice, count: Int, wrote: Boolean) = when (choice) {
        MergeChoice.COMBINE -> MergeResult(combined = count, wrote = wrote)
        MergeChoice.KEEP_PROTON -> MergeResult(keptProton = count, wrote = wrote)
        MergeChoice.KEEP_PHONE -> MergeResult(keptPhone = count, wrote = wrote)
        MergeChoice.KEEP_BOTH -> MergeResult(keptBoth = count, wrote = wrote)
    }

    private fun record(id: Long, version: Long = 1, copy: Long? = null, photoCopied: Boolean = false) =
        ImportRecordEntity(account.id.value, id, version, copy, photoCopied)

    private data class Rewritten(val account: PhoneAccount, val original: ExistingRow, val rows: List<Row>, val keepPhotoRow: Boolean)

    private data class Inserted(val original: ExistingRow, val rows: List<Row>, val starred: Boolean)

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
     * The local contacts' photos ([photo] gives none unless set), [rewrite], which records each write and answers from
     * [results] (then `Written`), and [insertImport] for Keep both, which answers from [insertResults] (then `Written`,
     * numbered from 101). Any other write fails the test.
     */
    private inner class FakeContacts : DeviceContacts {
        val photos = mutableMapOf<Long, () -> ByteArray?>()
        val rewritten = mutableListOf<Rewritten>()
        val inserted = mutableListOf<Inserted>()
        val results = ArrayDeque<ContactWriteResult>()
        val insertResults = ArrayDeque<ContactWriteResult>()

        /** A call, for one original, that throws the exception given. */
        var failing: Triple<String, Long, Exception>? = null

        private fun fail(call: String, rawContactId: Long) {
            failing?.let { (where, id, thrown) -> if (where == call && id == rawContactId) throw thrown }
        }

        override fun photo(rawContactId: Long): ByteArray? {
            fail("photo", rawContactId)
            return photos[rawContactId]?.invoke()
        }

        override fun rewrite(account: PhoneAccount, protonId: String, existing: ExistingRow, original: ExistingRow, rows: List<DataRow>, keepPhotoRow: Boolean): ContactWriteResult {
            fail("rewrite", original.rawContactId)
            calls += "rewrite $protonId row ${existing.rawContactId} at ${existing.version} for ${original.rawContactId} at ${original.version}"
            rewritten += Rewritten(account, original, rows.map(::plain), keepPhotoRow)
            return results.removeFirstOrNull() ?: ContactWriteResult.Written(existing.rawContactId)
        }

        override fun insertImport(account: PhoneAccount, original: ExistingRow, rows: List<DataRow>, starred: Boolean): ContactWriteResult {
            calls += "insertImport ${original.rawContactId} at ${original.version}"
            inserted += Inserted(original, rows.map(::plain), starred)
            return insertResults.removeFirstOrNull() ?: ContactWriteResult.Written(100L + original.rawContactId)
        }

        override fun query(account: PhoneAccount) = error("the pairs are read already")
        override fun queryLocal() = error("the pairs are read already")
        override fun readRows(rawContactId: Long) = error("the pairs are read already")
        override fun apply(account: PhoneAccount, writes: List<ContactWrite>) = error("Merge writes only its rewrites and copies")
        override fun removeOriginal(account: PhoneAccount, original: ExistingRow, protonId: String, copy: ExistingRow, copyStarred: Boolean): ContactWriteResult = error("Merge writes only its rewrites and copies")
        override fun clearDirty(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Merge writes only its rewrites and copies")
        override fun bind(account: PhoneAccount, protonId: String, rawContactId: Long) = error("Merge writes only its rewrites and copies")
        override fun removeTombstone(account: PhoneAccount, protonId: String, rawContactId: Long) = error("Merge writes only its rewrites and copies")
        override fun removeRow(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Merge writes only its rewrites and copies")
        override fun detach(account: PhoneAccount, protonId: String, existing: ExistingRow) = error("Merge writes only its rewrites and copies")
        override fun deleteAccountRows(accountName: String) = error("Merge writes only its rewrites and copies")
        override fun insertLocal(rows: List<DataRow>, starred: Boolean) = error("Merge writes only its rewrites and copies")
        override fun maxPhotoDimension() = error("not used by Merge")
        override fun photoHash(rawContactId: Long) = error("not used by Merge")
        override fun thumbnailHash(rawContactId: Long) = error("not used by Merge")
        override fun untouched(rawContactId: Long) = error("not used by Merge")
        override fun accountNamesWithRows() = error("not used by Merge")
    }
}
