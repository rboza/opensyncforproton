package dev.rboza.android.opensyncforproton.diagnostics

import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.Credentials
import dev.rboza.android.opensyncforproton.account.LoginFailure
import dev.rboza.android.opensyncforproton.account.LoginStep
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.account.PasswordMode
import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.login.FakeLoginInteractor
import dev.rboza.android.opensyncforproton.login.LoginModel
import dev.rboza.android.opensyncforproton.login.LoginUi
import dev.rboza.android.opensyncforproton.main.AccountUi
import dev.rboza.android.opensyncforproton.main.MainModel
import dev.rboza.android.opensyncforproton.main.SyncOutcome
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.settings.Check
import dev.rboza.android.opensyncforproton.settings.SettingsMessage
import dev.rboza.android.opensyncforproton.settings.SettingsModel
import dev.rboza.android.opensyncforproton.settings.exportFileName
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.lifecycle.LoginCommit
import dev.rboza.android.opensyncforproton.sync.lifecycle.LogoutResult
import dev.rboza.android.opensyncforproton.sync.lifecycle.RefusalReason
import dev.rboza.android.opensyncforproton.sync.lifecycle.RemovalResult
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// What the login, main, and settings screens record (docs/architecture.md § Diagnostics). Names and IDs are made up.
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenRecordsTest {
    private val errors = mutableListOf<Throwable>()
    private val scope = CoroutineScope(Job() + UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> errors += e })
    private val graph = FakeAppGraph()
    private var now = 0L
    private val logged = mutableListOf<LogEntry>()

    @AfterEach
    fun noUncaughtErrors() = assertEquals(emptyList<Throwable>(), errors)

    // Login

    private var commit: suspend () -> LoginCommit = { LoginCommit.Committed(resumed = false) }

    private fun login(vararg steps: suspend () -> LoginStep) = LoginModel(
        newInteractor = { FakeLoginInteractor(*steps.map { step -> suspend { now += 40_000_000; step() } }.toTypedArray()) },
        completeLogin = { commit() },
        scope = scope,
        log = { logged += it },
        nanoTime = { now },
    )

    private fun session(passphrase: ByteArray = "made-up".toByteArray()) = NewSession(
        AccountId("a1"), NAME,
        Credentials(Tokens("uid-1", "access-1", "refresh-1"), PasswordMode.ONE, emptyMap(), LockedKeyBundle(emptyList()), mapOf("key-1" to passphrase)),
    )

    private fun LoginModel.logIn() {
        username.setTextAndPlaceCursorAtEnd(NAME)
        password.setTextAndPlaceCursorAtEnd("made-up password")
        submit()
    }

    @Test
    fun `each login step's answer is recorded with its duration, and no account`() {
        val model = login({ LoginStep.NeedTotp(false) }, { LoginStep.NeedMailboxPassword(false) }, { LoginStep.NeedCaptcha("https://verify.proton.me/") })
        model.logIn()
        model.code.setTextAndPlaceCursorAtEnd("123456")
        model.submit()
        model.mailboxPassword.setTextAndPlaceCursorAtEnd("made-up")
        model.submit()

        assertEquals(
            listOf(Outcome.NEED_TOTP, Outcome.NEED_MAILBOX_PASSWORD, Outcome.NEED_CAPTCHA).map { LogEntry(Operation.LOGIN_STEP, null, it, durationMs = 40) },
            logged,
        )
    }

    @Test
    fun `a failed step records its reason`() {
        login({ LoginStep.Failed(LoginFailure.LOGIN_REFUSED) }).logIn()
        assertEquals(listOf(LogEntry(Operation.LOGIN_STEP, null, LoginFailure.LOGIN_REFUSED, durationMs = 40)), logged)
    }

    @Test
    fun `a step that throws records stopped, without the exception`() {
        login({ throw IllegalStateException(NAME) }).logIn()
        assertEquals(listOf(LogEntry(Operation.LOGIN_STEP, null, Outcome.STOPPED, durationMs = 40)), logged)
    }

    @Test
    fun `a step ended by cancel records cancelled`() {
        val model = login({ awaitCancellation() })
        model.logIn()
        model.cancel()
        assertEquals(listOf(Outcome.CANCELLED), logged.map { it.outcome })
    }

    @Test
    fun `a verified login records success, then the commit with the account`() {
        login({ LoginStep.Success(session()) }).logIn()
        assertEquals(
            listOf(
                LogEntry(Operation.LOGIN_STEP, null, Outcome.SUCCESS, durationMs = 40),
                LogEntry(Operation.LOGIN_COMMIT, AccountId("a1"), Outcome.COMMITTED),
            ),
            logged,
        )
    }

    @Test
    fun `a commit records resumed, a refusal's reason, or not saved`() {
        commit = { LoginCommit.Committed(resumed = true) }
        login({ LoginStep.Success(session()) }).logIn()
        commit = { LoginCommit.Refused(RefusalReason.OTHER_ACCOUNT_ACTIVE) }
        login({ LoginStep.Success(session()) }).logIn()
        commit = { throw IOException(NAME) }
        login({ LoginStep.Success(session()) }).logIn()

        assertEquals(
            listOf(Outcome.RESUMED, RefusalReason.OTHER_ACCOUNT_ACTIVE, Outcome.NOT_SAVED),
            logged.filter { it.operation == Operation.LOGIN_COMMIT }.map { it.outcome },
        )
    }

    @Test
    fun `a log that throws changes nothing about a login`() {
        val model = LoginModel(
            newInteractor = { FakeLoginInteractor({ LoginStep.Success(session()) }) },
            completeLogin = { LoginCommit.Committed(resumed = false) },
            scope = scope,
            log = { throw IllegalStateException("made-up") },
            nanoTime = { now },
        )
        model.logIn()
        assertEquals(LoginUi.Done(resumed = false), model.state.value)
    }

    @Test
    fun `a log that throws doesn't keep a late login's credentials open`() {
        val passphrase = "made-up".toByteArray()
        val answer = CompletableDeferred<LoginStep>()
        val model = LoginModel(
            newInteractor = { FakeLoginInteractor({ answer.await() }, cancelEndsStep = false) },
            completeLogin = { error("not committed after cancel") },
            scope = scope,
            log = { throw IllegalStateException("made-up") },
            nanoTime = { now },
        )
        model.logIn()
        model.cancel()
        answer.complete(LoginStep.Success(session(passphrase)))
        assertTrue(passphrase.all { it == 0.toByte() })
    }

    // Main screen

    private fun signedIn(mode: SyncMode = SyncMode.DRY_RUN) {
        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.ACTIVE))
        graph.settings["a1"] = SettingsEntity("a1", mode, 60)
        graph.readable += "a1"
    }

    private fun ready(kind: ReportKind) = RunReport(kind, 5, null, SyncPlan(emptyList(), emptyList(), false, false)).also {
        graph.reports["a1"] = LastReportEntity("a1", kind, 5, true, null, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0)
    }

    @Test
    fun `Sync now records ran, didn't run, or failed`() {
        signedIn()
        val main = MainModel(graph, scope)
        main.refresh()
        graph.request = { _, _ -> ready(ReportKind.DRY_RUN) }
        main.syncNow()
        graph.request = { _, _ -> null }
        main.syncNow()
        graph.request = { _, _ -> throw IOException(NAME) }
        main.syncNow()

        assertEquals(
            listOf(Outcome.RAN, Outcome.DIDNT_RUN, Outcome.FAILED).map { LogEntry(Operation.SYNC_NOW, AccountId("a1"), it) },
            graph.logged,
        )
    }

    @Test
    fun `turning sync on and off records done or failed`() {
        signedIn()
        val main = MainModel(graph, scope)
        main.refresh()
        graph.request = { _, request -> ready(if (request is SyncRequest.Full) ReportKind.SYNC else ReportKind.DRY_RUN) }
        main.turnOn()
        main.confirm()
        main.turnOff()
        graph.setSyncModeFailure = IllegalStateException("made-up")
        main.turnOn()
        main.confirm()

        assertEquals(
            listOf(
                LogEntry(Operation.SYNC_ON, AccountId("a1"), Outcome.DONE),
                LogEntry(Operation.SYNC_OFF, AccountId("a1"), Outcome.DONE),
                LogEntry(Operation.SYNC_ON, AccountId("a1"), Outcome.FAILED),
            ),
            graph.logged.filter { it.operation != Operation.SYNC_NOW },
        )
    }

    @Test
    fun `a log that throws changes nothing about Sync now`() {
        signedIn()
        graph.logFailure = IllegalStateException("made-up")
        val main = MainModel(graph, scope)
        main.refresh()
        graph.request = { _, _ -> null }
        main.syncNow()
        assertEquals(false, main.state.value?.running)
        assertEquals(SyncOutcome.DIDNT_RUN, main.state.value?.outcome)
    }

    // Settings

    private fun settings(state: LoginState = LoginState.ACTIVE) = SettingsModel(graph, scope).also {
        graph.records = listOf(AccountRecordEntity("a1", NAME, state))
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        graph.readable += "a1"
        it.refresh()
    }

    @Test
    fun `the timing records done or failed, for either interval and Manual`() {
        // INTERVAL covers the full sync, the check, and Manual.
        val model = settings()
        model.chooseFullSync(120)
        model.chooseCheck(Check.Every(30))
        model.setManual(true)
        graph.lifecycleFailure = IllegalStateException("made-up")
        model.setManual(false)
        val done = LogEntry(Operation.INTERVAL, AccountId("a1"), Outcome.DONE)
        assertEquals(listOf(done, done, done, LogEntry(Operation.INTERVAL, AccountId("a1"), Outcome.FAILED)), graph.logged)
    }

    @Test
    fun `a logout records done, and a session not ended its own record`() {
        val model = settings()
        graph.logoutResult = LogoutResult(sessionEnded = false)
        model.logOut()
        model.confirm()
        assertEquals(
            listOf(LogEntry(Operation.LOGOUT, AccountId("a1"), Outcome.DONE), LogEntry(Operation.SESSION_END, AccountId("a1"), Outcome.NOT_ENDED)),
            graph.logged,
        )
    }

    @Test
    fun `a logout that fails records failed, or done with the session not ended once signed out`() {
        val model = settings()
        graph.lifecycleFailure = IllegalStateException("made-up")
        model.logOut()
        model.confirm()
        graph.lifecycleFailure = null
        graph.logoutFailureAfterSignOut = IllegalStateException("made-up")
        model.logOut()
        model.confirm()
        assertEquals(
            listOf(
                LogEntry(Operation.LOGOUT, AccountId("a1"), Outcome.FAILED),
                LogEntry(Operation.LOGOUT, AccountId("a1"), Outcome.DONE),
                LogEntry(Operation.SESSION_END, AccountId("a1"), Outcome.NOT_ENDED),
            ),
            graph.logged,
        )
    }

    @Test
    fun `a removal records removed, kept, or failed, and a session not ended`() {
        var model = settings()
        graph.removalResult = RemovalResult(accountRemoved = true, sessionEnded = false)
        model.remove()
        model.confirm()
        model = settings()
        graph.removalResult = RemovalResult(accountRemoved = false, sessionEnded = true)
        model.remove()
        model.confirm()
        model = settings(LoginState.SIGNED_OUT)
        graph.lifecycleFailure = IllegalStateException("made-up")
        model.remove()
        model.confirm()

        assertEquals(
            listOf(
                LogEntry(Operation.REMOVE, AccountId("a1"), Outcome.REMOVED),
                LogEntry(Operation.SESSION_END, AccountId("a1"), Outcome.NOT_ENDED),
                LogEntry(Operation.REMOVE, AccountId("a1"), Outcome.KEPT),
                LogEntry(Operation.REMOVE, AccountId("a1"), Outcome.FAILED),
            ),
            graph.logged,
        )
    }

    @Test
    fun `a removal that fails after signing out a signed-in account records the session not ended`() {
        val model = settings()
        graph.removalFailureAfterSignOut = IllegalStateException("made-up")
        model.remove()
        model.confirm()
        assertEquals(
            listOf(LogEntry(Operation.REMOVE, AccountId("a1"), Outcome.FAILED), LogEntry(Operation.SESSION_END, AccountId("a1"), Outcome.NOT_ENDED)),
            graph.logged,
        )
    }

    @Test
    fun `a log that throws changes nothing about a logout`() {
        val model = settings()
        graph.logFailure = IllegalStateException("made-up")
        model.logOut()
        model.confirm()
        assertEquals(AccountUi.SignedOut(NAME), model.state.value?.account)
        assertEquals(false, model.state.value?.working)
        assertEquals(null, model.state.value?.message)
    }

    // Export

    /** Remembers being closed. */
    private class ClosingStream : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the chosen file is closed whether the export works or not`() {
        val model = settings()
        val saved = ClosingStream()
        model.exportLogs { saved }
        graph.exportFailure = IOException("made-up")
        val failed = ClosingStream()
        model.exportLogs { failed }
        assertTrue(saved.closed)
        assertTrue(failed.closed)
    }

    @Test
    fun `Export logs writes them to the chosen file and says they were saved`() {
        val model = settings()
        val out = ByteArrayOutputStream()
        model.exportLogs { out }
        assertEquals("made-up log line\n", out.toString(Charsets.UTF_8))
        assertEquals(SettingsMessage.LOGS_SAVED, model.state.value?.message)
    }

    @Test
    fun `an export that fails, or a file that won't open, says the logs weren't saved`() {
        val model = settings()
        graph.exportFailure = IOException("made-up")
        model.exportLogs { ByteArrayOutputStream() }
        assertEquals(SettingsMessage.LOGS_FAILED, model.state.value?.message)

        graph.exportFailure = null
        model.exportLogs { throw IOException("made-up") }
        assertEquals(SettingsMessage.LOGS_FAILED, model.state.value?.message)
    }

    @Test
    fun `the exported file is named for the day`() {
        assertEquals("osp-logs-2026-10-08.jsonl", exportFileName(LocalDate.of(2026, 10, 8)))
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
    }
}
