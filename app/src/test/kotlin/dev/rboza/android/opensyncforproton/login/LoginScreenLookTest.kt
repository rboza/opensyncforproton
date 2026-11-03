package dev.rboza.android.opensyncforproton.login

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.account.LoginStep
import dev.rboza.android.opensyncforproton.screenNodes
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
 * The login screen's reassurance line, and the eye icon on a password field ("Show password"), which shows what was
 * typed until the crossed-out eye ("Hide password"); a new step starts hidden. Values are made up.
 */
@RunWith(RobolectricTestRunner::class)
class LoginScreenLookTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = LoginModel(
        newInteractor = {
            FakeLoginInteractor(
                { LoginStep.NeedMailboxPassword(wrongPassword = false) },
                { LoginStep.NeedMailboxPassword(wrongPassword = true) },
                { LoginStep.NeedMailboxPassword(wrongPassword = true) },
            )
        },
        completeLogin = { error("not reached") },
        scope = scope,
        log = {},
        nanoTime = { 0L },
    )
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
        controller.get().setContent { LoginScreen(model, onClosed = {}) }
        idle()
    }

    private fun nodes(): List<SemanticsNode> = screenNodes(controller.get())

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    /**
     * What the text fields draw. Not their editable text, which a secure field also gives a screen reader while it
     * draws dots.
     */
    private fun shown(): List<String> = nodes().filter { SemanticsProperties.EditableText in it.config }.map { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
        layouts.single().layoutInput.text.text
    }

    private fun string(id: Int) = controller.get().getString(id)

    /** The icon buttons TalkBack reads as [label]. */
    private fun icons(label: String) = nodes().filter { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    /** Taps the eye icon TalkBack reads as [label]: an icon, no text. */
    private fun click(label: String) {
        val node = icons(label).single()
        assertTrue("an icon, not a text button", node.texts().isEmpty())
        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        idle()
    }

    @Test
    fun `the login screen says the password stays on the phone`() {
        show()
        assertTrue(texts().toString(), string(R.string.login_reassurance) in texts())
    }

    @Test
    fun `Show shows the typed password until Hide, and the next step starts hidden`() {
        show()
        model.username.setTextAndPlaceCursorAtEnd(USERNAME)
        model.password.setTextAndPlaceCursorAtEnd(PASSWORD)
        idle()
        assertFalse(shown().toString(), PASSWORD in shown())

        click(string(R.string.login_show))
        assertTrue(shown().toString(), PASSWORD in shown())
        assertEquals(1, icons(string(R.string.login_hide)).size)
        assertEquals(0, icons(string(R.string.login_show)).size)

        click(string(R.string.login_hide))
        assertFalse(shown().toString(), PASSWORD in shown())

        click(string(R.string.login_show))
        model.submit()
        idle()
        assertTrue(model.state.value is LoginUi.MailboxPassword)
        model.mailboxPassword.setTextAndPlaceCursorAtEnd(MAILBOX)
        idle()
        assertFalse(shown().toString(), MAILBOX in shown())
        assertEquals(1, icons(string(R.string.login_show)).size)
        assertEquals(0, icons(string(R.string.login_hide)).size)

        // The same step again (a wrong mailbox password) is a new step too.
        click(string(R.string.login_show))
        model.submit()
        idle()
        assertTrue((model.state.value as LoginUi.MailboxPassword).wrongPassword)
        model.mailboxPassword.setTextAndPlaceCursorAtEnd(MAILBOX)
        idle()
        assertFalse(shown().toString(), MAILBOX in shown())

        // A second wrong password gives an equal step, and the answer comes before "Working" is ever drawn.
        click(string(R.string.login_show))
        model.submit()
        idle()
        model.mailboxPassword.setTextAndPlaceCursorAtEnd(MAILBOX)
        idle()
        assertFalse(shown().toString(), MAILBOX in shown())
    }

    private companion object {
        const val USERNAME = "someone.made.up"
        const val PASSWORD = "made-up password 1"
        const val MAILBOX = "made-up mailbox password 2"
    }
}
