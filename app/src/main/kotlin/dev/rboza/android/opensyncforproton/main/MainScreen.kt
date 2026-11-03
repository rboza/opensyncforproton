package dev.rboza.android.opensyncforproton.main

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.settings.Check
import dev.rboza.android.opensyncforproton.settings.Timing
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.ui.Avatar
import dev.rboza.android.opensyncforproton.ui.Banner
import dev.rboza.android.opensyncforproton.ui.BrandMark
import dev.rboza.android.opensyncforproton.ui.SectionCard
import dev.rboza.android.opensyncforproton.ui.SectionTitle
import dev.rboza.android.opensyncforproton.ui.Tone
import java.text.DateFormat
import java.util.Date

/**
 * The account's state, the upgrade notice until it's dismissed, **Log in** when it isn't signed in (or its keys don't
 * unlock), the account card with its chip and, with sync on, the timing; the mode switch (with the contacts permission
 * request, the fresh dry run, and "Start syncing?" in a dialog), a revoked permission, **Sync now**, the last report
 * grouped into cards, and the held list. The state is read again each time the screen starts, and while it's started,
 * each time a stored report changes. A line's names, when the report has them, open with a tap; which lines are open is
 * kept only while the screen is (never in saved state).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    model: MainModel,
    onLogIn: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    notes: @Composable () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(model) {
        // Following first, so a report saved while the start's read runs is nearly always read too (Room installs its
        // watch on its own threads); one missed shows at the next change or start.
        val following = model.follow()
        model.refresh()
        onStopOrDispose { following.cancel() }
    }
    val activity = LocalActivity.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val answer = permissionAnswer(result) { activity?.shouldShowRequestPermissionRationale(it) == true }
        model.permissionResult(answer.granted, answer.canAskAgain)
    }
    // Its answer changes nothing here: problems go to notifications if allowed, and always to this screen.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val asksNotifications = state?.asksNotifications == true
    LaunchedEffect(asksNotifications) {
        if (asksNotifications) {
            model.notificationsAsked()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val change = state?.modeChange
    // Sent once: the model moves on to AwaitingPermission, so a recreated screen doesn't ask again.
    LaunchedEffect(change) {
        if (change is ModeChange.NeedsPermission) {
            model.permissionRequested()
            permissions.launch(CONTACTS_PERMISSIONS)
        }
    }
    BackHandler(enabled = change == ModeChange.Confirm) { model.cancelModeChange() }
    BackHandler(enabled = state?.confirmingDeletions != null) { model.cancelPhoneDeletions() }
    BackHandler(enabled = state?.confirmingRemoteDeletions != null) { model.cancelRemoteDeletions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandMark()
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    if (state?.account != null && state?.account != AccountUi.NotSignedIn) {
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.main_settings)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val ui = state ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.readFailed) Banner(stringResource(R.string.main_read_failed), Tone.ERROR)
            notes()
            if (ui.upgradeNotice) {
                Banner(stringResource(R.string.main_upgrade_notice), Tone.INFO) {
                    TextButton(onClick = model::dismissUpgradeNotice) { Text(stringResource(R.string.main_dismiss)) }
                }
            }
            if (ui.offersLogIn) LogIn(ui, onLogIn)
            if (ui.account != AccountUi.NotSignedIn) AccountCard(ui, model, onOpenAppSettings)
            ui.report?.let { Report(it, ui, model) }
            ui.held?.let { Held(it, ui.choiceOutcome, model) }
        }
        if (ui.modeChange == ModeChange.Confirm) StartSyncing(ui.report, model)
    }
}

/** **Log in**, with why: no account, signed out, the session expired, or keys that don't unlock. */
@Composable
private fun LogIn(ui: MainUi, onLogIn: () -> Unit) {
    val text = when (val account = ui.account) {
        AccountUi.NotSignedIn -> stringResource(R.string.main_not_signed_in)
        is AccountUi.SignedOut -> stringResource(R.string.main_signed_out, account.name)
        is AccountUi.SessionExpired -> stringResource(R.string.main_session_expired, account.name)
        is AccountUi.SignedIn -> stringResource(R.string.main_keys_log_in)
    }
    Banner(text, if (ui.account == AccountUi.NotSignedIn) Tone.INFO else Tone.ERROR) {
        Button(onClick = onLogIn) { Text(stringResource(R.string.main_log_in)) }
    }
}

