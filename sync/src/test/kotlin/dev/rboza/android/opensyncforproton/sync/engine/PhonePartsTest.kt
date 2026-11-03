package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.plan.PhoneParts
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Which contacts of conflicts and can't be synced are the phone's part: the ones on rows an upload-only sync reads
// again. Every value is made up for the test.
class PhonePartsTest {
    private fun row(dirty: Boolean = false, deleted: Boolean = false, sourceId: String? = "proton-id-1") =
        DeviceContact(10, sourceId, dirty, deleted, version = 1, displayName = "Row")

    private fun item(category: PlanCategory, row: DeviceContact?) = PlanItem(category, null, null, row, "Name", carriedOut = true)

    private fun plan(vararg items: PlanItem) = SyncPlan(items.toList(), emptyList(), bulkGuardRemote = false, bulkGuardPhone = false)

    @Test
    fun `a row is the phone's when it's dirty, deleted, or made on the phone, clean ones and none not`() {
        assertEquals(
            listOf(true, true, true, true, false, false),
            listOf(row(dirty = true), row(deleted = true), row(sourceId = null), row(sourceId = " "), row(), null).map { item(PlanCategory.CONFLICT, it).onChangedRow },
        )
    }

    @Test
    fun `the plan's phone parts are its conflicts and can't be synced on such rows`() {
        val plan = plan(
            item(PlanCategory.CONFLICT, row(dirty = true)),
            item(PlanCategory.CONFLICT, row(deleted = true)),
            item(PlanCategory.CONFLICT, row()),
            item(PlanCategory.EDITED_BEFORE_KEYS, row(dirty = true)),
            item(PlanCategory.UNREADABLE, row(dirty = true)),
            item(PlanCategory.UNREADABLE, row()),
            item(PlanCategory.UNREADABLE, null),
            item(PlanCategory.UNMAPPED_ROWS, row(sourceId = null)),
            item(PlanCategory.CHANGED_ON_PHONE, row(dirty = true)),
            item(PlanCategory.MISSING_ROW, null),
        )

        assertEquals(PhoneParts(conflicts = 3, unreadable = 2), plan.phoneParts())
        assertEquals(4 to 4, plan.counts().let { it.conflicts to it.unreadable }, "the totals")
    }

    @Test
    fun `a run's refused and reclassified contacts count in the phone's part only on such rows`() {
        val upload = item(PlanCategory.CHANGED_ON_PHONE, row(dirty = true))
        val download = item(PlanCategory.CHANGED_ON_PROTON, row())
        val deletion = item(PlanCategory.DELETED_ON_PHONE, row(deleted = true))
        val chosen = item(PlanCategory.DELETED_ON_PROTON, row())
        val settled = item(PlanCategory.CHANGED_ON_PHONE, row(dirty = true))
        val report = RunReport(
            ReportKind.SYNC, 1_000, null, plan(upload, download, deletion, chosen, settled),
            refused = listOf(Refusal(upload, NotSyncedReason.PROTON_REFUSED), Refusal(download, NotSyncedReason.VALUE_REJECTED)),
            reclassified = listOf(
                Reclassified(deletion, PlanCategory.CONFLICT),
                Reclassified(chosen, PlanCategory.CONFLICT, withdrawn = true),
                Reclassified(settled, PlanCategory.SETTLED),
            ),
        )

        assertEquals(PhoneParts(conflicts = 1, unreadable = 1), report.phoneParts())
        assertEquals(2 to 2, report.counts().let { it.conflicts to it.unreadable }, "the totals")
    }
}
