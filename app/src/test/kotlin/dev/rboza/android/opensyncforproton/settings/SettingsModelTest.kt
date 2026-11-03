package dev.rboza.android.opensyncforproton.settings

import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.diagnostics.CopyCount
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import dev.rboza.android.opensyncforproton.diagnostics.Operation
import dev.rboza.android.opensyncforproton.diagnostics.Outcome
import dev.rboza.android.opensyncforproton.main.AccountUi
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.lifecycle.LogoutResult
import dev.rboza.android.opensyncforproton.sync.lifecycle.RemovalResult
import dev.rboza.android.opensyncforproton.sync.provider.CopyResult
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The settings screen's model: the timing, Log out, and Remove from this phone (docs/sync.md § Logout and removal,
// § Screens and notifications). Names and IDs are made up.
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsModelTest {
    private val errors = mutableListOf<Throwable>()
    private val scope = CoroutineScope(Job() + UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> errors += e })
    private val graph = FakeAppGraph()
    private val model = SettingsModel(graph, scope)

    @AfterEach
    fun noUncaughtErrors() = assertEquals(emptyList<Throwable>(), errors)

    private fun ui() = checkNotNull(model.state.value)

    private fun account(state: LoginState = LoginState.ACTIVE, readable: Boolean = true, interval: Int = 60, check: Int? = 15, manual: Boolean = false) {
        graph.records = listOf(AccountRecordEntity("a1", NAME, state))
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, interval, check, manual)
        if (readable) graph.readable += "a1" else graph.readable -= "a1"
        model.refresh()
    }

    private fun row(id: Long, dirty: Boolean = false, deleted: Boolean = false) = DeviceContact(id, "p$id", dirty, deleted, 1, "Contact $id")

    @Test
    fun `it shows the account the main screen shows, with its timing`() {
        graph.records = listOf(
            AccountRecordEntity("a2", "b@example.test", LoginState.SIGNED_OUT),
            AccountRecordEntity("a1", NAME, LoginState.ACTIVE),
        )
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 360, 30)
        graph.readable += "a1"
        model.refresh()

        assertEquals(AccountUi.SignedIn(NAME), ui().account)
        assertEquals(Timing(360, Check.Every(30), manual = false), ui().timing)
    }

    @Test
    fun `a null check is Same as full sync, Manual keeps both intervals, and missing settings mean the defaults`() {
        account(interval = 120, check = null, manual = true)
        assertEquals(Timing(120, Check.SameAsFullSync, manual = true), ui().timing)

        graph.settings.clear()
        model.refresh()
        // docs/sync.md § Scheduling: a full sync every hour, a check every 15 minutes, Manual off.
        assertEquals(Timing(60, Check.Every(15), manual = false), ui().timing)
    }

    @Test
    fun `the choices are the listed intervals, and the check's end with Same as full sync`() {
        assertEquals(listOf(15, 30, 60, 120, 360, 720, 1440), Timing.intervals)
        assertEquals(Timing.intervals.map(Check::Every) + Check.SameAsFullSync, Timing.checkChoices)
    }

    @Test
    fun `a check longer than the full sync can't be chosen`() {
        val timing = Timing(60, Check.Every(15), manual = false)
        assertEquals(
            listOf(true, true, true, false, false, false, false, true),
            Timing.checkChoices.map(timing::allows),
        )
    }

    @Test
    fun `Manual is a switch of its own, and keeps the submenus closed`() {
        account()
        model.setManual(true)
        assertEquals(listOf("setManual a1 true"), graph.calls)
        assertEquals(Timing(60, Check.Every(15), manual = true), ui().timing)

        model.openMenu(Menu.FULL_SYNC)
        model.openMenu(Menu.CHECK)
        assertNull(ui().menu)

        model.setManual(false)
        assertEquals(listOf("setManual a1 true", "setManual a1 false"), graph.calls)
        model.openMenu(Menu.CHECK)
        assertEquals(Menu.CHECK, ui().menu)
    }

    @Test
    fun `choosing in a submenu sets it, shows it, and closes the submenu`() {
        account(interval = 60, check = 15)
        model.openMenu(Menu.FULL_SYNC)
        assertEquals(Menu.FULL_SYNC, ui().menu)
        model.chooseFullSync(360)
        assertNull(ui().menu)

        model.openMenu(Menu.CHECK)
        model.chooseCheck(Check.Every(120))
        model.openMenu(Menu.CHECK)
        model.chooseCheck(Check.SameAsFullSync)

        assertEquals(listOf("setInterval a1 360", "setCheckInterval a1 120", "setCheckInterval a1 null"), graph.calls)
        assertEquals(Timing(360, Check.SameAsFullSync, manual = false), ui().timing)
        assertNull(ui().menu)
    }

    @Test
    fun `a full sync's choice is read back, with the check the lifecycle lowered`() {
        // The lowering itself is the lifecycle's and Room's (AccountLifecycleTest, SyncDatabaseTest); here, the
        // re-read.
        account(interval = 360, check = 120)
        model.chooseFullSync(30)
        assertEquals(Timing(30, Check.Every(30), manual = false), ui().timing)
    }

    @Test
    fun `Back closes a submenu without a choice`() {
        account()
        model.openMenu(Menu.FULL_SYNC)
        model.closeMenu()
        assertNull(ui().menu)
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `a choice that isn't listed or is longer than the full sync sends nothing and keeps the submenu`() {
        account(interval = 60, check = 15)
        model.openMenu(Menu.CHECK)
        model.chooseFullSync(45)
        model.chooseCheck(Check.Every(45))
        model.chooseCheck(Check.Every(120))
        model.setManual(false)
        assertEquals(emptyList<String>(), graph.calls)
        assertEquals(Menu.CHECK, ui().menu, "nothing chosen: the submenu stays")
    }

    @Test
    fun `the choice already set closes the submenu and sends nothing`() {
        // Choosing closes the submenu.
        account(interval = 60, check = 15)
        model.openMenu(Menu.FULL_SYNC)
        model.chooseFullSync(60)
        assertNull(ui().menu)
        model.openMenu(Menu.CHECK)
        model.chooseCheck(Check.Every(15))
        assertNull(ui().menu)
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `a timing that fails to change says so`() {
        account()
        graph.lifecycleFailure = IllegalStateException("made-up failure")
        for (choose in listOf({ model.chooseFullSync(360) }, { model.chooseCheck(Check.Every(30)) }, { model.setManual(true) })) {
            model.openMenu(Menu.FULL_SYNC)
            choose()
            assertEquals(SettingsMessage.INTERVAL_FAILED, ui().message)
            assertEquals(Timing(60, Check.Every(15), manual = false), ui().timing)
            assertFalse(ui().working)
            assertNull(ui().menu)
        }
    }

    @Test
    fun `a read that shows Manual on, another account, or none closes an open submenu`() {
        account()
        model.openMenu(Menu.FULL_SYNC)
        account(manual = true)
        assertNull(ui().menu)

        account()
        model.openMenu(Menu.FULL_SYNC)
        graph.records = listOf(AccountRecordEntity("a2", "two@example.test", LoginState.SIGNED_OUT))
        model.refresh()
        assertNull(ui().menu, "another account")

        account()
        model.openMenu(Menu.CHECK)
        graph.records = emptyList()
        model.refresh()
        assertNull(ui().menu, "no account")
    }

    @Test
    fun `Log out is offered signed in or with an expired session, Remove whenever there's an account`() {
        model.refresh()
        assertFalse(ui().offersLogOut)
        assertFalse(ui().offersRemoval)

        account()
        assertTrue(ui().offersLogOut)
        assertTrue(ui().offersRemoval)

        account(readable = false)
        assertEquals(AccountUi.SessionExpired(NAME), ui().account)
        assertTrue(ui().offersLogOut)

        account(LoginState.SIGNED_OUT)
        assertFalse(ui().offersLogOut)
        assertTrue(ui().offersRemoval)
    }

    @Test
    fun `Log out asks first, and Cancel sends nothing`() {
        account()
        model.logOut()
        assertEquals(Question.LogOut, ui().question)
        assertEquals(emptyList<String>(), graph.calls)

        model.cancelQuestion()
        assertNull(ui().question)
        model.confirm()
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `a confirmed logout logs out and shows the account signed out`() {
        account()
        model.logOut()
        model.confirm()
        assertEquals(listOf("logout a1"), graph.calls)
        assertEquals(AccountUi.SignedOut(NAME), ui().account)
        assertNull(ui().question)
        assertEquals(emptySet<Note>(), ui().notes)
    }

    @Test
    fun `a logout whose session wasn't ended says it may need ending from Proton's web settings, until dismissed`() {
        account()
        graph.logoutResult = LogoutResult(sessionEnded = false)
        model.logOut()
        model.confirm()
        assertEquals(setOf(Note.SESSION_NOT_ENDED), ui().notes)

        model.refresh()
        assertEquals(setOf(Note.SESSION_NOT_ENDED), ui().notes)
        model.dismiss(Note.SESSION_NOT_ENDED)
        assertEquals(emptySet<Note>(), ui().notes)
    }

    @Test
    fun `a logout that fails after signing out is done, with the session note, not a failure`() {
        account()
        graph.logoutFailureAfterSignOut = IllegalStateException("made-up failure")
        model.logOut()
        model.confirm()
        assertNull(ui().message)
        assertEquals(AccountUi.SignedOut(NAME), ui().account)
        assertEquals(setOf(Note.SESSION_NOT_ENDED), ui().notes)
    }

    @Test
    fun `a logout that fails while another, signed-out account is shown is a failure`() {
        account()
        graph.lifecycleGate = {
            graph.records = listOf(AccountRecordEntity("a2", "a@example.test", LoginState.SIGNED_OUT))
        }
        graph.lifecycleFailure = IllegalStateException("made-up failure")
        model.logOut()
        model.confirm()
        assertEquals(SettingsMessage.LOGOUT_FAILED, ui().message)
        assertEquals(emptySet<Note>(), ui().notes)
    }

    @Test
    fun `a removal's count that comes back after a read shows another account asks nothing`() {
        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT), AccountRecordEntity("a2", "z@example.test", LoginState.SIGNED_OUT))
        model.refresh()
        val gate = CompletableDeferred<Unit>()
        graph.rowsGate = { gate.await() }
        model.remove()

        graph.records = listOf(AccountRecordEntity("a2", "z@example.test", LoginState.SIGNED_OUT))
        model.refresh()
        gate.complete(Unit)

        assertNull(ui().question)
        assertFalse(ui().working)
        model.confirm()
        assertEquals(listOf("deviceContacts a1 $NAME"), graph.calls)
    }

    @Test
    fun `a removal's count that comes back after a read shows no account asks nothing`() {
        account(LoginState.SIGNED_OUT)
        val gate = CompletableDeferred<Unit>()
        graph.rowsGate = { gate.await() }
        model.remove()

        graph.records = emptyList()
        model.refresh()
        gate.complete(Unit)

        assertNull(ui().question)
        assertFalse(ui().working)
    }

    @Test
    fun `a logout that fails says so`() {
        account()
        graph.lifecycleFailure = IOException("made-up failure")
        model.logOut()
        model.confirm()
        assertEquals(SettingsMessage.LOGOUT_FAILED, ui().message)
        assertNull(ui().question)
        assertFalse(ui().working)
    }

    @Test
    fun `Log out isn't offered signed out, so does nothing`() {
        account(LoginState.SIGNED_OUT)
        model.logOut()
        assertNull(ui().question)
    }

    @Test
    fun `Remove asks first, with the contacts it deletes and those with phone edits`() {
        account()
        graph.rows = listOf(row(1), row(2, dirty = true), row(3), row(4, dirty = true, deleted = true), row(5, deleted = true))

        model.remove()

        assertEquals(Question.Remove(RemovalCount(contacts = 3, withPhoneEdits = 1)), ui().question)
        assertEquals(listOf("deviceContacts a1 $NAME"), graph.calls)
    }

    @Test
    fun `Remove asks anyway when the rows can't be read, saying the count isn't available`() {
        account()
        graph.rowsFailure = SecurityException("made-up")
        model.remove()
        assertEquals(Question.Remove(count = null), ui().question)
    }

    @Test
    fun `a confirmed removal removes the account and is done`() {
        account()
        model.remove()
        model.confirm()
        assertEquals(listOf("deviceContacts a1 $NAME", "removeFromPhone a1"), graph.calls)
        assertTrue(ui().removed)
        assertEquals(emptySet<Note>(), ui().notes)
    }

    @Test
    fun `a removal Android refused keeps the account signed out and says so`() {
        account()
        graph.removalResult = RemovalResult(accountRemoved = false, sessionEnded = true)
        model.remove()
        model.confirm()
        assertFalse(ui().removed)
        assertEquals(setOf(Note.ACCOUNT_KEPT), ui().notes)
        assertEquals(AccountUi.SignedOut(NAME), ui().account)
    }

    @Test
    fun `a removal of a signed-in account whose session wasn't ended says so`() {
        account()
        graph.removalResult = RemovalResult(accountRemoved = true, sessionEnded = false)
        model.remove()
        model.confirm()
        assertEquals(setOf(Note.SESSION_NOT_ENDED), ui().notes)
    }

    @Test
    fun `a removal of an account whose session expired, so wasn't ended, says so`() {
        account(readable = false)
        graph.removalResult = RemovalResult(accountRemoved = true, sessionEnded = false)
        model.remove()
        model.confirm()
        assertEquals(setOf(Note.SESSION_NOT_ENDED), ui().notes)
    }

    @Test
    fun `a removal of a signed-out account has no session to end, so says nothing of it`() {
        account(LoginState.SIGNED_OUT)
        graph.removalResult = RemovalResult(accountRemoved = true, sessionEnded = false)
        model.remove()
        model.confirm()
        assertEquals(emptySet<Note>(), ui().notes)
        assertTrue(ui().removed)
    }

    @Test
    fun `a removal that fails after signing out a signed-in account says so, with the session note`() {
        account()
        graph.removalFailureAfterSignOut = IllegalStateException("made-up failure")
        model.remove()
        model.confirm()
        assertEquals(SettingsMessage.REMOVAL_FAILED, ui().message)
        assertEquals(setOf(Note.SESSION_NOT_ENDED), ui().notes)
        assertEquals(AccountUi.SignedOut(NAME), ui().account)
    }

    @Test
    fun `a removal that fails while another, signed-out account is shown has no session note`() {
        account()
        graph.lifecycleGate = {
            graph.records = listOf(AccountRecordEntity("a2", "a@example.test", LoginState.SIGNED_OUT))
        }
        graph.lifecycleFailure = IllegalStateException("made-up failure")
        model.remove()
        model.confirm()
        assertEquals(SettingsMessage.REMOVAL_FAILED, ui().message)
        assertEquals(emptySet<Note>(), ui().notes)
    }

    @Test
    fun `a removal of a signed-out account that fails has no session to note`() {
        account(LoginState.SIGNED_OUT)
        graph.removalFailureAfterSignOut = IllegalStateException("made-up failure")
        model.remove()
        model.confirm()
        assertEquals(SettingsMessage.REMOVAL_FAILED, ui().message)
        assertEquals(emptySet<Note>(), ui().notes)
    }

    @Test
    fun `a removal that fails says so`() {
        account()
        graph.lifecycleFailure = IllegalStateException("made-up failure")
        model.remove()
        model.confirm()
        assertEquals(SettingsMessage.REMOVAL_FAILED, ui().message)
        assertFalse(ui().removed)
        assertFalse(ui().working)
        assertEquals(emptySet<Note>(), ui().notes, "failed before signing out: the session is still there")
    }

    @Test
    fun `while an action runs, it's working and nothing else starts`() {
        account()
        val gate = CompletableDeferred<Unit>()
        graph.lifecycleGate = { gate.await() }
        model.logOut()
        model.confirm()
        assertTrue(ui().working)

        model.chooseFullSync(15)
        model.chooseCheck(Check.Every(30))
        model.setManual(true)
        model.openMenu(Menu.FULL_SYNC)
        assertNull(ui().menu)
        model.remove()
        model.logOut()
        model.confirm()
        assertEquals(listOf("logout a1"), graph.calls)

        gate.complete(Unit)
        assertFalse(ui().working)
    }

    @Test
    fun `while the rows are counted, it's working and nothing else starts`() {
        account()
        val gate = CompletableDeferred<Unit>()
        graph.rowsGate = { gate.await() }
        model.remove()
        assertTrue(ui().working)

        model.logOut()
        model.chooseFullSync(15)
        assertNull(ui().question)
        assertEquals(listOf("deviceContacts a1 $NAME"), graph.calls)

        gate.complete(Unit)
        assertFalse(ui().working)
        assertEquals(Question.Remove(RemovalCount(0, 0)), ui().question)
    }

    @Test
    fun `a read that ends after a newer one has shown its state is dropped`() {
        account()
        val gate = CompletableDeferred<Unit>()
        graph.accountsGate = { gate.await() }
        model.refresh() // reads a1's rows, then waits

        graph.accountsGate = null
        graph.records = listOf(AccountRecordEntity("a2", "two@example.test", LoginState.SIGNED_OUT))
        model.refresh()
        assertEquals(AccountUi.SignedOut("two@example.test"), ui().account)

        gate.complete(Unit)
        assertEquals(AccountUi.SignedOut("two@example.test"), ui().account)
    }

    @Test
    fun `while a question is asked, other actions wait`() {
        account()
        model.logOut()
        model.chooseFullSync(15)
        model.setManual(true)
        model.openMenu(Menu.CHECK)
        model.remove()
        assertEquals(Question.LogOut, ui().question)
        assertNull(ui().menu)
        assertEquals(emptyList<String>(), graph.calls)
    }

    @Test
    fun `the next action clears the last message`() {
        account()
        graph.lifecycleFailure = IllegalStateException("made-up failure")
        model.chooseFullSync(15)
        assertEquals(SettingsMessage.INTERVAL_FAILED, ui().message)

        graph.lifecycleFailure = null
        model.chooseFullSync(30)
        assertNull(ui().message)
    }

    @Test
    fun `a question is dropped when a read shows another account or no longer offers it`() {
        account()
        model.logOut()
        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT))
        model.refresh()
        assertNull(ui().question)
        model.confirm()
        assertEquals(emptyList<String>(), graph.calls)

        model.remove()
        graph.records = listOf(AccountRecordEntity("a2", "two@example.test", LoginState.SIGNED_OUT))
        model.refresh()
        assertNull(ui().question)
        model.confirm()
        assertEquals(listOf("deviceContacts a1 $NAME"), graph.calls)
    }

    @Test
    fun `Export logs asks the screen for a file once, and not while busy`() {
        account()
        model.requestExport()
        assertTrue(ui().asksExportFile)
        model.refresh()
        assertTrue(ui().asksExportFile, "a read keeps it")
        model.exportFileAsked()
        assertFalse(ui().asksExportFile)

        model.logOut()
        model.requestExport()
        assertFalse(ui().asksExportFile)
    }

    @Test
    fun `the third-party licences open and close, also while a question waits or an action runs, and stay open across a read`() {
        account()
        model.openNotices()
        assertTrue(ui().showsNotices)
        model.refresh()
        assertTrue(ui().showsNotices, "a read keeps them open")
        model.closeNotices()
        assertFalse(ui().showsNotices)

        model.logOut()
        model.openNotices()
        assertTrue(ui().showsNotices)
        assertEquals(Question.LogOut, ui().question)
        model.closeNotices()

        val gate = CompletableDeferred<Unit>()
        graph.lifecycleGate = { gate.await() }
        model.confirm()
        assertTrue(ui().working)
        model.openNotices()
        assertTrue(ui().showsNotices)
        gate.complete(Unit)
        assertFalse(ui().working)
        assertTrue(ui().showsNotices, "the action's read keeps them open")
    }

    @Test
    fun `opening again closes a submenu left open`() {
        account()
        model.openMenu(Menu.CHECK)
        model.open()
        assertNull(ui().menu)
    }

    @Test
    fun `opening again drops a question left open`() {
        account()
        model.logOut()
        model.open()
        assertNull(ui().question)
    }

    @Test
    fun `opening again starts without the last question, message, removal, or licences`() {
        account()
        model.remove()
        model.confirm()
        assertTrue(ui().removed)

        account()
        model.openNotices()
        model.open()
        assertFalse(ui().removed)
        assertNull(ui().question)
        assertNull(ui().message)
        assertFalse(ui().showsNotices)
    }

    // Keep a copy on this phone (docs/sync.md § Keep a copy).

    private fun copyRecord(copied: Int, notCopied: Int) =
        LogEntry(Operation.KEEP_COPY, AccountId("a1"), Outcome.DONE, mapOf(CopyCount.COPIED to copied, CopyCount.NOT_COPIED to notCopied))

    @Test
    fun `Import from this phone is offered whenever there's an account, in every state and mode, and not without one`() {
        model.refresh()
        assertFalse(ui().offersImport)

        for (state in listOf(LoginState.ACTIVE, LoginState.SIGNED_OUT)) {
            for (mode in SyncMode.entries) {
                for (readable in listOf(true, false)) {
                    graph.records = listOf(AccountRecordEntity("a1", NAME, state))
                    graph.settings["a1"] = SettingsEntity("a1", mode)
                    if (readable) graph.readable += "a1" else graph.readable -= "a1"
                    model.refresh()
                    assertTrue(ui().offersImport, "$state $mode $readable")
                }
            }
        }
    }

    @Test
    fun `Keep a copy is offered whenever there's an account, in every state and mode, and not without one`() {
        model.refresh()
        assertFalse(ui().offersCopy)

        for (state in listOf(LoginState.ACTIVE, LoginState.SIGNED_OUT)) {
            for (mode in SyncMode.entries) {
                for (readable in listOf(true, false)) {
                    graph.records = listOf(AccountRecordEntity("a1", NAME, state))
                    graph.settings["a1"] = SettingsEntity("a1", mode)
                    if (readable) graph.readable += "a1" else graph.readable -= "a1"
                    model.refresh()
                    assertTrue(ui().offersCopy, "$state $mode $readable")
                }
            }
        }
        // Signed in, signed out, and session expired all seen above.
        account(readable = false)
        assertEquals(AccountUi.SessionExpired(NAME), ui().account)
    }

    @Test
    fun `a tap counts the account's live rows and asks with the count`() {
        account()
        graph.rows = listOf(row(1), row(2, dirty = true), row(3, deleted = true), row(4))

        model.keepCopy()

        assertEquals(Question.KeepCopy(contacts = 3), ui().question)
        assertEquals(listOf("deviceContacts a1 $NAME"), graph.calls)
        assertEquals(emptyList<LogEntry>(), graph.logged)
    }

    @Test
    fun `none to count says there's nothing to copy, asks nothing, and records nothing`() {
        account()
        graph.rows = listOf(row(1, deleted = true))
        model.keepCopy()
        assertNull(ui().question)
        assertEquals(SettingsMessage.NOTHING_TO_COPY, ui().message)
        assertFalse(ui().working)
        model.confirm()
        assertEquals(listOf("deviceContacts a1 $NAME"), graph.calls)
        assertEquals(emptyList<LogEntry>(), graph.logged)
    }

    @Test
    fun `rows that can't be read say so, ask nothing, and record nothing`() {
        account()
        for (failure in listOf(SecurityException("made-up"), ProviderUnavailableException())) {
            graph.rowsFailure = failure
            model.keepCopy()
            assertNull(ui().question)
            assertEquals(SettingsMessage.COULDNT_READ, ui().message)
            assertFalse(ui().working)
        }
        assertTrue(graph.calls.none { it.startsWith("keepCopy") })
        assertEquals(emptyList<LogEntry>(), graph.logged)
    }

    @Test
    fun `Cancel copies nothing and records nothing`() {
        account()
        graph.rows = listOf(row(1))
        model.keepCopy()
        model.cancelQuestion()
        assertNull(ui().question)
        model.confirm()
        assertEquals(listOf("deviceContacts a1 $NAME"), graph.calls)
        assertEquals(emptyList<LogEntry>(), graph.logged)
        assertNull(ui().message)
    }

    @Test
    fun `Copy copies the account it was asked for and shows the copy's own total, all copied`() {
        account()
        graph.rows = listOf(row(1), row(2))
        graph.copyResult = CopyResult(copied = 3, notCopied = 0)
        model.keepCopy()
        model.confirm()

        assertEquals(listOf("deviceContacts a1 $NAME", "keepCopy a1 $NAME"), graph.calls)
        assertEquals(Copied(CopyResult(3, 0)), ui().message)
        assertNull(ui().question)
        assertFalse(ui().working)
        assertFalse(ui().copying)
        assertEquals(listOf(copyRecord(3, 0)), graph.logged)
    }

    @Test
    fun `some or none copied shows the counts`() {
        account()
        graph.rows = listOf(row(1), row(2))
        for (result in listOf(CopyResult(1, 1), CopyResult(0, 2))) {
            graph.copyResult = result
            model.keepCopy()
            model.confirm()
            assertEquals(Copied(result), ui().message)
        }
        assertEquals(listOf(copyRecord(1, 1), copyRecord(0, 2)), graph.logged)
    }

    @Test
    fun `none found by the copy after a count that wasn't 0 says there's nothing to copy, and is recorded`() {
        account()
        graph.rows = listOf(row(1))
        graph.copyResult = CopyResult(0, 0)
        model.keepCopy()
        model.confirm()
        assertEquals(SettingsMessage.NOTHING_TO_COPY, ui().message)
        assertEquals(listOf(copyRecord(0, 0)), graph.logged)
    }

    @Test
    fun `a copy that throws says it stopped and records failed`() {
        account()
        graph.rows = listOf(row(1))
        for (failure in listOf(ProviderUnavailableException(), SecurityException("made-up"), IllegalStateException("made-up"))) {
            graph.copyFailure = failure
            model.keepCopy()
            model.confirm()
            assertEquals(SettingsMessage.COPY_STOPPED, ui().message)
            assertFalse(ui().working)
            assertFalse(ui().copying)
        }
        assertEquals(List(3) { LogEntry(Operation.KEEP_COPY, AccountId("a1"), Outcome.FAILED) }, graph.logged)
    }

    @Test
    fun `a log that throws changes nothing about the copy`() {
        account()
        graph.rows = listOf(row(1))
        graph.copyResult = CopyResult(1, 0)
        graph.logFailure = IllegalStateException("made-up")
        model.keepCopy()
        model.confirm()
        assertEquals(Copied(CopyResult(1, 0)), ui().message)
        assertFalse(ui().working)
    }

    @Test
    fun `while it copies it's copying, and nothing else starts`() {
        account()
        graph.rows = listOf(row(1))
        val gate = CompletableDeferred<Unit>()
        graph.copyGate = { gate.await() }
        model.keepCopy()
        model.confirm()
        assertTrue(ui().working)
        assertTrue(ui().copying)

        model.keepCopy()
        model.logOut()
        model.remove()
        model.chooseFullSync(15)
        model.setManual(true)
        model.requestExport()
        model.cancelQuestion()
        assertNull(ui().question)
        assertFalse(ui().asksExportFile)
        assertEquals(listOf("deviceContacts a1 $NAME", "keepCopy a1 $NAME"), graph.calls)

        gate.complete(Unit)
        assertFalse(ui().working)
        assertFalse(ui().copying)
    }

    @Test
    fun `a read while it copies keeps it copying`() {
        account()
        graph.rows = listOf(row(1))
        val gate = CompletableDeferred<Unit>()
        graph.copyGate = { gate.await() }
        model.keepCopy()
        model.confirm()
        model.refresh()
        assertTrue(ui().copying)
        assertTrue(ui().working)
        gate.complete(Unit)
    }

    @Test
    fun `a new tap clears the last result while it counts`() {
        account()
        graph.rows = listOf(row(1))
        graph.copyResult = CopyResult(1, 0)
        model.keepCopy()
        model.confirm()
        assertEquals(Copied(CopyResult(1, 0)), ui().message)

        val gate = CompletableDeferred<Unit>()
        graph.rowsGate = { gate.await() }
        model.keepCopy()
        assertNull(ui().message)
        gate.complete(Unit)
    }

    @Test
    fun `while the rows are counted, or another question or action waits, a tap does nothing`() {
        account()
        val gate = CompletableDeferred<Unit>()
        graph.rowsGate = { gate.await() }
        model.keepCopy()
        assertTrue(ui().working)
        assertFalse(ui().copying, "counting isn't copying")
        model.keepCopy()
        model.remove()
        assertEquals(listOf("deviceContacts a1 $NAME"), graph.calls)
        gate.complete(Unit)
        graph.rowsGate = null

        model.cancelQuestion()
        model.logOut()
        model.keepCopy()
        assertEquals(Question.LogOut, ui().question)
        assertEquals(listOf("deviceContacts a1 $NAME"), graph.calls)
    }

    @Test
    fun `once started, the copy runs to its end when the screen goes away, then shows its result`() {
        account()
        graph.rows = listOf(row(1))
        graph.copyResult = CopyResult(1, 0)
        val gate = CompletableDeferred<Unit>()
        graph.copyGate = { gate.await() }
        model.keepCopy()
        model.confirm()

        scope.cancel()
        gate.complete(Unit)

        assertEquals(listOf(copyRecord(1, 0)), graph.logged)
        assertEquals(Copied(CopyResult(1, 0)), ui().message)
        assertFalse(ui().working)
    }

    @Test
    fun `a count that comes back after a read shows another account, or none, asks and says nothing`() {
        for (after in listOf(listOf(AccountRecordEntity("a2", "z@example.test", LoginState.SIGNED_OUT)), emptyList())) {
            for (rows in listOf(listOf(row(1)), emptyList(), null)) {
                account()
                // null: the rows can't be read.
                graph.rows = rows.orEmpty()
                graph.rowsFailure = if (rows == null) SecurityException("made-up") else null
                val gate = CompletableDeferred<Unit>()
                graph.rowsGate = { gate.await() }
                model.keepCopy()

                graph.records = after
                model.refresh()
                gate.complete(Unit)
                graph.rowsGate = null

                assertNull(ui().question, "$after $rows")
                assertNull(ui().message, "$after $rows")
                assertFalse(ui().working)
            }
        }
        assertEquals(emptyList<LogEntry>(), graph.logged)
    }

    @Test
    fun `the question goes when a read shows another account or none`() {
        for (after in listOf(listOf(AccountRecordEntity("a2", "z@example.test", LoginState.SIGNED_OUT)), emptyList())) {
            account()
            graph.rows = listOf(row(1))
            model.keepCopy()
            assertEquals(Question.KeepCopy(1), ui().question)
            graph.records = after
            model.refresh()
            assertNull(ui().question)
            model.confirm()
        }
        assertTrue(graph.calls.none { it.startsWith("keepCopy") })
    }

    @Test
    fun `the question stays when a read shows the same account in another state`() {
        account()
        graph.rows = listOf(row(1))
        model.keepCopy()
        account(LoginState.SIGNED_OUT)
        assertEquals(Question.KeepCopy(1), ui().question)
    }

    @Test
    fun `the result is gone when the screen opens again or another action starts`() {
        account()
        graph.rows = listOf(row(1))
        graph.copyResult = CopyResult(1, 0)
        model.keepCopy()
        model.confirm()
        model.refresh()
        assertEquals(Copied(CopyResult(1, 0)), ui().message, "a read keeps it")
        model.open()
        assertNull(ui().message)

        model.keepCopy()
        model.confirm()
        model.chooseFullSync(30)
        assertNull(ui().message)

        model.keepCopy()
        model.confirm()
        model.keepCopy()
        assertEquals(Question.KeepCopy(1), ui().question)
        assertNull(ui().message, "a new tap clears it too")
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
    }
}
