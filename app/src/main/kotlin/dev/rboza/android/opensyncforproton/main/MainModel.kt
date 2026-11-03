package dev.rboza.android.opensyncforproton.main

import dev.rboza.android.opensyncforproton.AppGraph
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import dev.rboza.android.opensyncforproton.diagnostics.Operation
import dev.rboza.android.opensyncforproton.diagnostics.Outcome
import dev.rboza.android.opensyncforproton.diagnostics.record
import dev.rboza.android.opensyncforproton.settings.Check
import dev.rboza.android.opensyncforproton.settings.Timing
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.NotSyncedReason
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.UnreadableReason
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The account the main screen shows (docs/sync.md § Account states), by its Android name, the Proton e-mail, which
 * `toString` leaves out.
 */
sealed interface AccountUi {
    data object NotSignedIn : AccountUi
    data class SignedOut(val name: String) : AccountUi {
        override fun toString() = "SignedOut"
    }
    data class SessionExpired(val name: String) : AccountUi {
        override fun toString() = "SessionExpired"
    }
    data class SignedIn(val name: String) : AccountUi {
        override fun toString() = "SignedIn"
    }
}

/**
 * The report's lines, in the order shown (docs/sync.md § Sync mode). [held] lines are contacts the app leaves alone
 * until the user deals with them or a later run clears them. [proton] and [phone] say which side of the report counts
 * them: conflicts and can't be synced keep a part of each.
 */
enum class ReportLine(val held: Boolean = false, val proton: Boolean = false, val phone: Boolean = false) {
    NEW_FROM_PROTON(proton = true),
    CHANGED_ON_PROTON(proton = true),
    DELETED_ON_PROTON(proton = true),
    NEW_ON_PHONE(phone = true),
    CHANGED_ON_PHONE(phone = true),
    DELETED_ON_PHONE(phone = true),
    CONFLICTS(held = true, proton = true, phone = true),
    CANT_BE_SYNCED(held = true, proton = true, phone = true),
    DUPLICATE_ROWS(held = true, phone = true),
    MISSING_ROWS(held = true, proton = true),
}

/**
 * The hint under a report whose counts lack names: Sync now would find those contacts again, or their names are gone.
 */
enum class NamesHint { SYNC_NOW, NOT_KEPT }

/** Why a contact can't be synced, as the report's list shows it beside the name. */
enum class CantSyncReason {
    DECRYPT, VERIFY, PARSE, UNMAPPED_ROWS, WRITE_TOO_LARGE, VALUE_REJECTED, PHOTO_MALFORMED, PHOTO_OVERSIZED, PHOTO_UNREADABLE,
    UNKEYED_PROPERTY, UNWRITABLE_LABEL, NO_NAME_OR_EMAIL, UNENCRYPTED_PROPERTY, ENCRYPT_FAILED, NO_PRIMARY_KEY, PROTON_REFUSED,
    REMOTE_UNREADABLE, UID_UNCONFIRMED,
}

/**
 * A name in a report line (null: the contact has none), with its reason under "can't be synced"; `toString` hides the
 * name.
 */
data class ReportName(val name: String?, val reason: CantSyncReason? = null) {
    override fun toString() = "ReportName(${if (name == null) "none" else "named"}, $reason)"
}

/**
 * The last report: counts per line, and the contact names in each only while it's the report this screen's Sync now
 * got, as are [phoneDeletionsWaiting] and [remoteDeletionsWaiting], each direction's bulk guard count. A sync's report
 * is the latest known state: [trigger] is what started it, and [protonCountedAt] and [phoneCountedAt] when each side
 * was counted ([finishedAt] by this run; null: by no sync yet), which sets each line's [level]. `toString` leaves the
 * names out.
 */
