package dev.rboza.android.opensyncforproton.importing

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import dev.rboza.android.opensyncforproton.AppGraph
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.diagnostics.CopyCount
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import dev.rboza.android.opensyncforproton.diagnostics.MergeCount
import dev.rboza.android.opensyncforproton.diagnostics.Operation
import dev.rboza.android.opensyncforproton.diagnostics.Outcome
import dev.rboza.android.opensyncforproton.diagnostics.RemoveCount
import dev.rboza.android.opensyncforproton.diagnostics.record
import dev.rboza.android.opensyncforproton.main.AccountUi
import dev.rboza.android.opensyncforproton.main.accountUi
import dev.rboza.android.opensyncforproton.main.shownRecord
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.provider.ActionResult
import dev.rboza.android.opensyncforproton.sync.provider.ColumnValue
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ImportCandidate
import dev.rboza.android.opensyncforproton.sync.provider.ImportList
import dev.rboza.android.opensyncforproton.sync.provider.ImportMark
import dev.rboza.android.opensyncforproton.sync.provider.ImportedOriginal
import dev.rboza.android.opensyncforproton.sync.provider.MatchReason
import dev.rboza.android.opensyncforproton.sync.provider.MergeChoice
import dev.rboza.android.opensyncforproton.sync.provider.MergePair
import dev.rboza.android.opensyncforproton.sync.provider.MergeResult
import dev.rboza.android.opensyncforproton.sync.provider.OriginalState
import dev.rboza.android.opensyncforproton.sync.provider.ReadContact
import dev.rboza.android.opensyncforproton.sync.provider.losesProtonValues
import java.text.Collator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the import screen lists nothing (docs/sync.md § Import from this phone): each has its own words. */
enum class Unlisted {
    /** Dry run: what an import writes waits for a sync. */
    NEEDS_SYNC_ON,

    /** Signed out, a session expired, or no account. */
    LOG_IN,

    /** The rows can't be read (permission missing, provider not answering). */
    COULDNT_READ,

    /** No local contacts, which Contact Scopes also gives. */
    NO_CONTACTS,
}

/** A candidate's line: why it can't be imported, which comes first, else its mark. */
enum class Mark { NONE, IMPORTED_BEFORE, LOOKS_IN_PROTON, NEEDS_NAME_OR_EMAIL, LABEL_CANT_BE_SAVED }

/**
 * A contact listed under "On this phone only": [name] as Android shows it (null for none: "No name"), and [detail] its
 * first e-mail address, else its first phone number. `toString` shows neither.
 */
data class Listed(val candidate: ImportCandidate, val name: String?, val detail: String?, val mark: Mark) {
    val id: Long get() = candidate.original.row.rawContactId

    /** One that can't be imported can't be ticked. */
    val tickable: Boolean get() = candidate.cantImport == null

    override fun toString() = "Listed(id=$id, mark=$mark)"
}

/**
 * A pair listed under "Already in Proton": [name] as Android shows the phone contact's (null for none: "No name"), and
 * [protonName] Proton's contact's. `toString` shows neither.
 */
data class ListedPair(val pair: MergePair, val name: String?, val protonName: String?) {
    val id: Long get() = pair.original.row.rawContactId
    val reason: MatchReason get() = pair.reason

    /**
     * The choices it offers: one with no name or e-mail can't replace Proton's or be imported as new, and one with a
     * label a line can't hold can't put it into Proton's either.
     */
    val offered: List<MergeChoice>
        get() = when (pair.cantImport) {
            null -> MergeChoice.entries
            MappingIssueCode.NO_NAME_OR_EMAIL -> listOf(MergeChoice.COMBINE, MergeChoice.KEEP_PROTON)
            else -> listOf(MergeChoice.KEEP_PROTON)
        }

    /** Whether it's still listed as read before: the same two rows, each at the `VERSION` read then. */
    internal fun same(other: ListedPair) =
        id == other.id && pair.original.row.version == other.pair.original.row.version &&
            pair.proton.row.rawContactId == other.pair.proton.row.rawContactId && pair.proton.row.version == other.pair.proton.row.version

    override fun toString() = "ListedPair(id=$id, reason=$reason)"
}

