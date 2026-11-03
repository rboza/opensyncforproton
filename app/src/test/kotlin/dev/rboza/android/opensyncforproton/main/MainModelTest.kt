package dev.rboza.android.opensyncforproton.main

import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.proton.Fingerprint
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.sync.engine.NotSyncedReason
import dev.rboza.android.opensyncforproton.sync.engine.Reclassified
import dev.rboza.android.opensyncforproton.sync.engine.Refusal
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.RemoteContact
import dev.rboza.android.opensyncforproton.sync.plan.UnreadableReason
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// What the main screen shows and what Sync now does. Names and IDs are made up.
@OptIn(ExperimentalCoroutinesApi::class)
class MainModelTest {
    private val errors = mutableListOf<Throwable>()
    private val scope = CoroutineScope(Job() + UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> errors += e })
    private val graph = FakeAppGraph()
    private val model = MainModel(graph, scope)

    @AfterEach
    fun noUncaughtErrors() = assertEquals(emptyList<Throwable>(), errors)

    private fun ui() = checkNotNull(model.state.value)

    private fun signedIn(id: String = "a1", name: String = "one@example.test", mode: SyncMode = SyncMode.DRY_RUN, manual: Boolean = false) {
        graph.records += AccountRecordEntity(id, name, LoginState.ACTIVE)
        graph.settings[id] = SettingsEntity(id, mode, manual = manual)
        graph.readable += id
    }

    private fun stored(report: RunReport, account: String = "a1") {
        graph.reports[account] = report.toRow(account)
    }

    @Test
    fun `nothing is shown until the first read`() {
        assertNull(model.state.value)
        model.refresh()
        assertEquals(AccountUi.NotSignedIn, ui().account)
    }

    @Test
    fun `no account is not signed in, with no report and nothing to sync`() {
        model.refresh()

        assertEquals(MainUi(account = AccountUi.NotSignedIn), ui())
        model.syncNow()
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `an account the user logged out of is signed out`() {
        graph.records = listOf(AccountRecordEntity("a1", "one@example.test", LoginState.SIGNED_OUT))
        graph.readable += "a1"
        model.refresh()

        assertEquals(AccountUi.SignedOut("one@example.test"), ui().account)
    }

    @Test
    fun `a signed-in account whose credentials can't be read has an expired session`() {
        signedIn()
        graph.readable.clear()
        model.refresh()

        assertEquals(AccountUi.SessionExpired("one@example.test"), ui().account)
    }

    @Test
    fun `a signed-in account with readable credentials is signed in, whatever the last report says`() {
        signedIn()
        stored(report(Blocker.SESSION_EXPIRED))
        model.refresh()

        assertEquals(AccountUi.SignedIn("one@example.test"), ui().account)
    }

    @Test
    fun `the signed-in account is shown, else the first signed-out one by name`() {
        graph.records = listOf(
            AccountRecordEntity("a1", "c@example.test", LoginState.SIGNED_OUT),
            AccountRecordEntity("a2", "b@example.test", LoginState.ACTIVE),
            AccountRecordEntity("a3", "a@example.test", LoginState.SIGNED_OUT),
        )
        graph.readable += "a2"
        model.refresh()
        assertEquals(AccountUi.SignedIn("b@example.test"), ui().account)

        graph.records = graph.records.filterNot { it.accountId == "a2" }
        model.refresh()
        assertEquals(AccountUi.SignedOut("a@example.test"), ui().account)
    }

    @Test
    fun `Log in is offered in every state but signed in, and signed in while the keys don't unlock`() {
        model.refresh()
        assertTrue(ui().offersLogIn, "not signed in")

        signedIn()
        model.refresh()
        assertFalse(ui().offersLogIn)

        stored(report(Blocker.KEYS_DONT_UNLOCK))
        model.refresh()
        assertEquals(AccountUi.SignedIn("one@example.test"), ui().account)
        assertTrue(ui().offersLogIn)

        stored(report(Blocker.OFFLINE))
        model.refresh()
        assertFalse(ui().offersLogIn)

        graph.readable.clear()
        model.refresh()
        assertTrue(ui().offersLogIn, "session expired")

        graph.records = listOf(AccountRecordEntity("a1", "one@example.test", LoginState.SIGNED_OUT))
        model.refresh()
        assertTrue(ui().offersLogIn, "signed out")
    }

    @Test
    fun `the mode comes from the settings, missing settings meaning dry run`() {
        signedIn(mode = SyncMode.ON)
        model.refresh()
        assertTrue(ui().syncOn)

        graph.settings.clear()
        model.refresh()
        assertFalse(ui().syncOn)
    }

    @Test
    fun `Android's automatic sync being off is shown only for a signed-in account syncing on a schedule`() {
        graph.autoSync = false
        signedIn(mode = SyncMode.ON)
        model.refresh()
        assertTrue(ui().androidSyncOff)

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, manual = true)
        model.refresh()
        assertFalse(ui().androidSyncOff, "Manual has no schedule")

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60)
        model.refresh()
        assertFalse(ui().androidSyncOff, "a dry run has no schedule")

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        graph.readable.clear()
        model.refresh()
        assertFalse(ui().androidSyncOff, "an expired session doesn't sync")

        graph.readable += "a1"
        graph.autoSync = true
        model.refresh()
        assertFalse(ui().androidSyncOff)
    }

    @Test
    fun `Android's automatic sync being on is shown only for a signed-in account syncing with Manual`() {
        // Manual turns Android's automatic sync off (docs/sync.md § Scheduling); if it's on again, Android may also
        // sync on its own schedule.
        graph.autoSync = true
        signedIn(mode = SyncMode.ON, manual = true)
        model.refresh()
        assertTrue(ui().androidSyncOnUnderManual)
        assertFalse(ui().androidSyncOff)

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        model.refresh()
        assertFalse(ui().androidSyncOnUnderManual, "Manual off wants it on")

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, manual = true)
        model.refresh()
        assertFalse(ui().androidSyncOnUnderManual, "a dry run isn't syncable")

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, manual = true)
        graph.readable.clear()
        model.refresh()
        assertFalse(ui().androidSyncOnUnderManual, "an expired session doesn't sync")

        graph.readable += "a1"
        graph.autoSync = false
        model.refresh()
        assertFalse(ui().androidSyncOnUnderManual)
    }

    @Test
    fun `a stored report shows its counts without names`() {
        signedIn()
        graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 1_000, false, Blocker.OFFLINE, 1, 2, 3, 4, 5, 6, 7, true, false, 8, 9, 10)
        model.refresh()

        val report = checkNotNull(ui().report)
        assertEquals(ReportKind.SYNC, report.kind)
        assertEquals(1_000, report.finishedAt)
        assertEquals(Blocker.OFFLINE, report.blocker)
        assertTrue(report.bulkGuardRemote)
        assertFalse(report.bulkGuardPhone)
        assertEquals(ReportLine.entries.zip(1..10).toMap(), report.counts)
        assertNull(report.names)
        assertNull(report.phoneDeletionsWaiting, "known only for this screen's Sync now, as names are")
        assertEquals(Triple(null, 1_000L, 1_000L), Triple(report.trigger, report.protonCountedAt, report.phoneCountedAt))
    }

    @Test
    fun `a stored report shows what started it and when each side was counted`() {
        // The report as the latest known state.
        signedIn()
        graph.reports["a1"] = LastReportEntity(
            "a1", ReportKind.SYNC, 2_000, true, null, 1, 2, 3, 4, 5, 6, 7, false, false, 8, 9, 10,
            conflictsPhone = 3, unreadablePhone = 2, trigger = ReportTrigger.CHECK, protonCountedAt = 500, phoneCountedAt = null,
        )
        model.refresh()

        val report = checkNotNull(ui().report)
        assertEquals(Triple(ReportTrigger.CHECK, 500L, null), Triple(report.trigger, report.protonCountedAt, report.phoneCountedAt))
        assertEquals(7 to 8, report.counts.getValue(ReportLine.CONFLICTS) to report.counts.getValue(ReportLine.CANT_BE_SYNCED), "the lines' totals")
    }

    /** Each direction of the bulk guard apart. */
    @Test
    fun `the report keeps the bulk guard's directions apart`() {
        signedIn()
        for ((remote, phone) in listOf(false to true, true to false, false to false, true to true)) {
            graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 1_000, true, null, 0, 0, 0, 0, 0, 0, 0, remote, phone, 0, 0, 0)
            model.refresh()

            assertEquals(remote to phone, checkNotNull(ui().report).let { it.bulkGuardRemote to it.bulkGuardPhone }, "$remote, $phone")
        }
    }

    /** A sync the phone guard stopped, with [waiting] tombstones counted. */
    private fun stoppedByPhoneGuard(waiting: Int = 12, kind: ReportKind = ReportKind.SYNC) = RunReport(
        kind, 2_000, null, SyncPlan(listOf(item(PlanCategory.DELETED_ON_PHONE, "Gone Here")), emptyList(), bulkGuardRemote = false, bulkGuardPhone = true, phoneDeletionsWaiting = waiting),
    )

    @Test
    fun `the phone guard's count is known for a report this screen's Sync now got`() {
        signedIn(mode = SyncMode.ON)
        graph.request = { _, _ -> stoppedByPhoneGuard().also { stored(it) } }
        model.refresh()

        model.syncNow()

        assertEquals(12, checkNotNull(ui().report).phoneDeletionsWaiting)
        assertTrue(ui().offersPhoneDeletions)

        // A later report from elsewhere (the periodic job, say): the count is gone with the names.
        stored(stoppedByPhoneGuard().let { RunReport(it.kind, 3_000, null, it.plan) })
        model.refresh()
        assertNull(checkNotNull(ui().report).phoneDeletionsWaiting)
        assertFalse(ui().offersPhoneDeletions)
    }

    @Test
    fun `Delete N on Proton is offered only with sync on, signed in, the phone guard set, and the count known`() {
        fun offered(mode: SyncMode = SyncMode.ON, run: RunReport = stoppedByPhoneGuard(), readable: Boolean = true, state: LoginState = LoginState.ACTIVE): Boolean {
            graph.records = listOf(AccountRecordEntity("a1", "one@example.test", state))
            graph.settings["a1"] = SettingsEntity("a1", mode, 60)
            if (readable) graph.readable += "a1" else graph.readable.clear()
            graph.request = { _, _ -> run.also { stored(it) } }
            model.refresh()
            model.syncNow()
            return ui().offersPhoneDeletions
        }
        assertTrue(offered())
        assertTrue(offered(run = stoppedByPhoneGuard(kind = ReportKind.DRY_RUN)), "the check before Start, with sync on")
        assertFalse(offered(mode = SyncMode.DRY_RUN), "dry run: nothing would be sent")
        assertFalse(offered(readable = false), "an expired session")
        assertFalse(offered(state = LoginState.SIGNED_OUT))
        val unguarded = stoppedByPhoneGuard().let { RunReport(it.kind, it.finishedAt, null, SyncPlan(it.plan!!.items, emptyList(), false, false, phoneDeletionsWaiting = 3)) }
        assertFalse(offered(run = unguarded), "the guard didn't stop")
    }

    @Test
    fun `Delete N on Proton asks first, and Cancel sends nothing`() {
        signedIn(mode = SyncMode.ON)
        graph.request = { _, _ -> stoppedByPhoneGuard().also { stored(it) } }
        model.refresh()
        model.syncNow()
        graph.calls.clear()

        model.askDeletePhoneDeletions()

        assertEquals(12, ui().confirmingDeletions)
        assertTrue(ui().busy, "Sync now and the switch wait for the answer")
        model.syncNow()
        assertEquals(emptyList<String>(), graph.calls)

        model.cancelPhoneDeletions()

        assertNull(ui().confirmingDeletions)
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `Delete runs Sync now carrying the count, then reads the state again`() {
        signedIn(mode = SyncMode.ON)
        graph.request = { _, _ -> stoppedByPhoneGuard().also { stored(it) } }
        model.refresh()
        model.syncNow()
        graph.calls.clear()
        model.askDeletePhoneDeletions()
        val done = RunReport(ReportKind.SYNC, 5_000, null, SyncPlan(emptyList(), emptyList(), false, false))
        graph.request = { _, _ -> done.also { stored(it) } }

        model.deletePhoneDeletions()

        assertEquals(listOf("reconcile", "request a1 FULL confirming 12"), graph.calls.take(2))
        assertNull(ui().confirmingDeletions)
        assertFalse(ui().running)
        assertEquals(5_000, checkNotNull(ui().report).finishedAt, "read again")
        assertFalse(ui().offersPhoneDeletions)
    }

    @Test
    fun `the question goes when a read no longer gives its count`() {
        signedIn(mode = SyncMode.ON)
        graph.request = { _, _ -> stoppedByPhoneGuard().also { stored(it) } }
        model.refresh()
        model.syncNow()
        model.askDeletePhoneDeletions()

        model.refresh()
        assertEquals(12, ui().confirmingDeletions, "the same report: still asked")

        stored(stoppedByPhoneGuard().let { RunReport(it.kind, 3_000, null, it.plan) })
        model.refresh()
        assertNull(ui().confirmingDeletions, "a report from elsewhere has no count to confirm")
    }

    @Test
    fun `nothing is asked while busy or when not offered`() {
        signedIn(mode = SyncMode.ON)
        model.refresh()

        model.askDeletePhoneDeletions()
        model.deletePhoneDeletions()

        assertNull(ui().confirmingDeletions)
        assertEquals(emptyList<String>(), graph.calls.filter { it.startsWith("request") })

        // Offered, but Sync now is running.
        graph.request = { _, _ -> stoppedByPhoneGuard().also { stored(it) } }
        model.syncNow()
        assertTrue(ui().offersPhoneDeletions)
        val gate = CompletableDeferred<Unit>()
        graph.request = { _, _ -> gate.await(); stoppedByPhoneGuard().also { stored(it) } }
        model.syncNow()
        assertTrue(ui().running)
        model.askDeletePhoneDeletions()
        assertNull(ui().confirmingDeletions, "while running")
        gate.complete(Unit)

        // The count known, but sync is off: nothing would be sent.
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60)
        graph.request = { _, _ -> stoppedByPhoneGuard(kind = ReportKind.DRY_RUN).also { stored(it) } }
        model.syncNow()
        assertEquals(12, checkNotNull(ui().report).phoneDeletionsWaiting)
        model.askDeletePhoneDeletions()
        assertNull(ui().confirmingDeletions, "not offered")
    }

    @Test
    fun `a confirmed run the guard stops again isn't asked about again until Delete N on Proton is tapped`() {
        // docs/sync.md § Deletion: more waiting than confirmed asks again, with a new tap.
        signedIn(mode = SyncMode.ON)
        graph.request = { _, _ -> stoppedByPhoneGuard().also { stored(it) } }
        model.refresh()
        model.syncNow()
        model.askDeletePhoneDeletions()
        graph.request = { _, _ -> stoppedByPhoneGuard().let { RunReport(it.kind, 6_000, null, it.plan) }.also { stored(it) } }

        model.deletePhoneDeletions()

        assertTrue(ui().offersPhoneDeletions)
        assertNull(ui().confirmingDeletions)
    }

    /** A sync the remote guard stopped, with [waiting] rows missing on Proton counted. */
    private fun stoppedByRemoteGuard(waiting: Int = 15, kind: ReportKind = ReportKind.SYNC, finishedAt: Long = 2_000) = RunReport(
        kind, finishedAt, null, SyncPlan(listOf(item(PlanCategory.DELETED_ON_PROTON, "Gone There")), emptyList(), bulkGuardRemote = true, bulkGuardPhone = false, remoteDeletionsWaiting = waiting),
    )

    @Test
    fun `the remote guard's count is known for a report this screen's Sync now got`() {
        // As for the phone direction.
        signedIn(mode = SyncMode.ON)
        graph.request = { _, _ -> stoppedByRemoteGuard().also { stored(it) } }
        model.refresh()

        model.syncNow()

        assertEquals(15, checkNotNull(ui().report).remoteDeletionsWaiting)
        assertTrue(ui().offersRemoteDeletions)
        assertFalse(ui().offersPhoneDeletions, "each direction apart")

        stored(stoppedByRemoteGuard(finishedAt = 3_000))
        model.refresh()
        assertNull(checkNotNull(ui().report).remoteDeletionsWaiting)
        assertFalse(ui().offersRemoteDeletions)
    }

    @Test
    fun `Delete N on this phone is offered only with sync on, signed in, the remote guard set, and the count known`() {
        fun offered(mode: SyncMode = SyncMode.ON, run: RunReport = stoppedByRemoteGuard(), readable: Boolean = true, state: LoginState = LoginState.ACTIVE): Boolean {
            graph.records = listOf(AccountRecordEntity("a1", "one@example.test", state))
            graph.settings["a1"] = SettingsEntity("a1", mode, 60)
            if (readable) graph.readable += "a1" else graph.readable.clear()
            graph.request = { _, _ -> run.also { stored(it) } }
            model.refresh()
            model.syncNow()
            return ui().offersRemoteDeletions
        }
        assertTrue(offered())
        assertTrue(offered(run = stoppedByRemoteGuard(kind = ReportKind.DRY_RUN)), "the check before Start, with sync on")
        assertFalse(offered(mode = SyncMode.DRY_RUN))
        assertFalse(offered(readable = false))
        assertFalse(offered(state = LoginState.SIGNED_OUT))
        val unguarded = stoppedByRemoteGuard().let { RunReport(it.kind, it.finishedAt, null, SyncPlan(it.plan!!.items, emptyList(), false, false, remoteDeletionsWaiting = 3)) }
        assertFalse(offered(run = unguarded), "the guard didn't stop")
    }

    @Test
    fun `Delete N on this phone asks first, Cancel sends nothing, and Delete runs Sync now carrying the count`() {
        signedIn(mode = SyncMode.ON)
        graph.request = { _, _ -> stoppedByRemoteGuard().also { stored(it) } }
        model.refresh()
        model.syncNow()
        graph.calls.clear()

        model.askDeleteRemoteDeletions()

        assertEquals(15, ui().confirmingRemoteDeletions)
        assertNull(ui().confirmingDeletions, "the other question stays closed")
        assertTrue(ui().busy, "Sync now and the switch wait for the answer")
        model.syncNow()
        assertEquals(emptyList<String>(), graph.calls)
        model.cancelRemoteDeletions()
        assertNull(ui().confirmingRemoteDeletions)
        assertEquals(emptyList<String>(), graph.calls)

        model.askDeleteRemoteDeletions()
        graph.request = { _, _ -> RunReport(ReportKind.SYNC, 5_000, null, SyncPlan(emptyList(), emptyList(), false, false)).also { stored(it) } }
        model.deleteRemoteDeletions()

        assertEquals(listOf("reconcile", "request a1 FULL confirming 15 remote"), graph.calls.take(2))
        assertNull(ui().confirmingRemoteDeletions)
        assertEquals(5_000, checkNotNull(ui().report).finishedAt, "read again")
        assertFalse(ui().offersRemoteDeletions)
    }

    @Test
    fun `the remote question goes when a read no longer gives its count, and nothing is asked while busy`() {
        signedIn(mode = SyncMode.ON)
        model.refresh()
        model.askDeleteRemoteDeletions()
        model.deleteRemoteDeletions()
        assertNull(ui().confirmingRemoteDeletions, "not offered")
        assertEquals(emptyList<String>(), graph.calls.filter { it.startsWith("request") })

        graph.request = { _, _ -> stoppedByRemoteGuard().also { stored(it) } }
        model.syncNow()
        model.askDeleteRemoteDeletions()
        model.refresh()
        assertEquals(15, ui().confirmingRemoteDeletions, "the same report: still asked")
        stored(stoppedByRemoteGuard(finishedAt = 3_000))
        model.refresh()
        assertNull(ui().confirmingRemoteDeletions, "a report from elsewhere has no count to confirm")

        graph.request = { _, _ -> stoppedByRemoteGuard().also { stored(it) } }
        model.syncNow()
        val gate = CompletableDeferred<Unit>()
        graph.request = { _, _ -> gate.await(); stoppedByRemoteGuard().also { stored(it) } }
        model.syncNow()
        model.askDeleteRemoteDeletions()
        assertNull(ui().confirmingRemoteDeletions, "while running")
        gate.complete(Unit)
    }

    @Test
    fun `while one direction's question is asked, the other's isn't`() {
        signedIn(mode = SyncMode.ON)
        val both = RunReport(
            ReportKind.SYNC, 2_000, null,
            SyncPlan(emptyList(), emptyList(), bulkGuardRemote = true, bulkGuardPhone = true, phoneDeletionsWaiting = 12, remoteDeletionsWaiting = 15),
        )
        graph.request = { _, _ -> both.also { stored(it) } }
        model.refresh()
        model.syncNow()

        model.askDeletePhoneDeletions()
        model.askDeleteRemoteDeletions()
        assertEquals(12 to null, ui().confirmingDeletions to ui().confirmingRemoteDeletions)

        model.cancelPhoneDeletions()
        model.askDeleteRemoteDeletions()
        model.askDeletePhoneDeletions()
        assertEquals(null to 15, ui().confirmingDeletions to ui().confirmingRemoteDeletions)
    }

    @Test
    fun `each line says whether it is held`() {
        // What goes to Proton is carried out, and so are deletions from Proton.
        assertEquals(
            setOf(ReportLine.CONFLICTS, ReportLine.CANT_BE_SYNCED, ReportLine.DUPLICATE_ROWS, ReportLine.MISSING_ROWS),
            ReportLine.entries.filter { it.held }.toSet(),
        )
    }

    @Test
    fun `the upgrade notice shows while it's set, whatever the account's state`() {
        // docs/sync.md § Sync mode: the notice after an upgrade from a download-only version.
        signedIn()
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60, upgradeNotice = true)
        model.refresh()
        assertTrue(ui().upgradeNotice)

        graph.readable.clear()
        model.refresh()
        assertTrue(ui().upgradeNotice, "session expired")

        graph.records = listOf(AccountRecordEntity("a1", "one@example.test", LoginState.SIGNED_OUT))
        model.refresh()
        assertTrue(ui().upgradeNotice, "signed out")

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60, upgradeNotice = true)
        model.refresh()
        assertTrue(ui().upgradeNotice, "turning sync on again doesn't dismiss it")
    }

    @Test
    fun `an account that was in dry run, or a new one, never shows the upgrade notice`() {
        signedIn(mode = SyncMode.ON)
        model.refresh()
        assertFalse(ui().upgradeNotice)

        graph.settings.clear()
        model.refresh()
        assertFalse(ui().upgradeNotice, "no settings row: the defaults")

        graph.records = emptyList()
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60, upgradeNotice = true)
        model.refresh()
        assertFalse(ui().upgradeNotice, "not the account shown")
    }

    @Test
    fun `dismissing the upgrade notice clears it for the shown account`() {
        signedIn()
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60, upgradeNotice = true)
        model.refresh()

        model.dismissUpgradeNotice()

        assertEquals(listOf("dismissUpgradeNotice a1"), graph.calls)
        assertFalse(ui().upgradeNotice)
        assertFalse(graph.settings.getValue("a1").upgradeNotice)
    }

    @Test
    fun `a dismissal that fails keeps the notice, and one without the notice does nothing`() {
        signedIn()
        model.refresh()
        model.dismissUpgradeNotice()
        assertEquals(emptyList<String>(), graph.calls, "nothing to dismiss")

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60, upgradeNotice = true)
        graph.dismissFailure = IOException("made-up failure")
        model.refresh()
        model.dismissUpgradeNotice()

        assertEquals(listOf("dismissUpgradeNotice a1"), graph.calls)
        assertTrue(ui().upgradeNotice)
        assertFalse(ui().readFailed)
    }

    @Test
    fun `Sync now reconciles, then asks for a dry run in dry-run mode`() {
        signedIn()
        model.refresh()
        model.syncNow()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `Sync now asks for a real sync with sync on`() {
        signedIn(mode = SyncMode.ON)
        model.refresh()
        model.syncNow()
        assertEquals(listOf("reconcile", "request a1 FULL"), graph.calls)
    }

    @Test
    fun `Sync now for a signed-out account asks for a dry run, which says why it can't sync`() {
        graph.records = listOf(AccountRecordEntity("a1", "one@example.test", LoginState.SIGNED_OUT))
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        model.refresh()
        model.syncNow()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `Sync now's report shows the names in each line, a refused contact under can't be synced with its reason`() {
        // Each "can't be synced" name carries its reason; an edit its upload found changed on Proton moves to the
        // conflicts, or out of the counts when settled.
        signedIn(mode = SyncMode.ON)
        val refusedOne = item(PlanCategory.NEW_FROM_PROTON, "Refused One")
        val refusedRewrite = item(PlanCategory.WRITTEN_BEFORE_KEYS, "Refused Rewrite")
        val refusedSettled = item(PlanCategory.SETTLED, "Refused Settled")
        val refusedEdit = item(PlanCategory.CHANGED_ON_PHONE, "Refused Edit")
        val conflictAtUpload = item(PlanCategory.CHANGED_ON_PHONE, "Conflict At Upload")
        val settledAtUpload = item(PlanCategory.CHANGED_ON_PHONE, "Settled At Upload")
        // An unreadable contact's tombstone is a phone deletion: a reason only under can't be synced.
        val unreadableTombstone = { name: String ->
            PlanItem(PlanCategory.DELETED_ON_PHONE, null, RemoteContact.Unreadable(ProtonContactId("proton-id-8"), Fingerprint("v1:x"), UnreadableReason.VERIFY), null, name, true)
        }
        val conflictAtDelete = unreadableTombstone("Conflict At Delete")
        // A missing row's Restore here taken back during the run: listed as held again.
        val restoreTakenBack = item(PlanCategory.NEW_FROM_PROTON, "Restore Taken Back")
        val unreadable = PlanItem(
            PlanCategory.UNREADABLE, null, RemoteContact.Unreadable(ProtonContactId("proton-id-9"), Fingerprint("v1:x"), UnreadableReason.VERIFY), null, "Unreadable One", false,
        )
        val plan = SyncPlan(
            listOf(
                item(PlanCategory.NEW_FROM_PROTON, "New One"), refusedOne, item(PlanCategory.NEW_FROM_PROTON, null),
                item(PlanCategory.CHANGED_ON_PROTON, "Changed One"), item(PlanCategory.DELETED_ON_PROTON, "Gone One"),
                item(PlanCategory.NEW_ON_PHONE, "Phone New"), item(PlanCategory.CHANGED_ON_PHONE, "Phone Changed"),
                item(PlanCategory.DELETED_ON_PHONE, "Phone Gone"), item(PlanCategory.CONFLICT, "Both Changed"),
                unreadable, item(PlanCategory.DUPLICATE_ROWS, "Twice"),
                item(PlanCategory.MISSING_ROW, "Missing One"), item(PlanCategory.EDITED_BEFORE_KEYS, "Edited Before Keys"),
                // Written again from Proton for its keys: in no line, unless refused.
                item(PlanCategory.WRITTEN_BEFORE_KEYS, "Rewritten"), refusedRewrite,
                // A settled edit is written from Proton too; one with rows the app can't read back can't be synced.
                item(PlanCategory.SETTLED, "Settled"), refusedSettled, item(PlanCategory.UNMAPPED_ROWS, "Unmapped Rows"),
                item(PlanCategory.SETTLED_REMOTE_CHANGED, "Settled, Changed There"), refusedEdit, conflictAtUpload, settledAtUpload,
                // Deleted on both sides: the removal is finished, in no line.
                item(PlanCategory.DELETED_ON_BOTH, "Gone On Both"), conflictAtDelete, unreadableTombstone("Unreadable Gone"),
                restoreTakenBack,
            ),
            emptyList(), bulkGuardRemote = false, bulkGuardPhone = false,
        )
        val run = RunReport(
            ReportKind.SYNC, 2_000, null, plan,
            listOf(
                Refusal(refusedOne, NotSyncedReason.PHOTO_OVERSIZED), Refusal(refusedRewrite, NotSyncedReason.VALUE_REJECTED),
                Refusal(refusedSettled, NotSyncedReason.WRITE_TOO_LARGE), Refusal(refusedEdit, NotSyncedReason.NO_NAME_OR_EMAIL),
            ),
            listOf(
                Reclassified(conflictAtUpload, PlanCategory.CONFLICT), Reclassified(settledAtUpload, PlanCategory.SETTLED_REMOTE_CHANGED),
                Reclassified(conflictAtDelete, PlanCategory.CONFLICT),
                Reclassified(restoreTakenBack, PlanCategory.MISSING_ROW, withdrawn = true),
            ),
        )
        graph.request = { _, _ -> run.also { stored(it) } }
        model.refresh()

        model.syncNow()

        val report = checkNotNull(ui().report)
        assertEquals(
            mapOf(
                ReportLine.NEW_FROM_PROTON to listOf(ReportName("New One"), ReportName(null)),
                ReportLine.CHANGED_ON_PROTON to listOf(ReportName("Changed One")),
                ReportLine.DELETED_ON_PROTON to listOf(ReportName("Gone One")),
                ReportLine.NEW_ON_PHONE to listOf(ReportName("Phone New")),
                ReportLine.CHANGED_ON_PHONE to listOf(ReportName("Phone Changed")),
                ReportLine.DELETED_ON_PHONE to listOf(ReportName("Phone Gone"), ReportName("Unreadable Gone")),
                ReportLine.CONFLICTS to listOf(
                    ReportName("Both Changed"), ReportName("Edited Before Keys"), ReportName("Conflict At Upload"), ReportName("Conflict At Delete"),
                ),
                ReportLine.CANT_BE_SYNCED to listOf(
                    ReportName("Unreadable One", CantSyncReason.VERIFY), ReportName("Unmapped Rows", CantSyncReason.UNMAPPED_ROWS),
                    ReportName("Refused One", CantSyncReason.PHOTO_OVERSIZED), ReportName("Refused Rewrite", CantSyncReason.VALUE_REJECTED),
                    ReportName("Refused Settled", CantSyncReason.WRITE_TOO_LARGE), ReportName("Refused Edit", CantSyncReason.NO_NAME_OR_EMAIL),
                ),
                ReportLine.DUPLICATE_ROWS to listOf(ReportName("Twice")),
                ReportLine.MISSING_ROWS to listOf(ReportName("Missing One"), ReportName("Restore Taken Back")),
            ),
            report.names,
        )
        assertEquals(report.names!!.mapValues { it.value.size }, report.counts)
    }

    @Test
    fun `every reason a sync gives is the one shown`() {
        signedIn(mode = SyncMode.ON)
        val refused = NotSyncedReason.entries.map { Refusal(item(PlanCategory.CHANGED_ON_PHONE, it.name), it) }
        val unreadable = UnreadableReason.entries.map {
            PlanItem(PlanCategory.UNREADABLE, null, RemoteContact.Unreadable(ProtonContactId("proton-id-$it"), Fingerprint("v1:x"), it), null, it.name, false)
        }
        val run = RunReport(ReportKind.SYNC, 2_000, null, SyncPlan(unreadable + refused.map { it.item }, emptyList(), false, false), refused)
        graph.request = { _, _ -> run.also { stored(it) } }
        model.refresh()

        model.syncNow()

        val shown = checkNotNull(ui().report?.names).getValue(ReportLine.CANT_BE_SYNCED)
        assertEquals(UnreadableReason.entries.map { it.name } + NotSyncedReason.entries.map { it.name }, shown.map { it.name })
        assertTrue(shown.all { it.reason?.name == it.name }, shown.toString())
    }

    @Test
    fun `a report with no plan has empty names`() {
        signedIn()
        graph.request = { _, _ -> report(Blocker.PERMISSION_MISSING).also { stored(it) } }
        model.refresh()

        model.syncNow()

        assertEquals(ReportLine.entries.associateWith { emptyList<ReportName>() }, ui().report?.names)
        assertEquals(Blocker.PERMISSION_MISSING, ui().report?.blocker)
    }

    @Test
    fun `names go once a later run's report replaces the one Sync now got`() {
        signedIn()
        graph.request = { _, _ -> report(null, finishedAt = 2_000).also { stored(it) } }
        model.refresh()
        model.syncNow()
        assertEquals(2_000, ui().report?.finishedAt)

        stored(report(null, finishedAt = 3_000))
        model.refresh()

        assertEquals(3_000, ui().report?.finishedAt)
        assertNull(ui().report?.names)
    }

    @Test
    fun `names need the same kind of report too`() {
        signedIn()
        graph.request = { _, _ -> report(null, finishedAt = 2_000).also { stored(it) } }
        model.refresh()
        model.syncNow()

        stored(RunReport(ReportKind.SYNC, 2_000, null, SyncPlan(emptyList(), emptyList(), false, false)))
        model.refresh()

        assertNull(ui().report?.names)
    }

    // The live report: while the screen follows, a report any run saves is read at once.

    @Test
    fun `a report saved while the screen follows is shown at once, with its counts and no names`() {
        signedIn(mode = SyncMode.ON)
        model.refresh()
        model.follow()

        stored(RunReport(ReportKind.SYNC, 3_000, null, SyncPlan(listOf(item(PlanCategory.NEW_FROM_PROTON, "New One")), emptyList(), false, false)))
        graph.reportSaved()

        assertEquals(3_000, ui().report?.finishedAt)
        assertEquals(1, ui().report?.counts?.get(ReportLine.NEW_FROM_PROTON))
        assertNull(ui().report?.names)
    }

    @Test
    fun `Sync now's names stay while its report is still the stored one, and go with a later run's`() {
        signedIn()
        graph.request = { _, _ -> RunReport(ReportKind.DRY_RUN, 2_000, null, SyncPlan(listOf(item(PlanCategory.NEW_FROM_PROTON, "New One")), emptyList(), false, false)).also { stored(it) } }
        model.refresh()
        model.follow()
        model.syncNow()

        graph.reportSaved()
        assertEquals(listOf(ReportName("New One")), ui().report?.names?.get(ReportLine.NEW_FROM_PROTON))

        stored(report(null, finishedAt = 3_000))
        graph.reportSaved()
        assertEquals(3_000, ui().report?.finishedAt)
        assertNull(ui().report?.names)
    }

    @Test
    fun `a report saved while the screen's own run goes is read when that run ends`() {
        signedIn()
        val answer = CompletableDeferred<RunReport?>()
        graph.request = { _, _ -> answer.await() }
        model.refresh()
        model.follow()
        model.syncNow()
        val readsBefore = graph.reads

        stored(report(null, finishedAt = 3_000))
        graph.reportSaved()
        assertEquals(readsBefore, graph.reads)
        assertNull(ui().report)

        answer.complete(report(null, finishedAt = 4_000).also { stored(it) })
        assertEquals(4_000, ui().report?.finishedAt)
        assertFalse(ui().running)
    }

    /**
     * Starts one of the screen's own runs with [start]; its closing read takes the stored row, then a newer run saves.
     */
    private fun savedDuringClosingRead(start: () -> Unit) {
        model.refresh()
        model.follow()
        val gate = CompletableDeferred<Unit>()
        graph.reportGate = { gate.await() }
        start()
        assertTrue(ui().running)
        graph.reportGate = null
        stored(report(null, finishedAt = 9_000))
        graph.reportSaved()
        gate.complete(Unit)
    }

    @Test
    fun `a report saved during Sync now's closing read is read once Sync now has ended`() {
        signedIn()
        graph.request = { _, _ -> report(null, finishedAt = 2_000).also { stored(it) } }
        savedDuringClosingRead { model.syncNow() }

        assertEquals(9_000, ui().report?.finishedAt)
        assertFalse(ui().running)

        // Once read, it isn't read again: the next Sync now reads once, as a refresh does.
        val beforeRefresh = graph.reads
        model.refresh()
        val oneRead = graph.reads - beforeRefresh
        val beforeSync = graph.reads
        model.syncNow()
        assertEquals(oneRead, graph.reads - beforeSync)
    }

    @Test
    fun `a report saved while the read after a run's end goes is read too`() {
        signedIn()
        graph.request = { _, _ -> report(null, finishedAt = 2_000).also { stored(it) } }
        model.refresh()
        model.follow()
        val closing = CompletableDeferred<Unit>()
        val again = CompletableDeferred<Unit>()
        val gates = ArrayDeque(listOf(closing, again))
        graph.reportGate = { gates.removeFirstOrNull()?.await() }
        model.syncNow() // its closing read takes the 2_000 row, then waits

        stored(report(null, finishedAt = 9_000))
        graph.reportSaved()
        closing.complete(Unit) // the end is shown; the read again takes the 9_000 row, then waits
        assertFalse(ui().running)
        stored(report(null, finishedAt = 10_000))
        graph.reportSaved()
        again.complete(Unit)

        assertEquals(10_000, ui().report?.finishedAt)
    }

    @Test
    fun `a report saved during the check's closing read, before Start syncing, is read once the check has ended`() {
        signedIn()
        graph.request = { _, _ -> report(null, finishedAt = 2_000).also { stored(it) } }
        savedDuringClosingRead { model.turnOn() }

        assertEquals(9_000, ui().report?.finishedAt)
        assertFalse(ui().running)
    }

    @Test
    fun `a report saved during Start's closing read is read once Start has ended`() {
        signedIn()
        graph.request = { _, _ -> report(null, finishedAt = 2_000).also { stored(it) } }
        model.refresh()
        model.turnOn()
        assertEquals(ModeChange.Confirm, ui().modeChange)
        savedDuringClosingRead { model.confirm() }

        assertEquals(9_000, ui().report?.finishedAt)
        assertFalse(ui().running)
    }

    @Test
    fun `a report saved during a failed Start's closing read is read once Start has ended`() {
        signedIn()
        graph.request = { _, _ -> report(null, finishedAt = 2_000).also { stored(it) } }
        model.refresh()
        model.turnOn()
        graph.setSyncModeFailure = IllegalStateException("made-up failure")
        savedDuringClosingRead { model.confirm() }

        assertEquals(9_000, ui().report?.finishedAt)
        assertEquals(ModeChange.Failed, ui().modeChange)
        assertFalse(ui().running)
    }

    @Test
    fun `a report saved during turning off's closing read is read once it has ended`() {
        signedIn(mode = SyncMode.ON)
        savedDuringClosingRead { model.turnOff() }

        assertEquals(9_000, ui().report?.finishedAt)
        assertFalse(ui().running)
    }

    @Test
    fun `once the screen stops following, a saved report waits for the next read`() {
        signedIn()
        model.refresh()
        model.follow().cancel()

        stored(report(null, finishedAt = 3_000))
        graph.reportSaved()
        assertNull(ui().report)

        model.refresh()
        assertEquals(3_000, ui().report?.finishedAt)
    }

    @Test
    fun `a watch that fails changes nothing on screen`() {
        signedIn()
        model.refresh()
        val shown = ui()
        graph.reportChangesFailure = IllegalStateException("made-up failure")

        model.follow()

        assertEquals(shown, ui())
    }

    @Test
    fun `while Sync now runs it is running, and another tap asks for nothing`() {
        signedIn()
        val answer = CompletableDeferred<RunReport?>()
        graph.request = { _, _ -> answer.await() }
        model.refresh()

        model.syncNow()
        assertTrue(ui().running)
        model.syncNow()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)

        answer.complete(report(null).also { stored(it) })
        assertFalse(ui().running)
        assertNull(ui().outcome)
    }

    @Test
    fun `a read while Sync now runs keeps it running`() {
        signedIn()
        val answer = CompletableDeferred<RunReport?>()
        graph.request = { _, _ -> answer.await() }
        model.refresh()
        model.syncNow()

        model.refresh()
        assertTrue(ui().running)
        model.syncNow()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `a read after Sync now keeps its outcome`() {
        signedIn()
        model.refresh()
        model.syncNow()

        model.refresh()
        assertEquals(SyncOutcome.DIDNT_RUN, ui().outcome)
    }

    @Test
    fun `a request that ran nothing says it didn't run`() {
        signedIn()
        model.refresh()
        model.syncNow()
        assertEquals(SyncOutcome.DIDNT_RUN, ui().outcome)
        assertFalse(ui().running)
    }

    @Test
    fun `a reconcile that fails is a failed sync, and nothing is requested`() {
        signedIn()
        graph.reconcile = { throw IllegalStateException("made-up failure") }
        model.refresh()
        model.syncNow()
        assertEquals(SyncOutcome.FAILED, ui().outcome)
        assertEquals(listOf("reconcile"), graph.calls)
        assertFalse(ui().running)
    }

    @Test
    fun `a run that fails is a failed sync`() {
        signedIn()
        graph.request = { _, _ -> throw IOException("made-up failure") }
        model.refresh()
        model.syncNow()
        assertEquals(SyncOutcome.FAILED, ui().outcome)
    }

    @Test
    fun `the next Sync now clears the last outcome`() {
        signedIn()
        model.refresh()
        model.syncNow()
        assertEquals(SyncOutcome.DIDNT_RUN, ui().outcome)

        val answer = CompletableDeferred<RunReport?>()
        graph.request = { _, _ -> answer.await() }
        model.syncNow()
        assertNull(ui().outcome)
    }

    @Test
    fun `Sync now reads the state again afterwards`() {
        signedIn()
        graph.request = { _, _ ->
            graph.records = listOf(AccountRecordEntity("a1", "one@example.test", LoginState.SIGNED_OUT))
            null
        }
        model.refresh()
        model.syncNow()
        assertEquals(AccountUi.SignedOut("one@example.test"), ui().account)
    }

    @Test
    fun `a read that fails keeps what was shown and says so, until a read works`() {
        signedIn()
        model.refresh()
        val shown = ui()

        graph.readFailure = IllegalStateException("made-up failure")
        model.refresh()
        assertEquals(shown.copy(readFailed = true), ui())

        graph.readFailure = null
        model.refresh()
        assertEquals(shown, ui())
    }

    @Test
    fun `after a read that fails, Sync now asks for the account shown, in its mode`() {
        signedIn(id = "a1", name = "one@example.test", mode = SyncMode.ON)
        model.refresh()

        graph.records = emptyList()
        signedIn(id = "a2", name = "two@example.test", mode = SyncMode.DRY_RUN)
        graph.settingsFailure = IllegalStateException("made-up failure")
        model.refresh()
        assertEquals(AccountUi.SignedIn("one@example.test"), ui().account)
        assertTrue(ui().readFailed)

        model.syncNow()
        assertEquals(listOf("reconcile", "request a1 FULL"), graph.calls)
    }

    @Test
    fun `a read that ends after a newer one has shown its state is dropped`() {
        signedIn(id = "a1", name = "one@example.test")
        val gate = CompletableDeferred<Unit>()
        graph.accountsGate = { gate.await() }
        model.refresh() // reads a1's rows, then waits

        graph.accountsGate = null
        graph.records = emptyList()
        signedIn(id = "a2", name = "two@example.test")
        model.refresh()
        assertEquals(AccountUi.SignedIn("two@example.test"), ui().account)

        gate.complete(Unit)
        assertEquals(AccountUi.SignedIn("two@example.test"), ui().account)
        model.syncNow()
        assertEquals(listOf("reconcile", "request a2 DRY_RUN"), graph.calls)
    }

    @Test
    fun `names need the same account too`() {
        signedIn(id = "a1", name = "one@example.test")
        graph.request = { _, _ -> report(null, finishedAt = 2_000).also { stored(it, "a1") } }
        model.refresh()
        model.syncNow()

        graph.records = emptyList()
        signedIn(id = "a2", name = "two@example.test")
        stored(report(null, finishedAt = 2_000), "a2")
        model.refresh()

        assertNull(ui().report?.names)
    }

    @Test
    fun `the account's name stays out of the state's toString`() {
        listOf(AccountUi.SignedOut("one@example.test"), AccountUi.SessionExpired("one@example.test"), AccountUi.SignedIn("one@example.test"))
            .forEach { assertFalse("one@example.test" in it.toString(), it::class.simpleName) }
        assertFalse("one@example.test" in MainUi(account = AccountUi.SignedIn("one@example.test")).toString())
    }

    @Test
    fun `a first read that fails shows only that it failed`() {
        graph.readFailure = IllegalStateException("made-up failure")
        model.refresh()
        assertEquals(MainUi(readFailed = true), ui())
    }

    private fun item(category: PlanCategory, name: String?) =
        PlanItem(category, protonId = null, remote = null, row = null, displayName = name, carriedOut = false)

    private fun report(blocker: Blocker?, finishedAt: Long = 2_000) = RunReport(ReportKind.DRY_RUN, finishedAt, blocker, plan = null)
}

/** What the coordinator stores for [this] (ContactSyncEngine's lastReport, which is internal to :sync). */
internal fun RunReport.toRow(account: String): LastReportEntity {
    val counts = counts()
    return LastReportEntity(
        account, kind, finishedAt, ready, blocker, counts.newFromProton, counts.changedOnProton, counts.deletedOnProton,
        counts.newOnPhone, counts.changedOnPhone, counts.deletedOnPhone, counts.conflicts, counts.bulkGuardRemote,
        counts.bulkGuardPhone, counts.unreadable, counts.duplicateRows, counts.missingRows,
    )
}
