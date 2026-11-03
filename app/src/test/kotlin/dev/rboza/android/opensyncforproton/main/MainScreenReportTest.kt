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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import java.text.DateFormat
import java.util.Date
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
 * The main screen reads the report as the latest known state (docs/sync.md § Sync mode): its time line says what
 * started the sync, a side counted earlier says when, and each earlier line says so to TalkBack too, the ones still
 * needing the user as well.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenReportTest {
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

    private fun descriptions(): List<String> = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun time(at: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))

    private fun string(id: Int, vararg args: Any) = controller.get().getString(id, *args)

    private fun line(id: Int, count: Int, held: Boolean = false) =
        string(R.string.main_line_count, string(id), count) + if (held) " (${string(R.string.main_held)})" else ""

    /**
     * A sync's report finished two hours after the epoch (each time here is its own minute): one new from Proton, one
     * new on the phone, two missing rows.
     */
    private fun stored(trigger: ReportTrigger?, blocker: Blocker? = null, proton: Long? = 7_200_000, phone: Long? = 7_200_000, remoteGuard: Boolean = false) =
        LastReportEntity(
            "a1", ReportKind.SYNC, 7_200_000, blocker == null, blocker, 1, 0, 0, 1, 0, 0, 0, remoteGuard, false, 0, 0, 2,
            trigger = trigger, protonCountedAt = proton, phoneCountedAt = phone,
        )

    private fun show(report: LastReportEntity) {
        graph.reports["a1"] = report
        if (!::controller.isInitialized) {
            controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            controller.get().setContent { MainScreen(model, onLogIn = {}, onOpenAppSettings = {}, onOpenSettings = {}, notes = {}) }
        }
        idle()
        model.refresh()
        idle()
    }

    @Test
    fun `the time line says what started the sync`() {
        for ((trigger, label) in listOf(
            ReportTrigger.SYNC_NOW to "Sync now",
            ReportTrigger.START to "sync turned on",
            ReportTrigger.ANDROID_SETTINGS to "Android Settings",
            ReportTrigger.IMPORT to "import from this phone",
            ReportTrigger.PROTON_CHANGES to "changes on Proton",
            ReportTrigger.SCHEDULED to "scheduled full sync",
            ReportTrigger.PHONE_CHANGES to "changes on this phone",
            ReportTrigger.CHECK to "checked Proton: no changes",
        )) {
            show(stored(trigger))

            assertTrue(texts().toString(), "Last sync: ${time(7_200_000)} · $label" in texts())
        }
        show(stored(ReportTrigger.CHECK, Blocker.PROTON_UNAVAILABLE, proton = 3_600_000, phone = 3_600_000))
        assertTrue(texts().toString(), "Last sync: ${time(7_200_000)} · Proton check" in texts())

        // A report from before the report said what started it.
        show(stored(null))
        assertTrue(texts().toString(), string(R.string.main_report_sync, time(7_200_000)) in texts())
    }

    @Test
    fun `a side counted earlier says when, and so does each of its lines, the ones still needing you too`() {
        show(stored(ReportTrigger.PHONE_CHANGES, proton = 3_600_000))

        assertTrue(texts().toString(), string(R.string.main_report_proton_side_from, time(3_600_000)) in texts())
        assertFalse(texts().toString(), texts().any { it.startsWith(string(R.string.main_report_phone_side_from, "")) })
        val earlier = line(R.string.main_line_new_from_proton, 1)
        val waiting = line(R.string.main_line_missing_rows, 2, held = true)
        assertTrue(descriptions().toString(), string(R.string.main_line_from, earlier, time(3_600_000)) in descriptions())
        assertTrue(descriptions().toString(), string(R.string.main_line_waiting, waiting, time(3_600_000)) in descriptions())
        val latest = line(R.string.main_line_new_on_phone, 1)
        assertTrue(rows().toString(), listOf(string(R.string.main_line_new_on_phone), "1") in rows())
        assertTrue(descriptions().toString(), descriptions().none { it.startsWith(latest) })
    }

    /** The weights a report line draws its name and its count with, read from what each text draws. */
    private fun weights(name: String): Pair<FontWeight?, FontWeight?> {
        val all = checkNotNull(root(controller.get().window.decorView)).semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
        val label = all.single { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } == listOf(name) }
        val count = checkNotNull(label.parent).children.dropWhile { it.id != label.id }[1]
        fun weight(node: SemanticsNode): FontWeight? {
            val layouts = mutableListOf<TextLayoutResult>()
            checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
            return layouts.single().layoutInput.style.fontWeight
        }
        return weight(label) to weight(count)
    }

    @Test
    fun `a line's count is bold when the latest run counted it or it still needs you, its name never`() {
        show(stored(ReportTrigger.PHONE_CHANGES, proton = 3_600_000))

        assertEquals(FontWeight.Normal to FontWeight.Bold, weights(string(R.string.main_line_new_on_phone)))
        assertEquals(FontWeight.Normal to FontWeight.Normal, weights(string(R.string.main_line_new_from_proton)))
        assertEquals(FontWeight.Normal to FontWeight.Bold, weights(string(R.string.main_line_missing_rows) + " (${string(R.string.main_held)})"))
    }

    @Test
    fun `a run that counted nothing leaves both sides earlier`() {
        show(stored(ReportTrigger.CHECK, Blocker.OFFLINE, proton = 3_600_000, phone = 5_400_000))

        assertTrue(texts().toString(), string(R.string.main_report_proton_side_from, time(3_600_000)) in texts())
        assertTrue(texts().toString(), string(R.string.main_report_phone_side_from, time(5_400_000)) in texts())
        assertTrue(descriptions().toString(), string(R.string.main_line_from, line(R.string.main_line_new_on_phone, 1), time(5_400_000)) in descriptions())
    }

    @Test
    fun `a side no sync has counted yet says so, on its lines too`() {
        show(stored(ReportTrigger.PHONE_CHANGES, proton = null))

        assertTrue(texts().toString(), string(R.string.main_report_proton_side_not_counted) in texts())
        assertTrue(texts().toString(), string(R.string.main_line_not_counted, string(R.string.main_line_new_from_proton)) in texts())
        // The line shows that text alone: no count beside it.
        assertTrue(rows().toString(), listOf(string(R.string.main_line_not_counted, string(R.string.main_line_new_from_proton))) in rows())
    }

    @Test
    fun `a stopped guard from an earlier full sync still needs you`() {
        show(stored(ReportTrigger.PHONE_CHANGES, proton = 3_600_000, remoteGuard = true))

        val guard = string(R.string.main_remote_guard_stopped_unknown)
        assertTrue(texts().toString(), guard in texts())
        assertTrue(descriptions().toString(), string(R.string.main_line_waiting, guard, time(3_600_000)) in descriptions())
    }

    @Test
    fun `a guard whose side has no time still shows, without one`() {
        // The engine never stores this; a row that holds it anyway mustn't stop the screen.
        show(stored(ReportTrigger.PHONE_CHANGES, proton = null, remoteGuard = true))

        val guard = string(R.string.main_remote_guard_stopped_unknown)
        assertTrue(texts().toString(), guard in texts())
        assertTrue(descriptions().toString(), descriptions().none { it.startsWith(guard) })
    }

    @Test
    fun `the hint under the report says whether Sync now can show the names`() {
        // Sync now shows names only for contacts it would find again.
        val syncNow = "Tap Sync now to see the names."
        val notKept = "Names aren't kept for contacts already synced."
        show(stored(ReportTrigger.IMPORT))
        assertTrue(texts().toString(), syncNow in texts() && notKept !in texts())

        show(stored(ReportTrigger.IMPORT).copy(missingRows = 0))
        assertTrue(texts().toString(), notKept in texts() && syncNow !in texts())

        show(stored(ReportTrigger.IMPORT).copy(newFromProton = 0, newOnPhone = 0, missingRows = 0))
        assertTrue(texts().toString(), syncNow !in texts() && notKept !in texts())
    }

    @Test
    fun `a dry run's report has every line counted by it`() {
        show(stored(null).copy(kind = ReportKind.DRY_RUN))

        assertTrue(texts().toString(), string(R.string.main_report_dry_run, time(7_200_000)) in texts())
        assertTrue(descriptions().toString(), descriptions().none { it.contains(time(7_200_000)) })
        assertFalse(texts().toString(), texts().any { it == string(R.string.main_report_proton_side_not_counted) })
    }
}