data class ReportUi(
    val kind: ReportKind,
    val finishedAt: Long,
    val blocker: Blocker?,
    val bulkGuardRemote: Boolean,
    val bulkGuardPhone: Boolean,
    val phoneDeletionsWaiting: Int? = null,
    val remoteDeletionsWaiting: Int? = null,
    val counts: Map<ReportLine, Int>,
    val names: Map<ReportLine, List<ReportName>>?,
    val trigger: ReportTrigger? = null,
    val protonCountedAt: Long? = finishedAt,
    val phoneCountedAt: Long? = finishedAt,
) {
    /** When each side of [line] was counted. */
    private fun sides(line: ReportLine) = listOfNotNull(protonCountedAt.takeIf { line.proton }, phoneCountedAt.takeIf { line.phone })

    /**
     * Counted by this run when either side was (a line of both shows the phone's part counted now); otherwise from an
     * earlier run, waiting while it holds contacts for the user; or counted by no sync yet.
     */
    fun level(line: ReportLine): LineLevel {
        val sides = sides(line)
        return when {
            finishedAt in sides -> LineLevel.LATEST
            sides.isEmpty() -> LineLevel.NOT_COUNTED
            line.held && counts.getValue(line) > 0 -> LineLevel.WAITING
            else -> LineLevel.EARLIER
        }
    }

    /** When [line] was counted: its older side's time, or null when no sync has counted it. */
    fun countedAt(line: ReportLine): Long? = sides(line).minOrNull()

    /** Each guard is shown only while it stopped, which needs the user until a run of its side counts again. */
    val remoteGuardLevel: LineLevel get() = if (protonCountedAt == finishedAt) LineLevel.LATEST else LineLevel.WAITING

    val phoneGuardLevel: LineLevel get() = if (phoneCountedAt == finishedAt) LineLevel.LATEST else LineLevel.WAITING

    /**
     * The hint for lines with a count but no names: Sync now shows them only when it would find those contacts again (a
     * line that needs the user, a line of a stopped guard's side, or, in a report it didn't get, a dry run or a run a
     * blocker stopped); a change already synced keeps no names. Null when every counted line has its names.
     */
    val namesHint: NamesHint?
        get() {
            val unnamed = ReportLine.entries.filter { counts.getValue(it) > 0 && names?.get(it).isNullOrEmpty() }
            return when {
                unnamed.isEmpty() -> null
                unnamed.any { it.held } -> NamesHint.SYNC_NOW
                bulkGuardRemote && unnamed.any { it.proton } || bulkGuardPhone && unnamed.any { it.phone } -> NamesHint.SYNC_NOW
                names == null && (kind == ReportKind.DRY_RUN || blocker != null) -> NamesHint.SYNC_NOW
                else -> NamesHint.NOT_KEPT
            }
        }

    override fun toString() = "ReportUi($kind, blocker=$blocker, counts=$counts, names=${if (names == null) "none" else "shown"})"
}

/**
 * How a report line reads: counted by the latest run (bold); from an earlier run and still needing the user (bold,
 * greyed); from an earlier run (greyed); or counted by no sync yet.
 */
enum class LineLevel { LATEST, WAITING, EARLIER, NOT_COUNTED }

/**
 * Where a change of mode stands (docs/sync.md § Sync mode). [NeedsPermission] asks the screen to request the contacts
 * permission, and [AwaitingPermission] is that request on its way; [thenCheck] says whether a fresh dry run follows
 * (turning on) or not (granting a revoked permission).
 */
sealed interface ModeChange {
    data object Idle : ModeChange
    data class NeedsPermission(val thenCheck: Boolean) : ModeChange
    data class AwaitingPermission(val thenCheck: Boolean) : ModeChange

    /** Declined; [openSettings] once Android no longer asks, so only the app's settings page can grant it. */
    data class PermissionDeclined(val openSettings: Boolean) : ModeChange

    /** The fresh dry run is running. */
    data object Checking : ModeChange

    /** The fresh dry run is ready: "Start syncing?". */
    data object Confirm : ModeChange

    /** The fresh dry run isn't ready (the report says why); the mode stays dry run. */
    data object NotReady : ModeChange
    data object Starting : ModeChange
    data object Stopping : ModeChange

    /** Changing the mode failed. */
    data object Failed : ModeChange
}

/**
 * How a held contact is labelled: by what's known of its phone row alone, since a hold's fingerprint doesn't say
 * whether Proton's copy is gone or wasn't seen.
 */
enum class HeldLabel { CHANGED_HERE, NOT_CHANGED_HERE, DELETED_HERE, EDITED_BEFORE_KEYS, GONE_HERE }

/**
 * One held contact: its [hold] as read, the [name] shown (null: none), and its label; `toString` shows neither name nor
 * ID.
 */
data class HeldContact(val hold: ConflictHoldEntity, val name: String?, val label: HeldLabel) {
    /** A missing row offers only **Restore here** (stored as `USE_PROTON`) and **Skip**. */
    val restoreOnly: Boolean get() = hold.kind == HoldKind.MISSING_ROW

    override fun toString() = "HeldContact(${if (name == null) "none" else "named"}, $label, ${hold.choice})"
}

/** The held list: [count] held, and each one, or null when the rows couldn't be read, so the count alone shows. */
data class HeldUi(val count: Int, val contacts: List<HeldContact>?)