/**
 * An original listed under "Imported originals": [name] as Android shows it (null for none: "No name"). `toString`
 * hides it.
 */
data class ListedOriginal(val original: ImportedOriginal, val name: String?) {
    val id: Long get() = original.original.rawContactId
    val state: OriginalState get() = original.state

    override fun toString() = "ListedOriginal(id=$id, state=$state)"
}

/**
 * "Remove R originals from this phone?" for [originals], the removable ones in list order; [othersStay]: some listed
 * aren't.
 */
data class RemoveQuestion(val originals: List<ImportedOriginal>, val othersStay: Boolean) {
    val count: Int get() = originals.size
}

/**
 * "Apply N choices?" for [chosen], in list order: how many of each choice, and [losing] the **Keep the phone's** pairs
 * whose Proton contact shows a value the phone contact doesn't.
 */
data class MergeQuestion(val chosen: List<Pair<MergePair, MergeChoice>>) {
    val count: Int get() = chosen.size
    val combined: Int get() = count(MergeChoice.COMBINE)
    val keptProton: Int get() = count(MergeChoice.KEEP_PROTON)
    val keptPhone: Int get() = count(MergeChoice.KEEP_PHONE)
    val keptBoth: Int get() = count(MergeChoice.KEEP_BOTH)
    val losing: Int get() = chosen.count { (pair, choice) -> choice == MergeChoice.KEEP_PHONE && losesProtonValues(pair.proton.rows, pair.original.rows) }

    private fun count(choice: MergeChoice) = chosen.count { it.second == choice }
}

/** "Import N contacts to Proton?" for [contacts], in list order; [unmapped] of them hold fields a copy doesn't take. */
data class ImportQuestion(val contacts: List<ImportCandidate>) {
    val count: Int get() = contacts.size
    val unmapped: Int get() = contacts.count { it.unmappedRows }
}

/** What the last action said, until another starts; a read keeps it. */
sealed interface ImportMessage

/** An import's result, with the contacts [ticked] for it. */
data class Imported(val ticked: Int, val result: ActionResult) : ImportMessage

/** The import itself stopped (the graph threw). */
data object ImportStopped : ImportMessage

/** Apply's result, with the pairs [chosen] for it. */
data class Merged(val chosen: Int, val result: MergeResult) : ImportMessage

/** Applying itself stopped (the graph threw). */
data object MergeStopped : ImportMessage

/**
 * Remove originals' result, with the removable originals [asked] about; [noneLeft]: none was still removable when
 * **Remove** read again, so none was tried.
 */
data class Removed(val asked: Int, val result: ActionResult, val noneLeft: Boolean = false) : ImportMessage

/** Removing itself stopped (the graph threw). */
data object RemoveStopped : ImportMessage

/** What the choices dialog picks for. */
sealed interface Picking {
    /** The pair tapped, as listed then, starting at its [choice]. */
    data class One(val pair: ListedPair, val choice: MergeChoice?) : Picking

    /** **Choose for selected…**: the ticked pairs. */
    data object Selected : Picking

    /** **Settle all as…**: every pair. */
    data object All : Picking
}

/** The action that runs: "Importing contacts…", "Applying choices…", or "Removing originals…". */
enum class Working { IMPORTING, APPLYING, REMOVING }

data class ImportUi(
    /** Why nothing is listed, or null when the lists are read. */
    val unlisted: Unlisted? = null,
    /** "On this phone only", sorted by name. */
    val candidates: List<Listed> = emptyList(),
    /** "Already in Proton", sorted by the phone contact's name. */
    val pairs: List<ListedPair> = emptyList(),
    /** "Imported originals", sorted by name. */
    val originals: List<ListedOriginal> = emptyList(),
    /** The last report says the bulk guard holds back deletions on the phone: Remove originals waits. */
    val removalWaits: Boolean = false,
    /** The ticked candidates' raw contact IDs, in memory only. */
    val ticked: Set<Long> = emptySet(),
    /** Each chosen pair's choice, by its phone contact's raw contact ID, and the ticked pairs', in memory only. */
    val choices: Map<Long, MergeChoice> = emptyMap(),
    val tickedPairs: Set<Long> = emptySet(),
    /** The choices dialog, while it's open. */
    val picking: Picking? = null,
    val working: Working? = null,
    val question: ImportQuestion? = null,
    val mergeQuestion: MergeQuestion? = null,
    val removeQuestion: RemoveQuestion? = null,
    val message: ImportMessage? = null,
) {
    /** An action runs or a question waits for its answer: nothing else starts, and the ticks and choices stay. */
    internal val busy: Boolean get() = working != null || question != null || mergeQuestion != null || removeQuestion != null

    /** How many originals **Remove originals** would remove. */
    val removable: Int get() = originals.count { it.state == OriginalState.REMOVABLE }
}

