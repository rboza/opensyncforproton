package dev.rboza.android.opensyncforproton.settings

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.backOnDialog
import dev.rboza.android.opensyncforproton.dialogNodes
import dev.rboza.android.opensyncforproton.screenNodes
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.provider.CopyResult
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import kotlinx.coroutines.CompletableDeferred
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
 * Keep a copy on this phone's row, question, and results, in their exact words (docs/sync.md § Keep a copy). Names are
 * made up.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenCopyTest {
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

    private fun click(text: String) {
        val node = nodes().single { text in it.texts() && SemanticsActions.OnClick in it.config }
        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        idle()
    }

    private fun rows(count: Int) = (1..count).map { DeviceContact(it.toLong(), "p$it", false, false, 1, "Contact $it") }

    /** Taps the row, then Copy in the question, and returns the banner's words. */
    private fun copy(rows: Int, result: CopyResult): List<String> {
        graph.rows = rows(rows)
        graph.copyResult = result
        click(ROW)
        click(COPY)
        return texts()
    }

    @Test
    fun `the row and its line are under Account, above Log out`() {
        show()
        val all = texts()
        assertEquals(all.toString(), all.indexOf(ROW) + 1, all.indexOf(DETAIL))
        val account = all.indexOf("Account")
        val row = all.indexOf(ROW)
        assertTrue(all.toString(), account in 0 until row)
        assertTrue(all.toString(), row < all.indexOf("Log out"))
        assertTrue(all.toString(), all.indexOf("Log out") < all.indexOf("Remove from this phone"))
    }

    @Test
    fun `it's offered signed out too, above Remove`() {
        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT))
        show()
        val all = texts()
        assertTrue(all.toString(), "Log out" !in all)
        assertTrue(all.toString(), all.indexOf(ROW) in 0 until all.indexOf("Remove from this phone"))
    }

    @Test
    fun `the question's exact words, with Cancel and Copy`() {
        show()
        graph.rows = rows(3)
        click(ROW)
        assertEquals(
            listOf(
                "Keep a copy on this phone?",
                "Copies your 3 Proton contacts into this phone's own contacts, so they stay if you uninstall OpenSync for Proton.",
                SECOND,
                "Cancel",
                COPY,
            ),
            dialogTexts(),
        )
    }

    @Test
    fun `one contact is asked about as one`() {
        show()
        graph.rows = rows(1)
        click(ROW)
        assertTrue(
            dialogTexts().toString(),
            "Copies your 1 Proton contact into this phone's own contacts, so it stays if you uninstall OpenSync for Proton." in dialogTexts(),
        )
    }

    @Test
    fun `Cancel and Back close the question and copy nothing`() {
        show()
        graph.rows = rows(2)
        click(ROW)
        click("Cancel")
        assertEquals(emptyList<String>(), dialogTexts())
        click(ROW)
        backOnDialog()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())
        assertTrue(graph.calls.toString(), graph.calls.none { it.startsWith("keepCopy") })
        assertEquals(0, closed)
    }

    @Test
    fun `while it copies the screen says so, and Back waits`() {
        show()
        graph.rows = rows(2)
        val gate = CompletableDeferred<Unit>()
        graph.copyGate = { gate.await() }
        click(ROW)
        click(COPY)
        assertTrue(texts().toString(), "Copying contacts…" in texts())
        assertTrue(texts().toString(), "Working…" !in texts())

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(0, closed)

        gate.complete(Unit)
        idle()
        assertTrue(texts().toString(), "Copying contacts…" !in texts())
    }

    @Test
    fun `all copied`() {
        show()
        assertTrue(copy(3, CopyResult(3, 0)).toString(), "Copied 3 contacts into this phone's contacts." in texts())
        assertTrue(copy(1, CopyResult(1, 0)).toString(), "Copied 1 contact into this phone's contacts." in texts())
    }

    @Test
    fun `some not copied`() {
        show()
        assertTrue(copy(3, CopyResult(2, 2)).toString(), "Copied 2 of 4 contacts; 2 couldn't be copied." in texts())
    }

    @Test
    fun `none copied, with the Contact Scopes hint`() {
        show()
        assertTrue(copy(3, CopyResult(0, 3)).toString(), "Couldn't copy any of your 3 contacts. $SCOPES" in texts())
        assertTrue(copy(1, CopyResult(0, 1)).toString(), "Couldn't copy your 1 contact. $SCOPES" in texts())
    }

    @Test
    fun `nothing to copy, at the tap or found by the copy, with the Contact Scopes hint`() {
        show()
        graph.rows = emptyList()
        click(ROW)
        assertEquals(emptyList<String>(), dialogTexts())
        assertTrue(texts().toString(), NOTHING in texts())

        assertTrue(copy(2, CopyResult(0, 0)).toString(), NOTHING in texts())
    }

    @Test
    fun `rows that can't be read`() {
        show()
        graph.rowsFailure = SecurityException("made-up")
        click(ROW)
        assertEquals(emptyList<String>(), dialogTexts())
        assertTrue(texts().toString(), "Couldn't read this phone's contacts." in texts())
    }

    @Test
    fun `a copy that stopped`() {
        show()
        graph.copyFailure = IllegalStateException("made-up")
        assertTrue(
            copy(2, CopyResult(0, 0)).toString(),
            "Keep a copy stopped. Some contacts may already be copied; check the Contacts app before trying again." in texts(),
        )
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
        const val ROW = "Keep a copy on this phone"
        const val DETAIL = "Copies your Proton contacts into this phone's own contacts before you uninstall."
        const val COPY = "Copy"
        const val SECOND = "The copies don't sync. Do this just before uninstalling: until then, an edit in the Contacts app may change " +
            "the copy instead of the synced contact. Each time you do this adds another copy."
        const val SCOPES = "If Contact Scopes is on for OpenSync for Proton, turn it off and try again."
        const val NOTHING = "There are no contacts on this phone to copy. $SCOPES"
    }
}
