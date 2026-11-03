package dev.rboza.android.opensyncforproton.settings

import dev.rboza.android.opensyncforproton.AppGraph
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.main.AccountUi
import dev.rboza.android.opensyncforproton.diagnostics.CopyCount
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import dev.rboza.android.opensyncforproton.diagnostics.Operation
import dev.rboza.android.opensyncforproton.diagnostics.Outcome
import dev.rboza.android.opensyncforproton.main.accountUi
import dev.rboza.android.opensyncforproton.diagnostics.record
import dev.rboza.android.opensyncforproton.main.shownRecord
import dev.rboza.android.opensyncforproton.sync.provider.CopyResult
import dev.rboza.android.opensyncforproton.sync.schedule.SyncScheduler
import java.io.OutputStream
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The check's choice (docs/sync.md § Scheduling): every so many minutes, or Same as full sync. */
sealed interface Check {
    data class Every(val minutes: Int) : Check
    data object SameAsFullSync : Check

    companion object {
        /** A stored check: null is Same as full sync. */
        fun of(minutes: Int?): Check = if (minutes == null) SameAsFullSync else Every(minutes)
    }
}

/** The sync timing: the full sync's interval, the check's, and Manual. */
data class Timing(val fullSyncMinutes: Int = 60, val check: Check = Check.Every(15), val manual: Boolean = false) {
    /** A listed check no longer than the full sync, or Same as full sync. */
    fun allows(check: Check): Boolean = check is Check.SameAsFullSync || check is Check.Every && check.minutes <= fullSyncMinutes

    companion object {
        /** SyncScheduler.INTERVALS in order, for the full sync and the check alike. */
        val intervals: List<Int> = SyncScheduler.INTERVALS.sorted()

        /** The check's: the intervals, then Same as full sync. */
        val checkChoices: List<Check> = intervals.map(Check::Every) + Check.SameAsFullSync
    }
}

/** A timing submenu of the settings screen. */
enum class Menu { FULL_SYNC, CHECK }

/** What Remove from this phone deletes: the account's contacts, and how many of them hold phone edits not uploaded. */
data class RemovalCount(val contacts: Int, val withPhoneEdits: Int)

/** A question the settings screen asks before acting. [Remove.count] is null when the rows couldn't be read. */
sealed interface Question {
    data object LogOut : Question
    data class Remove(val count: RemovalCount?) : Question

    /** Keep a copy on this phone, with the account's live rows counted at the tap (never 0). */
    data class KeepCopy(val contacts: Int) : Question
}

/** What the last action said, until the screen opens again or another action starts. */
sealed interface Message

enum class SettingsMessage : Message {
    INTERVAL_FAILED, LOGOUT_FAILED, REMOVAL_FAILED, LOGS_SAVED, LOGS_FAILED,

    /** Keep a copy: no live rows to copy, rows that couldn't be read, the copy stopped. */
    NOTHING_TO_COPY, COULDNT_READ, COPY_STOPPED,
}

/** Keep a copy's result, with the copy's own total (copied and not copied, never both 0). */
data class Copied(val result: CopyResult) : Message

/** Kept until dismissed, on the settings and main screens (in memory only). */
enum class Note { SESSION_NOT_ENDED, ACCOUNT_KEPT }

data class SettingsUi(
    val account: AccountUi = AccountUi.NotSignedIn,
    val timing: Timing = Timing(),
    /** The submenu open over the settings, in memory only; never under Manual. */
    val menu: Menu? = null,
    val working: Boolean = false,
    /** Keep a copy is copying (working, too). */
    val copying: Boolean = false,
    val question: Question? = null,
    val message: Message? = null,
    val notes: Set<Note> = emptySet(),
    val removed: Boolean = false,
    /** Export logs was tapped: the screen opens the system save dialog once. */
    val asksExportFile: Boolean = false,
    /** The third-party licences are shown over the settings, whatever runs or waits; Back closes them first. */
    val showsNotices: Boolean = false,
) {
    /** Log out: signed in, or with an expired session. */
    val offersLogOut: Boolean get() = account is AccountUi.SignedIn || account is AccountUi.SessionExpired
    val offersRemoval: Boolean get() = account != AccountUi.NotSignedIn

    /** Keep a copy: whenever there's an account, in every state and mode (docs/sync.md § Keep a copy). */
    val offersCopy: Boolean get() = account != AccountUi.NotSignedIn

    /** Import from this phone: whenever there's an account, in every state and mode. */
    val offersImport: Boolean get() = account != AccountUi.NotSignedIn

    /** An action runs or a question waits for its answer: nothing else starts. */
    internal val busy: Boolean get() = working || question != null
}

