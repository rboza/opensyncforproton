package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.ContactPhoto
import dev.rboza.android.opensyncforproton.mapping.SourceKey
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.EventCursorEntity
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.plan.ReportCounts
import dev.rboza.android.opensyncforproton.sync.provider.ColumnValue
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ExistingRow
import dev.rboza.android.opensyncforproton.sync.provider.FailureReason
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import dev.rboza.android.opensyncforproton.sync.provider.ScaledPhoto
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Downloads with sync on (docs/sync.md § Writes, § Photos), and when a full sync records that it ended. Every value is
// made up for the test.
class SyncDownloadTest : EngineHarness() {
    private fun fingerprint(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), contact(n).cards).value
    private fun row(raw: Long, n: Int?, dirty: Boolean = false, deleted: Boolean = false, version: Long = 1) =
        DeviceContact(raw, n?.let { "proton-id-$it" }, dirty, deleted, version, "Row $raw")
    /** A map whose rows the app wrote with their keys, unless [keyed] is false (written by `v0.1.0`, or rebuilt). */
    private fun map(n: Int, raw: Long, fingerprint: String? = "old-fingerprint", keyed: Boolean = true, photoBaseline: String? = null) =
        ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint, rowsKeyed = keyed, photoBaseline = photoBaseline)

    /** The map a write leaves: the contact's fingerprint, its rows keyed, and the photo read back as its baseline. */
    private fun written(n: Int, raw: Long) =
        ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint(n), rowsKeyed = true, photoBaseline = photoHashOf(raw), photoThumbnailBaseline = thumbnailHashOf(raw))

    /** The map a write leaves when its photo baseline is unknown: as if its rows weren't keyed. */
    private fun unknownBaseline(n: Int, raw: Long) = ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint(n), rowsKeyed = false)

    @Test
    fun `new contacts on an empty phone get rows with SOURCE_ID and maps`() = runTest {
        api.pages += listOf(contact(1), contact(2))

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-1", "proton-id-2"), phone.writes.map { it.protonId })
        assertTrue(phone.writes.all { it.existing == null })
        assertEquals(dataRows(ContactGraph(uid = null, formattedName = "Contact proton-id-1"), null).map { it.mimeType }, phone.writes[0].rows.map { it.mimeType })
        assertEquals(
            listOf(written(1, 1001), written(2, 1002)),
            mapDao.maps,
        )
        assertEquals(ReportKind.SYNC, reportDao.saved.single().kind)
        assertEquals(2, reportDao.saved.single().newFromProton)
        assertTrue(report.ready)
        assertEquals(listOf("C3 0"), calls, "reads only: no C4, C5, or C6")
    }

    @Test
    fun `a download write carries each value's key in Data SYNC1`() = runTest {
        api.pages += listOf(contact(1))
        mapper.nameKeys["1"] = SourceKey(listOf("FN.0123456789abcdef"))

        engine().sync(account)

        val row = phone.writes.single().rows.single()
        assertEquals(ColumnValue.Text("FN.0123456789abcdef"), row.values["data_sync1"])
    }

    @Test
    fun `each contact's map is saved as soon as it's written`() = runTest {
        api.pages += listOf(contact(1), contact(2))

        engine().sync(account)

        assertEquals(
            listOf("apply proton-id-1", "read back 1001", "map proton-id-1", "apply proton-id-2", "read back 1002", "map proton-id-2"),
            events,
        )
    }

    @Test
    fun `an unchanged contact isn't written`() = runTest {
        api.pages += listOf(contact(1))
        phone.rows += row(10, 1)
        mapDao.maps += map(1, 10, fingerprint(1))

        engine().sync(account)

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a changed contact replaces its row at the VERSION read, and its baseline advances`() = runTest {
        api.pages += listOf(contact(1))
        phone.rows += row(10, 1, version = 7)
        mapDao.maps += map(1, 10)

        engine().sync(account)

        assertEquals(ExistingRow(10, 7), phone.writes.single().existing)
        assertEquals(listOf(written(1, 10)), mapDao.maps)
    }

    @Test
    fun `phone changes, tombstones, absences, duplicates and missing rows are left alone, and a phone-made row only created`() = runTest {
        api.pages += listOf(contact(1), contact(2), contact(4), contact(5))
        phone.rows += listOf(row(10, 1, dirty = true), row(20, 2, deleted = true), row(30, 3), row(40, 4), row(41, 4), row(60, null))
        mapDao.maps += listOf(map(1, 10), map(2, 20), map(3, 30), map(5, 50))

        val report = engine().sync(account)

        // Row 60 is created on Proton and written back from it (SyncCreateTest); nothing else is written.
        assertEquals(listOf("apply proton-id-101"), events.filter { it.startsWith("apply") })
        assertEquals(60L, phone.writes.single().existing?.rawContactId)
        val counts = report.counts()
        // 1 dirty and 2 deleted on the phone, both changed on Proton: conflicts; 3 missing from a complete inventory;
        // 4 on two rows; 5's row gone; row 60 made on the phone.
        assertEquals(
            listOf(2, 1, 1, 1, 1),
            listOf(counts.conflicts, counts.deletedOnProton, counts.duplicateRows, counts.missingRows, counts.newOnPhone),
        )
    }

    @Test
    fun `a clean contact written before keys is written again from Proton with its keys, sending and counting nothing`() = runTest {
        // Written before keys: v0.1.0 wrote it without Data.SYNC1 keys.
        api.pages += listOf(contact(1))
        mapper.nameKeys["1"] = SourceKey(listOf("FN.0123456789abcdef"))
        phone.rows += row(10, 1, version = 7)
        mapDao.maps += map(1, 10, fingerprint(1), keyed = false)

        val report = engine().sync(account)

        val write = phone.writes.single()
        assertEquals(ExistingRow(10, 7), write.existing, "replaced in place at the VERSION read")
        assertEquals(ColumnValue.Text("FN.0123456789abcdef"), write.rows.single().values["data_sync1"])
        assertEquals(listOf(written(1, 10)), mapDao.maps, "the same baseline, its rows now keyed")
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0), report.counts())
        assertEquals(0, reportDao.saved.single().changedOnProton)
        assertTrue(report.ready)
        assertEquals(listOf("C3 0"), calls, "nothing uploaded")
    }

    @Test
    fun `a contact written before keys whose write is refused can't be synced, and stays unkeyed`() = runTest {
        api.pages += listOf(contact(1), contact(2), contact(3))
        phone.rows += listOf(row(10, 1), row(20, 2), row(30, 3))
        mapDao.maps += listOf(map(1, 10, fingerprint(1), keyed = false), map(2, 20, fingerprint(2), keyed = false), map(3, 30, fingerprint(3), keyed = false))
        phone.results["proton-id-1"] = ContactWriteResult.Failed(FailureReason.TOO_LARGE)
        phone.results["proton-id-2"] = ContactWriteResult.Stale
        mapper.photos["3"] = ContactPhoto(byteArrayOf(1), null)
        photos.result = ScaledPhoto.Oversized

        val report = engine().sync(account)

        assertEquals(listOf(map(1, 10, fingerprint(1), keyed = false), map(2, 20, fingerprint(2), keyed = false), map(3, 30, fingerprint(3), keyed = false)), mapDao.maps)
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 2, 0, 0), report.counts(), "a stale write is planned again, not refused")
        assertEquals(2, reportDao.saved.single().unreadable)
        assertEquals(
            listOf("proton-id-1" to NotSyncedReason.WRITE_TOO_LARGE, "proton-id-3" to NotSyncedReason.PHOTO_OVERSIZED),
            report.refused.map { it.item.protonId?.value to it.reason },
        )
    }

    @Test
    fun `a dirty contact written before keys is held, not written`() = runTest {
        api.pages += listOf(contact(1))
        phone.rows += row(10, 1, dirty = true)
        mapDao.maps += map(1, 10, fingerprint(1), keyed = false)

        val report = engine().sync(account)

        assertEquals(listOf("hold proton-id-1"), events, "nothing written but its hold")
        assertEquals(1, report.counts().conflicts)
        assertEquals(1, reportDao.saved.single().conflicts)
        assertEquals(listOf("C3 0"), calls)
    }

    @Test
    fun `map repairs are written first, in one go`() = runTest {
        api.pages += listOf(contact(1), contact(9))
        phone.rows += listOf(row(10, 1), row(30, 3))

        engine().sync(account)

        assertEquals("repairs [proton-id-1, proton-id-3]", events.first())
        assertEquals(ContactMapEntity("user-id-1", "proton-id-3", 30, null, rowsKeyed = false), mapDao.maps.single { it.protonId == "proton-id-3" }, "an empty baseline, rows not keyed")
        assertEquals(written(1, 10), mapDao.maps.single { it.protonId == "proton-id-1" }, "then rewritten")
    }

    @Test
    fun `a contact whose map write was lost is repaired, not duplicated`() = runTest {
        // A crash after the provider write left a row with SOURCE_ID and no map.
        api.pages += listOf(contact(1))
        phone.rows += row(10, 1)

        engine().sync(account)

        assertEquals(ExistingRow(10, 1), phone.writes.single().existing, "rewritten in place, no second row")
    }

    @Test
    fun `a sync without a network is offline at once and writes no contact`() = runTest {
        // Offline, a row whose map was lost is still repaired, as after any inventory blocker.
        online = false
        api.pages += listOf(contact(1))
        phone.rows += row(10, 3)

        val report = engine().sync(account)

        assertEquals(emptyList<String>(), calls, "no request to Proton")
        assertEquals(emptyList<String>(), events.filter { it.startsWith("apply") })
        assertEquals(listOf(ContactMapEntity("user-id-1", "proton-id-3", 10, null)), mapDao.maps, "the repair")
        assertTrue(report.retryable)
        assertEquals(ReportKind.SYNC to Blocker.OFFLINE, reportDao.saved.single().let { it.kind to it.blocker })
    }

    @Test
    fun `an unreadable contact is skipped and the others are applied`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        crypto.results["proton-id-1"] = CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-2"), phone.writes.map { it.protonId })
        assertEquals(1, report.counts().unreadable)
    }

    @Test
    fun `the first page's contacts still apply when a later page fails`() = runTest {
        api.pages += List(50) { contact(it) }
        api.failure = ProtonError.Network()

        val report = engine().sync(account)

        assertEquals(50, phone.writes.size)
        assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker)
        assertEquals(Blocker.PROTON_UNAVAILABLE, reportDao.saved.single().blocker)
    }

    @Test
    fun `a stale row isn't mapped and is planned again next run`() = runTest {
        api.pages += listOf(contact(1))
        phone.rows += row(10, 1)
        mapDao.maps += map(1, 10)
        phone.results["proton-id-1"] = ContactWriteResult.Stale

        val report = engine().sync(account)

        assertEquals(listOf(map(1, 10)), mapDao.maps, "the old baseline stays")
        assertEquals(0, report.counts().unreadable, "the phone moved on; not a failure")
    }

    @Test
    fun `a write that fails is counted as can't be synced, and the run goes on`() = runTest {
        api.pages += listOf(contact(1), contact(2), contact(3))
        phone.results["proton-id-1"] = ContactWriteResult.Failed(FailureReason.TOO_LARGE)
        phone.results["proton-id-2"] = ContactWriteResult.Failed(FailureReason.REJECTED_VALUE)

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-1", "proton-id-2", "proton-id-3"), phone.writes.map { it.protonId })
        assertEquals(listOf("proton-id-3"), mapDao.maps.map { it.protonId })
        assertEquals(2, report.counts().unreadable)
        assertEquals(1, report.counts().newFromProton, "only what was written; the refused ones moved")
        assertEquals(2, reportDao.saved.single().unreadable)
        assertEquals(1, reportDao.saved.single().newFromProton)
        assertEquals(
            listOf("proton-id-1" to NotSyncedReason.WRITE_TOO_LARGE, "proton-id-2" to NotSyncedReason.VALUE_REJECTED),
            report.refused.map { it.item.protonId?.value to it.reason },
        )
    }

    @Test
    fun `an operation the provider refuses stops the run as contacts hidden`() = runTest {
        api.pages += listOf(contact(1), contact(2), contact(3))
        phone.results["proton-id-2"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-1", "proton-id-2"), phone.writes.map { it.protonId }, "nothing after it")
        assertEquals(listOf("proton-id-1"), mapDao.maps.map { it.protonId }, "what was written is kept")
        assertEquals(Blocker.CONTACTS_HIDDEN, report.blocker)
        assertEquals(Blocker.CONTACTS_HIDDEN, reportDao.saved.single().blocker)
    }

    @Test
    fun `a map naming another row is repaired, then rewritten with the fresh fingerprint`() = runTest {
        api.pages += listOf(contact(1))
        phone.rows += row(7, 1)
        mapDao.maps += map(1, 5)

        engine().sync(account)

        assertEquals(listOf("repairs [proton-id-1]", "apply proton-id-1", "read back 7", "map proton-id-1"), events)
        assertEquals(listOf(written(1, 7)), mapDao.maps)
    }

    @Test
    fun `a changed contact whose photo is refused keeps its old map`() = runTest {
        api.pages += listOf(contact(1))
        phone.rows += row(10, 1)
        mapDao.maps += map(1, 10)
        mapper.photos["1"] = ContactPhoto(byteArrayOf(1), null)
        photos.result = ScaledPhoto.Malformed

        val report = engine().sync(account)

        assertEquals(listOf(map(1, 10)), mapDao.maps)
        assertEquals(0, report.counts().changedOnProton)
        assertEquals(1, report.counts().unreadable)
    }

    @Test
    fun `contacts hidden before planning writes nothing, not even repairs`() = runTest {
        api.pages += listOf(contact(1))
        mapDao.maps += map(1, 10)

        val report = engine().sync(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, report.blocker)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a provider that stops answering mid-run stops it, keeping what was written`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        phone.onApply = { if (it.protonId == "proton-id-2") phone.applyFailure = ProviderUnavailableException() }

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-1"), mapDao.maps.map { it.protonId })
        assertEquals(Blocker.CONTACTS_UNAVAILABLE, report.blocker)
    }

    @Test
    fun `a stop while writing beats an inventory blocker`() = runTest {
        api.pages += List(50) { contact(it) }
        api.failure = ProtonError.Network()
        phone.results["proton-id-3"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        assertEquals(Blocker.CONTACTS_HIDDEN, engine().sync(account).blocker)
    }

    @Test
    fun `a photo is scaled to the provider's maximum before it's written`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        mapper.photos["1"] = ContactPhoto(byteArrayOf(1, 2, 3), "image/jpeg")
        mapper.photos["2"] = ContactPhoto(byteArrayOf(4, 5), null)

        engine().sync(account)

        assertEquals(listOf(listOf<Byte>(1, 2, 3) to 720, listOf<Byte>(4, 5) to 720), photos.calls)
        assertEquals(1, phone.maxDimensionReads, "read once per run")
        val photo = phone.writes[0].rows.single { it.mimeType == "vnd.android.cursor.item/photo" }
        assertEquals(listOf<Byte>(3, 2, 1), (photo.values.getValue("data15") as ColumnValue.Blob).bytes.toList(), "the scaled bytes")
    }

    @Test
    fun `a malformed or oversized photo rejects that contact's write`() = runTest {
        listOf(ScaledPhoto.Malformed, ScaledPhoto.Oversized).forEach { rejected ->
            phone.writes.clear()
            mapDao.maps.clear()
            reportDao.saved.clear()
            api.pages.clear()
            api.pages += listOf(contact(1), contact(2))
            mapper.photos["1"] = ContactPhoto(byteArrayOf(1), null)
            photos.result = rejected

            val report = engine().sync(account)

            assertEquals(listOf("proton-id-2"), phone.writes.map { it.protonId }, "$rejected")
            assertEquals(1, report.counts().unreadable, "$rejected")
            assertEquals(1, report.counts().newFromProton, "$rejected")
            val reason = if (rejected == ScaledPhoto.Malformed) NotSyncedReason.PHOTO_MALFORMED else NotSyncedReason.PHOTO_OVERSIZED
            assertEquals(listOf("proton-id-1" to reason), report.refused.map { it.item.protonId?.value to it.reason })
        }
    }

    @Test
    fun `no photo, no scaling and no maximum read`() = runTest {
        api.pages += listOf(contact(1))

        engine().sync(account)

        assertTrue(photos.calls.isEmpty())
        assertEquals(0, phone.maxDimensionReads)
    }

    @Test
    fun `nothing to write reads no maximum`() = runTest {
        engine().sync(account)

        assertEquals(0, phone.maxDimensionReads)
        assertEquals(ReportKind.SYNC, reportDao.saved.single().kind)
    }

    @Test
    fun `a rows blocker writes nothing`() = runTest {
        api.pages += listOf(contact(1))
        phone.failure = SecurityException()

        val report = engine().sync(account)

        assertEquals(emptyList<String>(), events)
        assertEquals(Blocker.PERMISSION_MISSING, report.blocker)
        assertNull(report.plan)
        // With no sync's report before it, nothing is counted yet.
        assertEquals(
            listOf(zeroCounts(Blocker.PERMISSION_MISSING, ReportKind.SYNC).copy(trigger = ReportTrigger.SYNC_NOW, protonCountedAt = null, phoneCountedAt = null)),
            reportDao.saved,
        )
    }

    @Test
    fun `a permission revoked mid-run stops it, keeping what was written`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        phone.onApply = { if (it.protonId == "proton-id-2") phone.applyFailure = SecurityException() }

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-1"), mapDao.maps.map { it.protonId })
        assertEquals(Blocker.PERMISSION_MISSING, report.blocker)
        assertEquals(1, reportDao.saved.size)
    }

    @Test
    fun `a provider that can't give its photo maximum stops the run`() = runTest {
        api.pages += listOf(contact(1))
        mapper.photos["1"] = ContactPhoto(byteArrayOf(1), null)
        phone.maxDimensionFailure = ProviderUnavailableException()

        val report = engine().sync(account)

        assertEquals(emptyList<String>(), events.filter { it.startsWith("apply") })
        assertEquals(Blocker.CONTACTS_UNAVAILABLE, report.blocker)
    }

    @Test
    fun `cancelling stops before the next write, keeping the maps of what was written`() = runTest {
        api.pages += List(3) { contact(it + 1) }
        val run = launch { engine().sync(account) }
        phone.onApply = { if (it.protonId == "proton-id-2") run.cancel() }

        run.join()

        assertTrue(run.isCancelled)
        assertEquals(listOf("proton-id-1", "proton-id-2"), phone.writes.map { it.protonId })
        assertEquals(listOf("proton-id-1", "proton-id-2"), mapDao.maps.map { it.protonId }, "a write's map is saved even as the run is cancelled")
        assertTrue(reportDao.saved.isEmpty())
    }

    // The photo baseline.

    @Test
    fun `each write stores the photo and its thumbnail read back as its map's baselines, or none without a photo`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        phone.rows += row(20, 2, version = 3)
        mapDao.maps += map(2, 20, photoBaseline = "f".repeat(64)).copy(photoThumbnailBaseline = "f".repeat(64))
        phone.photoHashes[20] = null

        engine().sync(account)

        assertEquals(
            listOf(written(1, 1001), ContactMapEntity("user-id-1", "proton-id-2", 20, fingerprint(2), rowsKeyed = true, photoBaseline = null)),
            mapDao.maps.sortedBy { it.protonId },
        )
    }

    @Test
    fun `a stale or refused write reads nothing back`() = runTest {
        api.pages += listOf(contact(1), contact(2), contact(3))
        phone.results["proton-id-1"] = ContactWriteResult.Stale
        phone.results["proton-id-2"] = ContactWriteResult.Failed(FailureReason.TOO_LARGE)
        mapper.photos["3"] = ContactPhoto(byteArrayOf(1), null)
        photos.result = ScaledPhoto.Malformed

        engine().sync(account)

        assertEquals(emptyList<String>(), events.filter { it.startsWith("read back") })
    }

    @Test
    fun `a photo that can't be read back leaves the baseline unknown, and the run goes on`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        phone.onPhotoHash = { if (it == 1001L) throw IOException() }

        val report = engine().sync(account)

        assertEquals(listOf(unknownBaseline(1, 1001), written(2, 1002)), mapDao.maps)
        assertTrue(report.ready)
        assertEquals(2, report.counts().newFromProton, "both were written")
    }

    @Test
    fun `a row edited or deleted on the phone before its photo was read back leaves the baseline unknown`() = runTest {
        // The hash may be of the phone's edit, not of the write: DIRTY is checked after hashing.
        api.pages += listOf(contact(1), contact(2))
        phone.onPhotoHash = { if (it == 1001L) phone.touched += it }

        val report = engine().sync(account)

        assertEquals(listOf(unknownBaseline(1, 1001), written(2, 1002)), mapDao.maps)
        assertTrue(report.ready)
    }

    @Test
    fun `a row edited or deleted on the phone before its thumbnail was read back leaves the baseline unknown`() = runTest {
        // The thumbnail is read before DIRTY is checked too.
        api.pages += listOf(contact(1), contact(2))
        phone.onThumbnailHash = { if (it == 1001L) phone.touched += it }

        engine().sync(account)

        assertEquals(listOf(unknownBaseline(1, 1001), written(2, 1002)), mapDao.maps)
    }

    @Test
    fun `a permission revoked or a provider that stops answering while reading back keeps the map, unkeyed, and stops the run`() = runTest {
        val failures = listOf(SecurityException() to Blocker.PERMISSION_MISSING, ProviderUnavailableException() to Blocker.CONTACTS_UNAVAILABLE)
        for ((failure, blocker) in failures) {
            for (step in listOf("hashing", "reading the thumbnail", "checking the row")) {
                val case = "$blocker, $step"
                phone.writes.clear()
                mapDao.maps.clear()
                api.pages.clear()
                api.pages += listOf(contact(1), contact(2))
                phone.onPhotoHash = { if (step == "hashing") throw failure }
                phone.onThumbnailHash = { if (step == "reading the thumbnail") throw failure }
                phone.untouchedFailure = failure.takeIf { step == "checking the row" }

                val report = engine().sync(account)

                assertEquals(listOf("proton-id-1"), phone.writes.map { it.protonId }, case)
                assertEquals(listOf(unknownBaseline(1, 1001)), mapDao.maps, case)
                assertEquals(blocker, report.blocker, case)
            }
        }
    }

    @Test
    fun `a full sync that ends with no blocker records when it finished, keeping the cursor`() = runTest {
        // The full-sync interval counts from when the last full sync ended.
        cursorDao.rows["user-id-1"] = EventCursorEntity("user-id-1", "event-1", lastFullSyncAt = 5)
        api.pages += contact(1)

        val report = engine().sync(account)

        assertTrue(report.ready)
        assertEquals(EventCursorEntity("user-id-1", "event-1", report.finishedAt), cursorDao.rows["user-id-1"])
    }

    @Test
    fun `a full sync with a retryable blocker, a dry run, and an upload-only sync record no full sync`() = runTest {
        cursorDao.rows["user-id-1"] = EventCursorEntity("user-id-1", "event-1", lastFullSyncAt = 5)
        api.pages += contact(1)

        assertTrue(engine().dryRun(account).ready)
        assertTrue(engine().upload(account).ready)
        api.pages.clear()
        api.failure = ProtonError.Network()
        assertEquals(Blocker.PROTON_UNAVAILABLE, engine().sync(account).blocker)

        assertEquals(EventCursorEntity("user-id-1", "event-1", 5), cursorDao.rows["user-id-1"])
        cursorDao.rows.clear()
        engine().sync(account)
        assertNull(cursorDao.rows["user-id-1"], "no row made either")
    }

    @Test
    fun `a full sync that ends with a lasting blocker records when it ended too`() = runTest {
        // So a lasting blocker gets one full sync per interval.
        cursorDao.rows["user-id-1"] = EventCursorEntity("user-id-1", "event-1", lastFullSyncAt = 5)
        api.failure = ProtonError.Api(422, 2003)

        val report = engine().sync(account)

        assertEquals(Blocker.INVENTORY_INCOMPLETE, report.blocker)
        assertEquals(EventCursorEntity("user-id-1", "event-1", report.finishedAt), cursorDao.rows["user-id-1"])
    }
}
