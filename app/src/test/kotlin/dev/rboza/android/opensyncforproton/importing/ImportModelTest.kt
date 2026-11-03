package dev.rboza.android.opensyncforproton.importing

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.diagnostics.CopyCount
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import dev.rboza.android.opensyncforproton.diagnostics.MergeCount
import dev.rboza.android.opensyncforproton.diagnostics.Operation
import dev.rboza.android.opensyncforproton.diagnostics.Outcome
import dev.rboza.android.opensyncforproton.diagnostics.RemoveCount
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.provider.ActionResult
import dev.rboza.android.opensyncforproton.sync.provider.ColumnValue
import dev.rboza.android.opensyncforproton.sync.provider.DataRow
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
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import dev.rboza.android.opensyncforproton.sync.provider.ReadContact
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

// The import screen's model (docs/sync.md § Import from this phone): what it lists and when, the ticks, the question,
// the results, and the record. Names and IDs are made up.
@OptIn(ExperimentalCoroutinesApi::class)
class ImportModelTest {
    private val errors = mutableListOf<Throwable>()
    private val scope = CoroutineScope(Job() + UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> errors += e })
    private val graph = FakeAppGraph()
    private val model = ImportModel(graph, scope)

    @AfterEach
    fun noUncaughtErrors() = assertEquals(emptyList<Throwable>(), errors)

    private fun ui() = checkNotNull(model.state.value)

    private fun account(state: LoginState = LoginState.ACTIVE, readable: Boolean = true, mode: SyncMode = SyncMode.ON) {
        graph.records = listOf(AccountRecordEntity("a1", NAME, state))
        graph.settings["a1"] = SettingsEntity("a1", mode)
        if (readable) graph.readable += "a1" else graph.readable -= "a1"
    }

    private fun contact(id: Long, name: String?, email: String? = null, phone: String? = null): ReadContact {
        val rows = listOfNotNull(
            name?.let { DataRow(StructuredName.CONTENT_ITEM_TYPE, mapOf(StructuredName.DISPLAY_NAME to ColumnValue.Text(it))) },
            phone?.let { DataRow(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to ColumnValue.Text(it))) },
            email?.let { DataRow(Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to ColumnValue.Text(it))) },
        )
        return ReadContact(DeviceContact(id, null, false, false, version = 5, displayName = name, nameFromNameRow = name != null), rows)
    }

    private fun candidate(
        id: Long,
        name: String? = "Contact $id",
        mark: ImportMark = ImportMark.NONE,
        cantImport: MappingIssueCode? = null,
        unmapped: Boolean = false,
        email: String? = null,
        phone: String? = null,
    ) = ImportCandidate(contact(id, name, email, phone), mark, cantImport, unmapped)

    private fun pair(
        id: Long,
        name: String? = "Pair $id",
        protonName: String? = "Proton $id",
        reason: MatchReason = MatchReason.NAME,
        cantImport: MappingIssueCode? = null,
        version: Long = 5,
        protonId: Long = 100 + id,
        protonVersion: Long = 5,
        email: String? = null,
        protonEmail: String? = null,
    ): MergePair {
        val original = contact(id, name, email).let { it.copy(row = it.row.copy(version = version)) }
        val proton = contact(protonId, protonName, protonEmail).let { it.copy(row = it.row.copy(sourceId = "p$id", version = protonVersion)) }
        return MergePair(original, proton, reason, cantImport)
    }

    private fun original(id: Long, state: OriginalState = OriginalState.REMOVABLE, name: String? = "Original $id") =
        ImportedOriginal(contact(id, name).row, 5, contact(200 + id, "Copy $id").row.copy(sourceId = "c$id"), state)

    /** Lists [candidates] for a signed-in account with sync on, and reads. */
    private fun listing(vararg candidates: ImportCandidate) {
        account()
        graph.importList = ImportList(candidates.toList(), emptyList(), emptyList())
        model.refresh()
    }

    private fun importRecord(copied: Int, notCopied: Int) =
        LogEntry(Operation.IMPORT, AccountId("a1"), Outcome.DONE, mapOf(CopyCount.COPIED to copied, CopyCount.NOT_COPIED to notCopied))

    private fun ids() = ui().candidates.map { it.id }

    @Test
    fun `signed in with sync on, it reads the list of the account the main screen shows`() {
        graph.records = listOf(
            AccountRecordEntity("a2", "b@example.test", LoginState.SIGNED_OUT),
            AccountRecordEntity("a1", NAME, LoginState.ACTIVE),
        )
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON)
        graph.readable += "a1"
        graph.importList = ImportList(listOf(candidate(1)), emptyList(), emptyList())

        model.refresh()

        assertNull(ui().unlisted)
        assertEquals(listOf(1L), ids())
        assertEquals(listOf("importList a1 $NAME"), graph.calls)
    }

    @Test
    fun `in dry run it says import needs sync on, lists nothing, and reads no list`() {
        graph.importList = ImportList(listOf(candidate(1)), listOf(pair(2)), emptyList())
        account(mode = SyncMode.DRY_RUN)
        model.refresh()
        assertEquals(Unlisted.NEEDS_SYNC_ON, ui().unlisted)
        assertEquals(emptyList<Listed>(), ui().candidates)
        assertEquals(0, ui().pairs.size)

        // Missing settings are the defaults: dry run.
        graph.settings.clear()
        model.refresh()
        assertEquals(Unlisted.NEEDS_SYNC_ON, ui().unlisted)
        assertTrue(graph.calls.none { it.startsWith("importList") })
    }

    @Test
    fun `signed out, with the session expired, or with no account, it says log in, lists nothing, and reads no list`() {
        graph.importList = ImportList(listOf(candidate(1)), emptyList(), emptyList())
        for ((state, readable) in listOf(LoginState.SIGNED_OUT to true, LoginState.ACTIVE to false)) {
            account(state, readable)
            model.refresh()
            assertEquals(Unlisted.LOG_IN, ui().unlisted, "$state $readable")
            assertEquals(emptyList<Listed>(), ui().candidates)
        }
        graph.records = emptyList()
        model.refresh()
        assertEquals(Unlisted.LOG_IN, ui().unlisted)
        assertTrue(graph.calls.none { it.startsWith("importList") })
    }

    @Test
    fun `rows that can't be read say so and list nothing`() {
        account()
        for (failure in listOf(SecurityException("made-up"), ProviderUnavailableException())) {
            graph.importListFailure = failure
            model.refresh()
            assertEquals(Unlisted.COULDNT_READ, ui().unlisted)
            assertEquals(emptyList<Listed>(), ui().candidates)
        }
    }

    @Test
    fun `a read of the stores that fails lists nothing, as rows that can't be read, until a read works again`() {
        listing(candidate(1))
        model.toggle(1)
        graph.readFailure = IllegalStateException("made-up")
        model.refresh()
        assertEquals(Unlisted.COULDNT_READ, ui().unlisted)
        assertEquals(emptySet<Long>(), ui().ticked)

        graph.readFailure = null
        model.refresh()
        model.toggle(1)
        model.import()
        model.confirm()
        assertTrue("importContacts a1 $NAME [1]" in graph.calls, graph.calls.toString())
    }

    @Test
    fun `no local contacts give the Contact Scopes message, and pairs alone still list`() {
        account()
        graph.importList = ImportList(emptyList(), emptyList(), emptyList())
        model.refresh()
        assertEquals(Unlisted.NO_CONTACTS, ui().unlisted)

        graph.importList = ImportList(emptyList(), listOf(pair(1)), emptyList())
        model.refresh()
        assertNull(ui().unlisted)
        assertEquals(1, ui().pairs.size)
    }

    @Test
    fun `candidates are sorted by name, ignoring case, with no name last, each with its first e-mail or else its first phone`() {
        listing(
            candidate(1, "bob", phone = "+1 555 0101"),
            candidate(2, null, email = "nameless@example.test"),
            candidate(3, "Ann", email = "ann@example.test", phone = "+1 555 0102"),
            candidate(4, "  "),
            candidate(5, "Cat", email = " ", phone = "+1 555 0103"),
        )

        assertEquals(listOf(3L, 1L, 5L, 2L, 4L), ids())
        assertEquals(listOf("Ann", "bob", "Cat", null, null), ui().candidates.map { it.name })
        assertEquals(listOf("ann@example.test", "+1 555 0101", "+1 555 0103", "nameless@example.test", null), ui().candidates.map { it.detail })
    }

    @Test
    fun `each candidate has its mark, and a reason it can't be imported comes first`() {
        listing(
            candidate(1, "A"),
            candidate(2, "B", ImportMark.IMPORTED_BEFORE),
            candidate(3, "C", ImportMark.LOOKS_IN_PROTON),
            candidate(4, "D", cantImport = MappingIssueCode.NO_NAME_OR_EMAIL),
            candidate(5, "E", cantImport = MappingIssueCode.UNWRITABLE_LABEL),
            candidate(6, "F", ImportMark.IMPORTED_BEFORE, MappingIssueCode.NO_NAME_OR_EMAIL),
            candidate(7, "G", ImportMark.LOOKS_IN_PROTON, MappingIssueCode.UNWRITABLE_LABEL),
        )
        assertEquals(
            listOf(Mark.NONE, Mark.IMPORTED_BEFORE, Mark.LOOKS_IN_PROTON, Mark.NEEDS_NAME_OR_EMAIL, Mark.LABEL_CANT_BE_SAVED, Mark.NEEDS_NAME_OR_EMAIL, Mark.LABEL_CANT_BE_SAVED),
            ui().candidates.map { it.mark },
        )
        assertEquals(listOf(true, true, true, false, false, false, false), ui().candidates.map { it.tickable })
    }

    @Test
    fun `the headings count the pairs and every listed original`() {
        account()
        graph.importList = ImportList(
            listOf(candidate(1)),
            listOf(pair(2), pair(3)),
            listOf(original(4, OriginalState.REMOVABLE), original(5, OriginalState.CHANGED), original(6, OriginalState.HOLDS_MORE)),
        )
        model.refresh()
        assertEquals(2, ui().pairs.size)
        assertEquals(3, ui().originals.size)
    }

    @Test
    fun `Select all ticks the unmarked only, a marked one can be ticked by hand, and one that can't be imported can't`() {
        listing(
            candidate(1, "A"),
            candidate(2, "B", ImportMark.IMPORTED_BEFORE),
            candidate(3, "C", ImportMark.LOOKS_IN_PROTON),
            candidate(4, "D", cantImport = MappingIssueCode.NO_NAME_OR_EMAIL),
            candidate(5, "E"),
        )
        model.selectAll()
        assertEquals(setOf(1L, 5L), ui().ticked)

        model.toggle(2)
        model.toggle(3)
        model.toggle(4)
        assertEquals(setOf(1L, 2L, 3L, 5L), ui().ticked)

        model.toggle(1)
        assertEquals(setOf(2L, 3L, 5L), ui().ticked)
        model.selectAll()
        assertEquals(setOf(1L, 2L, 3L, 5L), ui().ticked, "Select all keeps the hand-ticked")

        model.toggle(99)
        assertEquals(setOf(1L, 2L, 3L, 5L), ui().ticked, "one that isn't listed")
    }

    @Test
    fun `Import counts the ticked and those with fields a copy doesn't take, and asks`() {
        listing(candidate(1, "A", unmapped = true), candidate(2, "B"), candidate(3, "C", unmapped = true), candidate(4, "D"))
        model.toggle(1)
        model.toggle(2)
        model.toggle(3)

        model.import()

        val question = checkNotNull(ui().question)
        assertEquals(3, question.count)
        assertEquals(2, question.unmapped)
        assertTrue(graph.calls.none { it.startsWith("importContacts") })
        assertEquals(emptyList<LogEntry>(), graph.logged)
    }

    @Test
    fun `with none ticked, Import asks nothing`() {
        listing(candidate(1))
        model.import()
        assertNull(ui().question)
    }

    @Test
    fun `Cancel imports nothing and records nothing`() {
        listing(candidate(1))
        model.toggle(1)
        model.import()
        model.cancelQuestion()
        assertNull(ui().question)
        model.confirm()
        assertTrue(graph.calls.none { it.startsWith("importContacts") })
        assertEquals(emptyList<LogEntry>(), graph.logged)
        assertEquals(setOf(1L), ui().ticked, "the ticks stay")
    }

    @Test
    fun `Import copies the ticked contacts in list order, once each, records done, reads again, and drops the ticks`() {
        listing(candidate(1, "Cat"), candidate(2, "Ann"), candidate(3, "Bob", ImportMark.IMPORTED_BEFORE), candidate(4, "Dan"))
        graph.importResult = ActionResult(done = 3, notDone = 0)
        model.toggle(1)
        model.toggle(3)
        model.toggle(2)
        model.import()
        graph.calls.clear()

        model.confirm()

        // The graph's importContacts asks for the sync after a copy; the screen asks for none itself.
        assertEquals(listOf("importContacts a1 $NAME [2, 3, 1]", "importList a1 $NAME"), graph.calls)
        assertEquals(Imported(ticked = 3, ActionResult(3, 0)), ui().message)
        assertEquals(listOf(importRecord(3, 0)), graph.logged)
        assertEquals(emptySet<Long>(), ui().ticked)
        assertNull(ui().question)
        assertNull(ui().working)
    }

    @Test
    fun `some or none copied shows the counts with the ticked count`() {
        listing(candidate(1), candidate(2))
        for (result in listOf(ActionResult(1, 1), ActionResult(0, 2))) {
            model.toggle(1)
            model.toggle(2)
            graph.importResult = result
            model.import()
            model.confirm()
            assertEquals(Imported(ticked = 2, result), ui().message)
        }
        assertEquals(listOf(importRecord(1, 1), importRecord(0, 2)), graph.logged)
    }

    @Test
    fun `an import that throws says it stopped, records failed, and reads again`() {
        listing(candidate(1))
        for (failure in listOf(ProviderUnavailableException(), SecurityException("made-up"), IllegalStateException("made-up"))) {
            model.toggle(1)
            graph.importFailure = failure
            model.import()
            graph.calls.clear()
            model.confirm()
            assertEquals(ImportStopped, ui().message)
            assertNull(ui().working)
            assertEquals(listOf("importContacts a1 $NAME [1]", "importList a1 $NAME"), graph.calls)
        }
        assertEquals(List(3) { LogEntry(Operation.IMPORT, AccountId("a1"), Outcome.FAILED) }, graph.logged)
    }

    @Test
    fun `a log that throws changes nothing about the import`() {
        listing(candidate(1))
        graph.importResult = ActionResult(1, 0)
        graph.logFailure = IllegalStateException("made-up")
        model.toggle(1)
        model.import()
        model.confirm()
        assertEquals(Imported(1, ActionResult(1, 0)), ui().message)
        assertNull(ui().working)
    }

    @Test
    fun `while it imports it's working, and nothing else starts or changes`() {
        listing(candidate(1), candidate(2))
        val gate = CompletableDeferred<Unit>()
        graph.importGate = { gate.await() }
        model.toggle(1)
        model.import()
        model.confirm()
        assertEquals(Working.IMPORTING, ui().working)

        model.toggle(2)
        model.toggle(1)
        model.selectAll()
        model.import()
        model.confirm()
        model.cancelQuestion()
        assertEquals(setOf(1L), ui().ticked)
        assertNull(ui().question)
        assertEquals(1, graph.calls.count { it.startsWith("importContacts") })

        gate.complete(Unit)
        assertNull(ui().working)
    }

    @Test
    fun `a read while it imports keeps it working`() {
        listing(candidate(1))
        val gate = CompletableDeferred<Unit>()
        graph.importGate = { gate.await() }
        model.toggle(1)
        model.import()
        model.confirm()
        model.refresh()
        assertEquals(Working.IMPORTING, ui().working)
        gate.complete(Unit)
        assertNull(ui().working)
    }

    @Test
    fun `once started, the import runs to its end when the screen goes away, then shows its result`() {
        listing(candidate(1))
        graph.importResult = ActionResult(1, 0)
        val gate = CompletableDeferred<Unit>()
        graph.importGate = { gate.await() }
        model.toggle(1)
        model.import()
        model.confirm()

        scope.cancel()
        gate.complete(Unit)

        assertEquals(listOf(importRecord(1, 0)), graph.logged)
        assertEquals(Imported(1, ActionResult(1, 0)), ui().message)
        assertNull(ui().working)
    }

    @Test
    fun `a read keeps the ticks of contacts still listed that can be ticked, and drops the others`() {
        listing(candidate(1), candidate(2), candidate(3), candidate(4))
        model.selectAll()
        graph.importList = ImportList(
            listOf(candidate(1, mark = ImportMark.IMPORTED_BEFORE), candidate(2, cantImport = MappingIssueCode.NO_NAME_OR_EMAIL), candidate(4)),
            emptyList(),
            emptyList(),
        )
        model.refresh()
        assertEquals(setOf(1L, 4L), ui().ticked)
    }

    @Test
    fun `a read that shows another account, or none, drops the ticks and the question`() {
        for (after in listOf(listOf(AccountRecordEntity("a2", "z@example.test", LoginState.ACTIVE)), emptyList())) {
            listing(candidate(1))
            model.toggle(1)
            model.import()
            graph.records = after
            graph.settings["a2"] = SettingsEntity("a2", SyncMode.ON)
            graph.readable += "a2"
            model.refresh()
            assertNull(ui().question)
            assertEquals(emptySet<Long>(), ui().ticked)
            model.confirm()
        }
        assertTrue(graph.calls.none { it.startsWith("importContacts") })
    }

    @Test
    fun `a read that lists nothing drops the question, and one that lists the same account keeps it`() {
        listing(candidate(1))
        model.toggle(1)
        model.import()
        model.refresh()
        assertEquals(1, ui().question?.count)

        account(mode = SyncMode.DRY_RUN)
        model.refresh()
        assertNull(ui().question)
        model.confirm()
        assertTrue(graph.calls.none { it.startsWith("importContacts") })
    }

    @Test
    fun `a read while the question is open asks about the ticked contacts as now listed, and drops it when none are left`() {
        listing(candidate(1, "Ann"), candidate(2, "Bob"), candidate(3, "Cat"))
        model.toggle(1)
        model.toggle(2)
        model.import()
        // Meanwhile a sync made Ann a pair, and Bob was edited (a new VERSION) and now has fields a copy doesn't take.
        val edited = candidate(2, "Bob", unmapped = true).let { it.copy(original = it.original.copy(row = it.original.row.copy(version = 6))) }
        graph.importList = ImportList(listOf(edited, candidate(3, "Cat")), listOf(pair(1)), emptyList())
        model.refresh()

        assertEquals(ImportQuestion(listOf(edited)), ui().question)
        assertEquals(1, ui().question?.unmapped)
        graph.calls.clear()
        model.confirm()
        assertEquals(listOf("importContacts a1 $NAME [2]", "importList a1 $NAME"), graph.calls)

        model.toggle(3)
        model.import()
        graph.importList = ImportList(listOf(candidate(3, "Cat", ImportMark.IMPORTED_BEFORE, MappingIssueCode.NO_NAME_OR_EMAIL)), emptyList(), emptyList())
        model.refresh()
        assertNull(ui().question)
    }

    @Test
    fun `the result stays across reads and goes when Import asks again`() {
        listing(candidate(1))
        graph.importResult = ActionResult(1, 0)
        model.toggle(1)
        model.import()
        model.confirm()
        model.refresh()
        assertEquals(Imported(1, ActionResult(1, 0)), ui().message)

        model.toggle(1)
        model.import()
        assertNull(ui().message)
    }

    @Test
    fun `a read that ends after a later one has shown its state is dropped`() {
        listing(candidate(1))
        val gate = CompletableDeferred<Unit>()
        graph.importListGate = { gate.await() }
        graph.importList = ImportList(listOf(candidate(2)), emptyList(), emptyList())
        model.refresh()
        graph.importListGate = null
        graph.importList = ImportList(listOf(candidate(3)), emptyList(), emptyList())
        model.refresh()
        assertEquals(listOf(3L), ids())

        gate.complete(Unit)
        assertEquals(listOf(3L), ids())
    }

    @Test
    fun `a listed contact shows no name or detail in its text`() {
        listing(candidate(1, "Made Up Name", email = "made.up@example.test"))
        val shown = ui().toString() + ui().candidates.single().toString()
        assertFalse(shown.contains("Made Up Name"), shown)
        assertFalse(shown.contains("made.up@example.test"), shown)
    }

    // The Merge section: the choices, the selection, Settle all as…, the question, the results, and the record.

    /** Lists [pairs] for a signed-in account with sync on, with one candidate, and reads. */
    private fun pairing(vararg pairs: MergePair) {
        account()
        graph.importList = ImportList(listOf(candidate(90, "Zed")), pairs.toList(), emptyList())
        model.refresh()
    }

    private fun mergeRecord(result: MergeResult) = LogEntry(
        Operation.MERGE,
        AccountId("a1"),
        Outcome.DONE,
        mapOf(
            MergeCount.COMBINED to result.combined,
            MergeCount.KEPT_PROTON to result.keptProton,
            MergeCount.KEPT_PHONE to result.keptPhone,
            MergeCount.KEPT_BOTH to result.keptBoth,
            MergeCount.NOT_APPLIED to result.notApplied,
        ),
    )

    private fun pairIds() = ui().pairs.map { it.id }

    @Test
    fun `pairs are sorted by the phone contact's name, with no name last, each with both names, its reason, and not chosen`() {
        pairing(
            pair(1, "bob", "Robert", MatchReason.PHONE),
            pair(2, null, "  ", MatchReason.EMAIL),
            pair(3, "Ann", "Ann Lee", MatchReason.SAME_CONTACT),
            pair(4, "  ", null, MatchReason.NAME),
        )

        assertEquals(listOf(3L, 1L, 2L, 4L), pairIds())
        assertEquals(listOf("Ann", "bob", null, null), ui().pairs.map { it.name })
        assertEquals(listOf("Ann Lee", "Robert", null, null), ui().pairs.map { it.protonName })
        assertEquals(listOf(MatchReason.SAME_CONTACT, MatchReason.PHONE, MatchReason.EMAIL, MatchReason.NAME), ui().pairs.map { it.reason })
        assertEquals(emptyMap<Long, MergeChoice>(), ui().choices)
        assertEquals(listOf(90L), ids(), "pairs aren't listed with the others")
    }

    @Test
    fun `a choice is picked for one pair, and Not chosen takes it back`() {
        pairing(pair(1), pair(2))

        model.choose(1, MergeChoice.KEEP_PHONE)
        model.choose(2, MergeChoice.KEEP_BOTH)
        model.choose(2, MergeChoice.COMBINE)
        assertEquals(mapOf(1L to MergeChoice.KEEP_PHONE, 2L to MergeChoice.COMBINE), ui().choices)

        model.choose(1, null)
        assertEquals(mapOf(2L to MergeChoice.COMBINE), ui().choices)

        model.choose(99, MergeChoice.COMBINE)
        assertEquals(mapOf(2L to MergeChoice.COMBINE), ui().choices, "one that isn't listed")
    }

    @Test
    fun `Choose for selected picks for the ticked pairs, and Settle all as for every pair, replacing earlier choices`() {
        pairing(pair(1), pair(2), pair(3))
        model.togglePair(1)
        model.togglePair(3)
        model.togglePair(2)
        model.togglePair(2)
        model.togglePair(90)
        assertEquals(setOf(1L, 3L), ui().tickedPairs)
        assertEquals(emptySet<Long>(), ui().ticked, "pair ticks aren't Import's")

        model.choose(2, MergeChoice.KEEP_BOTH)
        model.chooseForSelected(MergeChoice.KEEP_PROTON)
        assertEquals(mapOf(1L to MergeChoice.KEEP_PROTON, 2L to MergeChoice.KEEP_BOTH, 3L to MergeChoice.KEEP_PROTON), ui().choices)

        model.chooseForSelected(null)
        assertEquals(mapOf(2L to MergeChoice.KEEP_BOTH), ui().choices)

        model.settleAll(MergeChoice.COMBINE)
        assertEquals(mapOf(1L to MergeChoice.COMBINE, 2L to MergeChoice.COMBINE, 3L to MergeChoice.COMBINE), ui().choices)
        model.settleAll(null)
        assertEquals(emptyMap<Long, MergeChoice>(), ui().choices)
    }

    @Test
    fun `a pair with no name or e-mail offers only Combine both and Keep Proton's, and one with a label a line can't hold only Keep Proton's`() {
        pairing(pair(1, "A"), pair(2, "B", cantImport = MappingIssueCode.NO_NAME_OR_EMAIL), pair(3, "C", cantImport = MappingIssueCode.UNWRITABLE_LABEL))

        assertEquals(
            listOf(MergeChoice.entries, listOf(MergeChoice.COMBINE, MergeChoice.KEEP_PROTON), listOf(MergeChoice.KEEP_PROTON)),
            ui().pairs.map { it.offered },
        )
        for (choice in listOf(MergeChoice.KEEP_PHONE, MergeChoice.KEEP_BOTH)) {
            model.choose(2, choice)
            model.choose(3, choice)
        }
        model.choose(3, MergeChoice.COMBINE)
        assertEquals(emptyMap<Long, MergeChoice>(), ui().choices)

        // Choose for selected and Settle all as leave a pair that doesn't offer the choice as it was.
        model.choose(2, MergeChoice.COMBINE)
        model.choose(3, MergeChoice.KEEP_PROTON)
        model.settleAll(MergeChoice.KEEP_PHONE)
        assertEquals(mapOf(1L to MergeChoice.KEEP_PHONE, 2L to MergeChoice.COMBINE, 3L to MergeChoice.KEEP_PROTON), ui().choices)
        model.togglePair(1)
        model.togglePair(2)
        model.togglePair(3)
        model.chooseForSelected(MergeChoice.KEEP_BOTH)
        assertEquals(mapOf(1L to MergeChoice.KEEP_BOTH, 2L to MergeChoice.COMBINE, 3L to MergeChoice.KEEP_PROTON), ui().choices)
        model.chooseForSelected(MergeChoice.COMBINE)
        assertEquals(mapOf(1L to MergeChoice.COMBINE, 2L to MergeChoice.COMBINE, 3L to MergeChoice.KEEP_PROTON), ui().choices)
        // Not chosen is offered by every pair.
        model.settleAll(null)
        assertEquals(emptyMap<Long, MergeChoice>(), ui().choices)
    }

    @Test
    fun `the choices dialog opens for a listed pair at its choice, for the ticked pairs, or for all, and OK picks for what it opened for`() {
        pairing(pair(1), pair(2), pair(3))
        model.openChoices(99)
        model.openChooseForSelected()
        assertNull(ui().picking, "an unlisted pair, and none ticked")

        model.choose(1, MergeChoice.KEEP_BOTH)
        model.openChoices(1)
        assertEquals(Picking.One(ui().pairs.first { it.id == 1L }, MergeChoice.KEEP_BOTH), ui().picking)
        model.pickChoice(MergeChoice.COMBINE)
        assertNull(ui().picking)
        assertEquals(mapOf(1L to MergeChoice.COMBINE), ui().choices)

        model.togglePair(2)
        model.openChooseForSelected()
        assertEquals(Picking.Selected, ui().picking)
        model.pickChoice(MergeChoice.KEEP_PROTON)
        assertEquals(mapOf(1L to MergeChoice.COMBINE, 2L to MergeChoice.KEEP_PROTON), ui().choices)

        model.openSettleAll()
        assertEquals(Picking.All, ui().picking)
        model.closeChoices()
        assertNull(ui().picking)
        model.pickChoice(MergeChoice.KEEP_BOTH)
        assertEquals(mapOf(1L to MergeChoice.COMBINE, 2L to MergeChoice.KEEP_PROTON), ui().choices, "closed, it picks nothing")

        model.openSettleAll()
        model.pickChoice(null)
        assertEquals(emptyMap<Long, MergeChoice>(), ui().choices)
    }

    @Test
    fun `no choices dialog opens while a question is open or an action runs, and Settle all as needs a pair`() {
        pairing()
        model.openSettleAll()
        assertNull(ui().picking)

        pairing(pair(1))
        model.togglePair(1)
        model.choose(1, MergeChoice.COMBINE)
        model.apply()
        model.openChoices(1)
        model.openChooseForSelected()
        model.openSettleAll()
        assertNull(ui().picking)
    }

    @Test
    fun `a read drops a pair's dialog when it lists the pair changed or not at all, and keeps it otherwise`() {
        pairing(pair(1), pair(2))
        model.openChoices(1)
        graph.importList = graph.importList.copy(pairs = listOf(pair(1), pair(2, version = 6)))
        model.refresh()
        assertEquals(1L, (ui().picking as? Picking.One)?.pair?.id)

        graph.importList = graph.importList.copy(pairs = listOf(pair(1, protonVersion = 6), pair(2)))
        model.refresh()
        assertNull(ui().picking)

        model.openChoices(2)
        graph.importList = graph.importList.copy(pairs = listOf(pair(1)))
        model.refresh()
        assertNull(ui().picking)

        model.openSettleAll()
        model.refresh()
        assertEquals(Picking.All, ui().picking)
        graph.records = listOf(AccountRecordEntity("a2", "z@example.test", LoginState.ACTIVE))
        graph.settings["a2"] = SettingsEntity("a2", SyncMode.ON)
        graph.readable += "a2"
        model.refresh()
        assertNull(ui().picking, "another account")
    }

    @Test
    fun `with none chosen, Apply asks nothing`() {
        pairing(pair(1))
        model.togglePair(1)
        model.apply()
        assertNull(ui().mergeQuestion)
    }

    @Test
    fun `Apply counts each kind in list order, and L the Keep the phone's pairs whose Proton contact shows a value the phone's doesn't`() {
        val loses = pair(1, "A", email = "a@example.test", protonEmail = "proton-only@example.test")
        val keeps = pair(2, "B", protonName = "B", email = "b@example.test", protonEmail = "B@example.test")
        val combined = pair(3, "C", email = "c@example.test", protonEmail = "proton-only@example.test")
        val both = pair(4, "D")
        val proton = pair(5, "E")
        val unchosen = pair(6, "F")
        pairing(unchosen, proton, both, combined, keeps, loses)
        model.choose(1, MergeChoice.KEEP_PHONE)
        model.choose(2, MergeChoice.KEEP_PHONE)
        model.choose(3, MergeChoice.COMBINE)
        model.choose(4, MergeChoice.KEEP_BOTH)
        model.choose(5, MergeChoice.KEEP_PROTON)

        model.apply()

        val question = checkNotNull(ui().mergeQuestion)
        assertEquals(
            listOf(loses to MergeChoice.KEEP_PHONE, keeps to MergeChoice.KEEP_PHONE, combined to MergeChoice.COMBINE, both to MergeChoice.KEEP_BOTH, proton to MergeChoice.KEEP_PROTON),
            question.chosen,
        )
        assertEquals(5, question.count)
        assertEquals(1, question.combined)
        assertEquals(1, question.keptProton)
        assertEquals(2, question.keptPhone)
        assertEquals(1, question.keptBoth)
        assertEquals(1, question.losing)
        assertTrue(graph.calls.none { it.startsWith("applyMerges") })
        assertEquals(emptyList<LogEntry>(), graph.logged)
    }

    @Test
    fun `Cancel applies nothing and records nothing, and the choices stay`() {
        pairing(pair(1))
        model.choose(1, MergeChoice.COMBINE)
        model.apply()
        model.cancelQuestion()
        assertNull(ui().mergeQuestion)
        model.confirmMerge()
        assertTrue(graph.calls.none { it.startsWith("applyMerges") })
        assertEquals(emptyList<LogEntry>(), graph.logged)
        assertEquals(mapOf(1L to MergeChoice.COMBINE), ui().choices)
    }

    @Test
    fun `Apply applies the chosen pairs in list order, records done with each kind's count, reads again, and drops the pair ticks`() {
        pairing(pair(1, "Cat"), pair(2, "Ann"), pair(3, "Bob"))
        val result = MergeResult(combined = 1, keptProton = 1, notApplied = 0, wrote = true)
        graph.mergeResult = result
        model.choose(1, MergeChoice.COMBINE)
        model.choose(2, MergeChoice.KEEP_PROTON)
        model.togglePair(3)
        model.apply()
        graph.calls.clear()

        model.confirmMerge()

        // The graph's applyMerges asks for the sync after a write; the screen asks for none itself.
        assertEquals(listOf("applyMerges a1 $NAME [2:KEEP_PROTON, 1:COMBINE]", "importList a1 $NAME"), graph.calls)
        assertEquals(Merged(chosen = 2, result), ui().message)
        assertEquals(listOf(mergeRecord(result)), graph.logged)
        assertEquals(emptySet<Long>(), ui().tickedPairs)
        assertNull(ui().mergeQuestion)
        assertNull(ui().working)
    }

    @Test
    fun `some or none applied shows the counts with the chosen count`() {
        pairing(pair(1), pair(2))
        val results = listOf(MergeResult(keptPhone = 1, notApplied = 1, wrote = true), MergeResult(notApplied = 2))
        for (result in results) {
            model.settleAll(MergeChoice.KEEP_PHONE)
            graph.mergeResult = result
            model.apply()
            model.confirmMerge()
            assertEquals(Merged(chosen = 2, result), ui().message)
        }
        assertEquals(results.map(::mergeRecord), graph.logged)
    }

    @Test
    fun `an apply that throws says it stopped, records failed, and reads again`() {
        pairing(pair(1))
        for (failure in listOf(ProviderUnavailableException(), SecurityException("made-up"), IllegalStateException("made-up"))) {
            model.choose(1, MergeChoice.COMBINE)
            graph.mergeFailure = failure
            model.apply()
            graph.calls.clear()
            model.confirmMerge()
            assertEquals(MergeStopped, ui().message)
            assertNull(ui().working)
            assertEquals(listOf("applyMerges a1 $NAME [1:COMBINE]", "importList a1 $NAME"), graph.calls)
        }
        assertEquals(List(3) { LogEntry(Operation.MERGE, AccountId("a1"), Outcome.FAILED) }, graph.logged)
    }

    @Test
    fun `a log that throws changes nothing about the merge`() {
        pairing(pair(1))
        graph.mergeResult = MergeResult(combined = 1, wrote = true)
        graph.logFailure = IllegalStateException("made-up")
        model.choose(1, MergeChoice.COMBINE)
        model.apply()
        model.confirmMerge()
        assertEquals(Merged(1, MergeResult(combined = 1, wrote = true)), ui().message)
        assertNull(ui().working)
    }

    @Test
    fun `while it applies it's applying choices, and nothing else starts or changes`() {
        pairing(pair(1), pair(2))
        graph.importList = graph.importList.copy(candidates = listOf(candidate(90)))
        val gate = CompletableDeferred<Unit>()
        graph.mergeGate = { gate.await() }
        model.choose(1, MergeChoice.COMBINE)
        model.togglePair(2)
        model.apply()
        model.confirmMerge()
        assertEquals(Working.APPLYING, ui().working)

        model.choose(1, MergeChoice.KEEP_PROTON)
        model.choose(2, MergeChoice.KEEP_PROTON)
        model.togglePair(1)
        model.togglePair(2)
        model.chooseForSelected(MergeChoice.KEEP_BOTH)
        model.settleAll(MergeChoice.KEEP_BOTH)
        model.apply()
        model.confirmMerge()
        model.toggle(90)
        model.import()
        model.confirm()
        assertEquals(mapOf(1L to MergeChoice.COMBINE), ui().choices)
        assertEquals(setOf(2L), ui().tickedPairs)
        assertEquals(emptySet<Long>(), ui().ticked)
        assertNull(ui().mergeQuestion)
        assertNull(ui().question)
        assertEquals(1, graph.calls.count { it.startsWith("applyMerges") })

        gate.complete(Unit)
        assertNull(ui().working)
    }

    @Test
    fun `while a question is open nothing else is chosen, and an import question stops Apply asking`() {
        pairing(pair(1), pair(2))
        model.choose(1, MergeChoice.COMBINE)
        model.apply()
        model.choose(2, MergeChoice.KEEP_PROTON)
        model.settleAll(MergeChoice.KEEP_BOTH)
        model.togglePair(2)
        model.toggle(90)
        model.import()
        assertEquals(mapOf(1L to MergeChoice.COMBINE), ui().choices)
        assertEquals(emptySet<Long>(), ui().tickedPairs)
        assertNull(ui().question)

        model.cancelQuestion()
        model.toggle(90)
        model.import()
        model.apply()
        assertNull(ui().mergeQuestion)
        model.confirmMerge()
        assertTrue(graph.calls.none { it.startsWith("applyMerges") })
    }

    @Test
    fun `once started, the apply runs to its end when the screen goes away, then shows its result`() {
        pairing(pair(1))
        graph.mergeResult = MergeResult(keptBoth = 1, wrote = true)
        val gate = CompletableDeferred<Unit>()
        graph.mergeGate = { gate.await() }
        model.choose(1, MergeChoice.KEEP_BOTH)
        model.apply()
        model.confirmMerge()

        scope.cancel()
        gate.complete(Unit)

        assertEquals(listOf(mergeRecord(MergeResult(keptBoth = 1, wrote = true))), graph.logged)
        assertEquals(Merged(1, MergeResult(keptBoth = 1, wrote = true)), ui().message)
        assertNull(ui().working)
    }

    @Test
    fun `a read keeps the choices and ticks of pairs still listed with the same rows at the same VERSIONs, and drops the others`() {
        pairing(pair(1), pair(2), pair(3), pair(4), pair(5))
        model.settleAll(MergeChoice.COMBINE)
        (1L..5L).forEach(model::togglePair)
        graph.importList = graph.importList.copy(
            pairs = listOf(
                pair(1),
                pair(2, version = 6),
                pair(3, protonVersion = 6),
                pair(4, protonId = 444),
                // 5 is no longer a pair, and 6 is new.
                pair(6),
            ),
        )
        model.refresh()

        assertEquals(mapOf(1L to MergeChoice.COMBINE), ui().choices)
        assertEquals(setOf(1L, 2L, 3L, 4L), ui().tickedPairs)
    }

    @Test
    fun `a read that shows another account, or none, drops the choices, the pair ticks, and the question`() {
        for (after in listOf(listOf(AccountRecordEntity("a2", "z@example.test", LoginState.ACTIVE)), emptyList())) {
            pairing(pair(1))
            model.choose(1, MergeChoice.COMBINE)
            model.togglePair(1)
            model.apply()
            graph.records = after
            graph.settings["a2"] = SettingsEntity("a2", SyncMode.ON)
            graph.readable += "a2"
            model.refresh()
            assertNull(ui().mergeQuestion)
            assertEquals(emptyMap<Long, MergeChoice>(), ui().choices)
            assertEquals(emptySet<Long>(), ui().tickedPairs)
            model.confirmMerge()
        }
        assertTrue(graph.calls.none { it.startsWith("applyMerges") })
    }

    @Test
    fun `a read while the question is open asks about the choices still kept, and drops it when none are left`() {
        pairing(pair(1, "Ann"), pair(2, "Bob"))
        model.choose(1, MergeChoice.COMBINE)
        model.choose(2, MergeChoice.KEEP_PROTON)
        model.apply()
        graph.importList = graph.importList.copy(pairs = listOf(pair(1, "Ann", version = 6), pair(2, "Bob")))
        model.refresh()

        assertEquals(listOf(pair(2, "Bob") to MergeChoice.KEEP_PROTON), ui().mergeQuestion?.chosen)
        graph.calls.clear()
        model.confirmMerge()
        assertEquals(listOf("applyMerges a1 $NAME [2:KEEP_PROTON]", "importList a1 $NAME"), graph.calls)

        model.choose(1, MergeChoice.COMBINE)
        model.apply()
        graph.importList = graph.importList.copy(pairs = emptyList())
        model.refresh()
        assertNull(ui().mergeQuestion)
    }

    @Test
    fun `the merge result stays across reads and goes when Apply asks again`() {
        pairing(pair(1))
        graph.mergeResult = MergeResult(combined = 1)
        model.choose(1, MergeChoice.COMBINE)
        model.apply()
        model.confirmMerge()
        model.refresh()
        assertEquals(Merged(1, MergeResult(combined = 1)), ui().message)

        model.choose(1, MergeChoice.COMBINE)
        model.apply()
        assertNull(ui().message)
    }

    @Test
    fun `a listed pair shows neither name in its text`() {
        pairing(pair(1, "Made Up Name", "Made Up Proton Name"))
        val shown = ui().toString() + ui().pairs.single().toString()
        assertFalse(shown.contains("Made Up Name"), shown)
        assertFalse(shown.contains("Made Up Proton Name"), shown)
    }

    // Removing originals: the section, the question, the results, and the record.

    /** Lists [originals] for a signed-in account with sync on, with one candidate, and reads. */
    private fun removing(vararg originals: ImportedOriginal) {
        account()
        graph.importList = ImportList(listOf(candidate(90, "Zed")), emptyList(), originals.toList())
        model.refresh()
    }

    private fun removeRecord(removed: Int, notRemoved: Int) =
        LogEntry(Operation.REMOVE_ORIGINALS, AccountId("a1"), Outcome.DONE, mapOf(RemoveCount.REMOVED to removed, RemoveCount.NOT_REMOVED to notRemoved))

    /** The last report, with the bulk guard holding back deletions on the phone when [waiting]. */
    private fun deletionsWaiting(waiting: Boolean) {
        graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 5_000, true, null, 0, 0, 15, 0, 0, 0, 0, bulkGuardRemote = waiting, bulkGuardPhone = !waiting, 0, 0, 0)
    }

    private fun originalIds() = ui().originals.map { it.id }

    @Test
    fun `originals are sorted by name, with no name last, each with its state`() {
        removing(
            original(1, name = "cat"),
            original(2, OriginalState.CHANGED, name = null),
            original(3, OriginalState.HOLDS_MORE, name = "Bob"),
            original(4, name = "  "),
            original(5, name = "Ann"),
        )
        assertEquals(listOf(5L, 3L, 1L, 2L, 4L), originalIds())
        assertEquals(listOf("Ann", "Bob", "cat", null, null), ui().originals.map { it.name })
        assertEquals(
            listOf(OriginalState.REMOVABLE, OriginalState.HOLDS_MORE, OriginalState.REMOVABLE, OriginalState.CHANGED, OriginalState.REMOVABLE),
            ui().originals.map { it.state },
        )
    }

    @Test
    fun `Remove originals counts the removable ones and asks about them in list order, saying the others stay only when some do`() {
        removing(original(1, name = "Cat"), original(2, OriginalState.CHANGED), original(3, name = "Ann"), original(4, OriginalState.HOLDS_MORE))
        assertEquals(2, ui().removable)

        model.removeOriginals()

        assertEquals(RemoveQuestion(listOf(original(3, name = "Ann"), original(1, name = "Cat")), othersStay = true), ui().removeQuestion)
        assertEquals(2, ui().removeQuestion?.count)

        model.cancelQuestion()
        removing(original(1), original(3))
        model.removeOriginals()
        assertEquals(RemoveQuestion(listOf(original(1), original(3)), othersStay = false), ui().removeQuestion)
    }

    @Test
    fun `with none removable, Remove originals asks nothing`() {
        removing(original(1, OriginalState.CHANGED), original(2, OriginalState.HOLDS_MORE))
        assertEquals(0, ui().removable)
        model.removeOriginals()
        assertNull(ui().removeQuestion)
    }

    @Test
    fun `Cancel removes nothing and records nothing`() {
        removing(original(1))
        model.removeOriginals()
        model.cancelQuestion()
        assertNull(ui().removeQuestion)
        model.confirmRemove()
        assertTrue(graph.calls.none { it.startsWith("removeOriginals") })
        assertEquals(emptyList<LogEntry>(), graph.logged)
    }

    @Test
    fun `Remove removes the removable originals in list order, records done with the counts, and reads again`() {
        removing(original(1, name = "Cat"), original(2, OriginalState.CHANGED, name = "Ann"), original(3, name = "Bob"))
        graph.removeResult = ActionResult(done = 2, notDone = 0)
        model.removeOriginals()
        graph.calls.clear()

        model.confirmRemove()

        // The copies and Proton are untouched: the graph asks for no sync, nor does the screen.
        assertEquals(listOf("importList a1 $NAME", "removeOriginals a1 $NAME [3, 1]", "importList a1 $NAME"), graph.calls)
        assertEquals(Removed(asked = 2, ActionResult(2, 0)), ui().message)
        assertEquals(listOf(removeRecord(2, 0)), graph.logged)
        assertNull(ui().removeQuestion)
        assertNull(ui().working)
    }

    @Test
    fun `some or none removed shows the counts with the count asked about`() {
        removing(original(1), original(2))
        val results = listOf(ActionResult(1, 1), ActionResult(0, 2))
        for (result in results) {
            graph.removeResult = result
            model.removeOriginals()
            model.confirmRemove()
            assertEquals(Removed(asked = 2, result), ui().message)
        }
        assertEquals(results.map { removeRecord(it.done, it.notDone) }, graph.logged)
    }

    @Test
    fun `a removal that throws says it stopped, records failed, and reads again`() {
        removing(original(1))
        for (failure in listOf(ProviderUnavailableException(), SecurityException("made-up"), IllegalStateException("made-up"))) {
            graph.removeFailure = failure
            model.removeOriginals()
            graph.calls.clear()
            model.confirmRemove()
            assertEquals(RemoveStopped, ui().message)
            assertNull(ui().working)
            assertEquals(listOf("importList a1 $NAME", "removeOriginals a1 $NAME [1]", "importList a1 $NAME"), graph.calls)
        }
        assertEquals(List(3) { LogEntry(Operation.REMOVE_ORIGINALS, AccountId("a1"), Outcome.FAILED) }, graph.logged)
    }

    @Test
    fun `a log that throws changes nothing about the removal`() {
        removing(original(1))
        graph.removeResult = ActionResult(1, 0)
        graph.logFailure = IllegalStateException("made-up")
        model.removeOriginals()
        model.confirmRemove()
        assertEquals(Removed(1, ActionResult(1, 0)), ui().message)
        assertNull(ui().working)
    }

    @Test
    fun `while it removes it's removing originals, and nothing else starts or changes`() {
        account()
        graph.importList = ImportList(listOf(candidate(90)), listOf(pair(2)), listOf(original(1)))
        model.refresh()
        val gate = CompletableDeferred<Unit>()
        graph.removeGate = { gate.await() }
        model.removeOriginals()
        model.confirmRemove()
        assertEquals(Working.REMOVING, ui().working)

        model.removeOriginals()
        model.confirmRemove()
        model.toggle(90)
        model.import()
        model.choose(2, MergeChoice.COMBINE)
        model.togglePair(2)
        model.apply()
        model.openSettleAll()
        assertEquals(emptySet<Long>(), ui().ticked)
        assertEquals(emptyMap<Long, MergeChoice>(), ui().choices)
        assertEquals(emptySet<Long>(), ui().tickedPairs)
        assertNull(ui().removeQuestion)
        assertNull(ui().question)
        assertNull(ui().mergeQuestion)
        assertNull(ui().picking)
        assertEquals(1, graph.calls.count { it.startsWith("removeOriginals") })

        gate.complete(Unit)
        assertNull(ui().working)
    }

    @Test
    fun `while its question is open nothing else starts, and another question stops it asking`() {
        account()
        graph.importList = ImportList(listOf(candidate(90)), listOf(pair(2)), listOf(original(1)))
        model.refresh()
        model.removeOriginals()
        model.toggle(90)
        model.import()
        model.choose(2, MergeChoice.COMBINE)
        model.openSettleAll()
        assertEquals(emptySet<Long>(), ui().ticked)
        assertEquals(emptyMap<Long, MergeChoice>(), ui().choices)
        assertNull(ui().picking)
        assertNull(ui().question)

        model.cancelQuestion()
        model.toggle(90)
        model.import()
        model.removeOriginals()
        assertNull(ui().removeQuestion)
        model.confirmRemove()
        assertTrue(graph.calls.none { it.startsWith("removeOriginals") })
    }

    @Test
    fun `once started, the removal runs to its end when the screen goes away, then shows its result`() {
        removing(original(1))
        graph.removeResult = ActionResult(1, 0)
        val gate = CompletableDeferred<Unit>()
        graph.removeGate = { gate.await() }
        model.removeOriginals()
        model.confirmRemove()

        scope.cancel()
        gate.complete(Unit)

        assertEquals(listOf(removeRecord(1, 0)), graph.logged)
        assertEquals(Removed(1, ActionResult(1, 0)), ui().message)
        assertNull(ui().working)
    }

    @Test
    fun `a read while the question is open asks about the removable originals as read, and drops it when none are left`() {
        removing(original(1, name = "Ann"), original(2, name = "Bob"))
        model.removeOriginals()
        graph.importList = graph.importList.copy(originals = listOf(original(1, OriginalState.CHANGED, name = "Ann"), original(2, name = "Bob")))
        model.refresh()

        assertEquals(RemoveQuestion(listOf(original(2, name = "Bob")), othersStay = true), ui().removeQuestion)
        graph.calls.clear()
        model.confirmRemove()
        assertEquals(listOf("importList a1 $NAME", "removeOriginals a1 $NAME [2]", "importList a1 $NAME"), graph.calls)

        model.removeOriginals()
        graph.importList = graph.importList.copy(originals = emptyList())
        model.refresh()
        assertNull(ui().removeQuestion)
    }

    @Test
    fun `Remove reads again first, and removes only the originals asked about still removable, as read then, counting the others as not removed`() {
        removing(original(1, name = "Ann"), original(2, name = "Bob"), original(3, name = "Cat"))
        model.removeOriginals()
        val moved = original(2, name = "Bob").let { it.copy(copy = it.copy.copy(version = 6)) }
        // 1 changed, 2's copy moved on, 3 is gone, and 4 is new: only 2 is removed, its copy at the VERSION read now.
        graph.importList = graph.importList.copy(originals = listOf(original(1, OriginalState.CHANGED, name = "Ann"), moved, original(4, name = "Dan")))
        graph.removeResult = ActionResult(done = 1, notDone = 0)
        graph.calls.clear()

        model.confirmRemove()

        assertEquals(listOf("importList a1 $NAME", "removeOriginals a1 $NAME [2]", "importList a1 $NAME"), graph.calls)
        assertEquals(listOf(listOf(moved)), graph.removedOriginals)
        assertEquals(Removed(asked = 3, ActionResult(done = 1, notDone = 2)), ui().message)
        assertEquals(listOf(removeRecord(1, 2)), graph.logged)
    }

    @Test
    fun `Remove with none of them still removable tries none and says so`() {
        removing(original(1), original(2))
        model.removeOriginals()
        graph.importList = graph.importList.copy(originals = listOf(original(1, OriginalState.CHANGED), original(2, OriginalState.HOLDS_MORE)))

        model.confirmRemove()

        assertTrue(graph.calls.none { it.startsWith("removeOriginals") })
        assertEquals(Removed(asked = 2, ActionResult(done = 0, notDone = 2), noneLeft = true), ui().message)
        assertEquals(listOf(removeRecord(0, 2)), graph.logged)
    }

    @Test
    fun `Remove whose read lists nothing removes nothing, records nothing, and shows why it lists nothing`() {
        // As for another account (the next test): nothing removed, nothing recorded.
        val failing: List<Pair<() -> Unit, Unlisted>> = listOf(
            { graph.importListFailure = ProviderUnavailableException() } to Unlisted.COULDNT_READ,
            { graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN) } to Unlisted.NEEDS_SYNC_ON,
            { graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.SIGNED_OUT)) } to Unlisted.LOG_IN,
        )
        for ((fail, why) in failing) {
            removing(original(1))
            model.removeOriginals()
            fail()

            model.confirmRemove()

            assertEquals(why, ui().unlisted)
            assertNull(ui().working)
            assertNull(ui().message)
            graph.importListFailure = null
        }
        assertTrue(graph.calls.none { it.startsWith("removeOriginals") })
        assertEquals(emptyList<LogEntry>(), graph.logged)
    }

    @Test
    fun `Remove that finds deletions now waiting, or another account, removes nothing and records nothing`() {
        removing(original(1))
        model.removeOriginals()
        deletionsWaiting(true)

        model.confirmRemove()

        assertTrue(ui().removalWaits)
        assertNull(ui().working)
        assertNull(ui().message)

        deletionsWaiting(false)
        model.refresh()
        model.removeOriginals()
        graph.records = listOf(AccountRecordEntity("a2", "z@example.test", LoginState.ACTIVE))
        graph.settings["a2"] = SettingsEntity("a2", SyncMode.ON)
        graph.readable += "a2"
        model.confirmRemove()

        assertNull(ui().working)
        assertNull(ui().message)
        assertTrue(graph.calls.none { it.startsWith("removeOriginals") })
        assertEquals(emptyList<LogEntry>(), graph.logged)
    }

    @Test
    fun `a read that shows another account, or none, drops the question`() {
        for (after in listOf(listOf(AccountRecordEntity("a2", "z@example.test", LoginState.ACTIVE)), emptyList())) {
            removing(original(1))
            model.removeOriginals()
            graph.records = after
            graph.settings["a2"] = SettingsEntity("a2", SyncMode.ON)
            graph.readable += "a2"
            model.refresh()
            assertNull(ui().removeQuestion)
            model.confirmRemove()
        }
        assertTrue(graph.calls.none { it.startsWith("removeOriginals") })
    }

    @Test
    fun `while the bulk guard holds back deletions on the phone, Remove originals waits, and the originals stay listed`() {
        deletionsWaiting(true)
        removing(original(1), original(2, OriginalState.CHANGED))

        assertTrue(ui().removalWaits)
        assertEquals(listOf(1L, 2L), originalIds())
        model.removeOriginals()
        assertNull(ui().removeQuestion)

        // The guard on Proton's side doesn't hold it back.
        deletionsWaiting(false)
        model.refresh()
        assertFalse(ui().removalWaits)
        model.removeOriginals()
        assertEquals(listOf(original(1)), ui().removeQuestion?.originals)

        // A read that finds it waiting drops an open question.
        deletionsWaiting(true)
        model.refresh()
        assertNull(ui().removeQuestion)
        model.confirmRemove()
        assertTrue(graph.calls.none { it.startsWith("removeOriginals") })
    }

    @Test
    fun `the removal result stays across reads and goes when Remove originals asks again`() {
        removing(original(1))
        graph.removeResult = ActionResult(1, 0)
        model.removeOriginals()
        model.confirmRemove()
        model.refresh()
        assertEquals(Removed(1, ActionResult(1, 0)), ui().message)

        model.removeOriginals()
        assertNull(ui().message)
    }

    @Test
    fun `a listed original shows no name in its text`() {
        removing(original(1, name = "Made Up Name"))
        val shown = ui().toString() + ui().originals.single().toString()
        assertFalse(shown.contains("Made Up Name"), shown)
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
    }
}