/**
 * The settings screen's state: the account the main screen shows, its timing, Import from this phone, Keep a copy on
 * this phone, Log out, and Remove from this phone, the last three asked first. Read when the screen opens and after
 * each action, never kept in saved state; [Note]s stay until dismissed. Runs on the main thread.
 */
class SettingsModel(private val graph: AppGraph, private val scope: CoroutineScope) {
    private val ui = MutableStateFlow<SettingsUi?>(null)

    /** Null until the first read. */
    val state: StateFlow<SettingsUi?> = ui

    /** The account shown, and its Android name: set with the state that shows it. */
    private var shown: AccountId? = null
    private var shownName: String? = null

    /** The account the question on screen was asked for. */
    private var askedFor: AccountId? = null

    /** As MainModel's: a read that ends after a later-started one has shown its state is dropped. */
    private var readsStarted = 0
    private var latestShown = 0

    /** The screen opens: no question, message, removal, submenu, or licences from last time; then a read. */
    fun open() {
        ui.value = ui.value?.copy(question = null, message = null, removed = false, showsNotices = false, menu = null)
        refresh()
    }

    fun refresh() {
        scope.launch { read() }
    }

    /** Opens a timing submenu, unless Manual is on or something runs or waits. */
    fun openMenu(menu: Menu) {
        val now = ui.value ?: return
        if (shown == null || now.busy || now.timing.manual) return
        ui.value = now.copy(menu = menu, message = null)
    }

    /** Back, Close, or choosing what's already set: the submenu closes without a change. */
    fun closeMenu() {
        ui.value = ui.value?.copy(menu = null)
    }

    fun setManual(manual: Boolean) {
        val now = ui.value ?: return
        if (manual == now.timing.manual) return
        chooseTiming(now) { graph.setManual(it, manual) }
    }

    /** The full sync's interval: a listed one; a longer check drops to it (shown by the read after). */
    fun chooseFullSync(minutes: Int) {
        val now = ui.value ?: return
        if (minutes !in Timing.intervals) return
        if (minutes == now.timing.fullSyncMinutes) return closeMenu()
        chooseTiming(now) { graph.setInterval(it, minutes) }
    }

    fun chooseCheck(check: Check) {
        val now = ui.value ?: return
        if (check !in Timing.checkChoices || !now.timing.allows(check)) return
        if (check == now.timing.check) return closeMenu()
        chooseTiming(now) { graph.setCheckInterval(it, (check as? Check.Every)?.minutes) }
    }

    /** A timing choice closes the submenu; one failure message and log record for all three (INTERVAL). */
    private fun chooseTiming(now: SettingsUi, change: suspend (AccountId) -> Unit) {
        val account = shown ?: return
        if (now.busy) return
        act(
            now.copy(menu = null),
            onFailure = {
                graph.record(LogEntry(Operation.INTERVAL, account, Outcome.FAILED))
                copy(message = SettingsMessage.INTERVAL_FAILED)
            },
        ) {
            change(account)
            graph.record(LogEntry(Operation.INTERVAL, account, Outcome.DONE))
            ;{ this }
        }
    }

    fun logOut() {
        val now = ui.value ?: return
        if (!now.offersLogOut || now.busy) return
        askedFor = shown
        ui.value = now.copy(question = Question.LogOut, message = null)
    }

