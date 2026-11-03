package dev.rboza.android.opensyncforproton

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.util.SparseArray
import android.view.WindowManager
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.Credentials
import dev.rboza.android.opensyncforproton.account.LoginInteractor
import dev.rboza.android.opensyncforproton.account.PasswordMode
import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.account.LoginStep
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.login.FakeLoginInteractor
import dev.rboza.android.opensyncforproton.login.LoginUi
import dev.rboza.android.opensyncforproton.sync.lifecycle.LoginCommit
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Passwords, codes, and the captcha page never reach saved state and the activity is FLAG_SECURE
 * (docs/architecture.md § Security), except in the debug build; an attempt survives a configuration change, and leaving
 * the screen ends it. Values are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = CredentialUiLifetimeTest.TestApp::class)
class CredentialUiLifetimeTest {
    /** The composition root's stand-in: a scripted interactor per attempt, and a commit that always succeeds. */
    class TestApp(val graph: FakeAppGraph = FakeAppGraph()) : Application(), AppGraph by graph

    private val app = (RuntimeEnvironment.getApplication() as TestApp).graph

    private val launched = mutableListOf<ActivityController<MainActivity>>()

    /** As in MainActivityTest: an activity left alive stops the next test's screens from recomposing. */
    @After
    fun destroy() {
        launched.filterNot { it.get().isDestroyed }.forEach { it.pause().stop().destroy() }
        idle()
    }

