package dev.rboza.android.opensyncforproton.login

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import dev.rboza.android.opensyncforproton.account.LoginFailure
import dev.rboza.android.opensyncforproton.account.LoginInteractor
import dev.rboza.android.opensyncforproton.account.LoginStep
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import dev.rboza.android.opensyncforproton.diagnostics.Operation
import dev.rboza.android.opensyncforproton.diagnostics.Outcome
import dev.rboza.android.opensyncforproton.sync.lifecycle.LoginCommit
import dev.rboza.android.opensyncforproton.sync.lifecycle.RefusalReason
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the login screen shows. */
sealed interface LoginUi {
    /** [emptyInput]: the last submit left a field empty (nothing was sent); the step can be tried again. */
    data class Credentials(val emptyInput: Boolean = false) : LoginUi
    data class Totp(val wrongCode: Boolean, val emptyInput: Boolean = false) : LoginUi
    data class MailboxPassword(val wrongPassword: Boolean, val emptyInput: Boolean = false) : LoginUi

    /**
     * Proton's captcha page, already checked by the interactor (docs/protocol.md § Captcha (Code 9001)); [loadFailed]
     * when it didn't load. Not a data class: no toString shows the page, and each state is its own WebView.
     */
    class Captcha(val webUrl: String, val loadFailed: Boolean = false) : LoginUi

    /** A step is with Proton. */
    data object Working : LoginUi

    /** The verified login is being saved on the phone (AccountLifecycle.completeLogin). */
    data object Saving : LoginUi
    data class Done(val resumed: Boolean) : LoginUi
    data class Refused(val reason: RefusalReason) : LoginUi

    /** The attempt is over. [reason] is null when Proton accepted the login but saving it on the phone failed. */
    data class Failed(val reason: LoginFailure?, val retryAfter: Duration? = null) : LoginUi

    /** Something unexpected on the phone stopped the attempt (the interactor ended its session). */
    data object Stopped : LoginUi

    /** The screen was left; nothing is kept. */
    data object Closed : LoginUi
}

/**
 * The login screen's state, one attempt at a time. Typed values live only in these fields, never in saved state; every
 * change of step clears all of them, and a password is handed over as its own array, which the interactor zeroes.
 * (Compose's own text buffer can be cleared but not zeroed.) Everything runs on the caller's thread, as
 * [LoginInteractor] requires. A step, once sent, runs to its answer even if [scope] ends, so a verified login is never
 * dropped unclosed: it is committed, or after [cancel] its credentials are closed instead (its server session then
 * expires on its own); a commit already started finishes.
 */