    /** Reads the account's rows for the count, then asks; a count that can't be read is asked without one. */
    fun remove() {
        val now = ui.value ?: return
        val account = shown ?: return
        val name = shownName ?: return
        // Without an account nothing is shown, so the offer needs no check here.
        if (now.busy) return
        askedFor = account
        ui.value = now.copy(working = true, message = null)
        scope.launch {
            val count = try {
                val live = graph.deviceContacts(account, name).filterNot { it.deleted }
                RemovalCount(contacts = live.size, withPhoneEdits = live.count { it.dirty })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // The count is asked about only if its account is still the one shown (a read meanwhile may show another).
            ui.value = ui.value?.copy(working = false, question = Question.Remove(count).takeIf { shown == account })
        }
    }

    /**
     * Keep a copy on this phone: counts the account's live rows, then asks with the count; none says there's nothing to
     * copy, and rows that can't be read say so, neither recorded.
     */
    fun keepCopy() {
        val now = ui.value ?: return
        val account = shown ?: return
        val name = shownName ?: return
        // Without an account nothing is shown, so the offer needs no check here.
        if (now.busy) return
        askedFor = account
        ui.value = now.copy(working = true, message = null)
        scope.launch {
            val count = try {
                graph.deviceContacts(account, name).count { !it.deleted }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Only for the account still shown (a read meanwhile may show another): else nothing is asked or said.
            val still = shown == account
            ui.value = ui.value?.copy(
                working = false,
                question = Question.KeepCopy(count ?: 0).takeIf { still && count != null && count > 0 },
                message = when {
                    !still -> null
                    count == null -> SettingsMessage.COULDNT_READ
                    count == 0 -> SettingsMessage.NOTHING_TO_COPY
                    else -> null
                },
            )
        }
    }

    /** The question's answer: yes. */
    fun confirm() {
        val now = ui.value ?: return
        val account = shown ?: return
        // While working there's no question, so nothing to confirm. A session to end only if the account was signed in,
        // its session expired or not.
        val hadSession = now.offersLogOut
        when (now.question) {
            null -> Unit
            is Question.KeepCopy -> {
                val name = shownName ?: return
                // Once started it runs to its end, its record and result included, even if the screen goes away.
                act(
                    now.copy(copying = true),
                    NonCancellable,
                    onFailure = {
                        graph.record(LogEntry(Operation.KEEP_COPY, account, Outcome.FAILED))
                        copy(message = SettingsMessage.COPY_STOPPED)
                    },
                ) {
                    val result = graph.keepCopy(account, name)
                    graph.record(LogEntry(Operation.KEEP_COPY, account, Outcome.DONE, mapOf(CopyCount.COPIED to result.copied, CopyCount.NOT_COPIED to result.notCopied)))
                    // None found: a sync removed them after the count.
                    ;{ copy(message = if (result == CopyResult(0, 0)) SettingsMessage.NOTHING_TO_COPY else Copied(result)) }
                }
            }
            Question.LogOut -> act(
                now,
                // Signed out already (a credential delete failed after it, say): the logout is done, but whether the
                // session ended isn't known.
                onFailure = {
                    if (shown == account && this.account is AccountUi.SignedOut) {
                        graph.record(LogEntry(Operation.LOGOUT, account, Outcome.DONE))
                        graph.record(LogEntry(Operation.SESSION_END, account, Outcome.NOT_ENDED))
                        copy(notes = notes + Note.SESSION_NOT_ENDED)
                    } else {
                        graph.record(LogEntry(Operation.LOGOUT, account, Outcome.FAILED))
                        copy(message = SettingsMessage.LOGOUT_FAILED)
                    }
                },
            ) {
                val result = graph.logout(account)
                graph.record(LogEntry(Operation.LOGOUT, account, Outcome.DONE))
                if (!result.sessionEnded) graph.record(LogEntry(Operation.SESSION_END, account, Outcome.NOT_ENDED))
                ;{ if (result.sessionEnded) this else copy(notes = notes + Note.SESSION_NOT_ENDED) }
            }
            is Question.Remove -> act(
                now,
                // Signed out by then (AccountManager or Room failed afterwards, say): a retry no longer has a session
                // to note, so the note comes now; whether the session ended isn't known.
                onFailure = {
                    val signedOut = hadSession && shown == account && this.account is AccountUi.SignedOut
                    graph.record(LogEntry(Operation.REMOVE, account, Outcome.FAILED))
                    if (signedOut) graph.record(LogEntry(Operation.SESSION_END, account, Outcome.NOT_ENDED))
                    copy(message = SettingsMessage.REMOVAL_FAILED, notes = if (signedOut) notes + Note.SESSION_NOT_ENDED else notes)
                },
            ) {
                val result = graph.removeFromPhone(account)
                graph.record(LogEntry(Operation.REMOVE, account, if (result.accountRemoved) Outcome.REMOVED else Outcome.KEPT))
                if (hadSession && !result.sessionEnded) graph.record(LogEntry(Operation.SESSION_END, account, Outcome.NOT_ENDED))
                ;{
                    var notes = notes
                    if (!result.accountRemoved) notes = notes + Note.ACCOUNT_KEPT
                    if (hadSession && !result.sessionEnded) notes = notes + Note.SESSION_NOT_ENDED
                    copy(notes = notes, removed = result.accountRemoved)
                }
            }
        }
    }

    /** Export logs: the screen asks the user for a file (once), then calls [exportLogs]. */
    fun requestExport() {
        val now = ui.value ?: return
        if (now.busy) return
        ui.value = now.copy(asksExportFile = true, message = null)
    }

    /** The screen opened the save dialog [SettingsUi.asksExportFile] asked for. */
    fun exportFileAsked() {
        val now = ui.value ?: return
        ui.value = now.copy(asksExportFile = false)
    }

    /**
     * Export logs: writes the log to the stream [open] gives (the file the user chose), then says whether it worked.
     */
    fun exportLogs(open: suspend () -> OutputStream) {
        val now = ui.value ?: return
        if (now.busy) return
        ui.value = now.copy(working = true, message = null)
        scope.launch {
            val saved = try {
                open().use { graph.exportLogs(it) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            ui.value = ui.value?.copy(working = false, message = if (saved) SettingsMessage.LOGS_SAVED else SettingsMessage.LOGS_FAILED)
        }
    }

    fun openNotices() {
        ui.value = ui.value?.copy(showsNotices = true)
    }

    fun closeNotices() {
        ui.value = ui.value?.copy(showsNotices = false)
    }

    fun cancelQuestion() {
        val now = ui.value ?: return
        if (!now.working) ui.value = now.copy(question = null)
    }

    fun dismiss(note: Note) {
        val now = ui.value ?: return
        ui.value = now.copy(notes = now.notes - note)
    }

    /**
     * Runs [action] in [context]; its result changes the state read afterwards, and a failure applies [onFailure] to it
     * instead (nothing of the exception is kept).
     */
    private fun act(
        now: SettingsUi,
        context: CoroutineContext = EmptyCoroutineContext,
        onFailure: SettingsUi.() -> SettingsUi,
        action: suspend () -> SettingsUi.() -> SettingsUi,
    ) {
        ui.value = now.copy(working = true, question = null, message = null)
        scope.launch {
            withContext(context) {
                val after: SettingsUi.() -> SettingsUi = try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    onFailure
                }
                read()
                ui.value = ui.value?.copy(working = false, copying = false)?.after()
            }
        }
    }

    private suspend fun read() {
        val number = ++readsStarted
        val next = try {
            readState()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (number < latestShown) return
        latestShown = number
        val last = ui.value ?: SettingsUi()
        if (next == null) {
            ui.value = last
            return
        }
        val before = shown
        shown = next.account
        shownName = next.name
        val question = last.question?.takeIf {
            next.account == askedFor && when (it) {
                Question.LogOut -> next.ui.offersLogOut
                is Question.Remove -> next.ui.offersRemoval
                is Question.KeepCopy -> next.ui.offersCopy
            }
        }
        ui.value = next.ui.copy(
            // A submenu is for the account it opened on, and never under Manual.
            menu = last.menu.takeUnless { next.ui.timing.manual || next.account != before },
            working = last.working,
            copying = last.copying,
            question = question,
            message = last.message,
            notes = last.notes,
            removed = last.removed,
            asksExportFile = last.asksExportFile,
            showsNotices = last.showsNotices,
        )
    }

    private class Read(val account: AccountId?, val name: String?, val ui: SettingsUi)

    private suspend fun readState(): Read {
        val record = shownRecord(graph.accounts()) ?: return Read(null, null, SettingsUi())
        val id = AccountId(record.accountId)
        val settings = graph.settings(id)
        // Missing settings are the defaults.
        val timing = if (settings == null) Timing() else Timing(settings.intervalMinutes, Check.of(settings.checkMinutes), settings.manual)
        return Read(id, record.androidAccountName, SettingsUi(account = graph.accountUi(record), timing = timing))
    }
}

/** The name the save dialog suggests for an export. */
fun exportFileName(day: LocalDate): String = "osp-logs-$day.jsonl"
