package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.FailureReason
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Holding conflicts: docs/sync.md § Conflicts. Every value is made up for the test.
class SyncHoldTest : EngineHarness() {
    private fun fingerprint(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), contact(n).cards).value
    private fun row(raw: Long, n: Int, dirty: Boolean = false, deleted: Boolean = false) =
        DeviceContact(raw, "proton-id-$n", dirty = dirty, deleted = deleted, version = 1, displayName = "Row $raw")
    private fun map(n: Int, raw: Long, fingerprint: String? = fingerprint(n), keyed: Boolean = true) =
        ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint, rowsKeyed = keyed, photoBaseline = photoHashOf(raw))
    private fun hold(n: Int, raw: Long, kind: HoldKind = HoldKind.CONFLICT, account: String = "user-id-1") =
        ConflictHoldEntity(account, "proton-id-$n", raw, kind, choice = null, version = null, fingerprint = null)
    private fun moved(n: Int) = contact(n).copy(cards = listOf(CardDto(1, "data-$n-1-moved")))

    /** The account's holds, by Proton ID, without the state they record, which SyncChoiceTest checks. */
    private fun holds() = holdDao.holds.filter { it.accountId == "user-id-1" }.sortedBy { it.protonId }.map { it.copy(version = null, fingerprint = null) }

    /** Contact [n] on Proton and on the phone, unchanged on both sides. */
    private fun unchanged(n: Int, raw: Long = n * 10L) {
        api.pages += contact(n)
        phone.rows += row(raw, n)
        mapDao.maps += map(n, raw)
    }

    /** Contact [n] edited on the phone only, its row [raw] keyed. */
    private fun edited(n: Int, raw: Long = n * 10L) {
        api.pages += contact(n)
        phone.rows += row(raw, n, dirty = true)
        mapDao.maps += map(n, raw)
    }

    /** One conflict of each kind the plan finds, contacts 1 to 5, and contact 9 unchanged. */
    private fun planConflicts() {
        unchanged(9)
        // Edit vs edit, with different shown values.
        api.pages += contact(1)
        phone.rows += row(10, 1, dirty = true)
        mapDao.maps += map(1, 10, fingerprint = "old-fingerprint")
        // A phone edit vs a Proton deletion.
        phone.rows += row(20, 2, dirty = true)
        mapDao.maps += map(2, 20)
        // A phone deletion vs a Proton edit.
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true, deleted = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")
        // A dirty row with an empty baseline, and a tombstone without a map.
        api.pages += contact(4)
        phone.rows += row(40, 4, dirty = true)
        mapDao.maps += map(4, 40, fingerprint = null)
        api.pages += contact(5)
        phone.rows += row(50, 5, dirty = true, deleted = true)
        // A clean row with an empty baseline whose contact the inventory left out.
        phone.rows += row(130, 13)
        mapDao.maps += map(13, 130, fingerprint = null)
    }

    /** The holds [planConflicts] gives, and [more]. */
    private fun planned(vararg more: ConflictHoldEntity) = ((1..5).map { hold(it, it * 10L) } + hold(13, 130) + more).sortedBy { it.protonId }

    private fun reset() {
        calls.clear(); events.clear(); api.pages.clear(); api.refetches.clear(); api.repeatFirstPage = false; api.failure = null
        phone.rows.clear(); phone.writes.clear(); mapDao.maps.clear(); holdDao.holds.clear(); online = true
    }

    @Test
    fun `each conflict a sync's plan finds is held, naming its row, and nothing is uploaded or overwritten`() = runTest {
        planConflicts()

        val report = engine().sync(account)

        assertEquals(planned(), holds())
        assertEquals(listOf("C3 0"), calls, "nothing sent")
        assertEquals(emptyList<Any>(), phone.writes, "nothing overwritten")
        assertEquals(emptyList<Any>(), phone.rowsRemoved)
        assertEquals(6, report.counts().conflicts)
    }

    @Test
    fun `a dirty row edited before keys and a missing row are held with their own kinds`() = runTest {
        unchanged(9)
        api.pages += contact(1)
        phone.rows += row(10, 1, dirty = true)
        mapDao.maps += map(1, 10, keyed = false)
        // A mapped row gone without a tombstone: held for the row its map names.
        api.pages += contact(2)
        mapDao.maps += map(2, 20)

        val report = engine().sync(account)

        assertEquals(listOf(hold(1, 10, HoldKind.EDITED_BEFORE_KEYS), hold(2, 20, HoldKind.MISSING_ROW)), holds())
        assertEquals(listOf("C3 0"), calls, "never a C6")
        assertEquals(1, report.counts().conflicts)
        assertEquals(1, report.counts().missingRows)
    }

    @Test
    fun `a conflict a re-fetch finds is held by the run that found it`() = runTest {
        unchanged(9)
        // An edit whose remote moved after the inventory, one gone on re-fetch, a phone deletion whose remote moved.
        edited(1)
        api.refetches["proton-id-1"] = ArrayDeque(listOf(moved(1)))
        edited(2)
        api.refetches["proton-id-2"] = ArrayDeque(listOf(ProtonError.Api(422, 2501)))
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true, deleted = true)
        mapDao.maps += map(3, 30)
        api.refetches["proton-id-3"] = ArrayDeque(listOf(moved(3)))

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1", "C2 2", "C2 3"), calls, "nothing sent")
        assertEquals(listOf(hold(1, 10), hold(2, 20), hold(3, 30)), holds())
        assertEquals(3, report.counts().conflicts)
    }

    @Test
    fun `an upload-only sync holds what its re-fetches find, and its plan's conflicts`() = runTest {
        unchanged(9)
        edited(1)
        api.refetches["proton-id-1"] = ArrayDeque(listOf(moved(1)))
        // A tombstone without a map: an empty baseline.
        phone.rows += row(50, 5, dirty = true, deleted = true)

        engine().upload(account)

        assertEquals(listOf(hold(1, 10), hold(5, 50)), holds())
    }

    @Test
    fun `deleted on both sides, duplicates, unreadable contacts, refusals, and settled edits are never held`() = runTest {
        unchanged(9)
        // Deleted on both sides.
        phone.rows += row(10, 1, deleted = true)
        mapDao.maps += map(1, 10)
        // Duplicate rows.
        api.pages += contact(2)
        phone.rows += listOf(row(20, 2), row(21, 2))
        // An unreadable contact whose row was edited.
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")
        crypto.results["proton-id-3"] = CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)
        // An upload Proton refuses, and a download the phone refuses.
        edited(4)
        api.updateFailures["proton-id-4"] = ProtonError.Api(400, 2061)
        api.pages += contact(5)
        phone.rows += row(50, 5)
        mapDao.maps += map(5, 50, fingerprint = "old-fingerprint")
        phone.results["proton-id-5"] = ContactWriteResult.Failed(FailureReason.TOO_LARGE)
        // An edit whose re-fetch shows the remote moved to what the phone shows: settled, not a conflict.
        edited(6)
        api.refetches["proton-id-6"] = ArrayDeque(listOf(moved(6)))
        mapper.graphs["plain(data-6-1-moved)"] = ContactGraph(uid = null, formattedName = "Edited on the phone")

        val report = engine().sync(account)

        assertEquals(3, report.refused.size + report.counts().duplicateRows, "refused twice, one duplicate")
        assertEquals(listOf(PlanCategory.SETTLED_REMOTE_CHANGED), report.reclassified.map { it.category })
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
    }

    @Test
    fun `a hold found again stays one row, with this run's kind and row`() = runTest {
        holdDao.holds += hold(1, 99, HoldKind.MISSING_ROW)
        holdDao.holds += hold(1, 10, account = "user-id-2")
        unchanged(9)
        api.pages += contact(1)
        phone.rows += row(10, 1, dirty = true)
        mapDao.maps += map(1, 10, fingerprint = "old-fingerprint")

        engine().sync(account)

        assertEquals(listOf(hold(1, 10)), holds())
        assertEquals(listOf("hold proton-id-1"), events.filter { it.startsWith("hold") }, "replaced, never deleted first: a run stopped in between would lose it")
        assertEquals(listOf(hold(1, 10, account = "user-id-2")), holdDao.holds.filter { it.accountId == "user-id-2" }, "another account's are left")
    }

    @Test
    fun `a sync after a complete inventory removes the holds it no longer finds`() = runTest {
        // Contact 1 settled meanwhile, contact 2 is gone from both sides, contact 3 is still held.
        holdDao.holds += listOf(hold(1, 10), hold(2, 20, HoldKind.MISSING_ROW), hold(3, 30))
        unchanged(1)
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")

        engine().sync(account)

        assertEquals(listOf(hold(3, 30)), holds())
    }

    @Test
    fun `after an incomplete inventory a sync keeps the holds of contacts it left out`() = runTest {
        for (run in listOf("incomplete", "offline", "failed request")) {
            reset()
            // Contact 1 is in the inventory and no longer held; contact 2 isn't, so it may still be.
            holdDao.holds += listOf(hold(1, 10), hold(2, 20))
            unchanged(1)
            phone.rows += row(20, 2)
            mapDao.maps += map(2, 20)
            api.repeatFirstPage = run == "incomplete"
            online = run != "offline"
            if (run == "failed request") {
                api.pages.clear()
                api.failure = ProtonError.Network()
            }

            engine().sync(account)

            val expected = if (run == "incomplete") listOf(hold(2, 20)) else listOf(hold(1, 10), hold(2, 20))
            assertEquals(expected, holds(), run)
        }
    }

    @Test
    fun `while the bulk guard stops deletions on the phone, the holds of contacts the inventory lacks stay`() = runTest {
        // Its absences aren't trusted: an empty inventory while mapped rows exist, or 10 missing.
        for (missing in listOf(0, 10)) {
            reset()
            holdDao.holds += listOf(hold(1, 10, HoldKind.MISSING_ROW), hold(98, 980))
            if (missing > 0) unchanged(99)
            (2..maxOf(2, missing + 1)).forEach {
                phone.rows += row(it * 10L, it)
                mapDao.maps += map(it, it * 10L)
            }

            val report = engine().sync(account)

            assertTrue(report.counts().bulkGuardRemote, "$missing")
            assertEquals(listOf(hold(1, 10, HoldKind.MISSING_ROW), hold(98, 980)), holds(), "$missing")
        }
    }

    @Test
    fun `an upload-only sync removes no hold`() = runTest {
        holdDao.holds += listOf(hold(1, 10), hold(2, 20, HoldKind.MISSING_ROW))
        unchanged(1)

        engine().upload(account)

        assertEquals(listOf(hold(1, 10), hold(2, 20, HoldKind.MISSING_ROW)), holds())
    }

    @Test
    fun `a sync stopped part way still holds its plan's conflicts and what its re-fetches found`() = runTest {
        // A re-fetch finds contact 1 moved, then contact 2's upload stops the sends; contact 3 then stops the run.
        planConflicts()
        holdDao.holds += hold(8, 80)
        edited(6)
        api.refetches["proton-id-6"] = ArrayDeque(listOf(moved(6)))
        edited(7)
        api.refetches["proton-id-7"] = ArrayDeque(listOf(ProtonError.Network()))
        api.pages += contact(11)
        phone.rows += row(110, 11)
        mapDao.maps += map(11, 110, fingerprint = "old-fingerprint")
        phone.results["proton-id-11"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        val report = engine().sync(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, report.blocker)
        assertEquals(planned(hold(6, 60)), holds(), "contact 8's hold, no longer found, removed")
    }

    @Test
    fun `a dry run, a run that can't plan, and a cancelled run write and remove no hold`() = runTest {
        for (run in listOf("dry run", "signed out", "keys", "rows unreadable", "cancelled")) {
            reset()
            store.loadAnswer = null
            keys.result = null
            phone.failure = null
            planConflicts()
            holdDao.holds += hold(8, 80)
            when (run) {
                "signed out" -> store.loadAnswer = { null }
                "keys" -> keys.result = CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE)
                "rows unreadable" -> phone.failure = ProviderUnavailableException()
            }

            if (run == "cancelled") {
                lateinit var job: Job
                phone.onApply = { job.cancel() }
                api.pages += contact(12)
                job = launch { engine().sync(account) }
                job.join()
                assertTrue(job.isCancelled, run)
                phone.onApply = {}
            } else if (run == "dry run") {
                engine().dryRun(account)
            } else {
                engine().sync(account)
            }

            assertEquals(listOf(hold(8, 80)), holds(), run)
        }
    }
}