/**
 * The account's name, state, and chip; the mode switch and what it's doing; the timing; **Sync now** and its outcome.
 */
@Composable
private fun AccountCard(ui: MainUi, model: MainModel, onOpenAppSettings: () -> Unit) {
    val (name, state) = when (val account = ui.account) {
        is AccountUi.SignedIn -> account.name to R.string.main_state_signed_in
        is AccountUi.SignedOut -> account.name to R.string.main_state_signed_out
        is AccountUi.SessionExpired -> account.name to R.string.main_state_session_expired
        AccountUi.NotSignedIn -> return
    }
    SectionCard(corner = 24.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(name)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ModeChip(ui.chip)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.main_switch), Modifier.weight(1f))
                Switch(
                    checked = ui.syncOn,
                    onCheckedChange = { on -> if (on) model.turnOn() else model.turnOff() },
                    enabled = !ui.busy && (ui.offersTurnOn || ui.offersTurnOff),
                )
            }
            if (!ui.syncOn) Text(stringResource(R.string.main_mode_dry_run), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ui.timing?.let { TimingLine(it) }
            if (ui.permissionRevoked) {
                Problem(stringResource(R.string.main_permission_revoked))
                Button(onClick = model::grantPermission, enabled = !ui.busy) { Text(stringResource(R.string.main_grant)) }
            }
            ModeChangeText(ui.modeChange, model, onOpenAppSettings)
            if (ui.androidSyncOff) Problem(stringResource(R.string.main_android_sync_off))
            if (ui.androidSyncOnUnderManual) Text(stringResource(R.string.main_android_sync_on_manual), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = model::syncNow, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.main_sync_now), Modifier.padding(start = 8.dp))
            }
            // Text, not an endless progress animation (it would keep the main looper busy in tests).
            if (ui.running) Text(stringResource(R.string.main_running))
            when (ui.outcome) {
                SyncOutcome.DIDNT_RUN -> Text(stringResource(R.string.main_didnt_run))
                SyncOutcome.FAILED -> Problem(stringResource(R.string.main_failed))
                null -> Unit
            }
        }
    }
}

