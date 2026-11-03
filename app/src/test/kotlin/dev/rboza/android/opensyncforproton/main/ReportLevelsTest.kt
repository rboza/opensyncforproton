package dev.rboza.android.opensyncforproton.main

import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// The report as the latest known state, read at three levels (docs/sync.md § Sync mode).
class ReportLevelsTest {
    private val now = 5_000L

    private fun report(proton: Long?, phone: Long?, kind: ReportKind = ReportKind.SYNC, count: Int = 1) = ReportUi(
        kind, now, blocker = null, bulkGuardRemote = true, bulkGuardPhone = true, counts = ReportLine.entries.associateWith { count }, names = null,
        trigger = ReportTrigger.PHONE_CHANGES, protonCountedAt = proton, phoneCountedAt = phone,
    )

    private fun levels(report: ReportUi) = ReportLine.entries.associateWith(report::level)

    private fun expected(vararg levels: LineLevel) = ReportLine.entries.zip(levels.toList()).toMap()

    @Test
    fun `every line a full sync or a dry run counted is the latest`() {
        for (kind in ReportKind.entries) {
            val report = report(now, now, kind)

            assertEquals(ReportLine.entries.associateWith { LineLevel.LATEST }, levels(report), "$kind")
            assertEquals(LineLevel.LATEST to LineLevel.LATEST, report.remoteGuardLevel to report.phoneGuardLevel)
        }
    }

    @Test
    fun `after a partial run the phone's lines are the latest, and Proton's earlier, waiting when they need you`() {
        // Conflicts and can't be synced hold a part of each side, so the phone's part counted now makes them the
        // latest.
        val report = report(1_000, now)

        assertEquals(
            expected(
                LineLevel.EARLIER, LineLevel.EARLIER, LineLevel.EARLIER,
                LineLevel.LATEST, LineLevel.LATEST, LineLevel.LATEST,
                LineLevel.LATEST, LineLevel.LATEST, LineLevel.LATEST, LineLevel.WAITING,
            ),
            levels(report),
        )
        assertEquals(LineLevel.WAITING to LineLevel.LATEST, report.remoteGuardLevel to report.phoneGuardLevel)
        assertEquals(LineLevel.EARLIER, report(1_000, now, count = 0).level(ReportLine.MISSING_ROWS), "nothing waiting")
    }

    @Test
    fun `after a run that counted nothing every line is earlier, those that need you waiting`() {
        val report = report(1_000, 3_000)

        assertEquals(
            expected(
                LineLevel.EARLIER, LineLevel.EARLIER, LineLevel.EARLIER,
                LineLevel.EARLIER, LineLevel.EARLIER, LineLevel.EARLIER,
                LineLevel.WAITING, LineLevel.WAITING, LineLevel.WAITING, LineLevel.WAITING,
            ),
            levels(report),
        )
        assertEquals(LineLevel.WAITING to LineLevel.WAITING, report.remoteGuardLevel to report.phoneGuardLevel)
        assertEquals(ReportLine.entries.associateWith { LineLevel.EARLIER }, levels(report(1_000, 3_000, count = 0)))
    }

    @Test
    fun `a side no sync has counted yet is not counted`() {
        assertEquals(
            expected(
                LineLevel.NOT_COUNTED, LineLevel.NOT_COUNTED, LineLevel.NOT_COUNTED,
                LineLevel.LATEST, LineLevel.LATEST, LineLevel.LATEST,
                LineLevel.LATEST, LineLevel.LATEST, LineLevel.LATEST, LineLevel.NOT_COUNTED,
            ),
            levels(report(null, now)),
        )
        assertEquals(ReportLine.entries.associateWith { LineLevel.NOT_COUNTED }, levels(report(null, null)))
        assertEquals(LineLevel.WAITING, report(null, 3_000).level(ReportLine.CONFLICTS), "its phone part, counted earlier")
    }

    @Test
    fun `an earlier line says when it was counted, a line of both sides by its older one`() {
        val report = report(1_000, 3_000)

        assertEquals(1_000L, report.countedAt(ReportLine.NEW_FROM_PROTON))
        assertEquals(3_000L, report.countedAt(ReportLine.DUPLICATE_ROWS))
        assertEquals(1_000L, report.countedAt(ReportLine.CONFLICTS))
        assertEquals(3_000L, report(null, 3_000).countedAt(ReportLine.CANT_BE_SYNCED))
        assertEquals(null, report(null, 3_000).countedAt(ReportLine.MISSING_ROWS))
    }
}
