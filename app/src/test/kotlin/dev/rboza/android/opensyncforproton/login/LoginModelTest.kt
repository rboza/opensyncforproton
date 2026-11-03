package dev.rboza.android.opensyncforproton.login

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.Credentials
import dev.rboza.android.opensyncforproton.account.LoginFailure
import dev.rboza.android.opensyncforproton.account.LoginStep
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.account.PasswordMode
import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.sync.lifecycle.LoginCommit
import dev.rboza.android.opensyncforproton.sync.lifecycle.RefusalReason
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// What the login screen holds, and for how long (docs/architecture.md § Security). Values are made up.
@OptIn(ExperimentalCoroutinesApi::class)
class LoginModelTest {
    private val errors = mutableListOf<Throwable>()
    private val scope = CoroutineScope(Job() + UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> errors += e })
    private val interactors = mutableListOf<FakeLoginInteractor>()
    private var nextInteractor: () -> FakeLoginInteractor = { FakeLoginInteractor() }
    private val committed = mutableListOf<NewSession>()
    private var commit: suspend (NewSession) -> LoginCommit = { LoginCommit.Committed(resumed = false) }

    private val model = LoginModel(
        newInteractor = { nextInteractor().also { interactors += it } },
        completeLogin = { committed += it; commit(it) },
        scope = scope,
        log = {},
        nanoTime = { 0L },
    )

    @AfterEach
    fun noUncaughtErrors() = assertEquals(emptyList<Throwable>(), errors)

    private fun session(passphrase: ByteArray = "passphrase".toByteArray()) = NewSession(
        AccountId("user-id-1"), "user@example.test",
        Credentials(Tokens("uid-1", "access-1", "refresh-1"), PasswordMode.ONE, emptyMap(), LockedKeyBundle(emptyList()), mapOf("key-1" to passphrase)),
    )

    private fun script(vararg steps: LoginStep) {
        nextInteractor = { FakeLoginInteractor(*steps.map { step -> suspend { step } }.toTypedArray()) }
    }

    private fun TextFieldState.type(value: String) = setTextAndPlaceCursorAtEnd(value)

    private fun fields() = listOf(model.username, model.password, model.code, model.mailboxPassword)

    private fun logIn(username: String = "user@example.test", password: String = "correct horse") {
        model.username.type(username)
        model.password.type(password)
        model.submit()
    }

    @Test
    fun `credentials are handed over as typed, as an array, and the fields are cleared`() {
        script(LoginStep.NeedTotp(wrongCode = false))
        logIn(" user@example.test ", "correct horse")

        assertEquals(listOf("credentials"), interactors.single().calls)
        assertEquals(listOf("user@example.test", "correct horse"), interactors.single().received)
        fields().forEach { assertEquals("", it.text.toString()) }
    }

    @Test
    fun `every step change clears every field`() {
        script(LoginStep.NeedTotp(wrongCode = false), LoginStep.NeedMailboxPassword(wrongPassword = false), LoginStep.Success(session()))
        logIn()
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
        fields().forEach { it.type("left over") }

        model.code.type("123 456")
        model.submit()
        assertEquals(LoginUi.MailboxPassword(wrongPassword = false), model.state.value)
        assertEquals("123 456", interactors.single().received[2])
        fields().forEach { assertEquals("", it.text.toString()) }
        fields().forEach { it.type("left over") }

        model.mailboxPassword.type("mailbox secret")
        model.submit()
        assertEquals("mailbox secret", interactors.single().received[3])
        assertEquals(LoginUi.Done(resumed = false), model.state.value)
        fields().forEach { assertEquals("", it.text.toString()) }
    }

    @Test
    fun `a refused code or mailbox password asks again`() {
        script(LoginStep.NeedTotp(wrongCode = false), LoginStep.NeedTotp(wrongCode = true), LoginStep.NeedMailboxPassword(wrongPassword = true))
        logIn()
        model.code.type("000000")
        model.submit()
        assertEquals(LoginUi.Totp(wrongCode = true), model.state.value)
        model.code.type("111111")
        model.submit()
        assertEquals(LoginUi.MailboxPassword(wrongPassword = true), model.state.value)
    }

    @Test
    fun `empty input keeps the step and says so`() {
        script(
            LoginStep.Failed(LoginFailure.EMPTY_INPUT), LoginStep.NeedTotp(wrongCode = true), LoginStep.Failed(LoginFailure.EMPTY_INPUT),
            LoginStep.NeedMailboxPassword(wrongPassword = true), LoginStep.Failed(LoginFailure.EMPTY_INPUT),
        )
        logIn(username = "", password = "correct horse")
        assertEquals(LoginUi.Credentials(emptyInput = true), model.state.value)
        fields().forEach { assertEquals("", it.text.toString()) }

        logIn()
        assertEquals(LoginUi.Totp(wrongCode = true), model.state.value)
        model.submit()
        assertEquals(LoginUi.Totp(wrongCode = true, emptyInput = true), model.state.value)
        model.code.type("123456")
        model.submit()
        model.submit()
        assertEquals(LoginUi.MailboxPassword(wrongPassword = true, emptyInput = true), model.state.value)
        assertEquals(1, interactors.size)
    }

    @Test
    fun `a failure ends the attempt, and trying again starts a new one`() {
        script(LoginStep.Failed(LoginFailure.RATE_LIMITED, retryAfter = 40.seconds))
        logIn()
        assertEquals(LoginUi.Failed(LoginFailure.RATE_LIMITED, retryAfter = 40.seconds), model.state.value)

        script(LoginStep.NeedTotp(wrongCode = false))
        model.retry()
        assertEquals(LoginUi.Credentials(), model.state.value)
        logIn()
        assertEquals(2, interactors.size)
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
    }

    @Test
    fun `a captcha is shown with its page, and cancelling it ends the attempt`() {
        script(LoginStep.NeedCaptcha("https://verify.proton.me/?token=made-up"))
        logIn()
        val captcha = assertInstanceOf(LoginUi.Captcha::class.java, model.state.value)
        assertEquals("https://verify.proton.me/?token=made-up", captcha.webUrl)
        assertFalse("made-up" in captcha.toString())

        model.cancel()
        assertEquals(LoginUi.Closed, model.state.value)
        assertEquals(1, interactors.single().cancels)
    }

    @Test
    fun `a solved captcha retries with the page's answer, kept in no state, and a second captcha is shown afresh`() {
        script(
            LoginStep.NeedCaptcha("https://verify.proton.me/?token=first"),
            LoginStep.NeedCaptcha("https://verify.proton.me/?token=second"),
            LoginStep.NeedTotp(wrongCode = false),
        )
        logIn()
        val first = model.state.value as LoginUi.Captcha

        model.captchaSolved("page-answer-1")
        assertFalse(model.heldAnywhere("page-answer-1"), "the answer is in no state")
        // A 12087 (or a new 9001) brings a new page: a new state, so a new WebView.
        val second = assertInstanceOf(LoginUi.Captcha::class.java, model.state.value)
        assertTrue(first !== second)
        assertEquals("https://verify.proton.me/?token=second", second.webUrl)
        assertFalse(second.loadFailed)

        model.captchaSolved("page-answer-2")
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
        assertEquals(listOf("credentials", "captcha", "captcha"), interactors.single().calls)
        assertEquals(listOf("page-answer-1", "page-answer-2"), interactors.single().received.drop(2))
    }

    /** Whether [value] is in the model's state or any of the model's own fields, read by reflection. */
    private fun LoginModel.heldAnywhere(value: String): Boolean {
        val state = state.value
        val fields = (LoginModel::class.java.declaredFields.toList() + state.javaClass.declaredFields)
        return fields.any { field ->
            field.isAccessible = true
            val owner = if (field.declaringClass == LoginModel::class.java) this else state
            field.get(owner)?.toString()?.contains(value) == true
        } || state.toString().contains(value)
    }

    @Test
    fun `a captcha done twice sends one retry`() {
        val gate = CompletableDeferred<LoginStep>()
        nextInteractor = { FakeLoginInteractor({ LoginStep.NeedCaptcha("https://verify.proton.me/?token=t") }, { gate.await() }) }
        logIn()
        model.captchaSolved("page-answer")
        model.captchaSolved("page-answer")
        assertEquals(listOf("credentials", "captcha"), interactors.single().calls)
        gate.complete(LoginStep.NeedTotp(wrongCode = false))
        model.captchaSolved("page-answer")
        assertEquals(listOf("credentials", "captcha"), interactors.single().calls)
    }

    @Test
    fun `a page that fails to load can be reloaded, and is never reported done`() {
        script(LoginStep.NeedCaptcha("https://verify.proton.me/?token=t"), LoginStep.NeedTotp(wrongCode = false))
        logIn()
        val shown = model.state.value as LoginUi.Captcha
        model.captchaLoadFailed()
        val failed = model.state.value as LoginUi.Captcha
        assertTrue(failed.loadFailed)
        assertEquals(shown.webUrl, failed.webUrl)

        model.captchaSolved("page-answer")
        assertEquals(listOf("credentials"), interactors.single().calls)
        // A second report (the refusal's 403 after the backstop, say) changes nothing.
        model.captchaLoadFailed()
        assertTrue(failed === model.state.value)

        model.reloadCaptcha()
        val reloaded = model.state.value as LoginUi.Captcha
        assertFalse(reloaded.loadFailed)
        assertTrue(reloaded !== shown)
        assertEquals(shown.webUrl, reloaded.webUrl)
        model.captchaSolved("page-answer")
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
    }

    @Test
    fun `reload does nothing while the page is fine`() {
        script(LoginStep.NeedCaptcha("https://verify.proton.me/?token=t"))
        logIn()
        val shown = model.state.value
        model.reloadCaptcha()
        assertTrue(shown === model.state.value)
    }

    @Test
    fun `captcha calls outside the captcha step do nothing`() {
        script(LoginStep.NeedTotp(wrongCode = false))
        logIn()
        model.captchaSolved("page-answer")
        model.captchaLoadFailed()
        model.reloadCaptcha()
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
        assertEquals(listOf("credentials"), interactors.single().calls)
    }

    @Test
    fun `a verified login is committed, and the result shown`() {
        val session = session()
        script(LoginStep.Success(session))
        logIn()
        assertEquals(listOf(session), committed)
        assertEquals(LoginUi.Done(resumed = false), model.state.value)

        // Done offers only OK, which leaves the screen.
        model.retry()
        assertEquals(LoginUi.Done(resumed = false), model.state.value)
    }

    @Test
    fun `a resumed account is shown as resumed`() {
        commit = { LoginCommit.Committed(resumed = true) }
        script(LoginStep.Success(session()))
        logIn()
        assertEquals(LoginUi.Done(resumed = true), model.state.value)
    }

    @Test
    fun `a step that throws ends the attempt as stopped, without the exception`() {
        nextInteractor = { FakeLoginInteractor({ throw IllegalStateException("user@example.test") }) }
        logIn()
        assertEquals(LoginUi.Stopped, model.state.value)
        assertFalse("example.test" in model.state.value.toString())
        // The interactor ends its session on any exception; cancel makes sure, and is a no-op if it already did.
        assertEquals(1, interactors.single().cancels)

        script(LoginStep.NeedTotp(wrongCode = false))
        model.retry()
        logIn()
        assertEquals(2, interactors.size)
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
    }

    @Test
    fun `a refused commit is shown with its reason`() {
        commit = { LoginCommit.Refused(RefusalReason.OTHER_ACCOUNT_ACTIVE) }
        script(LoginStep.Success(session()))
        logIn()
        assertEquals(LoginUi.Refused(RefusalReason.OTHER_ACCOUNT_ACTIVE), model.state.value)

        model.retry()
        assertEquals(LoginUi.Credentials(), model.state.value)
    }

    @Test
    fun `a commit that fails is shown as not saved, without its exception`() {
        commit = { throw IllegalStateException("user@example.test") }
        script(LoginStep.Success(session()))
        logIn()
        assertEquals(LoginUi.Failed(reason = null), model.state.value)
        assertFalse("example.test" in model.state.value.toString())
    }

    @Test
    fun `the commit is shown as saving while it runs`() {
        val gate = CompletableDeferred<LoginCommit>()
        commit = { gate.await() }
        script(LoginStep.Success(session()))
        logIn()
        assertEquals(LoginUi.Saving, model.state.value)
        gate.complete(LoginCommit.Committed(resumed = false))
        assertEquals(LoginUi.Done(resumed = false), model.state.value)
    }

    @Test
    fun `a step is shown as working, and taps while it runs send nothing`() {
        val gate = CompletableDeferred<LoginStep>()
        nextInteractor = { FakeLoginInteractor({ gate.await() }) }
        logIn()
        assertEquals(LoginUi.Working, model.state.value)
        logIn()
        model.retry()
        assertEquals(LoginUi.Working, model.state.value)
        assertEquals(listOf("credentials"), interactors.single().calls)
        assertEquals(1, interactors.size)

        gate.complete(LoginStep.NeedTotp(wrongCode = false))
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
        model.retry()
        assertEquals(LoginUi.Totp(wrongCode = false), model.state.value)
    }

    @Test
    fun `back ends the attempt, except while the login is saved`() {
        val gate = CompletableDeferred<LoginCommit>()
        commit = { gate.await() }
        script(LoginStep.NeedTotp(wrongCode = false), LoginStep.Success(session()))
        logIn()
        model.code.type("123456")
        model.submit()
        assertEquals(LoginUi.Saving, model.state.value)

        model.back()
        assertEquals(LoginUi.Saving, model.state.value)
        gate.complete(LoginCommit.Committed(resumed = false))
        assertEquals(LoginUi.Done(resumed = false), model.state.value)

        model.back()
        assertEquals(LoginUi.Closed, model.state.value)
    }

    @Test
    fun `back on a step ends the attempt like cancel`() {
        script(LoginStep.NeedTotp(wrongCode = false))
        logIn()
        model.back()
        assertEquals(LoginUi.Closed, model.state.value)
        assertEquals(1, interactors.single().cancels)
    }

    @Test
    fun `cancel ends the attempt and clears the fields`() {
        script(LoginStep.NeedTotp(wrongCode = false))
        logIn()
        model.code.type("123456")
        model.username.type("user@example.test")

        model.cancel()
        assertEquals(LoginUi.Closed, model.state.value)
        assertEquals(1, interactors.single().cancels)
        fields().forEach { assertEquals("", it.text.toString()) }
        model.submit()
        assertEquals(listOf("credentials"), interactors.single().calls)
        assertEquals(LoginUi.Closed, model.state.value)
    }

    @Test
    fun `cancel before anything was sent clears the fields and sends nothing`() {
        model.username.type("user@example.test")
        model.password.type("correct horse")
        model.cancel()
        assertEquals(LoginUi.Closed, model.state.value)
        assertTrue(interactors.isEmpty())
        fields().forEach { assertEquals("", it.text.toString()) }
    }

    @Test
    fun `a step cancelled from elsewhere ends the attempt as stopped`() {
        nextInteractor = { FakeLoginInteractor({ throw CancellationException("not ours") }) }
        logIn()
        assertEquals(LoginUi.Stopped, model.state.value)
        assertEquals(1, interactors.single().cancels)

        script(LoginStep.NeedTotp(wrongCode = false))
        model.retry()
        logIn()
        assertEquals(2, interactors.size)
    }

    @Test
    fun `cancel while a step runs ends the step`() {
        val gate = CompletableDeferred<LoginStep>()
        nextInteractor = { FakeLoginInteractor({ gate.await() }) }
        logIn()
        model.cancel()
        assertEquals(1, interactors.single().cancels)
        assertEquals(LoginUi.Closed, model.state.value)
        assertTrue(gate.isActive)
        assertTrue(scope.coroutineContext.job.children.none())
    }

    @Test
    fun `an answer already on its way when cancel came is ignored`() {
        val gate = CompletableDeferred<LoginStep>()
        nextInteractor = { FakeLoginInteractor({ gate.await() }, cancelEndsStep = false) }
        logIn()
        model.cancel()

        gate.complete(LoginStep.NeedTotp(wrongCode = false))
        assertEquals(LoginUi.Closed, model.state.value)
    }

    @Test
    fun `a verified login that arrives after cancel is closed, not committed`() {
        val gate = CompletableDeferred<LoginStep>()
        val passphrase = "passphrase".toByteArray()
        nextInteractor = { FakeLoginInteractor({ gate.await() }, cancelEndsStep = false) }
        logIn()
        model.cancel()

        gate.complete(LoginStep.Success(session(passphrase)))
        assertTrue(committed.isEmpty())
        assertArrayEquals(ByteArray(passphrase.size), passphrase)
        assertEquals(LoginUi.Closed, model.state.value)
    }

    @Test
    fun `a verified login that arrives after the screen's scope ended is still committed, never lost`() {
        val gate = CompletableDeferred<LoginStep>()
        val session = session()
        nextInteractor = { FakeLoginInteractor({ gate.await() }) }
        logIn()
        scope.cancel()

        gate.complete(LoginStep.Success(session))
        assertEquals(listOf(session), committed)
    }

    @Test
    fun `a verified login that arrives after the screen closed (scope ended, then cancel) is closed, not committed`() {
        // ViewModel.clear() ends viewModelScope first, then onCleared() calls cancel().
        val gate = CompletableDeferred<LoginStep>()
        val passphrase = "passphrase".toByteArray()
        nextInteractor = { FakeLoginInteractor({ gate.await() }, cancelEndsStep = false) }
        logIn()
        scope.cancel()
        model.cancel()

        gate.complete(LoginStep.Success(session(passphrase)))
        assertTrue(committed.isEmpty())
        assertArrayEquals(ByteArray(passphrase.size), passphrase)
    }

    @Test
    fun `cancel while the commit runs lets it finish, and the screen stays closed`() {
        val gate = CompletableDeferred<LoginCommit>()
        commit = { gate.await() }
        script(LoginStep.Success(session()))
        logIn()
        model.cancel()
        assertEquals(0, interactors.single().cancels)

        gate.complete(LoginCommit.Committed(resumed = false))
        assertEquals(1, committed.size)
        assertEquals(LoginUi.Closed, model.state.value)
    }

    @Test
    fun `each submit hands over its own array`() {
        script(LoginStep.Failed(LoginFailure.EMPTY_INPUT), LoginStep.NeedMailboxPassword(wrongPassword = false), LoginStep.Failed(LoginFailure.KEY_CORRUPT))
        logIn(username = "")
        logIn()
        model.mailboxPassword.type("mailbox secret")
        model.submit()
        // The interactor zeroes what it receives, so no two submits may share an array.
        val arrays = interactors.single().arrays
        assertEquals(3, arrays.size)
        assertEquals(3, arrays.map { System.identityHashCode(it) }.toSet().size)
    }
}
