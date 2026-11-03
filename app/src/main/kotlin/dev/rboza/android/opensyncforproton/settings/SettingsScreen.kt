package dev.rboza.android.opensyncforproton.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.main.AccountUi
import dev.rboza.android.opensyncforproton.ui.Avatar
import dev.rboza.android.opensyncforproton.ui.Banner
import dev.rboza.android.opensyncforproton.ui.SectionCard
import dev.rboza.android.opensyncforproton.ui.SectionTitle
import dev.rboza.android.opensyncforproton.ui.Tone
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The account, the timing, **Import from this phone** (which opens its own screen: [onOpenImport]), **Keep a copy on
 * this phone**, **Log out**, and **Remove from this phone** under "Account" (the last three asked first, in a dialog),
 * **Export logs** under "Diagnostics", the notes kept until dismissed, and about. Back (or the top bar's arrow) does
 * nothing while an action runs, cancels a question, else returns to the main screen ([onClose]), as a finished removal
 * does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: SettingsModel, onClose: () -> Unit, onOpenImport: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(model) {
        model.refresh()
        onStopOrDispose {}
    }
    val ui = state
    LaunchedEffect(ui?.removed) { if (ui?.removed == true) onClose() }
    val context = LocalContext.current
    // The system save dialog: no permission, and the app reaches only the file the user chose.
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_TYPE)) { uri ->
        if (uri != null) {
            model.exportLogs {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) ?: throw IOException("no stream") }
            }
        }
    }
    val asksExportFile = ui?.asksExportFile == true
    LaunchedEffect(asksExportFile) {
        if (asksExportFile) {
            model.exportFileAsked()
            exportFile.launch(exportFileName(LocalDate.now()))
        }
    }
    // While an action runs, Back waits: the main screen reads its state only as it starts, so it would miss the result.
    // The licences close first, whatever runs.
    val back = {
        when {
            ui?.showsNotices == true -> model.closeNotices()
            ui?.menu != null -> model.closeMenu()
            ui?.working == true -> Unit
            ui?.question != null -> model.cancelQuestion()
            else -> onClose()
        }
    }
    BackHandler(onBack = back)

    if (ui?.showsNotices == true) {
        ThirdPartyNotices(onClose = model::closeNotices)
        return
    }
    ui?.menu?.let { menu ->
        TimingMenu(menu, ui, model)
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui != null) {
                SettingsNotes(ui.notes, model::dismiss)
                ui.message?.let { Banner(messageText(it), if (it == SettingsMessage.LOGS_SAVED || it is Copied && it.result.notCopied == 0) Tone.INFO else Tone.ERROR) }
                if (ui.working) {
                    Text(stringResource(if (ui.copying) R.string.settings_copying else R.string.settings_working), Modifier.padding(horizontal = 4.dp))
                }
                ui.question?.let { Ask(it, model) }
                if (ui.account != AccountUi.NotSignedIn) Account(ui, model, onOpenImport)
                SectionTitle(stringResource(R.string.settings_diagnostics))
                SectionCard {
                    ActionRow(
                        icon = Icons.Outlined.Share,
                        title = stringResource(R.string.settings_export_logs),
                        detail = stringResource(R.string.settings_export_logs_detail),
                        enabled = !ui.busy,
                        onClick = model::requestExport,
                    )
                }
            }
            About(onNotices = model::openNotices)
        }
    }
}

/** The notes kept until dismissed, shown on the settings and main screens. */
@Composable
fun SettingsNotes(notes: Set<Note>, onDismiss: (Note) -> Unit) {
    Note.entries.filter { it in notes }.forEach { note ->
        Banner(stringResource(if (note == Note.SESSION_NOT_ENDED) R.string.settings_note_session else R.string.settings_note_account_kept), Tone.ERROR) {
            TextButton(onClick = { onDismiss(note) }) { Text(stringResource(R.string.settings_dismiss)) }
        }
    }
}

