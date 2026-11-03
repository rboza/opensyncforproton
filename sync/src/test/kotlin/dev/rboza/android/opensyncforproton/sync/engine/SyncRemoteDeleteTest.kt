package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.plan.ReportCounts
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ExistingRow
import dev.rboza.android.opensyncforproton.sync.provider.FailureReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Deletions on Proton: docs/sync.md § Deletion and § The plan, step 5. Every value is made up for the test.
class SyncRemoteDeleteTest : EngineHarness() {
    private fun fingerprint(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), contact(n).cards).value
    private fun live(raw: Long, n: Int, dirty: Boolean = false, deleted: Boolean = false, version: Long = 1) =
        DeviceContact(raw, "proton-id-$n", dirty = dirty, deleted = deleted, version = version, displayName = "Row $raw")
    private fun map(n: Int, raw: Long, fingerprint: String? = fingerprint(n)) =
        ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint, rowsKeyed = true, photoBaseline = photoHashOf(raw))

    /** Contact [n] gone from Proton, its row [raw] clean and mapped at the fingerprint it had. */
    private fun deletedOnProton(n: Int, raw: Long = n * 10L, version: Long = 1) {
        phone.rows += live(raw, n, version = version)
        mapDao.maps += map(n, raw)
    }

    /** Contact [n] on Proton and on the phone, unchanged on both sides. */
    private fun unchanged(n: Int, raw: Long = n * 10L) {
        api.pages += contact(n)
        phone.rows += live(raw, n)
        mapDao.maps += map(n, raw)
    }

    private fun counts(deletedOnProton: Int = 0, conflicts: Int = 0, unreadable: Int = 0, bulkGuardRemote: Boolean = false) =
        ReportCounts(0, 0, deletedOnProton, 0, 0, 0, conflicts, bulkGuardRemote, false, unreadable, 0, 0)

    private fun reset() {
        calls.clear(); events.clear(); api.pages.clear(); api.repeatFirstPage = false
        phone.rows.clear(); phone.writes.clear(); phone.rowsRemoved.clear(); phone.removeRowResults.clear(); mapDao.maps.clear()
    }

    @Test
    fun `a clean mapped row missing from a complete inventory is deleted as the sync adapter, then its map`() = runTest {
        deletedOnProton(1, version = 7)
        unchanged(2)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0"), calls, "nothing sent to Proton")
        assertEquals(listOf("proton-id-1" to ExistingRow(10, 7)), phone.rowsRemoved, "guarded by the VERSION read when planning")
        assertEquals(listOf("remove row proton-id-1", "map deleted proton-id-1"), events, "the row, then its map")
        assertEquals(listOf(map(2, 20)), mapDao.maps)
        assertEquals(listOf(live(20, 2)), phone.rows)
        assertEquals(counts(deletedOnProton = 1), report.counts())
        assertTrue(report.ready)
        assertEquals(1, reportDao.saved.single().deletedOnProton)
    }

    @Test
    fun `after an incomplete inventory, offline, by a dry run, or after a failed inventory request, nothing is deleted`() = runTest {
        for (run in listOf("incomplete", "offline", "dry run", "failed request", "upload-only")) {
            reset()
            unchanged(2)
            deletedOnProton(1)
            // The inventory repeats its first page, so it ends incomplete without failing.
            api.repeatFirstPage = run == "incomplete"
            online = run != "offline"
            api.failure = if (run == "failed request") ProtonError.Network() else null
            // The fake fails the first page only when it has none to give.
            if (run == "failed request") api.pages.clear()

            when (run) {
                "dry run" -> engine().dryRun(account)
                "upload-only" -> engine().upload(account)
                else -> engine().sync(account)
            }

            assertEquals(emptyList<Any>(), phone.rowsRemoved, run)
            assertEquals(listOf(map(2, 20), map(1, 10)), mapDao.maps, run)
        }
    }

    @Test
    fun `a missing row that's dirty, a tombstone, or has an empty baseline is never deleted`() = runTest {
        unchanged(9)
        phone.rows += live(10, 1, dirty = true)
        mapDao.maps += map(1, 10)
        phone.rows += listOf(live(20, 2), live(30, 3))
        mapDao.maps += map(3, 30, fingerprint = null)
        phone.rows += live(40, 4, deleted = true)
        mapDao.maps += map(4, 40)
        // A map naming another row: an empty baseline for this one.
        phone.rows += live(50, 5)
        mapDao.maps += map(5, 99)

        val report = engine().sync(account)

        assertEquals(emptyList<Any>(), phone.rowsRemoved, "a conflict, three empty baselines (no map, a rebuilt one, another row's), and a removal")
        assertEquals(listOf("proton-id-4" to 40L), phone.removed, "deleted on both sides: only its removal finished")
        assertEquals(counts(conflicts = 4), report.counts())
        assertEquals(listOf("proton-id-1", "proton-id-2", "proton-id-3", "proton-id-5", "proton-id-9"), mapDao.maps.map { it.protonId }.sorted(), "the repairs map the rows without their own map")
        assertEquals(50L, mapDao.maps.single { it.protonId == "proton-id-5" }.rawContactId)
    }

    @Test
    fun `the bulk guard stops every deletion on the phone`() = runTest {
        // 10 missing, dirty ones among them.
        unchanged(99)
        (1..7).forEach { deletedOnProton(it) }
        (8..10).forEach {
            phone.rows += live(it * 10L, it, dirty = true)
            mapDao.maps += map(it, it * 10L)
        }

        val ten = engine().sync(account)

        assertTrue(ten.counts().bulkGuardRemote)
        assertEquals(emptyList<Any>(), phone.rowsRemoved)
        assertEquals(counts(deletedOnProton = 7, conflicts = 3, bulkGuardRemote = true), ten.counts(), "counted, not carried out")
        assertTrue(reportDao.saved.last().bulkGuardRemote)

        // 4 of 20 mapped.
        reset()
        (1..4).forEach { deletedOnProton(it) }
        (5..20).forEach { unchanged(it) }

        assertTrue(engine().sync(account).counts().bulkGuardRemote)
        assertEquals(emptyList<Any>(), phone.rowsRemoved, "4 of 20")

        // An empty inventory while mapped rows exist.
        reset()
        deletedOnProton(1)

        assertTrue(engine().sync(account).counts().bulkGuardRemote)
        assertEquals(emptyList<Any>(), phone.rowsRemoved, "an empty inventory")
        assertEquals(listOf(map(1, 10)), mapDao.maps)
    }

    @Test
    fun `a confirmed sync deletes the clean rows still missing, and stops again when more are`() = runTest {
        // The confirmation isn't stored; the run fetches a fresh inventory and counts again.
        unchanged(99)
        (1..7).forEach { deletedOnProton(it) }
        (8..10).forEach {
            phone.rows += live(it * 10L, it, dirty = true)
            mapDao.maps += map(it, it * 10L)
        }

        val confirmed = engine().sync(account, confirmedRemoteDeletions = 10)

        assertEquals(listOf("C3 0"), calls, "a fresh inventory")
        assertEquals((1..7).map { "proton-id-$it" }, phone.rowsRemoved.map { it.first }, "dirty ones stay held")
        assertEquals(counts(deletedOnProton = 7, conflicts = 3), confirmed.counts(), "the guard no longer stops")
        assertEquals(10, confirmed.plan!!.remoteDeletionsWaiting)

        reset()
        unchanged(99)
        (1..11).forEach { deletedOnProton(it) }

        val more = engine().sync(account, confirmedRemoteDeletions = 10)

        assertEquals(emptyList<Any>(), phone.rowsRemoved)
        assertTrue(more.counts().bulkGuardRemote)
        assertEquals(11, more.plan!!.remoteDeletionsWaiting, "asked again with the new count")
    }

    @Test
    fun `a confirmed sync deletes every row of an empty inventory, and asks again when more are mapped`() = runTest {
        deletedOnProton(1)
        deletedOnProton(2)

        val confirmed = engine().sync(account, confirmedRemoteDeletions = 2)

        assertEquals(listOf("proton-id-1", "proton-id-2"), phone.rowsRemoved.map { it.first })
        assertEquals(emptyList<ContactMapEntity>(), mapDao.maps)
        assertFalse(confirmed.counts().bulkGuardRemote)

        reset()
        (1..3).forEach { deletedOnProton(it) }

        val more = engine().sync(account, confirmedRemoteDeletions = 2)

        assertEquals(emptyList<Any>(), phone.rowsRemoved)
        assertEquals(3, more.plan!!.remoteDeletionsWaiting)
    }

    @Test
    fun `a confirmation of one direction never lifts the other's guard`() = runTest {
        unchanged(99)
        (1..10).forEach { deletedOnProton(it) }

        val report = engine().sync(account, confirmedPhoneDeletions = 100)

        assertTrue(report.counts().bulkGuardRemote)
        assertEquals(emptyList<Any>(), phone.rowsRemoved)
    }

    @Test
    fun `below the guard the missing rows are deleted`() = runTest {
        // 9 missing.
        unchanged(99)
        (1..9).forEach { deletedOnProton(it) }

        val nine = engine().sync(account)

        assertFalse(nine.counts().bulkGuardRemote)
        assertEquals((1..9).map { "proton-id-$it" }, phone.rowsRemoved.map { it.first })
        assertEquals(listOf(map(99, 990)), mapDao.maps)

        // 3 of 20 mapped.
        reset()
        (1..3).forEach { deletedOnProton(it) }
        (4..20).forEach { unchanged(it) }

        engine().sync(account)

        assertEquals(listOf("proton-id-1", "proton-id-2", "proton-id-3"), phone.rowsRemoved.map { it.first })
    }

    @Test
    fun `a row deleted by mistake comes back as new from Proton with the next sync`() = runTest {
        // A clean row holds nothing Proton doesn't, so one observation is enough.
        unchanged(2)
        deletedOnProton(1)
        engine().sync(account)
        assertEquals(listOf(map(2, 20)), mapDao.maps)

        api.pages += contact(1)
        val report = engine().sync(account)

        assertEquals(1, report.counts().newFromProton)
        val write = phone.writes.single { it.protonId == "proton-id-1" }
        assertNull(write.existing, "a new row")
        assertEquals(listOf("proton-id-1", "proton-id-2"), mapDao.maps.map { it.protonId }.sorted())
    }

    @Test
    fun `a row the phone changed before the deletion keeps its map for the next run`() = runTest {
        // The guard didn't hold (an edit, a new VERSION, or the row gone): the next run decides.
        unchanged(2)
        deletedOnProton(1)
        phone.removeRowResults["proton-id-1"] = ContactWriteResult.Stale

        val report = engine().sync(account)

        assertEquals(listOf(map(2, 20), map(1, 10)), mapDao.maps)
        assertEquals(counts(deletedOnProton = 1), report.counts(), "a write the phone made stale still counts in its category")
        assertTrue(report.ready)
    }

    @Test
    fun `a deletion refused while its guard holds stops the run as contacts hidden, and one refused otherwise is counted`() = runTest {
        unchanged(9)
        deletedOnProton(1)
        deletedOnProton(2)
        phone.removeRowResults["proton-id-1"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        val hidden = engine().sync(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, hidden.blocker)
        assertEquals(listOf("proton-id-1"), phone.rowsRemoved.map { it.first }, "nothing more written")
        assertEquals(listOf(map(9, 90), map(1, 10), map(2, 20)), mapDao.maps)

        for (reason in listOf(FailureReason.TOO_LARGE, FailureReason.REJECTED_VALUE)) {
            reset()
            unchanged(9)
            deletedOnProton(1)
            deletedOnProton(2)
            phone.removeRowResults["proton-id-1"] = ContactWriteResult.Failed(reason)

            val refused = engine().sync(account)

            assertEquals(listOf("proton-id-1", "proton-id-2"), phone.rowsRemoved.map { it.first }, "$reason: the next one is deleted")
            assertEquals(listOf(map(9, 90), map(1, 10)), mapDao.maps, "$reason")
            val expected = if (reason == FailureReason.TOO_LARGE) NotSyncedReason.WRITE_TOO_LARGE else NotSyncedReason.VALUE_REJECTED
            assertEquals(listOf("proton-id-1" to expected), refused.refused.map { it.item.protonId?.value to it.reason })
            assertEquals(counts(deletedOnProton = 1, unreadable = 1), refused.counts(), "$reason")
            assertTrue(refused.ready, "$reason")
        }
    }

    @Test
    fun `a run cancelled once a row is deleted still deletes its map`() = runTest {
        unchanged(9)
        deletedOnProton(1)
        deletedOnProton(2)
        lateinit var run: Job
        phone.onRemoveRow = { run.cancel() }

        run = launch { engine().sync(account) }
        run.join()

        assertTrue(run.isCancelled)
        assertEquals(listOf("proton-id-1"), phone.rowsRemoved.map { it.first }, "nothing more")
        assertEquals(listOf(map(9, 90), map(2, 20)), mapDao.maps)
    }

    @Test
    fun `deletions on the phone follow the uploads, in the plan's order with the downloads`() = runTest {
        // A row missing on Proton, an edit, and a contact changed on Proton.
        deletedOnProton(1)
        api.pages += contact(2)
        phone.rows += live(20, 2, dirty = true)
        mapDao.maps += map(2, 20)
        api.pages += contact(3)
        phone.rows += live(30, 3)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")

        engine().sync(account)

        val order = events.filter { it.startsWith("apply") || it.startsWith("remove row") }
        assertEquals(listOf("apply proton-id-2", "apply proton-id-3", "remove row proton-id-1"), order)
        assertEquals(listOf("C3 0", "C2 2", "C5 2", "C2 2"), calls)
    }
}
