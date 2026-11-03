package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.HvDetails
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.PendingContactCreateEntity
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.ReportCounts
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.FailureReason
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Phone deletions: docs/sync.md § Writes (Delete, Unknown outcome) and § Deletion. Every value is made up for the test.
class SyncDeleteTest : EngineHarness() {
    private fun fingerprint(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), contact(n).cards).value
    private fun tombstone(raw: Long, n: Int) = DeviceContact(raw, "proton-id-$n", dirty = true, deleted = true, version = 1, displayName = "Deleted $raw")
    private fun live(raw: Long, n: Int) = DeviceContact(raw, "proton-id-$n", dirty = false, deleted = false, version = 1, displayName = "Kept $raw")
    private fun map(n: Int, raw: Long, fingerprint: String = fingerprint(n)) =
        ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint, rowsKeyed = true, photoBaseline = photoHashOf(raw))

    /** Contact [n] on Proton, its row [raw] deleted on the phone, mapped at its fingerprint. */
    private fun deletedOnPhone(n: Int, raw: Long = n * 10L) {
        api.pages += contact(n)
        phone.rows += tombstone(raw, n)
        mapDao.maps += map(n, raw)
    }

    /** Contact [n] on Proton and on the phone, unchanged on both sides. */
    private fun unchanged(n: Int, raw: Long = n * 10L) {
        api.pages += contact(n)
        phone.rows += live(raw, n)
        mapDao.maps += map(n, raw)
    }

    private fun counts(deletedOnPhone: Int = 0, conflicts: Int = 0, unreadable: Int = 0, bulkGuardPhone: Boolean = false) =
        ReportCounts(0, 0, 0, 0, 0, deletedOnPhone, conflicts, false, bulkGuardPhone, unreadable, 0, 0)

    private fun reset() {
        calls.clear(); events.clear(); api.pages.clear(); api.refetches.clear(); api.deleted.clear(); api.deleteFailures.clear()
        phone.rows.clear(); phone.writes.clear(); phone.removed.clear(); phone.removeResults.clear(); mapDao.maps.clear()
    }

    @Test
    fun `a tombstone whose remote is unchanged is re-fetched, deleted once, then removed and its map deleted`() = runTest {
        deletedOnPhone(1)
        unchanged(2)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1", "C6 1"), calls)
        assertEquals(listOf("proton-id-1" to 10L), phone.removed, "as the sync adapter, so no tombstone is left")
        assertEquals(listOf("remove proton-id-1", "map deleted proton-id-1"), events, "the tombstone, then its map")
        assertEquals(listOf(map(2, 20)), mapDao.maps)
        assertEquals(counts(deletedOnPhone = 1), report.counts())
        assertTrue(report.ready)
        assertEquals(1, keys.unlocks, "the inventory's keys only: a deletion needs none")
    }

    @Test
    fun `a tombstone whose remote moved since the baseline is a conflict, and nothing is sent`() = runTest {
        api.pages += contact(1)
        phone.rows += tombstone(10, 1)
        mapDao.maps += map(1, 10, "old-fingerprint")

        val report = engine().sync(account)

        assertEquals(listOf("C3 0"), calls)
        assertEquals(emptyList<Any>(), phone.removed)
        assertEquals(listOf(map(1, 10, "old-fingerprint")), mapDao.maps)
        assertEquals(counts(conflicts = 1), report.counts())
    }

    @Test
    fun `a re-fetch whose stored cards moved since the baseline is a conflict, and nothing is sent`() = runTest {
        deletedOnPhone(1)
        api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1).copy(cards = listOf(CardDto(1, "data-1-1-moved")))))

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1"), calls)
        assertEquals(emptyList<Any>(), phone.removed)
        assertEquals(listOf(map(1, 10)), mapDao.maps)
        assertEquals(listOf(PlanCategory.CONFLICT), report.reclassified.map { it.category })
        assertEquals(counts(conflicts = 1), report.counts())
    }

    @Test
    fun `an upload-only sync deletes as a full sync does, comparing the stored cards without keys`() = runTest {
        // The re-fetch is compared by its fingerprint alone, so a contact the keys couldn't read is deleted too.
        deletedOnPhone(1)
        crypto.results["proton-id-1"] = CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)

        val report = engine().upload(account)

        assertEquals(listOf("C2 1", "C6 1"), calls, "no inventory")
        assertEquals(listOf("remove proton-id-1", "map deleted proton-id-1"), events)
        assertEquals(1, keys.unlocks, "only to check them, a login may be needed")
        assertTrue(keys.closed)
        assertEquals(0, crypto.decrypted)
        assertEquals(ReportTrigger.PHONE_CHANGES to 1, reportDao.saved.single().let { it.trigger to it.deletedOnPhone }, "the phone's side stored")
        assertTrue(report.ready)
    }

    @Test
    fun `a contact already gone when it's re-fetched is removed without a C6`() = runTest {
        // In the inventory, gone by the re-fetch.
        deletedOnPhone(1)
        api.refetches["proton-id-1"] = ArrayDeque(listOf(ProtonError.Api(422, 2501)))

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1"), calls)
        assertEquals(listOf("remove proton-id-1", "map deleted proton-id-1"), events)
        assertEquals(counts(deletedOnPhone = 1), report.counts())

        // Never seen by an upload-only sync.
        reset()
        phone.rows += tombstone(10, 1)
        mapDao.maps += map(1, 10)

        engine().upload(account)

        assertEquals(listOf("C2 1"), calls)
        assertEquals(listOf("remove proton-id-1", "map deleted proton-id-1"), events)
    }

    @Test
    fun `a C6 whose outcome is unknown keeps the tombstone, and the next run's re-fetch decides`() = runTest {
        for (landed in listOf(true, false)) {
            reset()
            deletedOnPhone(1)
            deletedOnPhone(2)
            api.deleteFailures["proton-id-1"] = ProtonError.Network()

            val first = engine().sync(account)

            assertEquals(listOf("C3 0", "C2 1", "C6 1"), calls, "nothing more sent")
            assertEquals(emptyList<Any>(), phone.removed)
            assertEquals(listOf(map(1, 10), map(2, 20)), mapDao.maps)
            assertEquals(Blocker.PROTON_UNAVAILABLE, first.blocker)
            assertTrue(first.retryable)

            calls.clear()
            api.deleteFailures.clear()
            if (landed) api.pages.removeAll { it.id == "proton-id-1" }

            engine().upload(account)

            assertEquals(if (landed) listOf("C2 1", "C2 2", "C6 2") else listOf("C2 1", "C6 1", "C2 2", "C6 2"), calls, "landed: $landed")
            assertEquals(listOf("proton-id-1" to 10L, "proton-id-2" to 20L), phone.removed)
            assertEquals(emptyList<Any>(), mapDao.maps)
        }
    }

    @Test
    fun `an error about the contact counts it as can't be synced, its tombstone and map kept, and the next one is sent`() = runTest {
        (1..7).forEach { deletedOnPhone(it) }
        api.refetches["proton-id-1"] = ArrayDeque(listOf(ProtonError.Api(400, 2061)))
        // An item Code other than 1000.
        api.deleteFailures["proton-id-2"] = ProtonError.Api(200, 2001)
        api.refetches["proton-id-3"] = ArrayDeque(listOf(ProtonError.InvalidRequest()))
        api.deleteFailures["proton-id-5"] = ProtonError.Api(400, 2061)
        api.deleteFailures["proton-id-6"] = ProtonError.InvalidRequest()
        // A C6 item answering 2501 is still another item Code, not a conflict.
        api.deleteFailures["proton-id-7"] = ProtonError.Api(200, 2501)

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-2", "proton-id-4", "proton-id-5", "proton-id-6", "proton-id-7"), api.deleted)
        assertEquals(listOf("proton-id-4" to 40L), phone.removed)
        assertEquals(listOf(1, 2, 3, 5, 6, 7).map { map(it, it * 10L) }, mapDao.maps)
        assertEquals(
            listOf(1, 2, 3, 5, 6, 7).map { "proton-id-$it" to NotSyncedReason.PROTON_REFUSED },
            report.refused.map { it.item.protonId?.value to it.reason },
        )
        assertEquals(emptyList<Any>(), report.reclassified)
        assertEquals(counts(deletedOnPhone = 1, unreadable = 6), report.counts())
        assertTrue(report.ready)
    }

    @Test
    fun `a failure that isn't about the contact stops sending, keeps the tombstone, and the downloads still apply`() = runTest {
        val cases = listOf(
            ProtonError.Network() to Blocker.PROTON_UNAVAILABLE,
            ProtonError.Api(503, null) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.BadResponse(200) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.RateLimited(null) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.SessionRejected() to Blocker.SESSION_EXPIRED,
            ProtonError.HumanVerification(HvDetails("token", "https://example.test", listOf("captcha"))) to Blocker.CAPTCHA,
            ProtonError.AppVersionRejected(5003) to Blocker.APP_VERSION_REJECTED,
        )
        for ((failure, blocker) in cases) {
            for (onDelete in listOf(false, true)) {
                reset()
                deletedOnPhone(1)
                deletedOnPhone(2)
                api.pages += contact(3)
                if (onDelete) api.deleteFailures["proton-id-1"] = failure else api.refetches["proton-id-1"] = ArrayDeque(listOf(failure))

                val report = engine().sync(account)

                val what = "$failure on ${if (onDelete) "C6" else "the re-fetch"}"
                assertEquals(if (onDelete) listOf("C3 0", "C2 1", "C6 1") else listOf("C3 0", "C2 1"), calls, what)
                assertEquals(emptyList<Any>(), phone.removed, what)
                assertEquals(listOf("proton-id-3"), phone.writes.map { it.protonId }, "downloaded: $what")
                assertTrue(mapDao.maps.containsAll(listOf(map(1, 10), map(2, 20))), what)
                assertEquals(blocker, report.blocker, what)
            }
        }
    }

    @Test
    fun `a tombstone gone by the time it's removed still has its map deleted`() = runTest {
        deletedOnPhone(1)
        phone.removeResults["proton-id-1"] = ContactWriteResult.Stale

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-1"), api.deleted)
        assertEquals(emptyList<Any>(), mapDao.maps)
        assertEquals(counts(deletedOnPhone = 1), report.counts())
    }

    @Test
    fun `a removal refused while its guard holds stops the run as contacts hidden, and one refused otherwise is counted`() = runTest {
        deletedOnPhone(1)
        deletedOnPhone(2)
        api.pages += contact(3)
        phone.removeResults["proton-id-1"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        val hidden = engine().sync(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, hidden.blocker)
        assertEquals(listOf("C3 0", "C2 1", "C6 1"), calls, "nothing more sent")
        assertEquals(listOf(map(1, 10), map(2, 20)), mapDao.maps)
        assertEquals(emptyList<Any>(), phone.writes, "nothing more written, downloads included")

        reset()
        deletedOnPhone(1)
        deletedOnPhone(2)
        phone.removeResults["proton-id-1"] = ContactWriteResult.Failed(FailureReason.TOO_LARGE)

        val refused = engine().sync(account)

        assertEquals(listOf("proton-id-1", "proton-id-2"), api.deleted)
        assertEquals(listOf(map(1, 10)), mapDao.maps, "the next run finds it deleted on both sides")
        assertEquals(listOf("proton-id-1" to NotSyncedReason.WRITE_TOO_LARGE), refused.refused.map { it.item.protonId?.value to it.reason })
        assertEquals(counts(deletedOnPhone = 1, unreadable = 1), refused.counts())
        assertTrue(refused.ready)
    }

    @Test
    fun `a run cancelled once C6 is sent still removes the tombstone and deletes its map`() = runTest {
        deletedOnPhone(1)
        deletedOnPhone(2)
        lateinit var run: Job
        api.onDelete = { run.cancel() }

        run = launch { engine().sync(account) }
        run.join()

        assertTrue(run.isCancelled)
        assertEquals(listOf("proton-id-1" to 10L), phone.removed)
        assertEquals(listOf(map(2, 20)), mapDao.maps)
        assertFalse("C2 2" in calls, "nothing more")
    }

    @Test
    fun `the edits upload first, then the creates, then the deletions, then deleted rows' records are looked up`() = runTest {
        // The tombstone comes first in the plan.
        deletedOnPhone(1)
        api.pages += contact(2)
        phone.rows += DeviceContact(20, "proton-id-2", dirty = true, deleted = false, version = 1, displayName = "Edited")
        mapDao.maps += map(2, 20)
        phone.rows += DeviceContact(30, null, dirty = true, deleted = false, version = 1, displayName = "Made here")
        phone.dataRowsByRaw[30] = dataRows(ContactGraph(uid = null, formattedName = "Made here"), null)
        pending.records += PendingContactCreateEntity("user-id-1", 40, "urn:uuid:gone-row", 1)

        engine().sync(account)

        assertEquals(listOf("C2 2", "C5 2", "C2 2", "C4", "C2 101", "C2 1", "C6 1", "C1 0"), calls.filterNot { it.startsWith("C3") })
    }

    @Test
    fun `the bulk guard stops every re-fetch and C6`() = runTest {
        // 10 tombstones, 3 of them conflicts; 4 of 20 mapped; 10 in an upload-only sync, which has no inventory.
        for ((tombstones, mapped, uploadOnly) in listOf(Triple(10, 10, false), Triple(4, 20, false), Triple(10, 10, true))) {
            reset()
            (1..tombstones).forEach { deletedOnPhone(it) }
            if (!uploadOnly) (1..3).forEach { n -> mapDao.maps.replaceAll { if (it.protonId == "proton-id-$n" && tombstones == 10) map(n, n * 10L, "old-fingerprint") else it } }
            ((tombstones + 1)..mapped).forEach { unchanged(it) }
            val maps = mapDao.maps.toList()

            val report = if (uploadOnly) engine().upload(account) else engine().sync(account)

            val what = "$tombstones of $mapped, upload-only: $uploadOnly"
            assertEquals(if (uploadOnly) emptyList() else listOf("C3 0"), calls, what)
            assertEquals(emptyList<Any>(), phone.removed, what)
            assertEquals(maps, mapDao.maps, what)
            assertTrue(report.counts().bulkGuardPhone, what)
            assertTrue(report.plan!!.items.filter { it.category == PlanCategory.DELETED_ON_PHONE }.none { it.carriedOut }, what)
        }
    }

    @Test
    fun `while the bulk guard stops phone deletions, an edit in the same run is still sent`() = runTest {
        (1..10).forEach { deletedOnPhone(it) }
        api.pages += contact(11)
        phone.rows += DeviceContact(110, "proton-id-11", dirty = true, deleted = false, version = 1, displayName = "Edited")
        mapDao.maps += map(11, 110)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 11", "C5 11", "C2 11"), calls)
        assertEquals(emptyList<Any>(), phone.removed)
        assertTrue(report.counts().bulkGuardPhone)
    }

    @Test
    fun `a confirmed sync sends the plain deletions still waiting, and stops again when more are`() = runTest {
        // The confirmation isn't stored; the run counts again.
        (1..10).forEach { deletedOnPhone(it) }
        (1..3).forEach { n -> mapDao.maps.replaceAll { if (it.protonId == "proton-id-$n") map(n, n * 10L, "old-fingerprint") else it } }

        val confirmed = engine().sync(account, confirmedPhoneDeletions = 10)

        assertEquals((4..10).map { "proton-id-$it" }, api.deleted, "conflicts stay held")
        assertEquals(counts(deletedOnPhone = 7, conflicts = 3), confirmed.counts(), "the guard no longer stops")
        assertEquals(10, confirmed.plan!!.phoneDeletionsWaiting)

        reset()
        (1..11).forEach { deletedOnPhone(it) }

        val more = engine().sync(account, confirmedPhoneDeletions = 10)

        assertEquals(emptyList<String>(), api.deleted)
        assertEquals(listOf("C3 0"), calls)
        assertTrue(more.counts().bulkGuardPhone)
        assertEquals(11, more.plan!!.phoneDeletionsWaiting, "asked again with the new count")
    }

    @Test
    fun `fewer phone deletions than the guard counts are sent`() = runTest {
        // 9 tombstones; 3 of 20 mapped.
        for ((tombstones, mapped) in listOf(9 to 9, 3 to 20)) {
            reset()
            (1..tombstones).forEach { deletedOnPhone(it) }
            ((tombstones + 1)..mapped).forEach { unchanged(it) }

            val report = engine().sync(account)

            assertEquals((1..tombstones).map { "proton-id-$it" }, api.deleted, "$tombstones of $mapped")
            assertEquals(counts(deletedOnPhone = tombstones), report.counts())
        }
    }

    @Test
    fun `a tombstone deleted on both sides has its removal finished, with no request, and isn't counted`() = runTest {
        phone.rows += listOf(tombstone(10, 1), tombstone(20, 2))
        mapDao.maps += map(1, 10)
        unchanged(3)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0"), calls)
        assertEquals(listOf("proton-id-1" to 10L, "proton-id-2" to 20L), phone.removed, "with a map or without")
        assertEquals(listOf(map(3, 30)), mapDao.maps)
        assertEquals(counts(), report.counts())
        assertTrue(report.ready)

        // A dry run leaves it.
        reset()
        phone.rows += tombstone(10, 1)
        mapDao.maps += map(1, 10)
        unchanged(3)

        engine().dryRun(account)

        assertEquals(emptyList<Any>(), phone.removed)
        assertEquals(listOf(map(1, 10), map(3, 30)), mapDao.maps)

        // The bulk guard doesn't stop it: nothing is sent.
        reset()
        (1..10).forEach { deletedOnPhone(it) }
        phone.rows += tombstone(110, 11)
        mapDao.maps += map(11, 110)

        val guarded = engine().sync(account)

        assertTrue(guarded.counts().bulkGuardPhone)
        assertEquals(listOf("proton-id-11" to 110L), phone.removed)
    }

    @Test
    fun `a removal deleted on both sides that's refused is counted, or stops the run while its guard holds`() = runTest {
        phone.rows += listOf(tombstone(10, 1), tombstone(20, 2))
        mapDao.maps += listOf(map(1, 10), map(2, 20))
        unchanged(3)
        phone.removeResults["proton-id-1"] = ContactWriteResult.Failed(FailureReason.TOO_LARGE)

        val refused = engine().sync(account)

        assertEquals(listOf("proton-id-1" to NotSyncedReason.WRITE_TOO_LARGE), refused.refused.map { it.item.protonId?.value to it.reason })
        assertEquals(counts(unreadable = 1), refused.counts(), "not counted until refused")
        assertEquals(listOf(map(1, 10), map(3, 30)), mapDao.maps, "the next run finishes it")

        reset()
        phone.rows += listOf(tombstone(10, 1), tombstone(20, 2))
        mapDao.maps += listOf(map(1, 10), map(2, 20))
        unchanged(3)
        phone.removeResults["proton-id-1"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        val hidden = engine().sync(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, hidden.blocker)
        assertEquals(listOf("proton-id-1" to 10L), phone.removed, "nothing more written")
        assertEquals(listOf(map(1, 10), map(2, 20), map(3, 30)), mapDao.maps)
    }

    @Test
    fun `a tombstone left out of an incomplete inventory is re-fetched and deleted`() = runTest {
        // The inventory stops after its first page (it repeats), so contact 2 isn't in it.
        api.pages += contact(1)
        phone.rows += live(10, 1)
        mapDao.maps += map(1, 10)
        api.repeatFirstPage = true
        phone.rows += tombstone(20, 2)
        mapDao.maps += map(2, 20)
        api.refetches["proton-id-2"] = ArrayDeque(listOf(contact(2)))

        val report = engine().sync(account)

        assertEquals(Blocker.INVENTORY_INCOMPLETE, report.blocker)
        assertEquals(listOf("C2 2", "C6 2"), calls.filterNot { it.startsWith("C3") })
        assertEquals(listOf("proton-id-2" to 20L), phone.removed)
        assertEquals(listOf(map(1, 10)), mapDao.maps)
    }

    @Test
    fun `a tombstone deleted on both sides that's already gone still has its map deleted`() = runTest {
        phone.rows += tombstone(10, 1)
        mapDao.maps += map(1, 10)
        unchanged(2)
        phone.removeResults["proton-id-1"] = ContactWriteResult.Stale

        engine().sync(account)

        assertEquals(listOf(map(2, 20)), mapDao.maps)
    }

    @Test
    fun `while the guard stops deletions on the phone, a tombstone deleted on both sides and a stale map wait`() = runTest {
        // An empty inventory while mapped rows exist: it may be wrong.
        phone.rows += listOf(live(10, 1), tombstone(20, 2))
        mapDao.maps += listOf(map(1, 10), map(2, 20), map(5, 50))

        val report = engine().sync(account)

        assertTrue(report.counts().bulkGuardRemote)
        assertEquals(emptyList<Any>(), phone.removed)
        assertEquals(listOf(map(1, 10), map(2, 20), map(5, 50)), mapDao.maps)
    }

    @Test
    fun `a tombstone of a contact that can't be read, unchanged on Proton, is deleted in a full sync too`() = runTest {
        // An upload-only sync never decrypts, so both modes agree.
        deletedOnPhone(1)
        crypto.results["proton-id-1"] = CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1", "C6 1"), calls)
        assertEquals(listOf("proton-id-1" to 10L), phone.removed)
        assertEquals(counts(deletedOnPhone = 1), report.counts())
    }

    @Test
    fun `a map with nothing left to pair is deleted by a real sync after a complete inventory`() = runTest {
        // A crash between a removal and its map's deletion leaves one.
        mapDao.maps += map(5, 50)
        api.pages += contact(1)
        phone.rows += live(10, 1)

        engine().sync(account)

        assertEquals(listOf("repairs [proton-id-1]", "map deleted proton-id-5"), events.take(2), "with the map repairs, before any write")
        assertEquals(listOf("proton-id-1"), mapDao.maps.map { it.protonId })

        // Kept by a dry run, an upload-only sync, and after an incomplete inventory.
        for (run in listOf("dry run", "upload-only", "incomplete")) {
            reset()
            mapDao.maps += listOf(map(1, 10), map(5, 50))
            api.pages += contact(1)
            phone.rows += live(10, 1)
            api.repeatFirstPage = run == "incomplete"

            when (run) {
                "dry run" -> engine().dryRun(account)
                "upload-only" -> engine().upload(account)
                else -> engine().sync(account)
            }

            assertEquals(listOf(map(1, 10), map(5, 50)), mapDao.maps, run)
        }
    }

    @Test
    fun `a dry run, offline, or after a failed inventory request, sends no C6 and removes nothing`() = runTest {
        deletedOnPhone(1)

        engine().dryRun(account)
        assertEquals(listOf("C3 0"), calls, "a dry run")

        calls.clear()
        online = false
        engine().sync(account)
        assertEquals(emptyList<String>(), calls, "offline")

        online = true
        api.pages.clear()
        api.failure = ProtonError.Network()
        engine().sync(account)
        assertEquals(listOf("C3 0"), calls, "a failed inventory request")

        assertEquals(emptyList<Any>(), phone.removed)
        assertEquals(listOf(map(1, 10)), mapDao.maps)
    }

    @Test
    fun `a mapped row gone without a tombstone is held as a missing row, never deleted on Proton`() = runTest {
        api.pages += contact(1)
        mapDao.maps += map(1, 10)
        unchanged(2)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0"), calls)
        assertEquals(1, report.counts().missingRows)
        assertEquals(listOf(map(1, 10), map(2, 20)), mapDao.maps)
    }
}