@Composable
private fun Account(ui: SettingsUi, model: SettingsModel, onOpenImport: () -> Unit) {
    val (name, state) = when (val account = ui.account) {
        is AccountUi.SignedIn -> account.name to R.string.main_state_signed_in
        is AccountUi.SignedOut -> account.name to R.string.main_state_signed_out
        is AccountUi.SessionExpired -> account.name to R.string.main_state_session_expired
        AccountUi.NotSignedIn -> return
    }
    SectionCard(corner = 24.dp) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(name, size = 48.dp)
            Column {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    TimingSection(ui, model)
    if (ui.offersImport || ui.offersCopy || ui.offersLogOut || ui.offersRemoval) {
        SectionTitle(stringResource(R.string.settings_account))
        SectionCard {
            if (ui.offersImport) {
                ActionRow(
                    Icons.AutoMirrored.Outlined.Send, stringResource(R.string.settings_import), stringResource(R.string.settings_import_detail),
                    enabled = !ui.busy, onClick = onOpenImport,
                )
            }
            if (ui.offersImport && ui.offersCopy) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (ui.offersCopy) {
                ActionRow(
                    Icons.Outlined.AccountBox, stringResource(R.string.settings_keep_copy), stringResource(R.string.settings_keep_copy_detail),
                    enabled = !ui.busy, onClick = model::keepCopy,
                )
            }
            if (ui.offersCopy && ui.offersLogOut) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (ui.offersLogOut) {
                ActionRow(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.settings_log_out), enabled = !ui.busy, onClick = model::logOut)
            }
            if ((ui.offersCopy || ui.offersLogOut) && ui.offersRemoval) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (ui.offersRemoval) {
                ActionRow(
                    Icons.Outlined.Delete, stringResource(R.string.settings_remove), enabled = !ui.busy, onClick = model::remove,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * A settings row that does something: its [icon], [title], and [detail] (if any), announced as a button, greyed out
 * while not [enabled].
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    enabled: Boolean,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val shown = if (enabled) color else color.copy(alpha = DISABLED_ALPHA)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (color == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.onSurfaceVariant else shown)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = shown)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A question, in a dialog: Back or a tap outside it cancels, as Cancel does. */
@Composable
private fun Ask(question: Question, model: SettingsModel) {
    if (question is Question.KeepCopy) return AskKeepCopy(question.contacts, model)
    val (text, yes, destructive) = when (question) {
        Question.LogOut -> Triple(stringResource(R.string.settings_log_out_question), stringResource(R.string.settings_log_out), false)
        is Question.KeepCopy -> error("asked above")
        is Question.Remove -> {
            val count = question.count
            val text = if (count == null) {
                stringResource(R.string.settings_remove_question_no_count)
            } else {
                pluralStringResource(R.plurals.settings_remove_question, count.contacts, count.contacts) + " " +
                    pluralStringResource(R.plurals.settings_remove_edits, count.withPhoneEdits, count.withPhoneEdits)
            }
            Triple(text, stringResource(R.string.settings_remove), true)
        }
    }
    AlertDialog(
        onDismissRequest = model::cancelQuestion,
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = model::confirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(yes) }
        },
        dismissButton = { TextButton(onClick = model::cancelQuestion) { Text(stringResource(R.string.login_cancel)) } },
    )
}

/** Keep a copy on this phone?, with its title and the count. */
@Composable
private fun AskKeepCopy(contacts: Int, model: SettingsModel) {
    AlertDialog(
        onDismissRequest = model::cancelQuestion,
        title = { Text(stringResource(R.string.settings_keep_copy_question)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pluralStringResource(R.plurals.settings_keep_copy_count, contacts, contacts))
                Text(stringResource(R.string.settings_keep_copy_warning))
            }
        },
        confirmButton = { Button(onClick = model::confirm) { Text(stringResource(R.string.settings_copy)) } },
        dismissButton = { TextButton(onClick = model::cancelQuestion) { Text(stringResource(R.string.login_cancel)) } },
    )
}

@Composable
private fun About(onNotices: () -> Unit) {
    SectionTitle(stringResource(R.string.settings_about))
    SectionCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_version, appVersion(LocalContext.current)))
            Text(stringResource(R.string.settings_licence))
            Text(stringResource(R.string.settings_source), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onNotices).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_third_party), Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (line in listOf(R.string.settings_not_affiliated, R.string.settings_copyright)) {
            Text(stringResource(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** A top bar whose icon closes what it heads (a submenu, the licences). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClosingBar(title: String, onClose: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_close)) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** The notices file as it is, a paragraph per item so a long text scrolls lazily. */
@Composable
private fun ThirdPartyNotices(onClose: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null) { value = withContext(Dispatchers.IO) { thirdPartyNotices(context) } }
    Scaffold(topBar = { ClosingBar(stringResource(R.string.settings_third_party), onClose) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 16.dp)) {
            items(text?.split("\n\n").orEmpty()) { paragraph ->
                Text(paragraph, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * Manual, a switch outside the submenus, greys out both rows while it's on; each row shows its choice and opens its
 * submenu.
 */
@Composable
private fun TimingSection(ui: SettingsUi, model: SettingsModel) {
    val timing = ui.timing
    SectionTitle(stringResource(R.string.settings_timing))
    SectionCard {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = timing.manual, enabled = !ui.busy, role = Role.Switch, onValueChange = model::setManual)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_manual), Modifier.weight(1f))
            Switch(checked = timing.manual, onCheckedChange = null, enabled = !ui.busy)
        }
        if (timing.manual) {
            Text(
                stringResource(R.string.settings_manual_on),
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val rows = !ui.busy && !timing.manual
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuRow(stringResource(R.string.settings_full_sync), intervalText(timing.fullSyncMinutes), rows) { model.openMenu(Menu.FULL_SYNC) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MenuRow(stringResource(R.string.settings_check), checkText(timing.check), rows) { model.openMenu(Menu.CHECK) }
    }
    // Under Manual, phone edits wait for Sync now too.
    if (!timing.manual) {
        Text(
            stringResource(R.string.settings_timing_note),
            Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MenuRow(title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    val detail = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = color)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = detail)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = detail)
    }
}

/** A submenu over the settings: what it sets, then its choices; choosing, Close, or Back closes it. */
@Composable
private fun TimingMenu(menu: Menu, ui: SettingsUi, model: SettingsModel) {
    val timing = ui.timing
    val title = if (menu == Menu.FULL_SYNC) R.string.settings_full_sync else R.string.settings_check
    Scaffold(topBar = { ClosingBar(stringResource(title), model::closeMenu) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val detail = if (menu == Menu.FULL_SYNC) R.string.settings_full_sync_detail else R.string.settings_check_detail
            Text(stringResource(detail), Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard {
                Column(Modifier.selectableGroup().padding(vertical = 4.dp)) {
                    when (menu) {
                        Menu.FULL_SYNC -> Timing.intervals.forEach { minutes ->
                            Choice(intervalText(minutes), timing.fullSyncMinutes == minutes, !ui.busy) { model.chooseFullSync(minutes) }
                        }
                        Menu.CHECK -> Timing.checkChoices.forEach { check ->
                            Choice(checkText(check), timing.check == check, !ui.busy && timing.allows(check)) { model.chooseCheck(check) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA))
    }
}

@Composable
private fun intervalText(minutes: Int): String = if (minutes < 60) {
    pluralStringResource(R.plurals.settings_interval_minutes, minutes, minutes)
} else {
    pluralStringResource(R.plurals.settings_interval_hours, minutes / 60, minutes / 60)
}

@Composable
private fun checkText(check: Check): String = when (check) {
    Check.SameAsFullSync -> stringResource(R.string.settings_check_same)
    is Check.Every -> intervalText(check.minutes)
}

/** Material's alpha for disabled content. */
private const val DISABLED_ALPHA = 0.38f

@Composable
private fun messageText(message: Message): String = when (message) {
    SettingsMessage.INTERVAL_FAILED -> stringResource(R.string.settings_interval_failed)
    SettingsMessage.LOGOUT_FAILED -> stringResource(R.string.settings_log_out_failed)
    SettingsMessage.REMOVAL_FAILED -> stringResource(R.string.settings_remove_failed)
    SettingsMessage.LOGS_SAVED -> stringResource(R.string.settings_logs_saved)
    SettingsMessage.LOGS_FAILED -> stringResource(R.string.settings_logs_failed)
    SettingsMessage.NOTHING_TO_COPY -> stringResource(R.string.settings_copy_nothing)
    SettingsMessage.COULDNT_READ -> stringResource(R.string.settings_copy_unreadable)
    SettingsMessage.COPY_STOPPED -> stringResource(R.string.settings_copy_stopped)
    is Copied -> {
        // N is the copy's own total, which a sync may have changed since the count.
        val (copied, notCopied) = message.result
        val total = copied + notCopied
        when {
            notCopied == 0 -> pluralStringResource(R.plurals.settings_copied, total, total)
            copied == 0 -> pluralStringResource(R.plurals.settings_copy_none, total, total)
            else -> stringResource(R.string.settings_copy_some, copied, total, notCopied)
        }
    }
}

/** JSON lines; a generic type, so every file provider takes it and keeps the .jsonl name. */
private const val EXPORT_TYPE = "application/octet-stream"

/** The installed app's version name (about). */
fun appVersion(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

const val NOTICES_ASSET = "third_party_notices.txt"

/** The bundled notices, shown from about. */
fun thirdPartyNotices(context: Context): String = context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