/**
 * The import screen's state (docs/sync.md § Import from this phone): what the account's import list holds, the ticks,
 * the pairs' choices and ticks, the questions, and the result, in memory only, so leaving the screen drops them. It
 * lists only while the account shown is signed in with sync on, and reads when the screen starts and after each action.
 * Its actions run in [scope] and, once started, to their end. Runs on the main thread.
 */
class ImportModel(private val graph: AppGraph, private val scope: CoroutineScope) {
    private val ui = MutableStateFlow<ImportUi?>(null)

    /** Null until the first read. */
    val state: StateFlow<ImportUi?> = ui

    /** The account shown, and its Android name: set with the state that shows it. */
    private var shown: AccountId? = null
    private var shownName: String? = null

    /** As MainModel's: a read that ends after a later-started one has shown its state is dropped. */
    private var readsStarted = 0
    private var latestShown = 0

    fun refresh() {
        scope.launch { read() }
    }

    /** Ticks or unticks a listed contact that can be imported. */
    fun toggle(id: Long) {
        val now = ui.value ?: return
        if (now.busy || now.candidates.none { it.id == id && it.tickable }) return
        ui.value = now.copy(ticked = if (id in now.ticked) now.ticked - id else now.ticked + id)
    }

    /** Ticks every contact with no mark, keeping those ticked by hand. */
    fun selectAll() {
        val now = ui.value ?: return
        if (now.busy) return
        ui.value = now.copy(ticked = now.ticked + now.candidates.filter { it.mark == Mark.NONE }.map { it.id })
    }

    /** Asks about the ticked contacts, in list order; with none ticked, nothing. */
    fun import() {
        val now = ui.value ?: return
        if (now.busy) return
        val chosen = now.candidates.filter { it.id in now.ticked }.map { it.candidate }
        if (chosen.isEmpty()) return
        ui.value = now.copy(question = ImportQuestion(chosen), message = null)
    }

    /** The question's answer: yes. Once started, the import runs to its end, its record and result included. */
    fun confirm() {
        val now = ui.value ?: return
        val question = now.question ?: return
        val account = shown ?: return
        val name = shownName ?: return
        ui.value = now.copy(working = Working.IMPORTING, question = null)
        scope.launch {
            withContext(NonCancellable) {
                val message = try {
                    val result = graph.importContacts(account, name, question.contacts.map { it.original })
                    graph.record(LogEntry(Operation.IMPORT, account, Outcome.DONE, mapOf(CopyCount.COPIED to result.done, CopyCount.NOT_COPIED to result.notDone)))
                    Imported(question.count, result)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    graph.record(LogEntry(Operation.IMPORT, account, Outcome.FAILED))
                    ImportStopped
                }
                read()
                // Its end drops the ticks: its contacts are imported before by then, or failed.
                ui.value = ui.value?.copy(working = null, message = message, ticked = emptySet())
            }
        }
    }

    /** Ticks or unticks a listed pair, for **Choose for selected**. */
    fun togglePair(id: Long) {
        val now = ui.value ?: return
        if (now.busy || now.pairs.none { it.id == id }) return
        ui.value = now.copy(tickedPairs = if (id in now.tickedPairs) now.tickedPairs - id else now.tickedPairs + id)
    }

    /** Opens the choices dialog for a listed pair, at its choice. */
    fun openChoices(id: Long) {
        val now = ui.value ?: return
        val listed = now.pairs.firstOrNull { it.id == id } ?: return
        if (!now.busy) ui.value = now.copy(picking = Picking.One(listed, now.choices[id]))
    }

