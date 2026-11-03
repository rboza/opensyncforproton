package dev.rboza.android.opensyncforproton.main

import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Turning sync on (permission, a fresh dry run, "Start syncing?") and off, and a revoked permission with sync on
// (docs/sync.md § Sync mode). Names and IDs are made up.
@OptIn(ExperimentalCoroutinesApi::class)
class ModeSwitchTest {
    private val errors = mutableListOf<Throwable>()
    private val scope = CoroutineScope(Job() + UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> errors += e })
    private val graph = FakeAppGraph()
    private val model = MainModel(graph, scope)

    @AfterEach
    fun noUncaughtErrors() = assertEquals(emptyList<Throwable>(), errors)

    private fun ui() = checkNotNull(model.state.value)

    private fun signedIn(mode: SyncMode = SyncMode.DRY_RUN) {
        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.ACTIVE))
        graph.settings["a1"] = SettingsEntity("a1", mode, 60)
        graph.readable += "a1"
        model.refresh()
    }

    /** The coordinator's answer to a dry run: [blocker], one new contact, stored as it would be. */
    private fun dryRunAnswers(blocker: Blocker? = null) {
        graph.request = { _, request ->
            val kind = if (request == SyncRequest.DryRun) ReportKind.DRY_RUN else ReportKind.SYNC
            val plan = SyncPlan(listOf(PlanItem(PlanCategory.NEW_FROM_PROTON, null, null, null, CONTACT, false)), emptyList(), false, false)
            val at = 1_000L + graph.calls.size
            graph.reports["a1"] = LastReportEntity("a1", kind, at, blocker == null, blocker, 1, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0)
            RunReport(kind, at, blocker, plan)
        }
    }

    @Test
    fun `turning on is offered only signed in in dry run, turning off whenever sync is on`() {
        model.refresh()
        assertFalse(ui().offersTurnOn)
        assertFalse(ui().offersTurnOff)

        signedIn()
        assertTrue(ui().offersTurnOn)
        assertFalse(ui().offersTurnOff)

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        model.refresh()
        assertFalse(ui().offersTurnOn)
        assertTrue(ui().offersTurnOff)

        graph.readable.clear()
        model.refresh()
        assertTrue(ui().offersTurnOff, "session expired with sync on")

        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT))
        model.refresh()
        assertTrue(ui().offersTurnOff, "signed out with sync on")
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60)
        model.refresh()
        assertFalse(ui().offersTurnOn, "signed out in dry run")
    }

    @Test
    fun `turning on with the permission runs a fresh dry run and asks Start syncing when it's ready`() {
        signedIn()
        dryRunAnswers()

        model.turnOn()

        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
        assertEquals(ModeChange.Confirm, ui().modeChange)
        assertEquals(listOf(ReportName(CONTACT)), ui().report?.names?.get(ReportLine.NEW_FROM_PROTON))
        assertFalse(ui().syncOn)
        assertFalse(ui().running)
    }

    @Test
    fun `while the fresh dry run runs, it's checking and nothing else can start`() {
        signedIn()
        val answer = CompletableDeferred<RunReport?>()
        graph.request = { _, _ -> answer.await() }

        model.turnOn()
        assertEquals(ModeChange.Checking, ui().modeChange)
        assertTrue(ui().running)
        model.syncNow()
        model.turnOn()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `a fresh dry run that isn't ready says so and the mode stays dry run`() {
        signedIn()
        dryRunAnswers(Blocker.OFFLINE)

        model.turnOn()

        assertEquals(ModeChange.NotReady, ui().modeChange)
        assertEquals(Blocker.OFFLINE, ui().report?.blocker)
        assertFalse(ui().syncOn)
        model.confirm()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `a fresh dry run that ran nothing or failed ends the change with its outcome`() {
        signedIn()
        model.turnOn()
        assertEquals(ModeChange.Idle, ui().modeChange)
        assertEquals(SyncOutcome.DIDNT_RUN, ui().outcome)

        graph.request = { _, _ -> throw IOException("made-up failure") }
        model.turnOn()
        assertEquals(ModeChange.Idle, ui().modeChange)
        assertEquals(SyncOutcome.FAILED, ui().outcome)
        assertFalse(ui().running)
    }

    @Test
    fun `Start turns sync on, then runs the first real sync as Sync now does, its report saying Start began it`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()

        model.confirm()

        assertEquals(
            listOf("reconcile", "request a1 DRY_RUN", "setSyncMode a1 ON", "reconcile", "request a1 FULL started by START"),
            graph.calls,
        )
        assertEquals(ModeChange.Idle, ui().modeChange)
        assertTrue(ui().syncOn)
        assertEquals(ReportKind.SYNC, ui().report?.kind)
        assertFalse(ui().running)
    }

    @Test
    fun `once sync is on, the screen asks for the notification permission if it's missing, once`() {
        signedIn()
        dryRunAnswers()
        graph.notificationPermission = false
        model.turnOn()
        assertFalse(ui().asksNotifications)

        model.confirm()
        assertTrue(ui().asksNotifications)
        model.refresh()
        assertTrue(ui().asksNotifications, "a read keeps it")

        model.notificationsAsked()
        assertFalse(ui().asksNotifications)
    }

    @Test
    fun `with the notification permission, or a mode change that failed, nothing is asked`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()
        model.confirm()
        assertFalse(ui().asksNotifications)

        signedIn()
        graph.notificationPermission = false
        graph.setSyncModeFailure = IllegalStateException("made-up failure")
        model.turnOn()
        model.confirm()
        assertFalse(ui().asksNotifications)
    }

    @Test
    fun `while sync is turned on, it's starting and nothing else can start`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()
        val gate = CompletableDeferred<Unit>()
        graph.setSyncModeGate = { gate.await() }

        model.confirm()
        assertEquals(ModeChange.Starting, ui().modeChange)
        assertTrue(ui().running)
        model.confirm()
        model.syncNow()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN", "setSyncMode a1 ON"), graph.calls)
    }

    @Test
    fun `a mode change that fails says so and runs no sync`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()
        graph.setSyncModeFailure = IllegalStateException("made-up failure")

        model.confirm()

        assertEquals(ModeChange.Failed, ui().modeChange)
        assertEquals(listOf("reconcile", "request a1 DRY_RUN", "setSyncMode a1 ON"), graph.calls)
        assertFalse(ui().syncOn)
        assertFalse(ui().running)
    }

    @Test
    fun `the first sync's failure is shown like Sync now's, with sync left on`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()
        graph.request = { _, _ -> throw IOException("made-up failure") }

        model.confirm()

        assertEquals(SyncOutcome.FAILED, ui().outcome)
        assertEquals(ModeChange.Idle, ui().modeChange)
        assertTrue(ui().syncOn)
    }

    @Test
    fun `Start syncing is dropped when a read finds the account signed out, and Start then does nothing`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()

        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT))
        model.refresh()
        assertEquals(ModeChange.Idle, ui().modeChange)
        model.confirm()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `Start syncing is dropped when a read shows another account, whose own check never ran`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()

        graph.records = listOf(AccountRecordEntity("a2", "two@example.test", LoginState.ACTIVE))
        graph.settings["a2"] = SettingsEntity("a2")
        graph.readable += "a2"
        model.refresh()
        assertEquals(ModeChange.Idle, ui().modeChange)
        model.confirm()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `a not-ready check's message is dropped when a read shows another account`() {
        signedIn()
        dryRunAnswers(Blocker.OFFLINE)
        model.turnOn()
        assertEquals(ModeChange.NotReady, ui().modeChange)

        graph.records = listOf(AccountRecordEntity("a2", "two@example.test", LoginState.ACTIVE))
        graph.readable += "a2"
        model.refresh()
        assertEquals(ModeChange.Idle, ui().modeChange)
    }

    @Test
    fun `a pending permission request for turning on is dropped when a read no longer offers turning on`() {
        signedIn()
        graph.permission = false
        model.turnOn()
        model.permissionRequested()

        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT))
        model.refresh()
        assertEquals(ModeChange.Idle, ui().modeChange)
        model.permissionResult(granted = true, canAskAgain = true)
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `a permission request not yet sent is dropped when a read no longer offers turning on`() {
        signedIn()
        graph.permission = false
        model.turnOn()

        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT))
        model.refresh()
        assertEquals(ModeChange.Idle, ui().modeChange)
    }

    @Test
    fun `a pending Grant is dropped when a read finds the permission no longer revoked`() {
        signedIn(SyncMode.ON)
        graph.permission = false
        model.refresh()
        model.grantPermission()
        model.permissionRequested()

        graph.permission = true
        model.refresh()
        assertEquals(ModeChange.Idle, ui().modeChange)
    }

    @Test
    fun `the refusal message goes once a read finds the permission granted, in Android's settings say`() {
        signedIn()
        graph.permission = false
        model.turnOn()
        model.permissionRequested()
        model.permissionResult(granted = false, canAskAgain = false)
        model.refresh()
        assertEquals(ModeChange.PermissionDeclined(openSettings = true), ui().modeChange, "still refused")

        graph.permission = true
        model.refresh()
        assertEquals(ModeChange.Idle, ui().modeChange)
    }

    @Test
    fun `Cancel keeps dry run`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()

        model.cancelModeChange()

        assertEquals(ModeChange.Idle, ui().modeChange)
        model.confirm()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
        assertFalse(ui().syncOn)
    }

    @Test
    fun `while Start syncing is asked, Sync now waits for the answer`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()

        model.syncNow()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `while the permission request is due or on its way, Sync now and the switch wait`() {
        signedIn()
        graph.permission = false
        model.turnOn()
        model.syncNow()
        model.turnOn()
        assertEquals(ModeChange.NeedsPermission(thenCheck = true), ui().modeChange)

        model.permissionRequested()
        model.syncNow()
        model.turnOn()
        assertEquals(ModeChange.AwaitingPermission(thenCheck = true), ui().modeChange)
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `a read while Start syncing is asked keeps the question`() {
        signedIn()
        dryRunAnswers()
        model.turnOn()

        model.refresh()

        assertEquals(ModeChange.Confirm, ui().modeChange)
    }

    @Test
    fun `without the permission, turning on asks for it first, once`() {
        signedIn()
        graph.permission = false
        dryRunAnswers()

        model.turnOn()
        assertEquals(ModeChange.NeedsPermission(thenCheck = true), ui().modeChange)
        assertEquals(emptyList<String>(), graph.calls)

        model.permissionRequested()
        assertEquals(ModeChange.AwaitingPermission(thenCheck = true), ui().modeChange)
        model.permissionRequested()
        assertEquals(ModeChange.AwaitingPermission(thenCheck = true), ui().modeChange)

        graph.permission = true
        model.permissionResult(granted = true, canAskAgain = true)
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
        assertEquals(ModeChange.Confirm, ui().modeChange)
    }

    @Test
    fun `a declined permission keeps dry run, offering the app's settings once Android stops asking`() {
        signedIn()
        graph.permission = false
        model.turnOn()
        model.permissionRequested()
        model.permissionResult(granted = false, canAskAgain = true)
        assertEquals(ModeChange.PermissionDeclined(openSettings = false), ui().modeChange)

        model.turnOn()
        model.permissionRequested()
        model.permissionResult(granted = false, canAskAgain = false)
        assertEquals(ModeChange.PermissionDeclined(openSettings = true), ui().modeChange)

        assertEquals(emptyList<String>(), graph.calls)
        assertFalse(ui().syncOn)
    }

    @Test
    fun `a permission result nobody waits for changes nothing`() {
        signedIn()
        model.permissionResult(granted = true, canAskAgain = true)
        assertEquals(ModeChange.Idle, ui().modeChange)

        graph.permission = false
        model.turnOn()
        model.permissionResult(granted = true, canAskAgain = true)
        assertEquals(ModeChange.NeedsPermission(thenCheck = true), ui().modeChange, "not asked yet")
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `turning on isn't offered, so does nothing, signed out, with sync on, or while Sync now runs`() {
        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT))
        graph.settings["a1"] = SettingsEntity("a1")
        model.refresh()
        model.turnOn()
        assertEquals(ModeChange.Idle, ui().modeChange)

        signedIn(SyncMode.ON)
        model.turnOn()
        assertEquals(ModeChange.Idle, ui().modeChange)

        signedIn()
        val answer = CompletableDeferred<RunReport?>()
        graph.request = { _, _ -> answer.await() }
        model.syncNow()
        model.turnOn()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
        assertEquals(ModeChange.Idle, ui().modeChange)
    }

    @Test
    fun `turning off changes the mode at once, stopping until it's done`() {
        signedIn(SyncMode.ON)
        val gate = CompletableDeferred<Unit>()
        graph.setSyncModeGate = { gate.await() }

        model.turnOff()
        assertEquals(ModeChange.Stopping, ui().modeChange)
        assertTrue(ui().running)
        model.syncNow()
        model.turnOff()
        assertEquals(listOf("setSyncMode a1 DRY_RUN"), graph.calls)

        gate.complete(Unit)
        assertEquals(ModeChange.Idle, ui().modeChange)
        assertFalse(ui().syncOn)
        assertFalse(ui().running)
    }

    @Test
    fun `turning off that fails says so`() {
        signedIn(SyncMode.ON)
        graph.setSyncModeFailure = IllegalStateException("made-up failure")
        model.turnOff()
        assertEquals(ModeChange.Failed, ui().modeChange)
        assertTrue(ui().syncOn)
        assertFalse(ui().running)
    }

    @Test
    fun `turning off does nothing in dry run or while Sync now runs`() {
        signedIn()
        model.turnOff()
        assertEquals(emptyList<String>(), graph.calls)

        signedIn(SyncMode.ON)
        val answer = CompletableDeferred<RunReport?>()
        graph.request = { _, _ -> answer.await() }
        model.syncNow()
        model.turnOff()
        assertEquals(listOf("reconcile", "request a1 FULL"), graph.calls)
    }

    @Test
    fun `a revoked permission with sync on pauses sync, and Grant asks for it without a dry run`() {
        signedIn(SyncMode.ON)
        assertFalse(ui().permissionRevoked)
        graph.permission = false
        model.refresh()
        assertTrue(ui().permissionRevoked)

        model.grantPermission()
        assertEquals(ModeChange.NeedsPermission(thenCheck = false), ui().modeChange)
        model.permissionRequested()
        graph.permission = true
        model.permissionResult(granted = true, canAskAgain = true)

        assertEquals(ModeChange.Idle, ui().modeChange)
        assertFalse(ui().permissionRevoked)
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `Grant declined offers the app's settings once Android stops asking`() {
        signedIn(SyncMode.ON)
        graph.permission = false
        model.refresh()
        model.grantPermission()
        model.permissionRequested()
        model.permissionResult(granted = false, canAskAgain = false)
        assertEquals(ModeChange.PermissionDeclined(openSettings = true), ui().modeChange)
        assertTrue(ui().permissionRevoked)
    }

    @Test
    fun `a missing permission in dry run isn't a revoked one, and Grant then does nothing`() {
        signedIn()
        graph.permission = false
        model.refresh()
        assertFalse(ui().permissionRevoked)
        model.grantPermission()
        assertEquals(ModeChange.Idle, ui().modeChange)
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
        const val CONTACT = "Made-Up Contact"
    }
}
