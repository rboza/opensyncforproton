package dev.rboza.android.opensyncforproton.settings

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
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
 * The settings screen's timing (docs/sync.md § Scheduling). Manual is a switch outside the two submenus and greys them
 * out; each row opens its submenu, where a check longer than the full sync can't be picked; Back closes a submenu. Read
 * through Compose's own test root.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTimingTest {
    private val graph = FakeAppGraph().apply {
        records = listOf(AccountRecordEntity("a1", "someone.made.up@example.test", LoginState.ACTIVE))
        settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60, 15)
        readable += "a1"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = SettingsModel(graph, scope)
    private lateinit var controller: ActivityController<ComponentActivity>

    @After
    fun end() {
        controller.pause().stop().destroy()
        idle()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun show() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { SettingsScreen(model, onClose = {}, onOpenImport = {}) }
        idle()
    }

    private fun root(view: View): ViewRootForTest? = when (view) {
        is ViewRootForTest -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { root(view.getChildAt(it)) }
        else -> null
    }

    private fun nodes(): List<SemanticsNode> = checkNotNull(root(controller.get().window.decorView)).semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun text(id: Int) = controller.get().getString(id)

    private fun plural(id: Int, n: Int) = controller.get().resources.getQuantityString(id, n, n)

    /** The clickable node showing [label]: a row with its value, a choice, or the switch. */
    private fun node(label: String) = nodes().single { label in it.texts() && SemanticsActions.OnClick in it.config }

    private fun enabled(label: String) = SemanticsProperties.Disabled !in node(label).config

    private fun role(label: String) = node(label).config.getOrNull(SemanticsProperties.Role)

    private fun click(label: String) {
        checkNotNull(node(label).config[SemanticsActions.OnClick].action).invoke()
        idle()
    }

    @Test
    fun `the settings show Manual, both rows with their choices, and how phone edits go`() {
        show()

        assertTrue(texts().toString(), text(R.string.settings_timing) in texts())
        assertEquals(listOf(text(R.string.settings_full_sync), plural(R.plurals.settings_interval_hours, 1)), node(text(R.string.settings_full_sync)).texts())
        assertEquals(listOf(text(R.string.settings_check), plural(R.plurals.settings_interval_minutes, 15)), node(text(R.string.settings_check)).texts())
        assertTrue(enabled(text(R.string.settings_manual)))
        assertTrue(text(R.string.settings_timing_note) in texts())
        // What a screen reader announces: a switch, and rows that open something.
        assertEquals(Role.Switch, role(text(R.string.settings_manual)))
        assertEquals(Role.Button, role(text(R.string.settings_full_sync)))
        assertEquals(Role.Button, role(text(R.string.settings_check)))
        assertFalse(text(R.string.settings_manual_on) in texts())
    }

    @Test
    fun `Manual on greys out both rows and says they have no effect`() {
        show()

        click(text(R.string.settings_manual))

        assertEquals(listOf("setManual a1 true"), graph.calls)
        assertTrue(texts().toString(), text(R.string.settings_manual_on) in texts())
        assertFalse("phone edits wait for Sync now too", text(R.string.settings_timing_note) in texts())
        assertFalse(enabled(text(R.string.settings_full_sync)))
        assertFalse(enabled(text(R.string.settings_check)))
    }

    @Test
    fun `the full sync's submenu explains it, and a choice sets it and closes the submenu`() {
        show()

        click(text(R.string.settings_full_sync))
        assertTrue(texts().toString(), text(R.string.settings_full_sync_detail) in texts())
        click(plural(R.plurals.settings_interval_hours, 6))

        assertEquals(listOf("setInterval a1 360"), graph.calls)
        assertFalse(text(R.string.settings_full_sync_detail) in texts())
        assertEquals(plural(R.plurals.settings_interval_hours, 6), node(text(R.string.settings_full_sync)).texts()[1])
    }

    @Test
    fun `the check's submenu greys out choices longer than the full sync`() {
        show()

        click(text(R.string.settings_check))

        assertTrue(texts().toString(), text(R.string.settings_check_detail) in texts())
        assertEquals(Role.RadioButton, role(plural(R.plurals.settings_interval_minutes, 30)))
        assertTrue(enabled(plural(R.plurals.settings_interval_minutes, 30)))
        assertTrue(enabled(plural(R.plurals.settings_interval_hours, 1)))
        assertFalse(enabled(plural(R.plurals.settings_interval_hours, 2)))
        assertFalse(enabled(plural(R.plurals.settings_interval_hours, 24)))
        assertTrue(enabled(text(R.string.settings_check_same)))

        click(text(R.string.settings_check_same))
        assertEquals(listOf("setCheckInterval a1 null"), graph.calls)
        assertEquals(text(R.string.settings_check_same), node(text(R.string.settings_check)).texts()[1])
    }

    @Test
    fun `Close closes a submenu without a choice`() {
        show()
        click(text(R.string.settings_full_sync))

        // Close is the top bar's icon, labelled for TalkBack.
        val close = nodes().single { text(R.string.settings_close) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        checkNotNull(close.config[SemanticsActions.OnClick].action).invoke()
        idle()

        assertFalse(text(R.string.settings_full_sync_detail) in texts())
        assertTrue(text(R.string.settings_timing) in texts())
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `Back closes a submenu and stays on the settings`() {
        show()
        click(text(R.string.settings_check))

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()

        assertFalse(text(R.string.settings_check_detail) in texts())
        assertTrue(text(R.string.settings_timing) in texts())
        assertEquals(emptyList<String>(), graph.calls)
    }
}