    /** Opens it for **Choose for selected…**, with a pair ticked. */
    fun openChooseForSelected() {
        val now = ui.value ?: return
        if (!now.busy && now.tickedPairs.isNotEmpty()) ui.value = now.copy(picking = Picking.Selected)
    }

    /** Opens it for **Settle all as…**, with a pair listed. */
    fun openSettleAll() {
        val now = ui.value ?: return
        if (!now.busy && now.pairs.isNotEmpty()) ui.value = now.copy(picking = Picking.All)
    }

    /** Cancel, or Back, on the choices dialog. */
    fun closeChoices() {
        ui.value = ui.value?.copy(picking = null)
    }

    /** OK on the choices dialog: [choice] for what it was opened for, then it closes. */
    fun pickChoice(choice: MergeChoice?) {
        val picking = ui.value?.picking ?: return
        closeChoices()
        when (picking) {
            is Picking.One -> choose(picking.pair.id, choice)
            Picking.Selected -> chooseForSelected(choice)
            Picking.All -> settleAll(choice)
        }
    }

    /** Picks [choice] for one pair, if it offers it; null (**Not chosen**) takes its choice back. */
    fun choose(id: Long, choice: MergeChoice?) = pick(choice) { it.id == id }

    /** **Choose for selected**: [choice] for each ticked pair that offers it. */
    fun chooseForSelected(choice: MergeChoice?) {
        val ticked = ui.value?.tickedPairs ?: return
        pick(choice) { it.id in ticked }
    }

    /** **Settle all as…**: [choice] for every pair that offers it, replacing the choices picked before. */
    fun settleAll(choice: MergeChoice?) = pick(choice) { true }

    /** [choice] for the listed pairs [which] picks, leaving one that doesn't offer it as it was. */
    private fun pick(choice: MergeChoice?, which: (ListedPair) -> Boolean) {
        val now = ui.value ?: return
        if (now.busy) return
        val picked = now.pairs.filter { which(it) && (choice == null || choice in it.offered) }.map { it.id }
        ui.value = now.copy(choices = if (choice == null) now.choices - picked.toSet() else now.choices + picked.map { it to choice })
    }

    /** **Apply**: asks about the chosen pairs, in list order; with none chosen, nothing. */
    fun apply() {
        val now = ui.value ?: return
        if (now.busy) return
        val chosen = chosen(now.pairs, now.choices)
        if (chosen.isEmpty()) return
        ui.value = now.copy(mergeQuestion = MergeQuestion(chosen), message = null)
    }

    /** Apply's answer: yes. Once started, it runs to its end, its record and result included. */
    fun confirmMerge() {
        val now = ui.value ?: return
        val question = now.mergeQuestion ?: return
        val account = shown ?: return
        val name = shownName ?: return
        ui.value = now.copy(working = Working.APPLYING, mergeQuestion = null)
        scope.launch {
            withContext(NonCancellable) {
                val message = try {
                    val result = graph.applyMerges(account, name, question.chosen)
                    val counts = mapOf(
                        MergeCount.COMBINED to result.combined,
                        MergeCount.KEPT_PROTON to result.keptProton,
                        MergeCount.KEPT_PHONE to result.keptPhone,
                        MergeCount.KEPT_BOTH to result.keptBoth,
                        MergeCount.NOT_APPLIED to result.notApplied,
                    )
                    graph.record(LogEntry(Operation.MERGE, account, Outcome.DONE, counts))
                    Merged(question.count, result)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    graph.record(LogEntry(Operation.MERGE, account, Outcome.FAILED))
                    MergeStopped
                }
                read()
                // As Import's, its end drops the pairs' ticks; a pair applied is no longer one.
                ui.value = ui.value?.copy(working = null, message = message, tickedPairs = emptySet())
            }
        }
    }

    /**
     * **Remove originals**: asks about the removable originals, in list order; with none, or while it waits, nothing.
     */
    fun removeOriginals() {
        val now = ui.value ?: return
        if (now.busy) return
        val question = removeQuestion(now) ?: return
        ui.value = now.copy(removeQuestion = question, message = null)
    }

