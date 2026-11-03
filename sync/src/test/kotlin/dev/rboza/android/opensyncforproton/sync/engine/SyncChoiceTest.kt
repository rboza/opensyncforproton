package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.plan.ReportCounts
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ExistingRow
import dev.rboza.android.opensyncforproton.sync.provider.FailureReason
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// Carrying out the user's choices for held contacts: docs/sync.md § Conflicts. Every value is made up for the test.
class SyncChoiceTest : EngineHarness() {
    private fun fingerprint(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), contact(n).cards).value
    private fun row(raw: Long, n: Int, dirty: Boolean = false, deleted: Boolean = false, version: Long = 1) =
        DeviceContact(raw, "proton-id-$n", dirty = dirty, deleted = deleted, version = version, displayName = "Row $raw")
    private fun map(n: Int, raw: Long, fingerprint: String? = fingerprint(n), keyed: Boolean = true) =
        ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint, rowsKeyed = keyed, photoBaseline = photoHashOf(raw))
    private fun hold(n: Int, raw: Long, version: Long? = 1, fingerprint: String? = fingerprint(n), kind: HoldKind = HoldKind.CONFLICT, choice: HoldChoice? = HoldChoice.USE_PROTON) =
        ConflictHoldEntity("user-id-1", "proton-id-$n", raw, kind, choice, version, fingerprint)
    private fun moved(n: Int) = contact(n).copy(cards = listOf(CardDto(1, "data-$n-1-moved")))

    /** What a write from Proton leaves: its fingerprint, its rows keyed, and the photo and its thumbnail read back. */
    private fun written(n: Int, raw: Long) = map(n, raw).copy(photoThumbnailBaseline = thumbnailHashOf(raw))

    private fun holds() = holdDao.holds.sortedBy { it.protonId }

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

    private fun counts(changedOnProton: Int = 0, deletedOnProton: Int = 0, newFromProton: Int = 0, conflicts: Int = 0, unreadable: Int = 0, missingRows: Int = 0) =
        ReportCounts(newFromProton, changedOnProton, deletedOnProton, 0, 0, 0, conflicts, false, false, unreadable, 0, missingRows)

    /** The user answers contact [n]'s hold again ([choice] null: Skip), as ConflictHoldDao.choose would. */
    private fun answer(n: Int, choice: HoldChoice?) {
        holdDao.holds.replaceAll { if (it.protonId == "proton-id-$n") it.copy(choice = choice) else it }
    }

    @Test
    fun `Use Proton on an edit vs edit writes the remote to the row, DIRTY cleared, then the hold goes`() = runTest {
        unchanged()
        editVsEdit(1, version = 7)
        holdDao.holds += hold(1, 10, version = 7)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0"), calls, "nothing sent")
        assertEquals(ExistingRow(10, 7, clearDirty = true), phone.writes.single().existing, "guarded by the VERSION recorded")
        assertEquals(written(1, 10), mapDao.maps.single { it.protonId == "proton-id-1" })
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(changedOnProton = 1), report.counts(), "counted as what it did")
    }

    @Test
    fun `Use Proton on a row edited before keys writes it from Proton`() = runTest {
        unchanged()
        api.pages += contact(1)
        phone.rows += row(10, 1, dirty = true)
        mapDao.maps += map(1, 10, keyed = false)
        holdDao.holds += hold(1, 10, kind = HoldKind.EDITED_BEFORE_KEYS)

        engine().sync(account)

        assertEquals(ExistingRow(10, 1, clearDirty = true), phone.writes.single().existing)
        assertEquals(written(1, 10), mapDao.maps.single { it.protonId == "proton-id-1" }, "its rows now keyed")
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
    }

    @Test
    fun `Use Proton on a phone edit vs a Proton deletion deletes the row here, dirty as it is, then its map and the hold`() = runTest {
        unchanged()
        phone.rows += row(20, 2, dirty = true, version = 3)
        mapDao.maps += map(2, 20)
        holdDao.holds += hold(2, 20, version = 3, fingerprint = null)
        // A clean row with an empty baseline gone from Proton, too.
        phone.rows += row(60, 6)
        mapDao.maps += map(6, 60, fingerprint = null)
        holdDao.holds += hold(6, 60, fingerprint = null)

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-2" to ExistingRow(20, 3, clearDirty = true), "proton-id-6" to ExistingRow(60, 1, clearDirty = true)), phone.rowsRemoved)
        assertEquals(
            listOf("remove row proton-id-2", "map deleted proton-id-2", "remove row proton-id-6", "map deleted proton-id-6", "hold deleted proton-id-2", "hold deleted proton-id-6"),
            events,
        )
        assertEquals(listOf(map(9, 90)), mapDao.maps)
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(deletedOnProton = 2), report.counts())
    }

    @Test
    fun `Use Proton on a phone deletion vs a Proton edit restores the contact as a new row`() = runTest {
        unchanged()
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true, deleted = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")
        holdDao.holds += hold(3, 30)

        val report = engine().sync(account)

        assertEquals(listOf("remove proton-id-3", "map deleted proton-id-3", "apply proton-id-3", "read back 1003", "map proton-id-3", "hold deleted proton-id-3"), events)
        assertNull(phone.writes.single().existing, "a new row with its SOURCE_ID")
        assertEquals(written(3, 1003), mapDao.maps.single { it.protonId == "proton-id-3" })
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(newFromProton = 1), report.counts())
        assertEquals(listOf("C3 0"), calls, "nothing sent")
    }

    @Test
    fun `Restore here writes a missing row's contact as a new row, its map replaced, and never sends a C6`() = runTest {
        unchanged()
        api.pages += contact(4)
        mapDao.maps += map(4, 40)
        holdDao.holds += hold(4, 40, version = null, kind = HoldKind.MISSING_ROW)

        val report = engine().sync(account)

        assertNull(phone.writes.single().existing)
        assertEquals(written(4, 1004), mapDao.maps.single { it.protonId == "proton-id-4" })
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
        assertEquals(counts(newFromProton = 1), report.counts())
        assertEquals(listOf("C3 0"), calls)
    }

    @Test
    fun `a chosen write the phone made stale drops the hold, and the next run asks again`() = runTest {
        unchanged()
        editVsEdit(1)
        holdDao.holds += hold(1, 10)
        phone.results["proton-id-1"] = ContactWriteResult.Stale

        engine().sync(account)

        assertEquals(emptyList<ConflictHoldEntity>(), holds())

        // The phone edited the row again: a new VERSION, so the next run holds it without a choice.
        phone.rows[phone.rows.indexOfFirst { it.rawContactId == 10L }] = row(10, 1, dirty = true, version = 2)
        phone.results.clear()
        engine().sync(account)

        assertEquals(listOf(hold(1, 10, version = 2, choice = null)), holds())
    }

    @Test
    fun `a chosen deletion the phone made stale drops the hold, and keeps the map for the next run`() = runTest {
        unchanged()
        phone.rows += row(20, 2, dirty = true)
        mapDao.maps += map(2, 20)
        holdDao.holds += hold(2, 20, fingerprint = null)
        phone.removeRowResults["proton-id-2"] = ContactWriteResult.Stale

        engine().sync(account)

        assertEquals(listOf("proton-id-2"), phone.rowsRemoved.map { it.first })
        assertEquals(listOf(map(9, 90), map(2, 20)), mapDao.maps)
        assertEquals(emptyList<ConflictHoldEntity>(), holds())
    }

    @Test
    fun `a chosen write refused, or not reached, keeps the hold and its choice`() = runTest {
        unchanged()
        editVsEdit(1)
        holdDao.holds += hold(1, 10)
        phone.results["proton-id-1"] = ContactWriteResult.Failed(FailureReason.TOO_LARGE)

        val refused = engine().sync(account)

        assertEquals(listOf("proton-id-1" to NotSyncedReason.WRITE_TOO_LARGE), refused.refused.map { it.item.protonId?.value to it.reason })
        assertEquals(counts(unreadable = 1), refused.counts())
        assertEquals(listOf(hold(1, 10)), holds())

        // An earlier write stops the run as contacts hidden before the choice is reached.
        phone.results.clear()
        api.pages.add(0, contact(5))
        phone.rows += row(50, 5)
        mapDao.maps += map(5, 50, fingerprint = "old-fingerprint")
        phone.results["proton-id-5"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        val stopped = engine().sync(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, stopped.blocker)
        assertEquals(listOf(hold(1, 10)), holds())
    }

    @Test
    fun `a choice taken back or changed after planning isn't carried out, keeps what's stored, and counts as held`() = runTest {
        // A choice tapped while a run goes is read again just before it's carried out.
        unchanged()
        editVsEdit(1)
        holdDao.holds += hold(1, 10)
        // A phone edit vs a Proton deletion, changed to Use phone.
        phone.rows += row(20, 2, dirty = true)
        mapDao.maps += map(2, 20)
        holdDao.holds += hold(2, 20, fingerprint = null)
        // A missing row's Restore here, taken back.
        api.pages += contact(4)
        mapDao.maps += map(4, 40)
        holdDao.holds += hold(4, 40, version = null, kind = HoldKind.MISSING_ROW)
        // Still chosen, so restored.
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true, deleted = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")
        holdDao.holds += hold(3, 30)
        // Once the plan has read them.
        holdDao.onHolds = { read ->
            if (read == 1) {
                answer(1, null)
                answer(2, HoldChoice.USE_PHONE)
                answer(4, null)
            }
        }

        val report = engine().sync(account)

        assertEquals(listOf("apply proton-id-3"), events.filter { it.startsWith("apply ") }, "only the restore written")
        assertEquals(emptyList<Any>(), phone.rowsRemoved)
        assertEquals(
            listOf(hold(1, 10, choice = null), hold(2, 20, fingerprint = null, choice = HoldChoice.USE_PHONE), hold(4, 40, version = null, kind = HoldKind.MISSING_ROW, choice = null)),
            holds(),
            "as now stored, for the next run",
        )
        assertEquals(counts(newFromProton = 1, conflicts = 2, missingRows = 1), report.counts(), "counted by the kind held")
        assertEquals(listOf("C3 0"), calls)
    }

    @Test
    fun `a sync records each hold's state, and drops a choice when a side changed`() = runTest {
        unchanged()
        // The row edited again since the choice; the remote moved since the choice; and new holds of each state.
        editVsEdit(1, version = 2)
        holdDao.holds += hold(1, 10, version = 1)
        editVsEdit(2)
        holdDao.holds += hold(2, 20, fingerprint = "fingerprint-chosen")
        phone.rows += row(30, 3, dirty = true, version = 4)
        mapDao.maps += map(3, 30)
        api.pages += contact(4)
        mapDao.maps += map(4, 40)
        // A conflict a re-fetch finds records the fingerprint it found.
        api.pages += contact(5)
        phone.rows += row(50, 5, dirty = true)
        mapDao.maps += map(5, 50)
        api.refetches["proton-id-5"] = ArrayDeque(listOf(moved(5)))
        // So does a phone deletion's.
        api.pages += contact(6)
        phone.rows += row(60, 6, dirty = true, deleted = true)
        mapDao.maps += map(6, 60)
        api.refetches["proton-id-6"] = ArrayDeque(listOf(moved(6)))

        engine().sync(account)

        assertEquals(
            listOf(
                hold(1, 10, version = 2, choice = null),
                hold(2, 20, choice = null),
                hold(3, 30, version = 4, fingerprint = null, choice = null),
                hold(4, 40, version = null, kind = HoldKind.MISSING_ROW, choice = null),
                hold(5, 50, fingerprint = contactFingerprint(ProtonContactId("proton-id-5"), moved(5).cards).value, choice = null),
                hold(6, 60, fingerprint = contactFingerprint(ProtonContactId("proton-id-6"), moved(6).cards).value, choice = null),
            ),
            holds(),
        )
    }

    @Test
    fun `an upload-only sync never carries out a choice, and keeps it while the row is as recorded`() = runTest {
        unchanged()
        // A tombstone without a map (an empty baseline) chosen gone: an upload-only sync can't see the remote.
        phone.rows += row(60, 6, dirty = true, deleted = true)
        holdDao.holds += hold(6, 60, fingerprint = null)
        phone.rows += row(70, 7, dirty = true, deleted = true, version = 2)
        holdDao.holds += hold(7, 70, fingerprint = "fingerprint-7")

        engine().upload(account)

        assertEquals(emptyList<Any>(), phone.removed)
        assertEquals(emptyList<Any>(), phone.writes)
        assertEquals(listOf(hold(6, 60, fingerprint = null), hold(7, 70, version = 2, fingerprint = "fingerprint-7", choice = null)), holds())
    }

    @Test
    fun `a dry run plans and counts a stored choice as a sync would, and writes nothing`() = runTest {
        unchanged()
        editVsEdit(1)
        holdDao.holds += hold(1, 10)

        val report = engine().dryRun(account)

        assertEquals(counts(changedOnProton = 1), report.counts())
        assertEquals(emptyList<Any>(), phone.writes)
        assertEquals(listOf(hold(1, 10)), holds())
        assertEquals(listOf("read back 10"), events)
    }

    @Test
    fun `a restore whose write is refused keeps its hold, and the next run finds a contact new from Proton`() = runTest {
        unchanged()
        api.pages += contact(3)
        phone.rows += row(30, 3, dirty = true, deleted = true)
        mapDao.maps += map(3, 30, fingerprint = "old-fingerprint")
        holdDao.holds += hold(3, 30)
        phone.results["proton-id-3"] = ContactWriteResult.Failed(FailureReason.REJECTED_VALUE)

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-3" to NotSyncedReason.VALUE_REJECTED), report.refused.map { it.item.protonId?.value to it.reason })
        assertEquals(listOf(map(9, 90)), mapDao.maps, "the tombstone and its map are gone")
        assertEquals(listOf(hold(3, 30)), holds())

        phone.results.clear()
        val next = engine().sync(account)

        assertEquals(counts(newFromProton = 1), next.counts())
        assertEquals(emptyList<ConflictHoldEntity>(), holds(), "no longer found")
    }
}
