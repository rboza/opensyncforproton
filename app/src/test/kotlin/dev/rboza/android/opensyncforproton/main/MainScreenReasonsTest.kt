package dev.rboza.android.opensyncforproton.main

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.proton.Fingerprint
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.NotSyncedReason
import dev.rboza.android.opensyncforproton.sync.engine.Refusal
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.RemoteContact
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import dev.rboza.android.opensyncforproton.sync.plan.UnreadableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** The main screen's "Can't be synced" list shows each name with its reason. Every name is made up for the test. */
@RunWith(RobolectricTestRunner::class)
class MainScreenReasonsTest {
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

    private fun root(view: View): ViewRootForTest? = when (view) {
        is ViewRootForTest -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { root(view.getChildAt(it)) }
        else -> null
    }

    private fun nodes(): List<SemanticsNode> = checkNotNull(root(controller.get().window.decorView)).semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)

    private fun texts(): List<String> = nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** What each node shows: a report line shows its name and its count apart. */
    private fun rows(): List<List<String>> = nodes().map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    @Test
    fun `each name under can't be synced shows its reason`() {
        val unreadable = UnreadableReason.entries.map { reason ->
            PlanItem(PlanCategory.UNREADABLE, null, RemoteContact.Unreadable(ProtonContactId("proton-id-$reason"), Fingerprint("v1:x"), reason), null, "Name $reason", false)
        }
        val unmapped = PlanItem(PlanCategory.UNMAPPED_ROWS, null, null, null, "Name unmapped", false)
        val refused = NotSyncedReason.entries.map { Refusal(PlanItem(PlanCategory.CHANGED_ON_PHONE, null, null, null, "Name $it", true), it) }
        val run = RunReport(ReportKind.SYNC, 2_000, null, SyncPlan(unreadable + unmapped + refused.map { it.item }, emptyList(), false, false), refused)
        val count = unreadable.size + 1 + refused.size
        graph.request = { _, _ ->
            graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 2_000, true, null, 0, 0, 0, 0, 0, 0, 0, false, false, count, 0, 0)
            run
        }
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { MainScreen(model, onLogIn = {}, onOpenAppSettings = {}, onOpenSettings = {}, notes = {}) }
        idle()
        model.syncNow()
        idle()

        val line = listOf(controller.get().getString(R.string.main_line_cant_be_synced) + " (${controller.get().getString(R.string.main_held)})", "$count")
        val open = nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } == line }
        checkNotNull(open.config[SemanticsActions.OnClick].action).invoke()
        idle()

        val shown = texts().filter { it.startsWith("Name ") }
        assertEquals(texts().toString(), count, shown.size)
        val reasons = shown.map { it.substringAfter(": ", missingDelimiterValue = "") }
        assertTrue("every name has a reason: $shown", reasons.none { it.isEmpty() })
        assertEquals("each reason its own text: $shown", count, reasons.toSet().size)
        assertTrue(shown.toString(), "Name ${NotSyncedReason.NO_NAME_OR_EMAIL}: ${controller.get().getString(R.string.main_reason_no_name_or_email)}" in shown)
        // A create held by its UID.
        assertTrue(shown.toString(), "Name ${NotSyncedReason.UID_UNCONFIRMED}: Proton can't confirm it was added" in shown)
    }

    @Test
    fun `contacts deleted on Proton no longer wait for two-way sync, and a held line still says so`() {
        // A real sync deletes them on the phone, so the line has no label.
        val deleted = (1..2).map { PlanItem(PlanCategory.DELETED_ON_PROTON, null, null, null, "Name $it", true) }
        graph.request = { _, _ ->
            graph.reports["a1"] = LastReportEntity("a1", ReportKind.DRY_RUN, 2_000, true, null, 0, 0, 2, 0, 0, 0, 1, false, false, 0, 0, 0)
            RunReport(ReportKind.DRY_RUN, 2_000, null, SyncPlan(deleted, emptyList(), false, false))
        }
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { MainScreen(model, onLogIn = {}, onOpenAppSettings = {}, onOpenSettings = {}, notes = {}) }
        idle()
        model.syncNow()
        idle()

        val line = listOf(controller.get().getString(R.string.main_line_deleted_on_proton), "2")
        assertTrue(rows().toString(), line in rows())
        assertTrue(texts().toString(), texts().none { "two-way" in it })
        val held = listOf(controller.get().getString(R.string.main_line_conflicts) + " (${controller.get().getString(R.string.main_held)})", "1")
        assertTrue(rows().toString(), held in rows())
    }
}