    /**
     * Its answer: yes. It reads again first, since a sync may have held back deletions, held a copy, or moved a row
     * since the question was asked: with deletions now waiting, another account shown, or nothing listed, it removes
     * nothing, records nothing, and shows no result; otherwise only the originals asked about that are still removable,
     * as read now, and the others count as not removed. Once started, it runs to its end, its record and result
     * included.
     */
    fun confirmRemove() {
        val now = ui.value ?: return
        val question = now.removeQuestion ?: return
        val account = shown ?: return
        val name = shownName ?: return
        ui.value = now.copy(working = Working.REMOVING, removeQuestion = null)
        scope.launch {
            withContext(NonCancellable) {
                read()
                val fresh = ui.value
                if (shown != account || fresh == null || fresh.unlisted != null || fresh.removalWaits) {
                    ui.value = ui.value?.copy(working = null)
                    return@withContext
                }
                val asked = question.originals.map { it.original.rawContactId }.toSet()
                val still = fresh.originals.filter { it.state == OriginalState.REMOVABLE && it.id in asked }.map { it.original }
                val message = try {
                    val removed = if (still.isEmpty()) 0 else graph.removeOriginals(account, name, still).done
                    val result = ActionResult(removed, question.count - removed)
                    graph.record(LogEntry(Operation.REMOVE_ORIGINALS, account, Outcome.DONE, mapOf(RemoveCount.REMOVED to result.done, RemoveCount.NOT_REMOVED to result.notDone)))
                    Removed(question.count, result, noneLeft = still.isEmpty())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    graph.record(LogEntry(Operation.REMOVE_ORIGINALS, account, Outcome.FAILED))
                    RemoveStopped
                }
                read()
                ui.value = ui.value?.copy(working = null, message = message)
            }
        }
    }

    /** Cancel, or Back: while an action runs there's no question. */
    fun cancelQuestion() {
        ui.value = ui.value?.copy(question = null, mergeQuestion = null, removeQuestion = null)
    }

    private suspend fun read() {
        val number = ++readsStarted
        val next = try {
            readState()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Room failing reads as the rows not being read: nothing is listed, for no account.
            Read(null, null, ImportUi(unlisted = Unlisted.COULDNT_READ))
        }
        if (number < latestShown) return
        latestShown = number
        val last = ui.value ?: ImportUi()
        val same = next.account == shown
        shown = next.account
        shownName = next.name
        val tickable = next.ui.candidates.filter { it.tickable }.map { it.id }.toSet()
        // Ticks and the question are for the account they were made on, and only while it lists.
        val ticked = if (same) last.ticked intersect tickable else emptySet()
        // A question open across a read asks about the ticked contacts as now read, so a contact that became a pair
        // meanwhile, or can't be ticked any more, isn't imported, and one edited is imported as shown.
        val chosen = next.ui.candidates.filter { it.id in ticked }.map { it.candidate }
        // A choice is kept while its pair is listed as it was; a pair's tick while it's listed.
        val choices = if (same) last.choices.filterKeys { id -> next.ui.pairs.any { it.id == id && last.pairs.any { old -> old.same(it) } } } else emptyMap()
        val tickedPairs = if (same) last.tickedPairs intersect next.ui.pairs.map { it.id }.toSet() else emptySet()
        // A pair's dialog is for the pair as it was listed: one listed changed, or not at all, closes it.
        val picking = last.picking.takeIf { same }?.takeIf { now -> now !is Picking.One || next.ui.pairs.any { it.same(now.pair) } }
        val merged = chosen(next.ui.pairs, choices)
        ui.value = next.ui.copy(
            ticked = ticked,
            choices = choices,
            tickedPairs = tickedPairs,
            picking = picking,
            question = last.question?.takeIf { chosen.isNotEmpty() }?.let { ImportQuestion(chosen) },
            mergeQuestion = last.mergeQuestion?.takeIf { merged.isNotEmpty() }?.let { MergeQuestion(merged) },
            // As the others: it asks about the originals as now read, of the same account.
            removeQuestion = last.removeQuestion?.takeIf { same }?.let { removeQuestion(next.ui) },
            working = last.working,
            message = last.message,
        )
    }

