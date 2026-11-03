package dev.rboza.android.opensyncforproton.settings

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
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
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

/**
 * Settings name the account and its state, group the actions under "Account" and "Diagnostics", go back with a labelled
 * arrow, close a timing submenu with a labelled icon, and ask "Log out?" and "Remove from this phone?" in dialogs
 * (docs/sync.md § Screens and notifications).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenLookTest {
    private val graph = FakeAppGraph().apply {
        records = listOf(AccountRecordEntity("a1", NAME, LoginState.ACTIVE))
        settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60, 15)
        readable += "a1"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = SettingsModel(graph, scope)
    private lateinit var controller: ActivityController<ComponentActivity>
    private var closed = 0

    @After
    fun end() {
        controller.pause().stop().destroy()
        idle()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun show() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { SettingsScreen(model, onClose = { closed++ }, onOpenImport = {}) }
        idle()
    }

    private fun nodes(): List<SemanticsNode> = screenNodes(controller.get())

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun dialogTexts(): List<String> = dialogNodes().flatMap { it.texts() }

    private fun string(id: Int) = controller.get().getString(id)

    private fun click(node: SemanticsNode) {
        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        idle()
    }

    private fun click(text: String) = click(nodes().single { text in it.texts() && SemanticsActions.OnClick in it.config })

    /** The icon-only button TalkBack reads as [label]. */
    private fun icon(label: String) = nodes().single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    @Test
    fun `settings name the account and its state, and group the actions`() {
        show()
        for (text in listOf(NAME, string(R.string.main_state_signed_in), string(R.string.settings_account), string(R.string.settings_diagnostics))) {
            assertTrue(texts().toString(), text in texts())
        }
    }

    @Test
    fun `the back arrow is labelled and returns`() {
        show()
        val back = icon(string(R.string.settings_back))
        assertTrue("an icon, not a text button", back.texts().isEmpty())
        click(back)
        assertEquals(1, closed)
    }

    @Test
    fun `a timing submenu closes with a labelled icon`() {
        show()
        click(string(R.string.settings_full_sync))
        assertTrue(texts().toString(), string(R.string.settings_full_sync_detail) in texts())
        click(icon(string(R.string.settings_close)))
        assertTrue(texts().toString(), string(R.string.settings_account) in texts())
        assertEquals(0, closed)
    }

    @Test
    fun `Log out asks in a dialog, and Cancel closes it`() {
        show()
        click(string(R.string.settings_log_out))
        assertTrue(dialogTexts().toString(), string(R.string.settings_log_out_question) in dialogTexts())
        click(nodes().single { string(R.string.login_cancel) in it.texts() })
        assertEquals(emptyList<String>(), dialogTexts())
    }

    @Test
    fun `Back on a question cancels it as Cancel does`() {
        show()
        for (question in listOf(R.string.settings_log_out, R.string.settings_remove)) {
            click(string(question))
            assertTrue(dialogTexts().toString(), dialogTexts().isNotEmpty())
            backOnDialog()
            idle()
            assertEquals(emptyList<String>(), dialogTexts())
            assertEquals(null, model.state.value?.question)
        }
        assertTrue(graph.calls.toString(), graph.calls.none { it.startsWith("logout") || it.startsWith("removeFromPhone") })
        assertEquals(0, closed)
    }

    @Test
    fun `Remove from this phone asks in a dialog with the count`() {
        show()
        click(string(R.string.settings_remove))
        val question = controller.get().resources.getQuantityString(R.plurals.settings_remove_question, 0, 0)
        assertTrue(dialogTexts().toString(), dialogTexts().any { it.startsWith(question) })
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
    }
}