@Composable
private fun ModeChip(chip: Chip) {
    val colors = MaterialTheme.colorScheme
    val (text, container, content) = when (chip) {
        Chip.ON -> Triple(R.string.main_chip_on, colors.primaryContainer, colors.onPrimaryContainer)
        Chip.DRY_RUN -> Triple(R.string.main_chip_dry_run, colors.secondaryContainer, colors.onSecondaryContainer)
        Chip.PAUSED -> Triple(R.string.main_chip_paused, colors.tertiaryContainer, colors.onTertiaryContainer)
    }
    Surface(shape = RoundedCornerShape(14.dp), color = container) {
        Text(stringResource(text), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** With sync on, the timing in one line; a check as long as the full sync says it once. */
@Composable
private fun TimingLine(timing: Timing) {
    val text = when {
        timing.manual -> stringResource(R.string.main_timing_manual)
        timing.check is Check.Every && timing.check.minutes != timing.fullSyncMinutes ->
            stringResource(R.string.main_timing, every(timing.fullSyncMinutes), every(timing.check.minutes))
        else -> stringResource(R.string.main_timing_same, every(timing.fullSyncMinutes))
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun every(minutes: Int): String = if (minutes < 60) {
    pluralStringResource(R.plurals.main_every_minutes, minutes, minutes)
} else {
    pluralStringResource(R.plurals.main_every_hours, minutes / 60, minutes / 60)
}

/** "Start syncing?": what the first sync will do, with the fresh check's counts that aren't zero. */
@Composable
private fun StartSyncing(report: ReportUi?, model: MainModel) {
    val lines = report?.let { r -> ReportLine.entries.filter { r.level(it) != LineLevel.NOT_COUNTED && r.counts.getValue(it) > 0 } }.orEmpty()
    AlertDialog(
        onDismissRequest = model::cancelModeChange,
        title = { Text(stringResource(R.string.main_start_syncing)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.main_start_syncing_detail))
                if (lines.isEmpty()) {
                    Text(stringResource(R.string.main_nothing_to_change), fontWeight = FontWeight.Bold)
                } else {
                    val held = stringResource(R.string.main_held)
                    lines.forEach { line ->
                        val text = stringResource(R.string.main_line_count, stringResource(lineText(line)), report!!.counts.getValue(line))
                        Text(if (line.held) "$text ($held)" else text, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = model::confirm) { Text(stringResource(R.string.main_start)) } },
        dismissButton = { TextButton(onClick = model::cancelModeChange) { Text(stringResource(R.string.login_cancel)) } },
    )
}

@Composable
private fun ModeChangeText(change: ModeChange, model: MainModel, onOpenAppSettings: () -> Unit) {
    when (change) {
        ModeChange.Idle, ModeChange.Confirm -> Unit
        is ModeChange.NeedsPermission, is ModeChange.AwaitingPermission -> Text(stringResource(R.string.main_asking_permission))
        is ModeChange.PermissionDeclined -> {
            Problem(stringResource(R.string.main_permission_needed))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (change.openSettings) Button(onClick = onOpenAppSettings) { Text(stringResource(R.string.main_open_app_settings)) }
                TextButton(onClick = model::cancelModeChange) { Text(stringResource(R.string.login_ok)) }
            }
        }
        ModeChange.Checking -> Text(stringResource(R.string.main_checking))
        ModeChange.NotReady -> {
            Problem(stringResource(R.string.main_not_ready_to_start))
            TextButton(onClick = model::cancelModeChange) { Text(stringResource(R.string.login_ok)) }
        }
        ModeChange.Starting -> Text(stringResource(R.string.main_starting))
        ModeChange.Stopping -> Text(stringResource(R.string.main_stopping))
        ModeChange.Failed -> {
            Problem(stringResource(R.string.main_mode_failed))
            TextButton(onClick = model::cancelModeChange) { Text(stringResource(R.string.login_ok)) }
        }
    }
}

/**
 * The last report: a sync's is the latest known state, so its time line says what started it, a side the run didn't
 * count says when it was, and each line reads at its [LineLevel]. Its lines come in three cards: from Proton, from this
 * phone, and what needs a look.
 */
@Composable
private fun Report(report: ReportUi, ui: MainUi, model: MainModel) {
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val time = { at: Long -> format.format(Date(at)) }
    val finished = time(report.finishedAt)
    val trigger = report.trigger
    val title = when {
        report.kind == ReportKind.DRY_RUN -> stringResource(R.string.main_report_dry_run, finished)
        trigger == null -> stringResource(R.string.main_report_sync, finished)
        else -> stringResource(R.string.main_report_sync_started, finished, stringResource(triggerText(trigger, report.blocker)))
    }
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val blocker = report.blocker
        when {
            blocker == null -> Marked(stringResource(R.string.main_ready), Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary)
            report.kind == ReportKind.DRY_RUN -> Marked(stringResource(R.string.main_not_ready, stringResource(blockerText(blocker))), Icons.Outlined.Info, MaterialTheme.colorScheme.error)
            else -> Marked(stringResource(R.string.main_sync_stopped, stringResource(blockerText(blocker))), Icons.Outlined.Info, MaterialTheme.colorScheme.error)
        }
    }
    // Each direction of the bulk guard apart, each asked about with its own count.
    if (report.bulkGuardRemote) {
        Guard(
            report.kind, report.remoteDeletionsWaiting, ui.confirmingRemoteDeletions, ui.offersRemoteDeletions, !ui.busy, RemoteGuardText,
            report.remoteGuardLevel, report.protonCountedAt?.let(time),
            ask = model::askDeleteRemoteDeletions, delete = model::deleteRemoteDeletions, cancel = model::cancelRemoteDeletions,
        )
    }
    if (report.bulkGuardPhone) {
        Guard(
            report.kind, report.phoneDeletionsWaiting, ui.confirmingDeletions, ui.offersPhoneDeletions, !ui.busy, PhoneGuardText,
            report.phoneGuardLevel, report.phoneCountedAt?.let(time),
            ask = model::askDeletePhoneDeletions, delete = model::deletePhoneDeletions, cancel = model::cancelPhoneDeletions,
        )
    }
    val line = @Composable { it: ReportLine -> Line(it, report.counts.getValue(it), report.names?.getValue(it), report.level(it), report.countedAt(it)?.let(time)) }
    Group(stringResource(R.string.main_from_proton), Icons.Outlined.Lock, MaterialTheme.colorScheme.onSurfaceVariant, side = {
        Side(report.protonCountedAt, report.finishedAt, R.string.main_report_proton_side_from, R.string.main_report_proton_side_not_counted, time)
    }) { PROTON_LINES.forEach { line(it) } }
    Group(stringResource(R.string.main_from_phone), Icons.Outlined.Phone, MaterialTheme.colorScheme.onSurfaceVariant, side = {
        Side(report.phoneCountedAt, report.finishedAt, R.string.main_report_phone_side_from, R.string.main_report_phone_side_not_counted, time)
    }) { PHONE_LINES.forEach { line(it) } }
    val looks = LOOK_LINES.any { report.level(it) != LineLevel.NOT_COUNTED && report.counts.getValue(it) > 0 }
    val lookColor = if (looks) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Group(stringResource(R.string.main_needs_look), Icons.Outlined.Warning, lookColor, side = {}) { LOOK_LINES.forEach { line(it) } }
    val hint = when (report.namesHint) {
        NamesHint.SYNC_NOW -> R.string.main_names_hint
        NamesHint.NOT_KEPT -> R.string.main_names_not_kept
        null -> null
    }
    if (hint != null) Text(stringResource(hint), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall)
}

private val PROTON_LINES = listOf(ReportLine.NEW_FROM_PROTON, ReportLine.CHANGED_ON_PROTON, ReportLine.DELETED_ON_PROTON)
private val PHONE_LINES = listOf(ReportLine.NEW_ON_PHONE, ReportLine.CHANGED_ON_PHONE, ReportLine.DELETED_ON_PHONE)
private val LOOK_LINES = listOf(ReportLine.CONFLICTS, ReportLine.CANT_BE_SYNCED, ReportLine.DUPLICATE_ROWS, ReportLine.MISSING_ROWS)

/** A text after its icon, both in [color]. */
@Composable
private fun Marked(text: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** A card of report lines under its heading, with [side]: when its side was counted, if not by the latest run. */
@Composable
private fun Group(title: String, icon: ImageVector, color: Color, side: @Composable () -> Unit, lines: @Composable () -> Unit) {
    SectionCard {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = color)
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = color)
            }
            side()
        }
        lines()
    }
}

/** One side's time, when the latest run didn't count it ([finishedAt]): greyed, as its lines are. */
@Composable
private fun Side(countedAt: Long?, finishedAt: Long, from: Int, notCounted: Int, time: (Long) -> String) {
    if (countedAt == finishedAt) return
    val text = if (countedAt == null) stringResource(notCounted) else stringResource(from, time(countedAt))
    Text(text, Modifier.padding(start = 28.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What started a sync; a check that failed has its blocker on the next line. */
private fun triggerText(trigger: ReportTrigger, blocker: Blocker?) = when (trigger) {
    ReportTrigger.SYNC_NOW -> R.string.main_trigger_sync_now
    ReportTrigger.START -> R.string.main_trigger_start
    ReportTrigger.ANDROID_SETTINGS -> R.string.main_trigger_android_settings
    ReportTrigger.IMPORT -> R.string.main_trigger_import
    ReportTrigger.PROTON_CHANGES -> R.string.main_trigger_proton_changes
    ReportTrigger.SCHEDULED -> R.string.main_trigger_scheduled
    ReportTrigger.PHONE_CHANGES -> R.string.main_trigger_phone_changes
    ReportTrigger.CHECK -> if (blocker == null) R.string.main_trigger_check_quiet else R.string.main_trigger_check
}

/**
 * What TalkBack reads for a line: when an earlier run counted it, [from], and whether it still needs the user; null for
 * one the latest run counted, which reads as shown.
 */
@Composable
private fun levelDescription(text: String, level: LineLevel, from: String?): String? = when {
    from == null -> null
    level == LineLevel.EARLIER -> stringResource(R.string.main_line_from, text, from)
    level == LineLevel.WAITING -> stringResource(R.string.main_line_waiting, text, from)
    else -> null
}

/**
 * Greyed for what an earlier run counted; [bold] (a line's count, a guard's sentence) for what the latest run counted
 * and what still needs the user.
 */
@Composable
private fun Leveled(text: String, level: LineLevel, modifier: Modifier = Modifier, color: Color = Color.Unspecified, bold: Boolean = true) {
    Text(
        text,
        modifier,
        color = if (level == LineLevel.LATEST) color else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (bold && (level == LineLevel.LATEST || level == LineLevel.WAITING)) FontWeight.Bold else FontWeight.Normal,
    )
}

/** [Leveled], with its TalkBack description. */
@Composable
private fun LeveledLine(text: String, level: LineLevel, from: String?, color: Color = Color.Unspecified) {
    val description = levelDescription(text, level, from)
    Leveled(text, level, if (description == null) Modifier else Modifier.semantics { contentDescription = description }, color)
}

/** One direction's guard texts: its line (stopped, or a dry run's), without the count, the button, and the question. */
private class GuardText(
    val stopped: Int, val wouldStop: Int, val stoppedUnknown: Int, val wouldStopUnknown: Int, val delete: Int, val question: Int, val detail: Int,
)

private val PhoneGuardText = GuardText(
    R.plurals.main_phone_guard_stopped, R.plurals.main_phone_guard_would_stop, R.string.main_phone_guard_stopped_unknown,
    R.string.main_phone_guard_would_stop_unknown, R.plurals.main_phone_guard_delete, R.plurals.main_phone_guard_question,
    R.string.main_phone_guard_question_detail,
)

private val RemoteGuardText = GuardText(
    R.plurals.main_remote_guard_stopped, R.plurals.main_remote_guard_would_stop, R.string.main_remote_guard_stopped_unknown,
    R.string.main_remote_guard_would_stop_unknown, R.plurals.main_remote_guard_delete, R.plurals.main_remote_guard_question,
    R.string.main_remote_guard_question_detail,
)

/**
 * One direction of the bulk guard, in a card: its [count] when known (a report this screen's Sync now got), and
 * **Delete N on Proton** or **Delete N on this phone**, offered while [offered] (and [enabled] while nothing runs),
 * which asks first in a dialog; [asked] is the count asked with.
 */
@Composable
private fun Guard(
    kind: ReportKind, count: Int?, asked: Int?, offered: Boolean, enabled: Boolean, text: GuardText, level: LineLevel, from: String?,
    ask: () -> Unit, delete: () -> Unit, cancel: () -> Unit,
) {
    val stopped = kind == ReportKind.SYNC
    SectionCard(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LeveledLine(
                if (count == null) {
                    stringResource(if (stopped) text.stoppedUnknown else text.wouldStopUnknown)
                } else {
                    pluralStringResource(if (stopped) text.stopped else text.wouldStop, count, count)
                },
                level, from, color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (asked == null && offered && count != null) {
                OutlinedButton(onClick = ask, enabled = enabled) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, Modifier.size(18.dp))
                    Text(pluralStringResource(text.delete, count, count), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    if (asked != null) {
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(pluralStringResource(text.question, asked, asked)) },
            text = { Text(stringResource(text.detail)) },
            confirmButton = {
                Button(
                    onClick = delete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.main_delete)) }
            },
            dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.login_cancel)) } },
        )
    }
}