    private class Read(val account: AccountId?, val name: String?, val ui: ImportUi)

    private suspend fun readState(): Read {
        val record = shownRecord(graph.accounts()) ?: return Read(null, null, ImportUi(unlisted = Unlisted.LOG_IN))
        val id = AccountId(record.accountId)
        val name = record.androidAccountName
        fun unlisted(why: Unlisted) = Read(id, name, ImportUi(unlisted = why))
        if (graph.accountUi(record) !is AccountUi.SignedIn) return unlisted(Unlisted.LOG_IN)
        // Missing settings are the defaults: dry run.
        if (graph.settings(id)?.syncMode != SyncMode.ON) return unlisted(Unlisted.NEEDS_SYNC_ON)
        val list = try {
            graph.importList(id, name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return unlisted(Unlisted.COULDNT_READ)
        }
        if (list.candidates.isEmpty() && list.pairs.isEmpty()) return unlisted(Unlisted.NO_CONTACTS)
        // A copy the bulk guard holds back from deletion may be gone from Proton already.
        val waits = graph.lastReport(id)?.bulkGuardRemote == true
        return Read(id, name, ImportUi(candidates = listed(list), pairs = listedPairs(list), originals = listedOriginals(list), removalWaits = waits))
    }
}

/** The removable originals, unless removal waits; null when there are none. */
private fun removeQuestion(ui: ImportUi): RemoveQuestion? {
    if (ui.removalWaits) return null
    val removable = ui.originals.filter { it.state == OriginalState.REMOVABLE }.map { it.original }
    return if (removable.isEmpty()) null else RemoveQuestion(removable, othersStay = removable.size < ui.originals.size)
}

/** The chosen pairs, with their choices, in list order. */
private fun chosen(pairs: List<ListedPair>, choices: Map<Long, MergeChoice>) = pairs.mapNotNull { listed -> choices[listed.id]?.let { listed.pair to it } }

/** A name as Android shows it, or null for none. */
private fun shownName(row: DeviceContact) = row.displayName?.trim()?.ifEmpty { null }

/** The pairs as listed: sorted by the phone contact's name as the candidates are. */
private fun listedPairs(list: ImportList): List<ListedPair> {
    val collator = Collator.getInstance()
    return list.pairs.map { ListedPair(it, shownName(it.original.row), shownName(it.proton.row)) }.sortedWith(compareBy(nullsLast(collator)) { it.name })
}

/** The imported originals as listed: sorted by name as the candidates are. */
private fun listedOriginals(list: ImportList): List<ListedOriginal> {
    val collator = Collator.getInstance()
    return list.originals.map { ListedOriginal(it, shownName(it.original)) }.sortedWith(compareBy(nullsLast(collator)) { it.name })
}

/**
 * The candidates as listed: sorted by name as the phone's language sorts it (which ignores case first), those with
 * none last, and otherwise in the list's order.
 */
private fun listed(list: ImportList): List<Listed> {
    val collator = Collator.getInstance()
    return list.candidates.map { candidate ->
        val rows = candidate.original.rows
        fun first(mimeType: String, column: String) =
            rows.filter { it.mimeType == mimeType }.firstNotNullOfOrNull { (it.values[column] as? ColumnValue.Text)?.value?.ifBlank { null } }
        val mark = when (candidate.cantImport) {
            MappingIssueCode.NO_NAME_OR_EMAIL -> Mark.NEEDS_NAME_OR_EMAIL
            null -> when (candidate.mark) {
                ImportMark.NONE -> Mark.NONE
                ImportMark.IMPORTED_BEFORE -> Mark.IMPORTED_BEFORE
                ImportMark.LOOKS_IN_PROTON -> Mark.LOOKS_IN_PROTON
            }
            // A label a line can't hold, the only other issue `create` gives.
            else -> Mark.LABEL_CANT_BE_SAVED
        }
        Listed(candidate, shownName(candidate.original.row), first(Email.CONTENT_ITEM_TYPE, Email.ADDRESS) ?: first(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER), mark)
    }.sortedWith(compareBy(nullsLast(collator)) { it.name })
}