    private fun launch(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup().also { launched += it }

    /** The activity, with the login screen opened from the main screen's **Log in**. */
    private fun launchLogin(): ActivityController<MainActivity> = launch().also {
        it.get().screens.openLogin()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ActivityController<MainActivity>.model() = checkNotNull(get().screens.login.value)

    private fun ActivityController<MainActivity>.saved(): Bundle = Bundle().also { saveInstanceState(it) }

    @Test
    fun `typed values and the captcha page never reach saved state`() {
        app.next = {
            FakeLoginInteractor(
                { LoginStep.NeedTotp(wrongCode = false) },
                { LoginStep.NeedMailboxPassword(wrongPassword = false) },
                { LoginStep.NeedCaptcha(CAPTCHA_URL) },
            )
        }
        val controller = launchLogin()
        val model = controller.model()

        model.username.setTextAndPlaceCursorAtEnd(USERNAME)
        model.password.setTextAndPlaceCursorAtEnd(PASSWORD)
        idle()
        assertNull(secretIn(controller.saved()))

        model.submit()
        idle()
        model.code.setTextAndPlaceCursorAtEnd(CODE)
        idle()
        assertNull(secretIn(controller.saved()))

        model.submit()
        idle()
        model.mailboxPassword.setTextAndPlaceCursorAtEnd(MAILBOX_PASSWORD)
        idle()
        assertNull(secretIn(controller.saved()))

        model.submit()
        idle()
        assertTrue(model.state.value is LoginUi.Captcha)
        assertNull(secretIn(controller.saved()))
    }

    @Test
    fun `the search finds a value a parcelable hides from toString`() {
        val hidden = Bundle().apply { putParcelable("state", HiddenState(PASSWORD)) }
        assertEquals(PASSWORD, secretIn(hidden))
    }

    /** A saved state whose toString shows nothing, as a view's saved state usually doesn't. */
    class HiddenState(private val value: String) : android.os.Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeString(value)
        override fun toString() = "HiddenState"
    }

    @Test
    fun `the search finds a value saved anywhere in a bundle`() {
        val nested = Bundle().apply { putSerializable("values", arrayListOf<Any?>(null, mapOf("k" to listOf(PASSWORD)))) }
        val outer = Bundle().apply {
            putBundle("registry", nested)
            putSparseParcelableArray("views", SparseArray<Bundle>().apply { put(7, Bundle().apply { putCharSequence("t", CODE) }) })
        }
        assertEquals(PASSWORD, secretIn(Bundle().apply { putBundle("outer", nested) }))
        assertEquals(CODE, secretIn(outer.apply { remove("registry") }))
    }

    @Test
    fun `only the debug build's window allows screenshots`() {
        // Debug builds (`.debug`) run only on the emulator (D4); every other build is secure.
        assertTrue(securesWindow("dev.rboza.android.opensyncforproton"))
        assertFalse(securesWindow("dev.rboza.android.opensyncforproton.debug"))
    }

    @Test
    fun `the debug build's window allows screenshots, through a configuration change`() {
        // The unit tests run the debug build; the release build's window is a hand step (docs/releasing.md § Pixel
        // check).
        val secure = false
        app.next = { FakeLoginInteractor({ LoginStep.NeedTotp(wrongCode = false) }) }
        val controller = launchLogin()
        assertEquals("dev.rboza.android.opensyncforproton.debug", controller.get().packageName)
        assertEquals(secure, controller.get().isSecure())

        controller.model().submit()
        idle()
        controller.recreate()
        assertEquals(secure, controller.get().isSecure())
    }

    @Test
    fun `a configuration change keeps the attempt`() {
        app.next = { FakeLoginInteractor({ LoginStep.NeedTotp(wrongCode = false) }) }
        val controller = launchLogin()
        val model = controller.model()
        model.username.setTextAndPlaceCursorAtEnd(USERNAME)
        model.password.setTextAndPlaceCursorAtEnd(PASSWORD)
        model.submit()
        idle()

        controller.recreate()
        idle()
        assertSame(model, controller.model())
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
        assertEquals(1, app.interactors.size)
        assertEquals(0, app.interactors.single().cancels)
    }

    @Test
    fun `leaving the screen ends the attempt and clears the fields`() {
        app.next = { FakeLoginInteractor({ LoginStep.NeedTotp(wrongCode = false) }) }
        val controller = launchLogin()
        val model = controller.model()
        model.username.setTextAndPlaceCursorAtEnd(USERNAME)
        model.password.setTextAndPlaceCursorAtEnd(PASSWORD)
        model.submit()
        idle()
        model.code.setTextAndPlaceCursorAtEnd(CODE)

        controller.get().finish()
        controller.pause().stop().destroy()
        idle()
        assertEquals(1, app.interactors.single().cancels)
        assertEquals(LoginUi.Closed, model.state.value)
        assertEquals("", model.code.text.toString())
    }

    @Test
    fun `back ends the attempt and returns to the main screen`() {
        app.next = { FakeLoginInteractor({ LoginStep.NeedTotp(wrongCode = false) }) }
        val controller = launchLogin()
        val model = controller.model()
        model.submit()
        idle()
        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(1, app.interactors.single().cancels)
        assertEquals(LoginUi.Closed, model.state.value)
        assertNull(controller.get().screens.login.value)
        assertFalse(controller.get().isFinishing)
    }

    @Test
    fun `back while the login is saved does nothing, so its result is seen`() {
        val gate = CompletableDeferred<LoginCommit>()
        app.commit = { gate.await() }
        app.next = { FakeLoginInteractor({ LoginStep.Success(session()) }) }
        val controller = launchLogin()
        // The main dispatcher runs the step at once here. No idle() while saving: Robolectric drains the progress
        // indicator's endless animation for minutes.
        controller.model().submit()
        assertEquals(LoginUi.Saving, controller.model().state.value)

        controller.get().onBackPressedDispatcher.onBackPressed()
        assertFalse(controller.get().isFinishing)
        gate.complete(LoginCommit.Committed(resumed = false))
        idle()
        assertEquals(LoginUi.Done(resumed = false), controller.model().state.value)
        assertFalse(controller.get().isFinishing)
    }

    @Test
    fun `cancelling returns to the main screen`() {
        val controller = launchLogin()
        controller.model().cancel()
        idle()
        assertNull(controller.get().screens.login.value)
        assertFalse(controller.get().isFinishing)
    }

    @Test
    fun `no app source keeps UI state across process death`() {
        val sources = File("src/main").walk().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no sources found under ${File("src/main").absolutePath}", sources.isNotEmpty())
        val forbidden = listOf("Saveable", "rememberTextFieldState", "SavedState", "onSaveInstanceState", "Saver", "Parcelize", "Parcelable")
        val found = sources.flatMap { file -> forbidden.filter { it in file.readText() }.map { "${file.name}: $it" } }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `the activity reads its collaborators from the application`() {
        assertNotNull(launch().get().screens.main.state.value)
        assertEquals(0, app.interactors.size)
    }

    private fun MainActivity.isSecure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    /**
     * The first secret found anywhere in [bundle], or null: in its values read back as text, and in its parcelled
     * bytes (UTF-8 and UTF-16), which also hold what a Parcelable's toString wouldn't show.
     */
    private fun secretIn(bundle: Bundle): String? {
        val text = StringBuilder().also { flatten(bundle, it) }.toString()
        val parcel = Parcel.obtain()
        val bytes = try {
            parcel.writeBundle(bundle)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
        return listOf(USERNAME, PASSWORD, CODE, MAILBOX_PASSWORD, CAPTCHA_URL, CAPTCHA_TOKEN).firstOrNull { secret ->
            secret in text || listOf(Charsets.UTF_8, Charsets.UTF_16LE).any { bytes.contains(secret.toByteArray(it)) }
        }
    }

    private fun ByteArray.contains(part: ByteArray) = (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    private fun session() = NewSession(
        AccountId("user-id-1"), USERNAME,
        Credentials(Tokens("uid-1", "access-1", "refresh-1"), PasswordMode.ONE, emptyMap(), LockedKeyBundle(emptyList()), emptyMap()),
    )

    @Suppress("DEPRECATION")
    private fun flatten(value: Any?, out: StringBuilder) {
        when (value) {
            null -> Unit
            is Bundle -> value.keySet().forEach { out.append(it).append('='); flatten(value.get(it), out); out.append(';') }
            is SparseArray<*> -> (0 until value.size()).forEach { flatten(value.valueAt(it), out) }
            is Map<*, *> -> value.forEach { (k, v) -> flatten(k, out); flatten(v, out) }
            is Iterable<*> -> value.forEach { flatten(it, out) }
            is Array<*> -> value.forEach { flatten(it, out) }
            is CharArray -> out.append(value)
            else -> out.append(value.toString()).append(';')
        }
    }

    private companion object {
        const val USERNAME = "someone.made.up@example.test"
        const val PASSWORD = "made-up password 1"
        const val CODE = "246810"
        const val MAILBOX_PASSWORD = "made-up mailbox password 2"
        const val CAPTCHA_TOKEN = "madeUpCaptchaToken"
        const val CAPTCHA_URL = "https://verify.proton.me/?methods=captcha&token=$CAPTCHA_TOKEN"
    }
}
