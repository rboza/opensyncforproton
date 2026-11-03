package dev.rboza.android.opensyncforproton.main

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.backOnDialog
import dev.rboza.android.opensyncforproton.dialogNodes
import dev.rboza.android.opensyncforproton.screenNodes
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The main screen's look: the account card's name, state, and chip; the timing in one line with sync on; **Settings**
 * as a labelled icon; "Start syncing?" and the bulk guard's questions as dialogs (docs/sync.md § Screens and
 * notifications). Names are made up.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenLookTest {
    private val graph = FakeAppGraph().apply {
        records = listOf(AccountRecordEntity("a1", NAME, LoginState.ACTIVE))
        settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60)
        readable += "a1"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = MainModel(graph, scope)
    private lateinit var controller: ActivityController<ComponentActivity>
    private var settingsOpened = 0

    @After
    fun end() {
        controller.pause().stop().destroy()
        idle()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun show() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { MainScreen(model, onLogIn = {}, onOpenAppSettings = {}, onOpenSettings = { settingsOpened++ }, notes = {}) }
        idle()
    }

    private fun nodes(): List<SemanticsNode> = screenNodes(controller.get())

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun dialogTexts(): List<String> = dialogNodes().flatMap { it.texts() }

    private fun string(id: Int, vararg args: Any) = controller.get().getString(id, *args)

    private fun plural(id: Int, count: Int) = controller.get().resources.getQuantityString(id, count, count)

    private fun click(text: String) {
        val node = nodes().single { text in it.texts() && SemanticsActions.OnClick in it.config }
        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        idle()
    }

    /** What the account card shows besides the name: its state and its chip. */
    private fun card(state: Int, chip: Int) {
        assertTrue(texts().toString(), NAME in texts())
        assertTrue(texts().toString(), string(state) in texts())
        assertTrue(texts().toString(), string(chip) in texts())
    }

    @Test
    fun `the account card names the account, its state, and the mode's chip`() {
        show()
        card(R.string.main_state_signed_in, R.string.main_chip_dry_run)

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        model.refresh()
        idle()
        card(R.string.main_state_signed_in, R.string.main_chip_on)

        graph.permission = false
        model.refresh()
        idle()
        card(R.string.main_state_signed_in, R.string.main_chip_paused)

        graph.permission = true
        graph.readable.clear()
        model.refresh()
        idle()
        card(R.string.main_state_session_expired, R.string.main_chip_paused)

        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT))
        model.refresh()
        idle()
        card(R.string.main_state_signed_out, R.string.main_chip_paused)

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60)
        model.refresh()
        idle()
        card(R.string.main_state_signed_out, R.string.main_chip_dry_run)
    }

    @Test
    fun `signed in with keys that don't unlock, the chip says paused while sync is on`() {
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 2_000, false, Blocker.KEYS_DONT_UNLOCK, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0)
        show()

        card(R.string.main_state_signed_in, R.string.main_chip_paused)
        assertTrue(texts().toString(), string(R.string.main_keys_log_in) in texts())
    }

    @Test
    fun `with sync on, the timing shows in one line`() {
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60, 15)
        show()
        val every15 = plural(R.plurals.main_every_minutes, 15)
        val hourly = controller.get().resources.getQuantityString(R.plurals.main_every_hours, 1, 1)
        assertTrue(texts().toString(), string(R.string.main_timing, hourly, every15) in texts())

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 120, null)
        model.refresh()
        idle()
        val every2h = controller.get().resources.getQuantityString(R.plurals.main_every_hours, 2, 2)
        assertTrue(texts().toString(), string(R.string.main_timing_same, every2h) in texts())

        // A check as long as the full sync says the same once.
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 120, 120)
        model.refresh()
        idle()
        assertTrue(texts().toString(), string(R.string.main_timing_same, every2h) in texts())

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60, 15, manual = true)
        model.refresh()
        idle()
        assertTrue(texts().toString(), string(R.string.main_timing_manual) in texts())

        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60, 15)
        model.refresh()
        idle()
        assertTrue(texts().toString(), texts().none { it.startsWith(string(R.string.main_timing_manual).substringBefore(':')) || "Full sync" in it })
    }

    @Test
    fun `Settings is an icon with its label, and it opens the settings`() {
        show()
        val settings = nodes().single { string(R.string.main_settings) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue("an icon, not a text button", settings.texts().isEmpty())
        checkNotNull(settings.config[SemanticsActions.OnClick].action).invoke()
        idle()
        assertEquals(1, settingsOpened)
    }

    /** The fresh dry run's answer: [new] new contacts from Proton and [both] changed on both sides, ready. */
    private fun dryRunAnswers(new: Int, both: Int = 0) {
        graph.request = { _, request ->
            val kind = if (request == SyncRequest.DryRun) ReportKind.DRY_RUN else ReportKind.SYNC
            val items = List(new) { PlanItem(PlanCategory.NEW_FROM_PROTON, null, null, null, "Made Up $it", false) }
            graph.reports["a1"] = LastReportEntity("a1", kind, 3_000, true, null, new, 0, 0, 0, 0, 0, both, false, false, 0, 0, 0)
            RunReport(kind, 3_000, null, SyncPlan(items, emptyList(), false, false))
        }
    }

    @Test
    fun `Start syncing is a dialog with the check's counts that aren't zero`() {
        dryRunAnswers(new = 2, both = 1)
        show()
        model.turnOn()
        idle()

        assertTrue(dialogTexts().toString(), string(R.string.main_start_syncing) in dialogTexts())
        assertTrue(dialogTexts().toString(), string(R.string.main_start_syncing_detail) in dialogTexts())
        assertTrue(dialogTexts().toString(), string(R.string.main_line_count, string(R.string.main_line_new_from_proton), 2) in dialogTexts())
        val held = string(R.string.main_line_count, string(R.string.main_line_conflicts), 1) + " (${string(R.string.main_held)})"
        assertTrue(dialogTexts().toString(), held in dialogTexts())
        assertTrue("a zero count stays out", dialogTexts().none { it.startsWith(string(R.string.main_line_changed_on_proton)) })
        assertFalse(string(R.string.main_nothing_to_change) in dialogTexts())

        click(string(R.string.login_cancel))
        assertEquals(emptyList<String>(), dialogTexts())
        assertEquals(ModeChange.Idle, model.state.value?.modeChange)
    }

    @Test
    fun `Back on Start syncing cancels it as Cancel does`() {
        dryRunAnswers(new = 2)
        show()
        model.turnOn()
        idle()
        assertTrue(dialogTexts().toString(), string(R.string.main_start_syncing) in dialogTexts())

        backOnDialog()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())
        assertEquals(ModeChange.Idle, model.state.value?.modeChange)
        assertFalse(model.state.value?.syncOn == true)
    }

    @Test
    fun `Start syncing says nothing changes when every count is zero`() {
        dryRunAnswers(new = 0)
        show()
        model.turnOn()
        idle()

        assertTrue(dialogTexts().toString(), string(R.string.main_nothing_to_change) in dialogTexts())
        click(string(R.string.main_start))
        assertTrue(model.state.value?.syncOn == true)
    }

    @Test
    fun `the bulk guard's question is a dialog`() {
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        graph.request = { _, _ ->
            graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 2_000, true, null, 0, 0, 0, 0, 0, 1, 0, false, true, 0, 0, 0)
            RunReport(
                ReportKind.SYNC, 2_000, null,
                SyncPlan(listOf(PlanItem(PlanCategory.DELETED_ON_PHONE, null, null, null, "Gone Here", false)), emptyList(), false, true, phoneDeletionsWaiting = 12),
            )
        }
        show()
        model.syncNow()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())

        click(plural(R.plurals.main_phone_guard_delete, 12))
        assertTrue(dialogTexts().toString(), plural(R.plurals.main_phone_guard_question, 12) in dialogTexts())
        assertTrue(dialogTexts().toString(), string(R.string.main_phone_guard_question_detail) in dialogTexts())
        click(string(R.string.login_cancel))
        assertEquals(emptyList<String>(), dialogTexts())

        // Back cancels it as Cancel does: nothing is deleted.
        click(plural(R.plurals.main_phone_guard_delete, 12))
        assertTrue(dialogTexts().toString(), plural(R.plurals.main_phone_guard_question, 12) in dialogTexts())
        backOnDialog()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())
        assertEquals(null, model.state.value?.confirmingDeletions)
        assertEquals(emptyList<String>(), graph.calls.filter { "confirming" in it })
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
    }
}
