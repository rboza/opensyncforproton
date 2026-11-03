package dev.rboza.android.opensyncforproton.main

import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// The hint under the report: Sync now shows the names only when it would find those contacts again.
class NamesHintTest {
    private val changes = ReportLine.entries.associateWith { if (it.held) 0 else 1 }

    private fun report(
        counts: Map<ReportLine, Int> = changes,
        names: Map<ReportLine, List<ReportName>>? = null,
        kind: ReportKind = ReportKind.SYNC,
        blocker: Blocker? = null,
        remoteGuard: Boolean = false,
        phoneGuard: Boolean = false,
    ) = ReportUi(kind, 5_000, blocker, remoteGuard, phoneGuard, counts = counts, names = names)

    @Test
    fun `changes already synced, without names, aren't kept`() {
        assertEquals(NamesHint.NOT_KEPT, report().namesHint)
    }

    @Test
    fun `every line at 0 has no hint`() {
        assertNull(report(counts = ReportLine.entries.associateWith { 0 }).namesHint)
    }

    @Test
    fun `a count Sync now would find again asks for Sync now`() {
        for (line in ReportLine.entries.filter { it.held }) {
            assertEquals(NamesHint.SYNC_NOW, report(counts = changes + (line to 1)).namesHint, "$line")
            assertEquals(NamesHint.SYNC_NOW, report(counts = ReportLine.entries.associateWith { if (it == line) 1 else 0 }).namesHint, "$line alone")
        }
        assertEquals(NamesHint.SYNC_NOW, report(kind = ReportKind.DRY_RUN).namesHint, "a dry run's lines are what a sync would do")
        assertEquals(NamesHint.SYNC_NOW, report(blocker = Blocker.INVENTORY_INCOMPLETE).namesHint, "a run that didn't finish")
        assertEquals(NamesHint.SYNC_NOW, report(remoteGuard = true).namesHint, "the remote guard")
        assertEquals(NamesHint.SYNC_NOW, report(phoneGuard = true).namesHint, "the phone guard")
    }

    @Test
    fun `this screen's Sync now report has no hint while every counted line has its names`() {
        val names = ReportLine.entries.associateWith { line -> if (changes.getValue(line) > 0) listOf(ReportName("Ann", null)) else emptyList() }

        assertNull(report(names = names).namesHint)
    }

    @Test
    fun `after this screen's Sync now stopped before counting, a line that needs the user asks for Sync now, changes don't`() {
        // A run with no plan has empty names (MainModelTest); every line is kept from an earlier run.
        val empty = ReportLine.entries.associateWith { emptyList<ReportName>() }

        assertEquals(NamesHint.SYNC_NOW, report(counts = changes + (ReportLine.MISSING_ROWS to 1), names = empty, blocker = Blocker.OFFLINE).namesHint)
        assertEquals(NamesHint.NOT_KEPT, report(names = empty, blocker = Blocker.OFFLINE).namesHint)
        // Deletions a guard stopped weren't carried out: Sync now finds them again.
        val protonDeletions = ReportLine.entries.associateWith { if (it == ReportLine.DELETED_ON_PROTON) 12 else 0 }
        assertEquals(NamesHint.SYNC_NOW, report(counts = protonDeletions, names = empty, blocker = Blocker.OFFLINE, remoteGuard = true).namesHint, "the remote guard")
        val phoneDeletions = ReportLine.entries.associateWith { if (it == ReportLine.DELETED_ON_PHONE) 12 else 0 }
        assertEquals(NamesHint.SYNC_NOW, report(counts = phoneDeletions, names = empty, blocker = Blocker.OFFLINE, phoneGuard = true).namesHint, "the phone guard")
    }

    @Test
    fun `a guard asks for Sync now only for lines of its own side`() {
        val phoneChanges = ReportLine.entries.associateWith { if (it.phone && !it.held) 1 else 0 }
        val protonChanges = ReportLine.entries.associateWith { if (it.proton && !it.held) 1 else 0 }

        assertEquals(NamesHint.NOT_KEPT, report(counts = phoneChanges, remoteGuard = true).namesHint, "the phone's changes, done")
        assertEquals(NamesHint.NOT_KEPT, report(counts = protonChanges, phoneGuard = true).namesHint, "Proton's changes, done")
    }

    @Test
    fun `a line kept from an earlier run after this screen's Sync now has no names, which aren't kept`() {
        val names = ReportLine.entries.associateWith { line -> if (line == ReportLine.NEW_ON_PHONE) emptyList() else listOf(ReportName("Ann", null)) }

        assertEquals(NamesHint.NOT_KEPT, report(names = names).namesHint)
        // Sync now just ran: tapping it again wouldn't name them, whatever else the report holds.
        assertEquals(NamesHint.NOT_KEPT, report(names = names, blocker = Blocker.INVENTORY_INCOMPLETE).namesHint, "a blocker")
        assertEquals(NamesHint.NOT_KEPT, report(names = names, remoteGuard = true).namesHint, "the remote guard")
    }
}