/**
 * The held list: each contact named as its row shows, labelled by the phone side alone, with its choices, which work
 * whether or not notifications are allowed; a missing row's are **Restore here** and **Skip**. The count alone when the
 * rows can't be read.
 */
@Composable
private fun Held(held: HeldUi, outcome: ChoiceOutcome?, model: MainModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.main_held_title, held.count))
        if (held.contacts != null) {
            Text(stringResource(R.string.main_held_intro), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val contacts = held.contacts ?: return
    when (outcome) {
        ChoiceOutcome.CHANGED -> Problem(stringResource(R.string.main_held_choice_changed))
        ChoiceOutcome.FAILED -> Problem(stringResource(R.string.main_held_choice_failed))
        null -> Unit
    }
    contacts.forEach { HeldItem(it, model) }
}

@Composable
private fun HeldItem(contact: HeldContact, model: MainModel) {
    val choices = if (contact.restoreOnly) {
        listOf(HoldChoice.USE_PROTON to R.string.main_restore_here)
    } else {
        listOf(HoldChoice.USE_PHONE to R.string.main_use_phone, HoldChoice.USE_PROTON to R.string.main_use_proton)
    }
    val name = contact.name ?: stringResource(R.string.main_no_name)
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(contact.name.orEmpty(), size = 40.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(heldText(contact.label)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    choices.firstOrNull { it.first == contact.hold.choice }?.let { (_, label) ->
                        Text(stringResource(R.string.main_held_chosen, stringResource(label)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (choice, label) ->
                    FilledTonalButton(onClick = { model.choose(contact, choice) }, Modifier.weight(1f)) { Text(stringResource(label)) }
                }
                OutlinedButton(onClick = { model.choose(contact, null) }) { Text(stringResource(R.string.main_skip)) }
            }
        }
    }
}

private fun heldText(label: HeldLabel) = when (label) {
    HeldLabel.CHANGED_HERE -> R.string.main_held_changed_here
    HeldLabel.NOT_CHANGED_HERE -> R.string.main_held_not_changed_here
    HeldLabel.DELETED_HERE -> R.string.main_held_deleted_here
    HeldLabel.EDITED_BEFORE_KEYS -> R.string.main_held_edited_before_keys
    HeldLabel.GONE_HERE -> R.string.main_held_gone_here
}

/**
 * A report line: its name (with "held" when the app leaves it alone) and its count, read at its [level]; tapped, its
 * names when the report has them. TalkBack reads it as one item.
 */
@Composable
private fun Line(line: ReportLine, count: Int, names: List<ReportName>?, level: LineLevel, from: String?) {
    var open by remember(names) { mutableStateOf(false) }
    val held = if (line.held) " (${stringResource(R.string.main_held)})" else ""
    val notCounted = level == LineLevel.NOT_COUNTED
    val whole = if (notCounted) {
        stringResource(R.string.main_line_not_counted, stringResource(lineText(line)))
    } else {
        stringResource(R.string.main_line_count, stringResource(lineText(line)), count) + held
    }
    val description = levelDescription(whole, level, from)
    val canOpen = !names.isNullOrEmpty()
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val described = if (description == null) Modifier else Modifier.semantics { contentDescription = description }
    Column(Modifier.clickable(enabled = canOpen) { open = !open }.then(described)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Leveled(if (notCounted) whole else stringResource(lineText(line)) + held, level, Modifier.weight(1f), bold = false)
            if (!notCounted) Leveled(count.toString(), level)
            if (canOpen) {
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (open && names != null) {
            val noName = stringResource(R.string.main_no_name)
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                names.forEach { (name, reason) ->
                    val shown = name ?: noName
                    val text = if (reason == null) shown else stringResource(R.string.main_name_reason, shown, stringResource(reasonText(reason)))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Problem(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error)
}

private val CONTACTS_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

internal data class PermissionAnswer(val granted: Boolean, val canAskAgain: Boolean)

/**
 * Android's answer to the contacts permission request: granted only when both are; Android asks again while it gives a
 * rationale for either, and no longer shows the request once it gives none after a refusal.
 */
internal fun permissionAnswer(result: Map<String, Boolean>, showsRationale: (String) -> Boolean) = PermissionAnswer(
    granted = CONTACTS_PERMISSIONS.all { result[it] == true },
    canAskAgain = CONTACTS_PERMISSIONS.any(showsRationale),
)

private fun lineText(line: ReportLine) = when (line) {
    ReportLine.NEW_FROM_PROTON -> R.string.main_line_new_from_proton
    ReportLine.CHANGED_ON_PROTON -> R.string.main_line_changed_on_proton
    ReportLine.DELETED_ON_PROTON -> R.string.main_line_deleted_on_proton
    ReportLine.NEW_ON_PHONE -> R.string.main_line_new_on_phone
    ReportLine.CHANGED_ON_PHONE -> R.string.main_line_changed_on_phone
    ReportLine.DELETED_ON_PHONE -> R.string.main_line_deleted_on_phone
    ReportLine.CONFLICTS -> R.string.main_line_conflicts
    ReportLine.CANT_BE_SYNCED -> R.string.main_line_cant_be_synced
    ReportLine.DUPLICATE_ROWS -> R.string.main_line_duplicate_rows
    ReportLine.MISSING_ROWS -> R.string.main_line_missing_rows
}

private fun reasonText(reason: CantSyncReason) = when (reason) {
    CantSyncReason.DECRYPT -> R.string.main_reason_decrypt
    CantSyncReason.VERIFY -> R.string.main_reason_verify
    CantSyncReason.PARSE -> R.string.main_reason_parse
    CantSyncReason.UNMAPPED_ROWS -> R.string.main_reason_unmapped_rows
    CantSyncReason.WRITE_TOO_LARGE -> R.string.main_reason_write_too_large
    CantSyncReason.VALUE_REJECTED -> R.string.main_reason_value_rejected
    CantSyncReason.PHOTO_MALFORMED -> R.string.main_reason_photo_malformed
    CantSyncReason.PHOTO_OVERSIZED -> R.string.main_reason_photo_oversized
    CantSyncReason.PHOTO_UNREADABLE -> R.string.main_reason_photo_unreadable
    CantSyncReason.UNKEYED_PROPERTY -> R.string.main_reason_unkeyed_property
    CantSyncReason.UNWRITABLE_LABEL -> R.string.main_reason_unwritable_label
    CantSyncReason.NO_NAME_OR_EMAIL -> R.string.main_reason_no_name_or_email
    CantSyncReason.UNENCRYPTED_PROPERTY -> R.string.main_reason_unencrypted_property
    CantSyncReason.ENCRYPT_FAILED -> R.string.main_reason_encrypt_failed
    CantSyncReason.NO_PRIMARY_KEY -> R.string.main_reason_no_primary_key
    CantSyncReason.PROTON_REFUSED -> R.string.main_reason_proton_refused
    CantSyncReason.REMOTE_UNREADABLE -> R.string.main_reason_remote_unreadable
    CantSyncReason.UID_UNCONFIRMED -> R.string.main_reason_uid_unconfirmed
}

private fun blockerText(blocker: Blocker) = when (blocker) {
    Blocker.OFFLINE -> R.string.main_blocker_offline
    Blocker.SIGNED_OUT -> R.string.main_blocker_signed_out
    Blocker.SESSION_EXPIRED -> R.string.main_blocker_session_expired
    Blocker.PERMISSION_MISSING -> R.string.main_blocker_permission_missing
    Blocker.CONTACTS_HIDDEN -> R.string.main_blocker_contacts_hidden
    Blocker.KEYS_DONT_UNLOCK -> R.string.main_blocker_keys_dont_unlock
    Blocker.CAPTCHA -> R.string.main_blocker_captcha
    Blocker.PROTON_UNAVAILABLE -> R.string.main_blocker_proton_unavailable
    Blocker.INVENTORY_INCOMPLETE -> R.string.main_blocker_inventory_incomplete
    Blocker.APP_VERSION_REJECTED -> R.string.main_blocker_app_version_rejected
    Blocker.CONTACTS_UNAVAILABLE -> R.string.main_blocker_contacts_unavailable
}
