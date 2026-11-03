package dev.rboza.android.opensyncforproton.sync.engine

import android.provider.ContactsContract.CommonDataKinds.Note
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.PhotoUpload
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.CreateReply
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.plan.ReportCounts
import dev.rboza.android.opensyncforproton.sync.provider.ColumnValue
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DataRow
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ExistingRow
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Use phone on held contacts: docs/sync.md § Conflicts. Every value is made up for the test.
class SyncUsePhoneTest : EngineHarness() {
    private fun fingerprint(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), contact(n).cards).value
    private fun row(raw: Long, n: Int, dirty: Boolean = false, deleted: Boolean = false, version: Long = 1) =
        DeviceContact(raw, "proton-id-$n", dirty = dirty, deleted = deleted, version = version, displayName = "Row $raw")
    private fun map(n: Int, raw: Long, fingerprint: String? = fingerprint(n), keyed: Boolean = true) =
        ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint, rowsKeyed = keyed, photoBaseline = photoHashOf(raw))
    private fun hold(n: Int, raw: Long, version: Long? = 1, fingerprint: String? = fingerprint(n), kind: HoldKind = HoldKind.CONFLICT, choice: HoldChoice? = HoldChoice.USE_PHONE) =
        ConflictHoldEntity("user-id-1", "proton-id-$n", raw, kind, choice, version, fingerprint)
    private fun moved(n: Int) = contact(n).copy(cards = listOf(CardDto(1, "data-$n-1-moved")))

    /**
     * The map an upload or a bound create leaves: the fingerprint of the cards Proton stored, its rows keyed, the photo
     * and its thumbnail read back.
     */
    private fun stored(n: Int, raw: Long) =
        map(n, raw, fingerprint = contactFingerprint(ProtonContactId("proton-id-$n"), api.pages.single { it.id == "proton-id-$n" }.cards).value)
            .copy(photoThumbnailBaseline = thumbnailHashOf(raw))

    private fun holds() = holdDao.holds.sortedBy { it.protonId }

    private fun maps() = mapDao.maps.sortedBy { it.protonId }

    /** Contact 9, on both sides and unchanged, so the inventory is never empty. */
    private fun unchanged() {
        api.pages += contact(9)
        phone.rows += row(90, 9)
        mapDao.maps += map(9, 90)
    }

    /** Edit vs edit: contact [n] changed on Proton since the baseline, its row [raw] edited at [version]. */
    private fun editVsEdit(n: Int, raw: Long = n * 10L, version: Long = 1) {
        api.pages += contact(n)
        phone.rows += row(raw, n, dirty = true, version = version)
        mapDao.maps += map(n, raw, fingerprint = "old-fingerprint")
    }

    /** A phone edit vs a Proton deletion: row [raw], edited at [version], whose contact [n] is gone from Proton. */
    private fun editVsDeletion(n: Int, raw: Long = n * 10L, version: Long = 1) {
        phone.rows += row(raw, n, dirty = true, version = version)
        mapDao.maps += map(n, raw)
        holdDao.holds += hold(n, raw, version = version, fingerprint = null)
    }

    private fun counts(changedOnPhone: Int = 0, newOnPhone: Int = 0, deletedOnPhone: Int = 0, conflicts: Int = 0, unreadable: Int = 0) =
        ReportCounts(0, 0, 0, newOnPhone, changedOnPhone, deletedOnPhone, conflicts, false, false, unreadable, 0, 0)

    @Test
    fun `Use phone on an edit vs edit uploads what the phone shows, its re-fetch compared with the chosen fingerprint`() = runTest {
        unchanged()
        editVsEdit(1, version = 7)
        holdDao.holds += hold(1, 10, version = 7)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1", "C5 1", "C2 1"), calls)
        val (shown, remote, photo) = mapper.patched.single()
        assertEquals("Edited on the phone", shown.graph.formattedName)
        assertEquals(listOf("plain(data-1-1)"), remote.map { it.vcard }, "patched from the remote's cards, so remote-only properties stay")
        assertEquals(PhotoUpload.Keep, photo)
        assertEquals(ExistingRow(10, 7, clearDirty = true), phone.writes.single().existing, "written from the re-read at the VERSION recorded")
        assertEquals(listOf(stored(1, 10), map(9, 90)), maps())
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(changedOnPhone = 1), report.counts(), "counted as what it did")
    }

    @Test
    fun `a remote the re-fetch finds moved or gone since the choice sends nothing and is held again without the choice`() = runTest {
        unchanged()
        editVsEdit(1)
        holdDao.holds += hold(1, 10)
        api.refetches["proton-id-1"] = ArrayDeque(listOf(moved(1)))
        editVsEdit(2)
        holdDao.holds += hold(2, 20)
        api.refetches["proton-id-2"] = ArrayDeque(listOf(ProtonError.Api(422, 2501)))

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1", "C2 2"), calls, "nothing sent")
        assertEquals(emptyList<Any>(), phone.writes)
        assertEquals(
            listOf(hold(1, 10, fingerprint = contactFingerprint(ProtonContactId("proton-id-1"), moved(1).cards).value, choice = null), hold(2, 20, fingerprint = null, choice = null)),
            holds(),
        )
        assertEquals(counts(conflicts = 2), report.counts())
    }

    @Test
    fun `Use phone on a row edited before keys, or one without a map, uploads through the patch and keeps Proton's photo`() = runTest {
        // Rows written before keys: their photo baseline is unknown, so Proton's photo stays.
        unchanged()
        api.pages += contact(5)
        phone.rows += row(50, 5, dirty = true)
        mapDao.maps += map(5, 50, keyed = false).copy(photoBaseline = null)
        holdDao.holds += hold(5, 50, kind = HoldKind.EDITED_BEFORE_KEYS)
        api.pages += contact(7)
        phone.rows += row(70, 7, dirty = true)
        holdDao.holds += hold(7, 70)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 5", "C5 5", "C2 5", "C2 7", "C5 7", "C2 7"), calls)
        assertEquals(listOf(PhotoUpload.Keep, PhotoUpload.Keep), mapper.patched.map { it.third })
        assertEquals(emptyList<String>(), events.filter { it.startsWith("photo ") }, "the phone's photo isn't read")
        assertEquals(listOf(stored(5, 50), stored(7, 70), map(9, 90)), maps(), "each now keyed")
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(changedOnPhone = 2), report.counts())
    }

    @Test
    fun `Use phone on a row edited before keys that shows what Proton does only clears DIRTY, and the hold goes`() = runTest {
        unchanged()
        api.pages += contact(5)
        phone.rows += row(50, 5, dirty = true)
        phone.dataRowsByRaw[50] = dataRows(ContactGraph(uid = null, formattedName = "Contact proton-id-5"), null)
        // The phone's photo is never compared with an unknown baseline (v0.1.0 kept none).
        mapDao.maps += map(5, 50, keyed = false).copy(photoBaseline = null)
        holdDao.holds += hold(5, 50, kind = HoldKind.EDITED_BEFORE_KEYS)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 5"), calls, "nothing sent")
        assertEquals(listOf("proton-id-5" to ExistingRow(50, 1)), phone.cleared)
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(), report.counts())
    }

    @Test
    fun `a map without keys knows no photo thumbnail either, so a row showing it only has DIRTY cleared`() = runTest {
        // Like the photo baseline, the thumbnail's is unknown while rows_keyed is false.
        unchanged()
        api.pages += contact(5)
        phone.rows += row(50, 5, dirty = true)
        phone.dataRowsByRaw[50] = dataRows(ContactGraph(uid = null, formattedName = "Contact proton-id-5"), null)
        phone.photoHashes[50] = thumbnailHashOf(50)
        mapDao.maps += map(5, 50, keyed = false).copy(photoThumbnailBaseline = thumbnailHashOf(50))
        holdDao.holds += hold(5, 50, kind = HoldKind.EDITED_BEFORE_KEYS)

        engine().sync(account)

        assertEquals(listOf("proton-id-5" to ExistingRow(50, 1)), phone.cleared)
        assertEquals(emptyList<String>(), phone.writes.map { it.protonId }, "not written again from Proton")
    }

    @Test
    fun `a chosen upload without a map whose write the phone made stale keeps the new baseline, without keys`() = runTest {
        // The next edit uploads over what Proton now holds, its photo still unknown.
        unchanged()
        api.pages += contact(7)
        phone.rows += row(70, 7, dirty = true)
        holdDao.holds += hold(7, 70)
        phone.results["proton-id-7"] = ContactWriteResult.Stale

        engine().sync(account)

        assertEquals(listOf("C3 0", "C2 7", "C5 7", "C2 7"), calls)
        assertEquals(listOf(stored(7, 70).copy(rowsKeyed = false, photoBaseline = null, photoThumbnailBaseline = null), map(9, 90)), maps())
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
    }

    @Test
    fun `Use phone on a phone edit vs a Proton deletion clears SOURCE_ID, deletes the map and hold, then creates it with a fresh UID`() = runTest {
        unchanged()
        editVsDeletion(2, version = 3)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C4", "C2 101"), calls)
        assertEquals(listOf("proton-id-2" to ExistingRow(20, 3)), phone.detached)
        assertEquals(listOf("urn:uuid:test-1" to "Edited on the phone"), mapper.created.map { it.uid to it.phone.formattedName }, "what the row shows")
        val write = phone.writes.single()
        assertEquals("proton-id-101" to ExistingRow(20, 3, clearDirty = true, bind = true), write.protonId to write.existing, "guarded by the VERSION read")
        assertEquals(
            listOf(
                "read back 20", "photo 20", "detach proton-id-2", "map deleted proton-id-2", "hold deleted proton-id-2", "record 20",
                "apply proton-id-101", "read back 20", "map proton-id-101", "record deleted 20",
            ),
            events,
        )
        assertEquals(listOf(stored(101, 20), map(9, 90)), maps())
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(newOnPhone = 1), report.counts(), "counted as what it did")
    }

    @Test
    fun `a recreate the phone made stale drops the hold and keeps the map, and a create refused after detaching keeps no hold`() = runTest {
        unchanged()
        editVsDeletion(2)
        phone.detachResults["proton-id-2"] = ContactWriteResult.Stale
        editVsDeletion(3)
        api.createAnswers += CreateReply.CardRefused

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-2", "proton-id-3"), phone.detached.map { it.first })
        assertEquals(listOf("C3 0", "C4"), calls, "only the detached row is sent")
        assertEquals(listOf(map(2, 20), map(9, 90)), maps(), "the stale one's map stays for the next run")
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(listOf("proton-id-3" to NotSyncedReason.PROTON_REFUSED), report.refused.map { it.item.protonId?.value to it.reason })
        assertEquals(null, phone.rows.single { it.rawContactId == 30L }.sourceId, "new on the phone, so the next run creates it")
    }

    @Test
    fun `a crash between clearing SOURCE_ID and deleting the map leaves a map the next sync deletes, and no duplicate`() = runTest {
        unchanged()
        phone.rows += DeviceContact(20, null, dirty = true, deleted = false, version = 3, displayName = "Row 20")
        mapDao.maps += map(2, 20)
        holdDao.holds += hold(2, 20, version = 3, fingerprint = null)

        val dry = engine().dryRun(account)

        assertEquals(listOf(map(2, 20), map(9, 90)), maps(), "a dry run deletes nothing")
        assertEquals(counts(newOnPhone = 1), dry.counts())

        engine().sync(account)

        assertEquals(1, api.created.size)
        assertEquals(listOf(stored(101, 20), map(9, 90)), maps())
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
    }

    @Test
    fun `a Use phone taken back after planning sends nothing and stays held, unanswered`() = runTest {
        // A choice tapped while a run goes is read again just before it's carried out.
        unchanged()
        editVsEdit(1)
        holdDao.holds += hold(1, 10)
        editVsDeletion(2)
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true, deleted = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")
        holdDao.holds += hold(3, 30)
        api.pages += contact(5)
        phone.rows += row(50, 5, dirty = true)
        mapDao.maps += map(5, 50, keyed = false).copy(photoBaseline = null)
        holdDao.holds += hold(5, 50, kind = HoldKind.EDITED_BEFORE_KEYS)
        // Still chosen, so uploaded.
        editVsEdit(4)
        holdDao.holds += hold(4, 40)
        // Skip tapped on the others once the plan has read them.
        holdDao.onHolds = { read ->
            if (read == 1) holdDao.holds.replaceAll { if (it.protonId == "proton-id-4") it else it.copy(choice = null) }
        }

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 4", "C5 4", "C2 4"), calls, "only contact 4 re-fetched and sent")
        assertEquals(emptyList<Any>(), phone.detached)
        assertEquals(
            listOf(hold(1, 10, choice = null), hold(2, 20, fingerprint = null, choice = null), hold(3, 30, choice = null), hold(5, 50, kind = HoldKind.EDITED_BEFORE_KEYS, choice = null)),
            holds(),
        )
        assertEquals(counts(changedOnPhone = 1, conflicts = 4), report.counts(), "a row edited before keys among the conflicts")
    }

    @Test
    fun `Use phone on a phone deletion vs a Proton edit sends C6, compared with the chosen fingerprint, then the removal finishes`() = runTest {
        unchanged()
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true, deleted = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")
        holdDao.holds += hold(3, 30)
        // A tombstone without a map (an empty baseline), too.
        api.pages += contact(4)
        phone.rows += row(40, 4, dirty = true, deleted = true)
        holdDao.holds += hold(4, 40)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 3", "C6 3", "C2 4", "C6 4"), calls)
        assertEquals(listOf("proton-id-3" to 30L, "proton-id-4" to 40L), phone.removed)
        assertEquals(listOf(map(9, 90)), maps())
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(deletedOnPhone = 2), report.counts())
    }

    @Test
    fun `a chosen C6 whose re-fetch finds the remote moved sends nothing and is held again, and one gone only finishes the removal`() = runTest {
        unchanged()
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true, deleted = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")
        holdDao.holds += hold(3, 30)
        api.refetches["proton-id-3"] = ArrayDeque(listOf(moved(3)))
        api.pages += contact(4)
        phone.rows += row(40, 4, dirty = true, deleted = true)
        mapDao.maps += map(4, 40, fingerprint = "old-fingerprint")
        holdDao.holds += hold(4, 40)
        api.refetches["proton-id-4"] = ArrayDeque(listOf(ProtonError.Api(422, 2501)))

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 3", "C2 4"), calls, "no C6")
        assertEquals(listOf("proton-id-4" to 40L), phone.removed)
        assertEquals(listOf(map(3, 30, fingerprint = "old-fingerprint"), map(9, 90)), maps())
        assertEquals(listOf(hold(3, 30, fingerprint = contactFingerprint(ProtonContactId("proton-id-3"), moved(3).cards).value, choice = null)), holds())
        assertEquals(counts(deletedOnPhone = 1, conflicts = 1), report.counts())
    }

    @Test
    fun `a Use phone recorded gone is never recreated after an incomplete inventory`() = runTest {
        // Its absence may only mean the inventory missed it: recreating would make a second contact on Proton.
        unchanged()
        api.repeatFirstPage = true
        editVsDeletion(2)

        engine().sync(account)

        assertEquals(emptyList<Any>(), phone.detached)
        assertEquals(emptyList<List<CardDto>>(), api.created)
        assertEquals(listOf(hold(2, 20, fingerprint = null)), holds(), "kept with its choice")
    }

    @Test
    fun `Use phone on a clean row with an empty baseline gone from Proton recreates it the same way`() = runTest {
        unchanged()
        phone.rows += row(60, 6)
        mapDao.maps += map(6, 60, fingerprint = null)
        holdDao.holds += hold(6, 60, fingerprint = null)

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-6" to ExistingRow(60, 1)), phone.detached)
        assertEquals(listOf("C3 0", "C4", "C2 101"), calls)
        assertEquals(listOf(stored(101, 60), map(9, 90)), maps())
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(newOnPhone = 1), report.counts())
    }

    @Test
    fun `a chosen upload or recreate whose row holds rows the app can't read back sends nothing and keeps its hold and choice`() = runTest {
        unchanged()
        editVsEdit(1)
        holdDao.holds += hold(1, 10)
        editVsDeletion(2)
        // A second note, which only another app writes (docs/mapping.md § Reading rows back).
        val notes = listOf("one", "two").map { DataRow(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to ColumnValue.Text(it))) }
        phone.dataRowsByRaw[10] = notes
        phone.dataRowsByRaw[20] = notes
        val stored = holds()

        val report = engine().sync(account)

        assertEquals(listOf("C3 0"), calls)
        assertEquals(emptyList<Any>(), phone.detached)
        assertEquals(stored, holds())
        assertEquals(counts(unreadable = 2), report.counts())
    }

    @Test
    fun `a chosen C6 waits under the phone-deletion guard, its choice kept, until the user confirms the count`() = runTest {
        for (n in 1..10) {
            api.pages += contact(n)
            phone.rows += row(n * 10L, n, dirty = true, deleted = true)
            mapDao.maps += map(n, n * 10L)
        }
        api.pages += contact(11)
        phone.rows += row(110, 11, dirty = true, deleted = true)
        mapDao.maps += map(11, 110, fingerprint = "old-fingerprint")
        holdDao.holds += hold(11, 110)

        engine().sync(account)

        assertEquals(listOf("C3 0"), calls, "nothing sent")
        assertEquals(listOf(hold(11, 110)), holds())

        engine().sync(account, confirmedPhoneDeletions = 11)

        assertEquals(11, api.deleted.size)
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
    }

    @Test
    fun `a chosen upload Proton refuses keeps the hold and its choice`() = runTest {
        unchanged()
        editVsEdit(1)
        holdDao.holds += hold(1, 10)
        api.updateFailures["proton-id-1"] = ProtonError.Api(422, 2001)

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-1" to NotSyncedReason.PROTON_REFUSED), report.refused.map { it.item.protonId?.value to it.reason })
        assertEquals(counts(unreadable = 1), report.counts())
        assertEquals(listOf(hold(1, 10)), holds())
    }

    @Test
    fun `a dry run plans and counts each stored Use phone as a sync would, and sends and writes nothing`() = runTest {
        unchanged()
        editVsEdit(1)
        holdDao.holds += hold(1, 10)
        editVsDeletion(2)
        // A clean row with an empty baseline gone from Proton is recreated the same way.
        phone.rows += row(60, 6)
        mapDao.maps += map(6, 60, fingerprint = null)
        holdDao.holds += hold(6, 60, fingerprint = null)
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true, deleted = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")
        holdDao.holds += hold(3, 30)
        val stored = holds()

        val report = engine().dryRun(account)

        assertEquals(counts(changedOnPhone = 1, newOnPhone = 2, deletedOnPhone = 1), report.counts())
        assertEquals(listOf("C3 0"), calls)
        assertEquals(emptyList<Any>(), phone.detached)
        assertEquals(emptyList<Any>(), phone.writes)
        assertEquals(stored, holds())
    }
}
