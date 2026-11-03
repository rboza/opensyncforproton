package dev.rboza.android.opensyncforproton.importing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.sync.provider.MatchReason
import dev.rboza.android.opensyncforproton.sync.provider.MergeChoice
import dev.rboza.android.opensyncforproton.sync.provider.MergeResult
import dev.rboza.android.opensyncforproton.sync.provider.OriginalState
import dev.rboza.android.opensyncforproton.ui.Banner
import dev.rboza.android.opensyncforproton.ui.SectionTitle
import dev.rboza.android.opensyncforproton.ui.Tone

/**
 * The import screen, opened from the settings screen. It says why when it lists nothing; otherwise "On this phone only"
 * with its ticks, **Select all**, and **Import** (asked first, in a dialog); "Already in Proton" with each pair's tick
 * and choice, picked in a dialog by tapping the pair, **Choose for selected…**, **Settle all as…**, and **Apply**
 * (asked first); and "Imported originals" with the caution under its heading, **Remove originals** (asked first; it
 * waits while the bulk guard holds back deletions on the phone), and each original's name and why it stays when it
 * does. It reads each time it starts. Back (or the top bar's arrow) waits while an action runs, cancels a question,
 * else returns to settings ([onClose]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(model: ImportModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(model) {
        model.refresh()
        onStopOrDispose {}
    }
    val ui = state
    val back = {
        when {
            ui?.working != null -> Unit
            ui?.question != null || ui?.mergeQuestion != null || ui?.removeQuestion != null -> model.cancelQuestion()
            else -> onClose()
        }
    }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_import)) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        // Lazy, since the phone may hold many contacts.
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui == null) return@LazyColumn
            ui.message?.let { message ->
                val all = (message is Imported && message.result.done == message.ticked) || (message is Merged && applied(message.result) == message.chosen) ||
                    (message is Removed && message.result.done == message.asked)
                item { Banner(messageText(message), if (all) Tone.INFO else Tone.ERROR) }
            }
            ui.working?.let { working ->
                val text = when (working) {
                    Working.IMPORTING -> R.string.import_importing
                    Working.APPLYING -> R.string.import_applying
                    Working.REMOVING -> R.string.import_removing
                }
                item { Text(stringResource(text), Modifier.padding(horizontal = 4.dp)) }
            }
            ui.unlisted?.let { unlisted ->
                item { Banner(unlistedText(unlisted), if (unlisted == Unlisted.COULDNT_READ) Tone.ERROR else Tone.INFO) }
                return@LazyColumn
            }
            item { SectionTitle(stringResource(R.string.import_phone_only, ui.candidates.size)) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = model::selectAll, enabled = !ui.busy) { Text(stringResource(R.string.import_select_all)) }
                    Button(onClick = model::import, enabled = !ui.busy && ui.ticked.isNotEmpty()) { Text(stringResource(R.string.import_import)) }
                }
            }
            items(ui.candidates, key = { it.id }) { listed ->
                Candidate(listed, ticked = listed.id in ui.ticked, enabled = !ui.busy && listed.tickable) { model.toggle(listed.id) }
            }
            item { SectionTitle(stringResource(R.string.import_in_proton, ui.pairs.size)) }
            item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = model::openChooseForSelected, enabled = !ui.busy && ui.tickedPairs.isNotEmpty()) { Text(stringResource(R.string.import_choose_selected)) }
                    TextButton(onClick = model::openSettleAll, enabled = !ui.busy && ui.pairs.isNotEmpty()) { Text(stringResource(R.string.import_settle_all)) }
                    Button(onClick = model::apply, enabled = !ui.busy && ui.choices.isNotEmpty()) { Text(stringResource(R.string.import_apply)) }
                }
            }
            items(ui.pairs, key = { "pair-${it.id}" }) { listed ->
                PairRow(listed, ui.choices[listed.id], ticked = listed.id in ui.tickedPairs, enabled = !ui.busy, onToggle = { model.togglePair(listed.id) }) {
                    model.openChoices(listed.id)
                }
            }
            item { SectionTitle(stringResource(R.string.import_originals, ui.originals.size)) }
            item {
                Text(
                    stringResource(R.string.import_caution),
                    Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.removalWaits) item { Text(stringResource(R.string.import_removal_waits), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = model::removeOriginals, enabled = !ui.busy && !ui.removalWaits && ui.removable > 0) { Text(stringResource(R.string.import_remove_originals)) }
                }
            }
            items(ui.originals, key = { "original-${it.id}" }) { OriginalRow(it) }
        }
    }
    ui?.question?.let { AskImport(it, model) }
    ui?.mergeQuestion?.let { AskMerge(it, model) }
    ui?.removeQuestion?.let { AskRemove(it, model) }
    ui?.picking?.let { Choices(it, onDismiss = model::closeChoices, onPick = model::pickChoice) }
}

/** An imported original: its name, and why it stays when it does. */
@Composable
private fun OriginalRow(listed: ListedOriginal) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(listed.name ?: stringResource(R.string.import_no_name), style = MaterialTheme.typography.bodyLarge)
        val stays = when (listed.state) {
            OriginalState.REMOVABLE -> null
            OriginalState.CHANGED -> R.string.import_original_changed
            OriginalState.HOLDS_MORE -> R.string.import_original_holds_more
        }
        stays?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Remove R originals from this phone?, with "The others stay." only when some do. */
