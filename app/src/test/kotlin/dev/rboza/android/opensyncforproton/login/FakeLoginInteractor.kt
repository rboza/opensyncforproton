package dev.rboza.android.opensyncforproton.login

import dev.rboza.android.opensyncforproton.account.LoginInteractor
import dev.rboza.android.opensyncforproton.account.LoginStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job

/**
 * Answers each call with the next scripted step; records what it was given (copies) and how often it was cancelled.
 * Like the real interactor, [cancel] ends a running step with a CancellationException (each step runs in its own
 * scope); with [cancelEndsStep] false, the step's answer was already on its way when [cancel] came, so it still
 * arrives.
 */
class FakeLoginInteractor(vararg steps: suspend () -> LoginStep, private val cancelEndsStep: Boolean = true) : LoginInteractor {
    private val steps = ArrayDeque(steps.toList())
    private var running: Job? = null
    val calls = mutableListOf<String>()

    /** What each call received, copied before the arrays are zeroed (as the real interactor zeroes them). */
    val received = mutableListOf<String>()

    /** The arrays the calls received. */
    val arrays = mutableListOf<CharArray>()
    var cancels = 0

    private suspend fun answer(call: String, vararg values: String): LoginStep = coroutineScope {
        calls += call
        received += values
        running = coroutineContext.job
        try {
            steps.removeFirstOrNull()?.invoke() ?: error("no step scripted for $call")
        } finally {
            running = null
        }
    }

    override suspend fun submitCredentials(username: String, password: CharArray): LoginStep {
        arrays += password
        val copy = String(password)
        password.fill('\u0000')
        return answer("credentials", username, copy)
    }

    override suspend fun submitTotp(code: String): LoginStep = answer("totp", code)

    override suspend fun submitMailboxPassword(password: CharArray): LoginStep {
        arrays += password
        val copy = String(password)
        password.fill('\u0000')
        return answer("mailbox", copy)
    }

    override suspend fun captchaSolved(token: String): LoginStep = answer("captcha", token)

    override fun cancel() {
        cancels++
        if (cancelEndsStep) running?.cancel()
    }
}
