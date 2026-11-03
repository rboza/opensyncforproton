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
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
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

/** The held list on the main screen, its labels and choices (docs/sync.md § Conflicts). Every name is made up. */
@RunWith(RobolectricTestRunner::class)
class MainScreenHeldTest {
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

    private fun click(text: String) {
        val node = nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        idle()
    }

    private fun string(id: Int, vararg args: Any) = controller.get().getString(id, *args)

    private fun hold(n: Int, raw: Long, kind: HoldKind = HoldKind.CONFLICT, choice: HoldChoice? = null) =
        ConflictHoldEntity("a1", "proton-id-$n", raw, kind, choice, if (kind == HoldKind.MISSING_ROW) null else 1, "fingerprint-$n")

    private fun row(raw: Long, n: Int, name: String, dirty: Boolean = true, deleted: Boolean = false) =
        DeviceContact(raw, "proton-id-$n", dirty = dirty, deleted = deleted, version = 1, displayName = name)

    private fun launch() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { MainScreen(model, onLogIn = {}, onOpenAppSettings = {}, onOpenSettings = {}, notes = {}) }
        idle()
    }

    @Test
    fun `each held contact shows its name, the phone side, and its choices, and a choice is saved`() {
        graph.holds += listOf(hold(1, 10), hold(3, 30, choice = HoldChoice.USE_PROTON))
        graph.rows = listOf(row(10, 1, "Ada Example"), row(30, 3, "Deleted Example", deleted = true))
        launch()

        assertTrue(texts().toString(), string(R.string.main_held_title, 2) in texts())
        assertTrue(string(R.string.main_held_intro) in texts())
        assertTrue("Ada Example" in texts())
        assertTrue(string(R.string.main_held_changed_here) in texts())
        assertTrue(string(R.string.main_held_deleted_here) in texts())
        assertEquals(
            "only the answered one says what's chosen",
            listOf(string(R.string.main_held_chosen, string(R.string.main_use_proton))),
            texts().filter { it.startsWith(string(R.string.main_held_chosen, "").substringBefore(",").trim()) },
        )
        assertEquals(2, texts().count { it == string(R.string.main_use_phone) })
        assertEquals(2, texts().count { it == string(R.string.main_skip) })
        assertFalse(string(R.string.main_restore_here) in texts())

        graph.holds.removeAt(1)
        graph.rows = graph.rows.take(1)
        model.refresh()
        idle()
        click(string(R.string.main_use_phone))

        assertTrue(graph.calls.toString(), "choose proton-id-1 USE_PHONE" in graph.calls)
        assertTrue(texts().toString(), string(R.string.main_held_chosen, string(R.string.main_use_phone)) in texts())
        click(string(R.string.main_skip))
        assertTrue("choose proton-id-1 null" in graph.calls)
        assertFalse(texts().any { it == string(R.string.main_held_chosen, string(R.string.main_use_phone)) })
    }

    @Test
    fun `every label is the phone side's, and a missing row offers only Restore here and Skip`() {
        graph.holds += listOf(hold(2, 20), hold(4, 40, kind = HoldKind.EDITED_BEFORE_KEYS), hold(5, 50, kind = HoldKind.MISSING_ROW))
        graph.rows = listOf(row(20, 2, "Clean Example", dirty = false), row(40, 4, "Keys Example"))
        launch()

        assertTrue(texts().toString(), string(R.string.main_held_not_changed_here) in texts())
        assertTrue(string(R.string.main_held_edited_before_keys) in texts())
        assertTrue(string(R.string.main_held_gone_here) in texts())
        assertTrue("a missing row has no name without this screen's Sync now", string(R.string.main_no_name) in texts())
        assertEquals(2, texts().count { it == string(R.string.main_use_phone) })
        assertEquals(3, texts().count { it == string(R.string.main_skip) })

        click(string(R.string.main_restore_here))

        assertTrue(graph.calls.toString(), "choose proton-id-5 USE_PROTON" in graph.calls)
        assertTrue(string(R.string.main_held_chosen, string(R.string.main_restore_here)) in texts())
    }

    @Test
    fun `rows that can't be read show the count alone, with no choices`() {
        graph.holds += listOf(hold(1, 10), hold(2, 20))
        graph.rowsFailure = SecurityException("made-up failure")
        launch()

        assertTrue(texts().toString(), string(R.string.main_held_title, 2) in texts())
        assertFalse(string(R.string.main_held_intro) in texts())
        assertFalse(string(R.string.main_skip) in texts())
    }

    @Test
    fun `a choice the hold no longer takes, or that fails, says so`() {
        graph.holds += hold(1, 10)
        graph.rows = listOf(row(10, 1, "Ada Example"))
        launch()
        // A sync found the row edited again meanwhile.
        graph.holds[0] = graph.holds[0].copy(version = 2)

        click(string(R.string.main_use_proton))

        assertTrue(texts().toString(), string(R.string.main_held_choice_changed) in texts())
        graph.chooseFailure = IllegalStateException("made-up failure")
        click(string(R.string.main_use_proton))
        assertTrue(texts().toString(), string(R.string.main_held_choice_failed) in texts())
        assertFalse(string(R.string.main_held_choice_changed) in texts())
    }

    @Test
    fun `nothing held shows no list`() {
        launch()
        assertFalse(texts().any { it.startsWith(string(R.string.main_held_title, 0).substringBefore("(")) })
    }
}