@Composable
private fun AskRemove(question: RemoveQuestion, model: ImportModel) {
    val copies = pluralStringResource(R.plurals.import_remove_copies, question.count)
    AlertDialog(
        onDismissRequest = model::cancelQuestion,
        title = { Text(pluralStringResource(R.plurals.import_remove_question, question.count, question.count)) },
        text = { Text(if (question.othersStay) "$copies ${stringResource(R.string.import_remove_others_stay)}" else copies) },
        confirmButton = { Button(onClick = model::confirmRemove) { Text(stringResource(R.string.import_remove)) } },
        dismissButton = { TextButton(onClick = model::cancelQuestion) { Text(stringResource(R.string.login_cancel)) } },
    )
}

/** The pairs applied of each choice. */
private fun applied(result: MergeResult) = result.combined + result.keptProton + result.keptPhone + result.keptBoth

/**
 * A pair: its tick, announced as a checkbox named after the phone contact, then its names, why they match, and its
 * choice, which tapping opens.
 */
@Composable
private fun PairRow(listed: ListedPair, choice: MergeChoice?, ticked: Boolean, enabled: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val name = listed.name ?: stringResource(R.string.import_no_name)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = ticked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            modifier = Modifier.padding(horizontal = 12.dp).semantics { contentDescription = name },
        )
        Column(Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button, onClick = onOpen).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.import_proton_name, listed.protonName ?: stringResource(R.string.import_no_name)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(reasonText(listed.reason)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(choiceText(choice)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The choices for [picking]: those a pair offers, else all four, with **Not chosen** first; OK picks the one selected.
 * One pair's starts at its choice, the others at none.
 */
@Composable
private fun Choices(picking: Picking, onDismiss: () -> Unit, onPick: (MergeChoice?) -> Unit) {
    // Wrapped, so Not chosen (null) can be told from nothing selected yet.
    var selected by remember(picking) { mutableStateOf((picking as? Picking.One)?.let { Selected(it.choice) }) }
    val offered = (picking as? Picking.One)?.pair?.offered ?: MergeChoice.entries
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (picking) {
                    is Picking.One -> picking.pair.name ?: stringResource(R.string.import_no_name)
                    Picking.Selected -> stringResource(R.string.import_choose_selected_title)
                    Picking.All -> stringResource(R.string.import_settle_all_title)
                },
            )
        },
        text = {
            Column(Modifier.selectableGroup()) {
                (listOf(null) + offered).forEach { choice ->
                    val isSelected = selected?.choice == choice && selected != null
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = isSelected, role = Role.RadioButton, onClick = { selected = Selected(choice) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Text(stringResource(choiceText(choice)), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { selected?.let { onPick(it.choice) } }, enabled = selected != null) { Text(stringResource(R.string.login_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.login_cancel)) } },
    )
}

/** A choice selected in [Choices], **Not chosen** being null. */
private data class Selected(val choice: MergeChoice?)

/** Apply N choices?, with a line for each kind chosen, and L on the replacing line when it isn't 0. */
@Composable
private fun AskMerge(question: MergeQuestion, model: ImportModel) {
    AlertDialog(
        onDismissRequest = model::cancelQuestion,
        title = { Text(pluralStringResource(R.plurals.import_merge_question, question.count, question.count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (question.combined > 0) Text(pluralStringResource(R.plurals.import_merge_combined, question.combined, question.combined))
                    if (question.keptProton > 0) Text(pluralStringResource(R.plurals.import_merge_kept_proton, question.keptProton, question.keptProton))
                    if (question.keptPhone > 0) Text(replacedText(question.keptPhone, question.losing))
                    if (question.keptBoth > 0) Text(pluralStringResource(R.plurals.import_merge_kept_both, question.keptBoth, question.keptBoth))
                }
                Text(stringResource(R.string.import_merge_later))
            }
        },
        confirmButton = { Button(onClick = model::confirmMerge) { Text(stringResource(R.string.import_apply)) } },
        dismissButton = { TextButton(onClick = model::cancelQuestion) { Text(stringResource(R.string.login_cancel)) } },
    )
}

/** R will replace Proton's copy, and L of them lose values only Proton has. */
@Composable
private fun replacedText(replaced: Int, losing: Int): String = when {
    losing == 0 -> pluralStringResource(R.plurals.import_merge_replaced, replaced, replaced)
    replaced == 1 -> stringResource(R.string.import_merge_replaced_one_losing)
    else -> pluralStringResource(R.plurals.import_merge_replaced_losing, losing, replaced, losing)
}

private fun reasonText(reason: MatchReason) = when (reason) {
    MatchReason.SAME_CONTACT -> R.string.import_reason_same_contact
    MatchReason.EMAIL -> R.string.import_reason_email
    MatchReason.PHONE -> R.string.import_reason_phone
    MatchReason.NAME -> R.string.import_reason_name
}

private fun choiceText(choice: MergeChoice?) = when (choice) {
    null -> R.string.import_not_chosen
    MergeChoice.COMBINE -> R.string.import_combine
    MergeChoice.KEEP_PROTON -> R.string.import_keep_proton
    MergeChoice.KEEP_PHONE -> R.string.import_keep_phone
    MergeChoice.KEEP_BOTH -> R.string.import_keep_both
}

/** A contact with its tick, announced as a checkbox: its name, its first e-mail or number, and its mark, if any. */
@Composable
private fun Candidate(listed: Listed, ticked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = ticked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ticked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(horizontal = 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(listed.name ?: stringResource(R.string.import_no_name), style = MaterialTheme.typography.bodyLarge)
            listed.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            markText(listed.mark)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (listed.tickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Import N contacts to Proton?, with M of them holding fields a copy doesn't take, when M isn't 0. */
@Composable
private fun AskImport(question: ImportQuestion, model: ImportModel) {
    AlertDialog(
        onDismissRequest = model::cancelQuestion,
        title = { Text(pluralStringResource(R.plurals.import_question, question.count, question.count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_question_copies))
                if (question.unmapped > 0) Text(pluralStringResource(R.plurals.import_question_unmapped, question.unmapped, question.unmapped))
            }
        },
        confirmButton = { Button(onClick = model::confirm) { Text(stringResource(R.string.import_import)) } },
        dismissButton = { TextButton(onClick = model::cancelQuestion) { Text(stringResource(R.string.login_cancel)) } },
    )
}

@Composable
private fun markText(mark: Mark): String? = when (mark) {
    Mark.NONE -> null
    Mark.IMPORTED_BEFORE -> stringResource(R.string.import_mark_imported_before)
    Mark.LOOKS_IN_PROTON -> stringResource(R.string.import_mark_in_proton)
    Mark.NEEDS_NAME_OR_EMAIL -> stringResource(R.string.import_mark_no_name_or_email)
    Mark.LABEL_CANT_BE_SAVED -> stringResource(R.string.import_mark_label)
}

@Composable
private fun unlistedText(unlisted: Unlisted): String = stringResource(
    when (unlisted) {
        Unlisted.NEEDS_SYNC_ON -> R.string.import_needs_sync_on
        Unlisted.LOG_IN -> R.string.import_log_in
        Unlisted.COULDNT_READ -> R.string.import_unreadable
        Unlisted.NO_CONTACTS -> R.string.import_no_contacts
    },
)

@Composable
private fun messageText(message: ImportMessage): String = when (message) {
    ImportStopped -> stringResource(R.string.import_stopped)
    MergeStopped -> stringResource(R.string.import_merge_stopped)
    RemoveStopped -> stringResource(R.string.import_remove_stopped)
    is Removed -> {
        // R is the removable originals asked about.
        val asked = message.asked
        when (val removed = message.result.done) {
            asked -> pluralStringResource(R.plurals.import_removed, asked, asked)
            // None tried, since none was still removable: they changed, not Contact Scopes.
            0 if !message.noneLeft -> pluralStringResource(R.plurals.import_remove_none, asked, asked)
            0 if asked == 1 -> stringResource(R.string.import_remove_one_changed)
            else -> stringResource(R.string.import_removed_some, removed, asked, asked - removed)
        }
    }
    is Merged -> {
        // N is the pairs chosen for it.
        val chosen = message.chosen
        when (val applied = applied(message.result)) {
            chosen -> pluralStringResource(R.plurals.import_applied, chosen, chosen)
            0 -> pluralStringResource(R.plurals.import_apply_none, chosen, chosen)
            else -> stringResource(R.string.import_applied_some, applied, chosen, chosen - applied)
        }
    }
    is Imported -> {
        // N is the contacts ticked for it.
        val ticked = message.ticked
        val copied = message.result.done
        when (copied) {
            ticked -> pluralStringResource(R.plurals.import_copied, ticked, ticked)
            0 -> pluralStringResource(R.plurals.import_copy_none, ticked, ticked)
            else -> stringResource(R.string.import_copied_some, copied, ticked, ticked - copied)
        }
    }
}