/** Why the last choice wasn't saved: the hold changed since it was shown, or saving failed. */
enum class ChoiceOutcome { CHANGED, FAILED }

/** How the last Sync now ended when it brought no report. */
enum class SyncOutcome { DIDNT_RUN, FAILED }

/** What the main screen shows. */
data class MainUi(
    val account: AccountUi = AccountUi.NotSignedIn,
    val syncOn: Boolean = false,
    /** Signed in, syncing on a schedule, and Android's automatic sync is off, so the schedule doesn't run. */
    val androidSyncOff: Boolean = false,
    /**
     * Signed in with sync on and the interval Manual, and Android's automatic sync is on: Android may sync on its own.
     */
    val androidSyncOnUnderManual: Boolean = false,
    val report: ReportUi? = null,
    val running: Boolean = false,
    val outcome: SyncOutcome? = null,
    /** The last read of the app's state failed; what's shown is from before it. */
    val readFailed: Boolean = false,
    val modeChange: ModeChange = ModeChange.Idle,
    /** Sync is on but the contacts permission isn't granted, so sync is paused (docs/sync.md § Sync mode). */
    val permissionRevoked: Boolean = false,
    /** Sync was just turned on without POST_NOTIFICATIONS: the screen asks for it once. */
    val asksNotifications: Boolean = false,
    /**
     * The upgrade put the account back in dry run and the user hasn't dismissed the notice yet (Room's
     * `upgrade_notice`).
     */
    val upgradeNotice: Boolean = false,
    /** "Delete N contacts on Proton?" is asked, with N (docs/sync.md § Deletion). */
    val confirmingDeletions: Int? = null,
    /** "Delete N contacts on this phone?" is asked, with N. */
    val confirmingRemoteDeletions: Int? = null,
    /** The held list (docs/sync.md § Conflicts); null while nothing is held. */
    val held: HeldUi? = null,
    val choiceOutcome: ChoiceOutcome? = null,
    /** The timing, shown in one line with sync on; null in dry run. */
    val timing: Timing? = null,
) {
    /**
     * **Delete N on Proton**: with sync on and signed in, while the last report says the phone guard stopped and its
     * count is known.
     */
    val offersPhoneDeletions: Boolean
        get() = syncOn && account is AccountUi.SignedIn && report?.bulkGuardPhone == true && report.phoneDeletionsWaiting != null

    /** **Delete N on this phone**: the same, for the other direction's guard. */
    val offersRemoteDeletions: Boolean
        get() = syncOn && account is AccountUi.SignedIn && report?.bulkGuardRemote == true && report.remoteDeletionsWaiting != null

    /** Turning sync on is offered only while signed in, since another state's dry run is never ready. */
    val offersTurnOn: Boolean get() = account is AccountUi.SignedIn && !syncOn
    val offersTurnOff: Boolean get() = syncOn

    /** Something runs, or waits for the user's answer: Sync now and the mode switch wait. */
    internal val busy: Boolean
        get() = running || modeChange == ModeChange.Confirm || modeChange is ModeChange.NeedsPermission ||
            modeChange is ModeChange.AwaitingPermission || confirmingDeletions != null || confirmingRemoteDeletions != null

    /**
     * **Log in**: in every state but signed in, and signed in while the last report says the keys don't unlock (the
     * password changed elsewhere, say), since logging in again unlocks them.
     */
    val offersLogIn: Boolean get() = account !is AccountUi.SignedIn || report?.blocker == Blocker.KEYS_DONT_UNLOCK

    /** The account card's chip: sync on is paused while a login or the contacts permission is needed. */
    val chip: Chip
        get() = when {
            !syncOn -> Chip.DRY_RUN
            offersLogIn || permissionRevoked -> Chip.PAUSED
            else -> Chip.ON
        }
}

enum class Chip { ON, DRY_RUN, PAUSED }

/**
 * The main screen's state: read from the app's stores when the screen starts, after each action, and while it follows,
 * when a stored report changes; never kept in saved state. Runs on the main thread.
 */
class MainModel(private val graph: AppGraph, private val scope: CoroutineScope) {
    private val ui = MutableStateFlow<MainUi?>(null)

    /** Null until the first read. */
    val state: StateFlow<MainUi?> = ui

    /** The account shown: set with the state that shows it, so Sync now asks for what the screen shows. */
    private var shown: AccountId? = null

    /**
     * The report this screen's last Sync now got, and for whom: its names, and the phone guard's count, are shown while
     * Room's report is still it.
     */
    private var requested: Pair<AccountId, RunReport>? = null

    /** The account the last fresh dry run checked: "Start syncing?" answers only for it. */
    private var checkedFor: AccountId? = null

