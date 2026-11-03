package dev.rboza.android.opensyncforproton.login

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.autofill.createFromText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.account.LoginStep
import dev.rboza.android.opensyncforproton.composeRoot
import dev.rboza.android.opensyncforproton.screenNodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The six-digit code's field keeps only ASCII digits, the first six, however they come (typed, pasted, autofilled),
 * with the cursor kept at the end, and is drawn as six boxes that TalkBack reads as one field (docs/sync.md § Screens
 * and notifications). Values are made up.
 */
@RunWith(RobolectricTestRunner::class)
class LoginCodeFieldTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val interactor = FakeLoginInteractor({ LoginStep.NeedTotp(wrongCode = false) }, { LoginStep.NeedTotp(wrongCode = true) })
    private val model = LoginModel(
        newInteractor = { interactor },
        completeLogin = { error("not reached") },
        scope = scope,
        log = {},
        nanoTime = { 0L },
    )
    private lateinit var controller: ActivityController<ComponentActivity>

    @Before
    fun toCodeStep() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { LoginScreen(model, onClosed = {}) }
        idle()
        model.username.setTextAndPlaceCursorAtEnd("someone.made.up")
        model.password.setTextAndPlaceCursorAtEnd("made-up password 1")
        model.submit()
        idle()
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
    }

    @After
    fun end() {
        controller.pause().stop().destroy()
        idle()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun nodes(): List<SemanticsNode> = screenNodes(controller.get())

    /** The one node with editable text: the code's field. */
    private fun field(): SemanticsNode = nodes().filter { SemanticsProperties.EditableText in it.config }.single()

    private fun focus() {
        checkNotNull(field().config[SemanticsActions.RequestFocus].action).invoke()
        idle()
    }

    /** What autofill does: fills the field with its value. */
    private fun fill(text: String) {
        checkNotNull(field().config[SemanticsActions.OnFillData].action).invoke(checkNotNull(FillableData.createFromText(text)))
        idle()
    }

    /** What a screen reader's set-text does: replaces the field's text. */
    private fun setText(text: String) {
        checkNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
        idle()
    }

    /** Presses a hardware keyboard's keys for [text]: digits, lower-case letters, and spaces. */
    private fun type(text: String) = text.forEach { c ->
        key(
            when (c) {
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
                ' ' -> KeyEvent.KEYCODE_SPACE
                else -> error("no key for $c")
            },
        )
    }

    private fun view() = checkNotNull(composeRoot(controller.get().window.decorView)).view

    /** What the on-screen keyboard does: commits text through its connection. */
    private fun commit(text: String) {
        checkNotNull(view().onCreateInputConnection(EditorInfo())).commitText(text, 1)
        idle()
    }

    /** Moves the cursor, as arrow keys or TalkBack's cursor moves do. */
    private fun select(start: Int, end: Int) {
        checkNotNull(field().config[SemanticsActions.SetSelection].action).invoke(start, end, false)
        idle()
    }

    private fun key(code: Int) {
        view().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        view().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        idle()
    }

    /** Pastes at the cursor (focusing the field first if it isn't). */
    private fun paste(text: String) {
        val clipboard = controller.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        if (field().config.getOrNull(SemanticsProperties.Focused) != true) focus()
        checkNotNull(field().config[SemanticsActions.PasteText].action).invoke()
        idle()
    }

    private fun code() = model.code.text.toString()

    /** The boxes, left to right: hidden from TalkBack, each with its digit (or none) and whether it is the next one. */
    private fun boxes(): List<Pair<String, Boolean>> = nodes().filter { SemanticsProperties.HideFromAccessibility in it.config }
        .sortedBy { it.boundsInRoot.left }
        .map { box ->
            box.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text } to
                (box.config.getOrNull(SemanticsProperties.Selected) == true)
        }

    private fun expectedBoxes(digits: String) = List(6) { i -> digits.getOrElse(i) { ' ' }.toString().trim() to (i == digits.length) }

    @Test
    fun `autofill keeps only the digits, the first six`() {
        fill("123 456")
        assertEquals("123456", code())
        fill("12a3b4")
        assertEquals("1234", code())
        fill("1234567890")
        assertEquals("123456", code())
        fill("١٢٣٤٥٦")
        assertEquals("not ASCII digits", "", code())
    }

    @Test
    fun `typed keys ignore letters, a space, and a seventh digit`() {
        focus()
        type("12")
        type("a")
        type(" ")
        type("3456")
        assertEquals("123456", code())
        type("7")
        assertEquals("123456", code())
    }

    @Test
    fun `the keyboard's own text is kept to its digits too`() {
        focus()
        commit("12a ")
        commit("34567")
        assertEquals("123456", code())
    }

    @Test
    fun `TalkBack's set-text keeps only the digits`() {
        setText("12a 34")
        assertEquals("1234", code())
    }

    @Test
    fun `the cursor stays at the end, so a seventh digit is ignored and Backspace clears the last`() {
        fill("123456")
        focus()
        select(1, 1)
        type("7")
        assertEquals("123456", code())
        fill("1234")
        select(1, 1)
        key(KeyEvent.KEYCODE_DEL)
        assertEquals("123", code())
    }

    @Test
    fun `a selection takes the whole code, so a paste replaces it`() {
        fill("123456")
        focus()
        select(0, 6)
        paste("654 321")
        assertEquals("654321", code())
        select(2, 4)
        paste("78")
        assertEquals("78", code())
    }

    @Test
    fun `a pasted code with a space fills all six`() {
        paste("123 456")
        assertEquals("123456", code())
    }

    @Test
    fun `Continue sends the kept digits`() {
        fill("123 456")
        model.submit()
        idle()
        assertEquals("123456", interactor.received.last())
    }

    @Test
    fun `six boxes show the digits and mark where the next one goes`() {
        assertEquals(expectedBoxes(""), boxes())
        fill("1234")
        assertEquals(expectedBoxes("1234"), boxes())
        fill("123456")
        assertEquals("all six filled: no next box", expectedBoxes("123456"), boxes())
        assertTrue(boxes().none { it.second })
    }

    @Test
    fun `TalkBack reads one field, labelled Code, with its digits and the authenticator-code hint`() {
        fill("1234")
        val field = field()
        val label = controller.get().getString(R.string.login_totp_label)
        assertEquals(listOf(label), field.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
        assertEquals("1234", field.config[SemanticsProperties.EditableText].text)
        val hints = field.config[SemanticsProperties.ContentType].let { it.javaClass.getMethod("getAndroidAutofillHints").apply { isAccessible = true }.invoke(it) }
        assertEquals(setOf("2faAppOTPCode"), hints)
        // The boxes' digits reach TalkBack only through the field.
        nodes().filter { SemanticsProperties.HideFromAccessibility !in it.config && it != field }.forEach { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertTrue(texts.toString(), texts.none { it.any(Char::isDigit) })
        }
    }

    @Test
    fun `the keyboard is the digits one, its action Next`() {
        focus()
        val info = EditorInfo()
        checkNotNull(view().onCreateInputConnection(info))
        assertEquals(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, info.inputType)
        assertEquals(EditorInfo.IME_ACTION_NEXT, info.imeOptions and EditorInfo.IME_MASK_ACTION)
    }
}
