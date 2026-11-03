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
import dev.rboza.android.opensyncforproton.screenNodes
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
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
 * The report's guard lines, and **Delete N on Proton** and **Delete N on this phone** asking first
 * (docs/sync.md § Deletion). Every name is made up.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenGuardTest {
    private val graph = FakeAppGraph().apply {
        records = listOf(AccountRecordEntity("a1", "someone.made.up@example.test", LoginState.ACTIVE))
        settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        readable += "a1"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = MainModel(graph, scope)
    private lateinit var controller: ActivityController<ComponentActivity>

    @After
    fun end() {
        controller.pause().stop().destroy()
        idle()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The screen's nodes, the questions' dialogs included. */
    private fun nodes(): List<SemanticsNode> = screenNodes(controller.get())

    private fun texts(): List<String> = nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun click(text: String) {
        val node = nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        idle()
    }

    private fun plural(id: Int, count: Int) = controller.get().resources.getQuantityString(id, count, count)

    private fun string(id: Int) = controller.get().getString(id)

    /** A run the guard stopped, stored as the engine would; [remote] and [phone] are the two directions. */
    private fun answer(kind: ReportKind = ReportKind.SYNC, remote: Boolean = false, phone: Boolean = true, waiting: Int = 12, missing: Int = 15) {
        val plan = SyncPlan(
            listOf(PlanItem(PlanCategory.DELETED_ON_PHONE, null, null, null, "Gone Here", false)), emptyList(), remote, phone,
            phoneDeletionsWaiting = waiting, remoteDeletionsWaiting = missing,
        )
        graph.request = { _, _ ->
            graph.reports["a1"] = LastReportEntity("a1", kind, 2_000, true, null, 0, 0, 0, 0, 0, 1, 0, remote, phone, 0, 0, 0)
            RunReport(kind, 2_000, null, plan)
        }
    }

    private fun launch() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { MainScreen(model, onLogIn = {}, onOpenAppSettings = {}, onOpenSettings = {}, notes = {}) }
        idle()
    }

    @Test
    fun `the phone guard's line gives the count, and Delete N on Proton asks before running Sync now with it`() {
        answer()
        launch()
        model.syncNow()
        idle()

        assertTrue(texts().toString(), plural(R.plurals.main_phone_guard_stopped, 12) in texts())
        val remoteLines = listOf(plural(R.plurals.main_remote_guard_stopped, 15), string(R.string.main_remote_guard_stopped_unknown))
        assertTrue("the other direction didn't stop", texts().none { it in remoteLines })
        click(plural(R.plurals.main_phone_guard_delete, 12))

        assertTrue(texts().toString(), plural(R.plurals.main_phone_guard_question, 12) in texts())
        assertTrue(string(R.string.main_phone_guard_question_detail) in texts())
        click(string(R.string.login_cancel))

        assertFalse(plural(R.plurals.main_phone_guard_question, 12) in texts())
        assertEquals(emptyList<String>(), graph.calls.filter { "confirming" in it })
        click(plural(R.plurals.main_phone_guard_delete, 12))
        click(string(R.string.main_delete))

        assertEquals(listOf("request a1 FULL confirming 12"), graph.calls.filter { "confirming" in it })
    }

    @Test
    fun `without the count the line says to tap Sync now, and nothing is offered`() {
        graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 2_000, true, null, 0, 0, 0, 0, 0, 12, 0, false, true, 0, 0, 0)
        launch()
        model.refresh()
        idle()

        assertTrue(texts().toString(), string(R.string.main_phone_guard_stopped_unknown) in texts())
        assertFalse(texts().any { it == plural(R.plurals.main_phone_guard_delete, 12) })
    }

    @Test
    fun `a dry run's lines say it would stop, each direction with its own line`() {
        answer(kind = ReportKind.DRY_RUN, remote = true, phone = true, waiting = 10, missing = 11)
        launch()
        model.syncNow()
        idle()

        assertTrue(texts().toString(), plural(R.plurals.main_phone_guard_would_stop, 10) in texts())
        assertTrue(texts().toString(), plural(R.plurals.main_remote_guard_would_stop, 11) in texts())

        answer(kind = ReportKind.SYNC, remote = true, phone = false)
        model.syncNow()
        idle()
        assertTrue(texts().toString(), plural(R.plurals.main_remote_guard_stopped, 15) in texts())
        assertFalse(texts().any { it.contains("deleted on this phone") })
    }

    @Test
    fun `the remote guard's line gives the count, and Delete N on this phone asks before running Sync now with it`() {
        answer(remote = true, phone = false)
        launch()
        model.syncNow()
        idle()

        click(plural(R.plurals.main_remote_guard_delete, 15))

        assertTrue(texts().toString(), plural(R.plurals.main_remote_guard_question, 15) in texts())
        assertTrue(string(R.string.main_remote_guard_question_detail) in texts())
        click(string(R.string.login_cancel))

        assertFalse(plural(R.plurals.main_remote_guard_question, 15) in texts())
        assertEquals(emptyList<String>(), graph.calls.filter { "confirming" in it })
        click(plural(R.plurals.main_remote_guard_delete, 15))
        click(string(R.string.main_delete))

        assertEquals(listOf("request a1 FULL confirming 15 remote"), graph.calls.filter { "confirming" in it })
    }

    @Test
    fun `while one direction's question is asked, the other's button shows but is disabled`() {
        answer(remote = true, phone = true)
        launch()
        model.syncNow()
        idle()

        click(plural(R.plurals.main_phone_guard_delete, 12))

        val other = nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == plural(R.plurals.main_remote_guard_delete, 15) } }
        assertTrue("busy: it waits", SemanticsProperties.Disabled in other.config)
        click(string(R.string.login_cancel))
        val enabled = nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == plural(R.plurals.main_remote_guard_delete, 15) } }
        assertFalse(SemanticsProperties.Disabled in enabled.config)
    }

    @Test
    fun `without the remote count the line says to tap Sync now, and nothing is offered`() {
        for ((kind, line) in listOf(ReportKind.SYNC to R.string.main_remote_guard_stopped_unknown, ReportKind.DRY_RUN to R.string.main_remote_guard_would_stop_unknown)) {
            graph.reports["a1"] = LastReportEntity("a1", kind, 2_000, true, null, 0, 0, 15, 0, 0, 0, 0, true, false, 0, 0, 0)
            if (!::controller.isInitialized) launch()
            model.refresh()
            idle()

            assertTrue(texts().toString(), string(line) in texts())
            assertFalse(texts().any { it == plural(R.plurals.main_remote_guard_delete, 15) })
        }
    }
}