    /** Reads are numbered as they start; one that ends after a later-started one has shown its state is dropped. */
    private var readsStarted = 0
    private var latestShown = 0

    /** A report changed while one of this screen's own runs went: read once it has ended. */
    private var savedWhileRunning = false

    /**
     * Reads the state again each time a stored report changes, whatever run saved it, until the job is cancelled: the
     * screen follows while it's started. A change while one of this screen's own runs goes is read once that run has
     * ended ([ended]). A watch that fails stops following, and the screen still reads as it starts.
     */
    fun follow(): Job = scope.launch {
        try {
            graph.reportChanges().collect { if (ui.value?.running == true) savedWhileRunning = true else read() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing of the exception is shown or kept (D23).
        }
    }

    /** Reads the state again. */
    fun refresh() {
        scope.launch { read() }
    }

    /**
     * The switch, turned on: with the contacts permission, a fresh dry run (else the screen asks for the permission
     * first), then "Start syncing?" if it's ready (docs/sync.md § Sync mode).
     */
    fun turnOn() {
        val now = ui.value ?: return
        if (!now.offersTurnOn || now.busy) return
        if (graph.hasContactsPermission()) check(now) else ui.value = now.copy(modeChange = ModeChange.NeedsPermission(thenCheck = true), outcome = null)
    }

    /** The screen sent the permission request [ModeChange.NeedsPermission] asked for; it sends it once. */
    fun permissionRequested() {
        val now = ui.value ?: return
        val change = now.modeChange as? ModeChange.NeedsPermission ?: return
        ui.value = now.copy(modeChange = ModeChange.AwaitingPermission(change.thenCheck))
    }

    /** Android's answer; [canAskAgain] false once Android no longer shows the request. */
    fun permissionResult(granted: Boolean, canAskAgain: Boolean) {
        val now = ui.value ?: return
        val change = now.modeChange as? ModeChange.AwaitingPermission ?: return
        when {
            !granted -> ui.value = now.copy(modeChange = ModeChange.PermissionDeclined(openSettings = !canAskAgain))
            change.thenCheck -> check(now)
            else -> {
                ui.value = now.copy(modeChange = ModeChange.Idle)
                refresh()
            }
        }
    }

    /** The screen sent the notification permission request [MainUi.asksNotifications] asked for. */
    fun notificationsAsked() {
        val now = ui.value ?: return
        ui.value = now.copy(asksNotifications = false)
    }

    /** Start: sync on, then the first real sync, as Sync now runs one. */
    fun confirm() {
        val now = ui.value ?: return
        val account = shown ?: return
        // A read drops the question once it no longer applies (see `still`), so it is always the shown account's.
        if (now.modeChange != ModeChange.Confirm) return
        ui.value = now.copy(modeChange = ModeChange.Starting, running = true, outcome = null)
        scope.launch {
            val changed = setMode(account, SyncMode.ON)
            graph.record(LogEntry(Operation.SYNC_ON, account, if (changed) Outcome.DONE else Outcome.FAILED))
            if (!changed) {
                read()
                ended { it.copy(running = false, modeChange = ModeChange.Failed) }
                return@launch
            }
            // Problems are notified only with sync on, so this is the moment to ask.
            ui.value = ui.value?.copy(asksNotifications = !graph.hasNotificationPermission())
            val (_, outcome) = run(account, SyncRequest.Full(trigger = ReportTrigger.START))
            read()
            ended { it.copy(running = false, outcome = outcome, modeChange = ModeChange.Idle) }
        }
    }

    /** Cancel (or Back) on "Start syncing?", or dismissing what the last change said: the mode stays as it is. */
    fun cancelModeChange() {
        val now = ui.value ?: return
        val change = now.modeChange
        if (change == ModeChange.Confirm || change == ModeChange.NotReady || change == ModeChange.Failed || change is ModeChange.PermissionDeclined) {
            ui.value = now.copy(modeChange = ModeChange.Idle)
        }
    }

    /** The switch, turned off: at once, no confirmation. */
    fun turnOff() {
        val now = ui.value ?: return
        val account = shown ?: return
        if (!now.offersTurnOff || now.busy) return
        ui.value = now.copy(modeChange = ModeChange.Stopping, running = true, outcome = null)
        scope.launch {
            val changed = setMode(account, SyncMode.DRY_RUN)
            graph.record(LogEntry(Operation.SYNC_OFF, account, if (changed) Outcome.DONE else Outcome.FAILED))
            read()
            ended { it.copy(running = false, modeChange = if (changed) ModeChange.Idle else ModeChange.Failed) }
        }
    }

    /** Dismisses the upgrade notice; one that fails stays shown (nothing of the exception is kept). */
    fun dismissUpgradeNotice() {
        val now = ui.value ?: return
        val account = shown ?: return
        if (!now.upgradeNotice) return
        scope.launch {
            try {
                graph.dismissUpgradeNotice(account)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next read shows it still set.
            }
            read()
        }
    }

    /** Grant, for a permission revoked with sync on: the request alone, no dry run. */
    fun grantPermission() {
        val now = ui.value ?: return
        if (!now.permissionRevoked || now.busy) return
        ui.value = now.copy(modeChange = ModeChange.NeedsPermission(thenCheck = false))
    }

    /**
     * Reconciles, then asks for a dry run, or a real sync with sync on; does nothing while busy or without an account.
     */
    fun syncNow() {
        val now = ui.value ?: return
        val account = shown ?: return
        if (now.busy) return
        // Signed out, a real sync wouldn't run; a dry run says why.
        sync(now, account, if (now.syncOn && now.account !is AccountUi.SignedOut) SyncRequest.Full() else SyncRequest.DryRun)
    }

    /** Sync now's run of [request], recorded as Sync now, then the state read again. */
    private fun sync(now: MainUi, account: AccountId, request: SyncRequest) {
        ui.value = now.copy(running = true, outcome = null, modeChange = ModeChange.Idle)
        scope.launch {
            val (_, outcome) = run(account, request)
            val recorded = when (outcome) {
                null -> Outcome.RAN
                SyncOutcome.DIDNT_RUN -> Outcome.DIDNT_RUN
                SyncOutcome.FAILED -> Outcome.FAILED
            }
            graph.record(LogEntry(Operation.SYNC_NOW, account, recorded))
            read()
            ended { it.copy(running = false, outcome = outcome) }
        }
    }

    /** **Delete N on Proton**: asks first, with the count the report gave. */
    fun askDeletePhoneDeletions() {
        val now = ui.value ?: return
        if (!now.offersPhoneDeletions || now.busy) return
        ui.value = now.copy(confirmingDeletions = now.report?.phoneDeletionsWaiting, outcome = null)
    }

    fun cancelPhoneDeletions() {
        val now = ui.value ?: return
        ui.value = now.copy(confirmingDeletions = null)
    }

    /** **Delete**: Sync now carrying the count asked with, as the guard's confirmation (docs/sync.md § Deletion). */
    fun deletePhoneDeletions() {
        val now = ui.value ?: return
        val count = now.confirmingDeletions ?: return
        val account = shown ?: return
        sync(now.copy(confirmingDeletions = null), account, SyncRequest.Full(confirmedPhoneDeletions = count))
    }

    /** **Delete N on this phone**: asks first, with the count the report gave. */
    fun askDeleteRemoteDeletions() {
        val now = ui.value ?: return
        if (!now.offersRemoteDeletions || now.busy) return
        ui.value = now.copy(confirmingRemoteDeletions = now.report?.remoteDeletionsWaiting, outcome = null)
    }

    fun cancelRemoteDeletions() {
        val now = ui.value ?: return
        ui.value = now.copy(confirmingRemoteDeletions = null)
    }

    /** **Delete**: Sync now carrying the count asked with, as the other direction's confirmation. */
    fun deleteRemoteDeletions() {
        val now = ui.value ?: return
        val count = now.confirmingRemoteDeletions ?: return
        val account = shown ?: return
        sync(now.copy(confirmingRemoteDeletions = null), account, SyncRequest.Full(confirmedRemoteDeletions = count))
    }

    /**
     * A held contact's answer ([choice] null: Skip), saved only while its hold records the state the list showed
     * (docs/sync.md § Conflicts); a missing row never takes Use phone. One the hold no longer takes, since a sync found
     * the contact changed, says so; either way the list is read again. A run going meanwhile reads the choice again
     * before carrying it out.
     */
    fun choose(contact: HeldContact, choice: HoldChoice?) {
        if (contact.restoreOnly && choice == HoldChoice.USE_PHONE) return
        scope.launch {
            val outcome = try {
                if (graph.choose(contact.hold, choice)) null else ChoiceOutcome.CHANGED
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing of the exception is shown or kept (D23).
                ChoiceOutcome.FAILED
            }
            ui.value = ui.value?.copy(choiceOutcome = outcome)
            read()
        }
    }

    /** The fresh dry run before turning sync on. */
    private fun check(now: MainUi) {
        val account = shown
        if (account == null) {
            ui.value = now.copy(modeChange = ModeChange.Idle)
            return
        }
        checkedFor = account
        ui.value = now.copy(modeChange = ModeChange.Checking, running = true, outcome = null)
        scope.launch {
            val (report, outcome) = run(account, SyncRequest.DryRun)
            read()
            val change = when {
                report == null -> ModeChange.Idle
                report.ready -> ModeChange.Confirm
                else -> ModeChange.NotReady
            }
            ended { it.copy(running = false, outcome = outcome, modeChange = change) }
        }
    }

    /**
     * The end of one of this screen's own runs, after its closing read: [end] shown, then the state read again if a
     * report changed while it went, since that read may have missed it. Room's notice of the run's own report
     * usually comes while it goes too, so a run mostly reads twice: harmless, and simpler than telling saves apart.
     */
    private suspend fun ended(end: (MainUi) -> MainUi) {
        ui.value = ui.value?.let(end)
        if (!savedWhileRunning) return
        savedWhileRunning = false
        read()
    }

    /** Reconciles, then asks for [request]: its report, or how it ended without one. */
    private suspend fun run(account: AccountId, request: SyncRequest): Pair<RunReport?, SyncOutcome?> = try {
        graph.reconcile()
        val report = graph.requestSync(account, request)
        if (report == null) {
            null to SyncOutcome.DIDNT_RUN
        } else {
            requested = account to report
            report to null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Nothing of the exception is shown or kept (D23).
        null to SyncOutcome.FAILED
    }

    /** AccountLifecycle.setSyncMode; false if it failed (nothing of the exception is kept). */
    private suspend fun setMode(account: AccountId, mode: SyncMode): Boolean = try {
        graph.setSyncMode(account, mode)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
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
        val last = ui.value ?: MainUi()
        if (next == null) {
            ui.value = last.copy(readFailed = true)
            return
        }
        shown = next.account
        ui.value = next.ui.copy(
            running = last.running,
            outcome = last.outcome,
            modeChange = still(last.modeChange, next),
            asksNotifications = last.asksNotifications,
            choiceOutcome = last.choiceOutcome,
            // A question the report no longer offers goes, as when another run's report replaces Sync now's; its count
            // changes only with this screen's own run, which closes it first.
            confirmingDeletions = last.confirmingDeletions?.takeIf { next.ui.offersPhoneDeletions },
            confirmingRemoteDeletions = last.confirmingRemoteDeletions?.takeIf { next.ui.offersRemoteDeletions },
        )
    }

    /**
     * What's left of a change of mode once [next] is shown: a question or message that no longer applies (another
     * account, one that no longer offers turning on, a permission granted meanwhile) goes; a running step ends itself.
     */
    private fun still(change: ModeChange, next: Read): ModeChange {
        val applies = when (change) {
            ModeChange.Confirm, ModeChange.NotReady -> next.account == checkedFor && next.ui.offersTurnOn
            is ModeChange.NeedsPermission -> if (change.thenCheck) next.ui.offersTurnOn else next.ui.permissionRevoked
            is ModeChange.AwaitingPermission -> if (change.thenCheck) next.ui.offersTurnOn else next.ui.permissionRevoked
            is ModeChange.PermissionDeclined -> !next.permission
            else -> true
        }
        return if (applies) change else ModeChange.Idle
    }

    /** [permission]: both contacts permissions are granted. */
    private class Read(val account: AccountId?, val ui: MainUi, val permission: Boolean)

    private suspend fun readState(): Read {
        val record = shownRecord(graph.accounts()) ?: return Read(null, MainUi(), graph.hasContactsPermission())
        val id = AccountId(record.accountId)
        val name = record.androidAccountName
        val settings = graph.settings(id)
        val permission = graph.hasContactsPermission()
        val syncOn = settings?.syncMode == SyncMode.ON
        val account = graph.accountUi(record)
        val syncing = account is AccountUi.SignedIn && syncOn
        val manual = settings?.manual == true
        val stored = graph.lastReport(id)
        val run = stored?.let { requestedRun(id, it) }
        val ui = MainUi(
            account = account,
            syncOn = syncOn,
            androidSyncOff = syncing && !manual && !graph.androidAutoSync(name),
            androidSyncOnUnderManual = syncing && manual && graph.androidAutoSync(name),
            permissionRevoked = syncOn && !permission,
            report = stored?.toUi(run?.let(::names), run?.plan?.phoneDeletionsWaiting, run?.plan?.remoteDeletionsWaiting),
            upgradeNotice = settings?.upgradeNotice == true,
            held = held(id, name, run),
            timing = settings?.takeIf { syncOn }?.let { Timing(it.intervalMinutes, Check.of(it.checkMinutes), it.manual) },
        )
        return Read(id, ui, permission)
    }

    /**
     * The held list: each hold whose row still holds its Proton ID (one left by a crash while recreating doesn't),
     * named as the row shows, never stored. A missing row's ID may be another contact's by now, so it's never read: its
     * name comes only from [run], this screen's Sync now report. Null with nothing to show; the count alone when the
     * rows can't be read.
     */
    private suspend fun held(account: AccountId, androidName: String, run: RunReport?): HeldUi? {
        val holds = graph.holds(account).ifEmpty { return null }
        val rows = try {
            graph.deviceContacts(account, androidName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return HeldUi(holds.size, contacts = null)
        }.associateBy { it.rawContactId }
        val contacts = holds.mapNotNull { hold ->
            if (hold.kind == HoldKind.MISSING_ROW) {
                val name = run?.plan?.items?.firstOrNull { it.protonId?.value == hold.protonId }?.displayName
                return@mapNotNull HeldContact(hold, name, HeldLabel.GONE_HERE)
            }
            val row = rows[hold.rawContactId]?.takeIf { it.sourceId == hold.protonId } ?: return@mapNotNull null
            val label = when {
                // A row deleted since its hold was written is deleted, whatever its kind.
                row.deleted -> HeldLabel.DELETED_HERE
                hold.kind == HoldKind.EDITED_BEFORE_KEYS -> HeldLabel.EDITED_BEFORE_KEYS
                row.dirty -> HeldLabel.CHANGED_HERE
                else -> HeldLabel.NOT_CHANGED_HERE
            }
            HeldContact(hold, row.displayName, label)
        }.sortedWith(compareBy(nullsLast()) { it.name?.lowercase() })
        return if (contacts.isEmpty()) null else HeldUi(contacts.size, contacts)
    }

    /** The report Sync now got for [account], if [stored] is still it. */
    private fun requestedRun(account: AccountId, stored: LastReportEntity): RunReport? {
        val (forAccount, run) = requested ?: return null
        return run.takeIf { forAccount == account && it.finishedAt == stored.finishedAt && it.kind == stored.kind }
    }

    /**
     * The names in each line of [run]: a refused contact under can't be synced, an edit its upload found changed on
     * Proton in its new category's line.
     */
    private fun names(run: RunReport): Map<ReportLine, List<ReportName>> {
        val names = ReportLine.entries.associateWith { mutableListOf<ReportName>() }
        run.plan?.items.orEmpty().filter { item -> run.refused.none { it.item === item } }.forEach { item ->
            val category = run.reclassified.firstOrNull { it.item === item }?.category ?: item.category
            category.line()?.let { names.getValue(it) += ReportName(item.displayName, item.reason()) }
        }
        run.refused.forEach { names.getValue(ReportLine.CANT_BE_SYNCED) += ReportName(it.item.displayName, it.reason.shown()) }
        return names
    }
}

/** The account the screens show: the signed-in one, else the first signed-out one by name. */
internal fun shownRecord(records: List<AccountRecordEntity>): AccountRecordEntity? {
    val byName = records.sortedBy { it.androidAccountName }
    return byName.firstOrNull { it.loginState == LoginState.ACTIVE } ?: byName.firstOrNull()
}

/** Signed out, a session expired (credentials unreadable), or signed in (docs/sync.md § Account states). */
internal suspend fun AppGraph.accountUi(record: AccountRecordEntity): AccountUi = when {
    record.loginState == LoginState.SIGNED_OUT -> AccountUi.SignedOut(record.androidAccountName)
    !hasCredentials(AccountId(record.accountId)) -> AccountUi.SessionExpired(record.androidAccountName)
    else -> AccountUi.SignedIn(record.androidAccountName)
}

private fun LastReportEntity.toUi(names: Map<ReportLine, List<ReportName>>?, phoneDeletionsWaiting: Int?, remoteDeletionsWaiting: Int?) = ReportUi(
    kind = kind,
    finishedAt = finishedAt,
    blocker = blocker,
    bulkGuardRemote = bulkGuardRemote,
    bulkGuardPhone = bulkGuardPhone,
    phoneDeletionsWaiting = phoneDeletionsWaiting,
    remoteDeletionsWaiting = remoteDeletionsWaiting,
    counts = mapOf(
        ReportLine.NEW_FROM_PROTON to newFromProton,
        ReportLine.CHANGED_ON_PROTON to changedOnProton,
        ReportLine.DELETED_ON_PROTON to deletedOnProton,
        ReportLine.NEW_ON_PHONE to newOnPhone,
        ReportLine.CHANGED_ON_PHONE to changedOnPhone,
        ReportLine.DELETED_ON_PHONE to deletedOnPhone,
        ReportLine.CONFLICTS to conflicts,
        ReportLine.CANT_BE_SYNCED to unreadable,
        ReportLine.DUPLICATE_ROWS to duplicateRows,
        ReportLine.MISSING_ROWS to missingRows,
    ),
    names = names,
    trigger = trigger,
    protonCountedAt = protonCountedAt,
    phoneCountedAt = phoneCountedAt,
)

/**
 * An item's own reason, for one the plan finds can't be synced; none in another line, an unreadable contact's tombstone
 * deleted on the phone included.
 */
private fun PlanItem.reason(): CantSyncReason? = when (category) {
    PlanCategory.UNMAPPED_ROWS -> CantSyncReason.UNMAPPED_ROWS
    PlanCategory.UNREADABLE -> when (unreadableReason) {
        UnreadableReason.DECRYPT -> CantSyncReason.DECRYPT
        UnreadableReason.VERIFY -> CantSyncReason.VERIFY
        UnreadableReason.PARSE -> CantSyncReason.PARSE
        null -> null
    }
    else -> null
}

private fun NotSyncedReason.shown(): CantSyncReason = when (this) {
    NotSyncedReason.WRITE_TOO_LARGE -> CantSyncReason.WRITE_TOO_LARGE
    NotSyncedReason.VALUE_REJECTED -> CantSyncReason.VALUE_REJECTED
    NotSyncedReason.PHOTO_MALFORMED -> CantSyncReason.PHOTO_MALFORMED
    NotSyncedReason.PHOTO_OVERSIZED -> CantSyncReason.PHOTO_OVERSIZED
    NotSyncedReason.PHOTO_UNREADABLE -> CantSyncReason.PHOTO_UNREADABLE
    NotSyncedReason.UNKEYED_PROPERTY -> CantSyncReason.UNKEYED_PROPERTY
    NotSyncedReason.UNWRITABLE_LABEL -> CantSyncReason.UNWRITABLE_LABEL
    NotSyncedReason.NO_NAME_OR_EMAIL -> CantSyncReason.NO_NAME_OR_EMAIL
    NotSyncedReason.UNENCRYPTED_PROPERTY -> CantSyncReason.UNENCRYPTED_PROPERTY
    NotSyncedReason.ENCRYPT_FAILED -> CantSyncReason.ENCRYPT_FAILED
    NotSyncedReason.NO_PRIMARY_KEY -> CantSyncReason.NO_PRIMARY_KEY
    NotSyncedReason.PROTON_REFUSED -> CantSyncReason.PROTON_REFUSED
    NotSyncedReason.REMOTE_UNREADABLE -> CantSyncReason.REMOTE_UNREADABLE
    NotSyncedReason.UID_UNCONFIRMED -> CantSyncReason.UID_UNCONFIRMED
}

/**
 * The line an item is listed in; none for an item carried out but not counted: a row written before keys, a settled
 * edit, or a removal finished for a contact deleted on both sides.
 */
private fun PlanCategory.line(): ReportLine? = when (this) {
    PlanCategory.NEW_FROM_PROTON -> ReportLine.NEW_FROM_PROTON
    PlanCategory.CHANGED_ON_PROTON -> ReportLine.CHANGED_ON_PROTON
    PlanCategory.DELETED_ON_PROTON -> ReportLine.DELETED_ON_PROTON
    PlanCategory.NEW_ON_PHONE -> ReportLine.NEW_ON_PHONE
    PlanCategory.CHANGED_ON_PHONE -> ReportLine.CHANGED_ON_PHONE
    PlanCategory.DELETED_ON_PHONE -> ReportLine.DELETED_ON_PHONE
    PlanCategory.CONFLICT, PlanCategory.EDITED_BEFORE_KEYS -> ReportLine.CONFLICTS
    PlanCategory.UNREADABLE, PlanCategory.UNMAPPED_ROWS -> ReportLine.CANT_BE_SYNCED
    PlanCategory.DUPLICATE_ROWS -> ReportLine.DUPLICATE_ROWS
    PlanCategory.MISSING_ROW -> ReportLine.MISSING_ROWS
    PlanCategory.WRITTEN_BEFORE_KEYS, PlanCategory.SETTLED, PlanCategory.SETTLED_REMOTE_CHANGED, PlanCategory.DELETED_ON_BOTH -> null
}