class LoginModel(
    private val newInteractor: () -> LoginInteractor,
    private val completeLogin: suspend (NewSession) -> LoginCommit,
    private val scope: CoroutineScope,
    private val log: (LogEntry) -> Unit,
    private val nanoTime: () -> Long,
) {
    val username = TextFieldState()
    val password = TextFieldState()
    val code = TextFieldState()
    val mailboxPassword = TextFieldState()

    private val ui = MutableStateFlow<LoginUi>(LoginUi.Credentials())
    val state: StateFlow<LoginUi> = ui

    private val shownSteps = MutableStateFlow(0)

    /**
     * Counts the changes of step, an equal step again included (two wrong mailbox passwords), so the screen can start
     * each one hidden.
     */
    val stepCount: StateFlow<Int> = shownSteps

    private var attempt: LoginInteractor? = null
    private var closed = false

    /** Sends what the current step asks for; does nothing while a step runs or when the step takes no input. */
    fun submit() {
        when (val step = ui.value) {
            is LoginUi.Credentials -> {
                val name = username.text.toString().trim()
                val secret = password.take()
                send(step) { it.submitCredentials(name, secret) }
            }
            is LoginUi.Totp -> {
                val typed = code.text.toString()
                send(step) { it.submitTotp(typed) }
            }
            is LoginUi.MailboxPassword -> {
                val secret = mailboxPassword.take()
                send(step) { it.submitMailboxPassword(secret) }
            }
            else -> Unit
        }
    }

    /**
     * The captcha page said it's done (once), with its answer: the retry's token, handed straight to the interactor and
     * kept in no state.
     */
    fun captchaSolved(token: String) {
        val step = ui.value
        if (step is LoginUi.Captcha && !step.loadFailed) send(step) { it.captchaSolved(token) }
    }

    /** The captcha page didn't load: offer Reload and Cancel (once; a second report changes nothing). */
    fun captchaLoadFailed() {
        val step = ui.value
        if (step is LoginUi.Captcha && !step.loadFailed) show(LoginUi.Captcha(step.webUrl, loadFailed = true))
    }

    /** A fresh page (a new state, so a new WebView) for the same captcha. */
    fun reloadCaptcha() {
        val step = ui.value
        if (step is LoginUi.Captcha && step.loadFailed) show(LoginUi.Captcha(step.webUrl))
    }

    /** After the attempt failed, was refused, or stopped: a new one, from the start. */
    fun retry() {
        if (ui.value is LoginUi.Failed || ui.value is LoginUi.Refused || ui.value == LoginUi.Stopped) show(LoginUi.Credentials())
    }

    /** Back: [cancel], except while the login is saved, so its result is seen; the save finishes either way. */
    fun back() {
        if (ui.value != LoginUi.Saving) cancel()
    }

    /**
     * Leaves the screen: ends the attempt (the interactor sends its A5), clears every field, and shows
     * [LoginUi.Closed].
     */
    fun cancel() {
        if (closed) return
        closed = true
        attempt?.cancel()
        attempt = null
        show(LoginUi.Closed)
    }

    private fun send(from: LoginUi, call: suspend (LoginInteractor) -> LoginStep) {
        val interactor = attempt ?: newInteractor().also { attempt = it }
        show(LoginUi.Working)
        val start = nanoTime()
        fun recordStep(outcome: Enum<*>) = record(LogEntry(Operation.LOGIN_STEP, null, outcome, durationMs = (nanoTime() - start) / 1_000_000))
        scope.launch {
            // Only the interactor's own cancel ends a step early, so its answer is never lost.
            withContext(NonCancellable) {
                val step = try {
                    call(interactor)
                } catch (e: CancellationException) {
                    recordStep(Outcome.CANCELLED)
                    // Ours, after cancel(); or from elsewhere, and then the attempt can't go on.
                    if (!closed) stop(interactor)
                    throw e
                } catch (_: Exception) {
                    // A library error, say. Nothing of the exception is kept (D23).
                    recordStep(Outcome.STOPPED)
                    null
                }
                step?.let { recordStep(it.outcome()) }
                when {
                    closed -> (step as? LoginStep.Success)?.session?.credentials?.close()
                    step == null -> stop(interactor)
                    else -> answer(from, step)
                }
            }
        }
    }

    /** The interactor ends its session on any exception; cancel makes sure (a no-op if it already has). */
    private fun stop(interactor: LoginInteractor) {
        attempt = null
        interactor.cancel()
        show(LoginUi.Stopped)
    }

    private suspend fun answer(from: LoginUi, step: LoginStep) {
        when (step) {
            is LoginStep.NeedTotp -> show(LoginUi.Totp(step.wrongCode))
            is LoginStep.NeedMailboxPassword -> show(LoginUi.MailboxPassword(step.wrongPassword))
            is LoginStep.NeedCaptcha -> show(LoginUi.Captcha(step.webUrl))
            is LoginStep.Failed -> if (step.reason == LoginFailure.EMPTY_INPUT) {
                show(from.withEmptyInput())
            } else {
                attempt = null
                show(LoginUi.Failed(step.reason, step.retryAfter))
            }
            is LoginStep.Success -> {
                attempt = null
                show(LoginUi.Saving)
                val account = step.session.accountId
                val result = try {
                    when (val commit = completeLogin(step.session)) {
                        is LoginCommit.Committed -> LoginUi.Done(commit.resumed)
                        is LoginCommit.Refused -> LoginUi.Refused(commit.reason)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Nothing of the exception is kept: its message could name the account (D23).
                    LoginUi.Failed(reason = null)
                }
                val outcome: Enum<*> = when (result) {
                    is LoginUi.Done -> if (result.resumed) Outcome.RESUMED else Outcome.COMMITTED
                    is LoginUi.Refused -> result.reason
                    else -> Outcome.NOT_SAVED
                }
                record(LogEntry(Operation.LOGIN_COMMIT, account, outcome))
                if (!closed) show(result)
            }
        }
    }

    /** A record never fails the login it describes (docs/architecture.md § Diagnostics). */
    private fun record(entry: LogEntry) {
        try {
            log(entry)
        } catch (_: Exception) {
        }
    }

    private fun LoginStep.outcome(): Enum<*> = when (this) {
        is LoginStep.NeedTotp -> Outcome.NEED_TOTP
        is LoginStep.NeedMailboxPassword -> Outcome.NEED_MAILBOX_PASSWORD
        is LoginStep.NeedCaptcha -> Outcome.NEED_CAPTCHA
        is LoginStep.Success -> Outcome.SUCCESS
        is LoginStep.Failed -> reason
    }

    private fun LoginUi.withEmptyInput(): LoginUi = when (this) {
        is LoginUi.Credentials -> copy(emptyInput = true)
        is LoginUi.Totp -> copy(emptyInput = true)
        is LoginUi.MailboxPassword -> copy(emptyInput = true)
        else -> this
    }

    /** Every change of step clears every field. */
    private fun show(next: LoginUi) {
        listOf(username, password, code, mailboxPassword).forEach { it.clearText() }
        shownSteps.value++
        ui.value = next
    }

    /** The field's characters as a new array (no String copy), for the interactor to zero. */
    private fun TextFieldState.take(): CharArray {
        val typed = text
        return CharArray(typed.length) { typed[it] }
    }
}
