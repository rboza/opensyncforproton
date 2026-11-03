package dev.rboza.android.opensyncforproton.importing

import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.backOnDialog
import dev.rboza.android.opensyncforproton.dialogNodes
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.screenNodes
import dev.rboza.android.opensyncforproton.settings.SettingsModel
import dev.rboza.android.opensyncforproton.settings.SettingsScreen
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
import dev.rboza.android.opensyncforproton.sync.provider.ReadContact
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The settings row, and the import screen's top bar, headings, marks, question, and results in their exact words
 * (docs/sync.md § Import from this phone). A tall window, so the lazy list composes every row. Names are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp")
class ImportScreenTest {
    private val graph = FakeAppGraph().apply {
        records = listOf(AccountRecordEntity("a1", NAME, LoginState.ACTIVE))
        settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60, 15)
        readable += "a1"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = ImportModel(graph, scope)
    private lateinit var controller: ActivityController<ComponentActivity>
    private var closed = 0
    private var opened = 0

    @After
    fun end() {
        controller.pause().stop().destroy()
        idle()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun show() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { ImportScreen(model, onClose = { closed++ }) }
        idle()
    }

    private fun showSettings() {
        val settings = SettingsModel(graph, scope)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { SettingsScreen(settings, onClose = {}, onOpenImport = { opened++ }) }
        idle()
    }

    private fun nodes(): List<SemanticsNode> = screenNodes(controller.get())

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun dialogTexts(): List<String> = dialogNodes().flatMap { it.texts() }

    private fun click(node: SemanticsNode) {
        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        idle()
    }

    /** Clicks the one node with [text] that can be clicked, the dialog's when [inDialog]. */
    private fun click(text: String, inDialog: Boolean = false) =
        click((if (inDialog) dialogNodes() else nodes()).single { text in it.texts() && SemanticsActions.OnClick in it.config })

    private fun contact(id: Long, name: String?, email: String? = null, phone: String? = null): ReadContact {
        val rows = listOfNotNull(
            phone?.let { DataRow(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to ColumnValue.Text(it))) },
            email?.let { DataRow(Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to ColumnValue.Text(it))) },
        )
        return ReadContact(DeviceContact(id, null, false, false, 5, name), rows)
    }

    private fun candidate(id: Long, name: String? = "Contact $id", mark: ImportMark = ImportMark.NONE, cantImport: MappingIssueCode? = null, unmapped: Boolean = false) =
        ImportCandidate(contact(id, name), mark, cantImport, unmapped)

    private fun pair(
        id: Long,
        name: String? = "Pair $id",
        protonName: String? = "Proton $id",
        reason: MatchReason = MatchReason.NAME,
        cantImport: MappingIssueCode? = null,
        email: String? = null,
        protonEmail: String? = null,
    ) = MergePair(contact(id, name, email), contact(100 + id, protonName, protonEmail), reason, cantImport)

    private fun original(id: Long, state: OriginalState = OriginalState.REMOVABLE, name: String? = "Original $id") =
        ImportedOriginal(contact(id, name).row, 5, contact(200 + id, "Copy $id").row.copy(sourceId = "c$id"), state)

    private fun list(vararg candidates: ImportCandidate) {
        graph.importList = ImportList(candidates.toList(), emptyList(), emptyList())
    }

    /** Ticks every contact listed by [names], then taps Import. */
    private fun ask(vararg names: String) {
        for (name in names) click(name)
        click(IMPORT)
    }

    /** Lists [count] contacts, ticks them all, imports with [result] (or [failure]), and returns the screen's words. */
    private fun import(count: Int, result: ActionResult, failure: Exception? = null): List<String> {
        list(*(1..count).map { candidate(it.toLong()) }.toTypedArray())
        model.refresh()
        idle()
        graph.importResult = result
        graph.importFailure = failure
        click("Select all")
        click(IMPORT)
        click(IMPORT, inDialog = true)
        return texts()
    }

    @Test
    fun `the settings row and its line sit above Keep a copy, and open the import screen`() {
        showSettings()
        val all = texts()
        assertEquals(all.toString(), all.indexOf(ROW) + 1, all.indexOf(DETAIL))
        assertTrue(all.toString(), all.indexOf("Account") in 0 until all.indexOf(ROW))
        assertTrue(all.toString(), all.indexOf(DETAIL) < all.indexOf("Keep a copy on this phone"))

        click(ROW)
        assertEquals(1, opened)
    }

    @Test
    fun `the settings row is greyed out while a settings action runs`() {
        val gate = CompletableDeferred<Unit>()
        graph.rowsGate = { gate.await() }
        showSettings()
        click("Keep a copy on this phone")
        val row = nodes().single { ROW in it.texts() }
        assertTrue(SemanticsProperties.Disabled in row.config)
        gate.complete(Unit)
        idle()
    }

    @Test
    fun `the settings row is offered in every state with an account, in every mode`() {
        for ((state, readable) in listOf(LoginState.ACTIVE to true, LoginState.ACTIVE to false, LoginState.SIGNED_OUT to true)) {
            for (mode in SyncMode.entries) {
                graph.records = listOf(AccountRecordEntity("a1", NAME, state))
                graph.settings["a1"] = SettingsEntity("a1", mode)
                if (readable) graph.readable += "a1" else graph.readable -= "a1"
                showSettings()
                assertTrue("$state $readable $mode", ROW in texts())
                controller.pause().stop().destroy()
                idle()
            }
        }
        graph.records = emptyList()
        showSettings()
        assertTrue(ROW !in texts())
    }

    @Test
    fun `the top bar has the screen's name and a back arrow that closes it`() {
        list(candidate(1))
        show()
        assertTrue(texts().toString(), ROW in texts())
        click(nodes().single { config(it, SemanticsProperties.ContentDescription) == listOf("Back") })
        assertEquals(1, closed)
    }

    @Test
    fun `the headings have their counts, with the caution under Imported originals`() {
        graph.importList = ImportList(listOf(candidate(1), candidate(2), candidate(3)), listOf(pair(4), pair(5)), listOf(original(1)))
        show()
        val all = texts()
        val headings = listOf("On this phone only (3)", "Already in Proton (2)", "Imported originals (1)", CAUTION)
        assertEquals(all.toString(), headings, all.filter { it in headings })
        assertEquals(all.toString(), all.indexOf("Imported originals (1)") + 1, all.indexOf(CAUTION))
    }

    @Test
    fun `each contact shows its name, its first e-mail or else its number, and its mark's words`() {
        graph.importList = ImportList(
            listOf(
                ImportCandidate(contact(1, "Ann", email = "ann@example.test", phone = "+1 555 0101"), ImportMark.NONE, null, false),
                ImportCandidate(contact(2, "Bob", phone = "+1 555 0102"), ImportMark.IMPORTED_BEFORE, null, false),
                candidate(3, "Cat", ImportMark.LOOKS_IN_PROTON),
                candidate(4, "Dan", cantImport = MappingIssueCode.NO_NAME_OR_EMAIL),
                candidate(5, "Eve", cantImport = MappingIssueCode.UNWRITABLE_LABEL),
                candidate(6, null),
            ),
            emptyList(),
            emptyList(),
        )
        show()
        val all = texts()
        assertEquals(
            all.toString(),
            listOf(
                "Ann", "ann@example.test",
                "Bob", "+1 555 0102", "Imported before",
                "Cat", "Looks like it's already in Proton",
                "Dan", "Can't be imported: it needs a name or an e-mail address.",
                "Eve", "Can't be imported: one of its labels can't be saved to Proton.",
                "No name",
            ),
            all.subList(all.indexOf("Ann"), all.indexOf("No name") + 1),
        )
    }

    @Test
    fun `contacts are ticked by tapping them, those that can't be imported are greyed out, and Import waits for a tick`() {
        list(candidate(1, "Ann"), candidate(2, "Bob", cantImport = MappingIssueCode.NO_NAME_OR_EMAIL))
        show()
        fun row(name: String) = nodes().single { name in it.texts() && SemanticsActions.OnClick in it.config }
        fun state(name: String) = config(row(name), SemanticsProperties.ToggleableState)
        assertEquals(ToggleableState.Off, state("Ann"))
        assertTrue(SemanticsProperties.Disabled in row("Bob").config)
        assertTrue(SemanticsProperties.Disabled !in row("Ann").config)
        assertTrue(SemanticsProperties.Disabled in nodes().single { IMPORT in it.texts() }.config)

        click("Ann")
        assertEquals(ToggleableState.On, state("Ann"))
        assertTrue(SemanticsProperties.Disabled !in nodes().single { IMPORT in it.texts() }.config)
    }

    @Test
    fun `the question's exact words, with Cancel and Import`() {
        list(candidate(1, "Ann", unmapped = true), candidate(2, "Bob", unmapped = true), candidate(3, "Cat"))
        show()
        ask("Ann", "Bob", "Cat")
        assertEquals(listOf("Import 3 contacts to Proton?", FIRST, "2 of them have $UNMAPPED", "Cancel", IMPORT), dialogTexts())
    }

    @Test
    fun `one contact is asked about as one, and one with unmapped fields as one`() {
        list(candidate(1, "Ann", unmapped = true), candidate(2, "Bob"))
        show()
        ask("Ann")
        assertEquals(listOf("Import 1 contact to Proton?", FIRST, "1 of them has $UNMAPPED", "Cancel", IMPORT), dialogTexts())
    }

    @Test
    fun `without unmapped fields there's no second paragraph`() {
        list(candidate(1, "Ann"), candidate(2, "Bob"))
        show()
        ask("Ann", "Bob")
        assertEquals(listOf("Import 2 contacts to Proton?", FIRST, "Cancel", IMPORT), dialogTexts())
    }

    @Test
    fun `Cancel and Back close the question and import nothing, and Back then closes the screen`() {
        list(candidate(1, "Ann"))
        show()
        ask("Ann")
        click("Cancel", inDialog = true)
        assertEquals(emptyList<String>(), dialogTexts())
        click(IMPORT)
        backOnDialog()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())
        assertTrue(graph.calls.toString(), graph.calls.none { it.startsWith("importContacts") })
        assertEquals(0, closed)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(1, closed)
    }

    @Test
    fun `while it imports the screen says so, and Back and the arrow wait`() {
        list(candidate(1, "Ann"))
        show()
        val gate = CompletableDeferred<Unit>()
        graph.importGate = { gate.await() }
        ask("Ann")
        click(IMPORT, inDialog = true)
        assertTrue(texts().toString(), "Importing contacts…" in texts())

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        click(nodes().single { config(it, SemanticsProperties.ContentDescription) == listOf("Back") })
        assertEquals(0, closed)

        gate.complete(Unit)
        idle()
        assertTrue(texts().toString(), "Importing contacts…" !in texts())
    }

    @Test
    fun `all copied`() {
        show()
        assertTrue(texts().toString(), "Copied 3 contacts. They're sent to Proton at the next sync." in import(3, ActionResult(3, 0)))
        assertTrue(texts().toString(), "Copied 1 contact. It's sent to Proton at the next sync." in import(1, ActionResult(1, 0)))
    }

    @Test
    fun `some not copied`() {
        show()
        assertTrue(texts().toString(), "Copied 1 of 3 contacts; 2 couldn't be copied." in import(3, ActionResult(1, 2)))
    }

    @Test
    fun `none copied, with the Contact Scopes hint`() {
        show()
        assertTrue(texts().toString(), "Couldn't copy any of the 3 contacts. $SCOPES" in import(3, ActionResult(0, 3)))
        assertTrue(texts().toString(), "Couldn't copy the contact. $SCOPES" in import(1, ActionResult(0, 1)))
    }

    @Test
    fun `an import that stopped`() {
        show()
        assertTrue(
            texts().toString(),
            "Import stopped. Some contacts may already be copied; check this screen before trying again." in
                import(2, ActionResult(0, 0), IllegalStateException("made-up")),
        )
    }

    @Test
    fun `what it says instead of a list`() {
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN)
        show()
        assertTrue(texts().toString(), "Import needs sync on. Turn off dry run on the main screen first." in texts())
        assertTrue(texts().toString(), texts().none { it.startsWith("On this phone only") })

        graph.readable -= "a1"
        model.refresh()
        idle()
        assertTrue(texts().toString(), "Log in to import." in texts())

        graph.readable += "a1"
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON)
        graph.importListFailure = SecurityException("made-up")
        model.refresh()
        idle()
        assertTrue(texts().toString(), "Couldn't read this phone's contacts." in texts())

        graph.importListFailure = null
        list()
        model.refresh()
        idle()
        assertTrue(texts().toString(), "There are no contacts in this phone's own contacts to import. $SCOPES" in texts())
    }

    // The Merge section: a pair's tick, and its choices in a dialog opened by tapping it.

    private fun pairs(vararg pairs: MergePair) {
        graph.importList = ImportList(emptyList(), pairs.toList(), emptyList())
    }

    /** A pair's tick: its checkbox, named after the phone contact. */
    private fun tick(name: String) = nodes().single { config(it, SemanticsProperties.ContentDescription) == listOf(name) && SemanticsProperties.ToggleableState in it.config }

    private fun disabled(text: String) = SemanticsProperties.Disabled in nodes().single { text in it.texts() && SemanticsActions.OnClick in it.config }.config

    /** Picks [choice] in the open choices dialog, then OK. */
    private fun pick(choice: String) {
        click(choice, inDialog = true)
        click("OK", inDialog = true)
    }

    /** Taps each pair by its phone contact's [name] and picks its [choice]. */
    private fun choose(vararg chosen: Pair<String, String>) {
        for ((name, choice) in chosen) {
            click(name)
            pick(choice)
        }
    }

    /**
     * Lists [count] pairs, settles them all as Keep Proton's, applies with [result] (or [failure]), and returns the
     * words.
     */
    private fun merge(count: Int, result: MergeResult, failure: Exception? = null): List<String> {
        pairs(*(1..count).map { pair(it.toLong()) }.toTypedArray())
        model.refresh()
        idle()
        graph.mergeResult = result
        graph.mergeFailure = failure
        click(SETTLE_ALL)
        pick(KEEP_PROTON)
        click(APPLY)
        click(APPLY, inDialog = true)
        return texts()
    }

    @Test
    fun `each pair shows both names, its reason's words, and its choice, under the buttons`() {
        pairs(
            pair(1, "Ann", "Ann Lee", MatchReason.SAME_CONTACT),
            pair(2, "Bob", "Robert", MatchReason.EMAIL),
            pair(3, "Cat", null, MatchReason.PHONE),
            pair(4, null, "Dan", MatchReason.NAME),
        )
        show()
        val all = texts()
        assertEquals(
            all.toString(),
            listOf(
                "Already in Proton (4)", CHOOSE_SELECTED, SETTLE_ALL, APPLY,
                "Ann", "Proton: Ann Lee", "Android shows them as one contact", "Not chosen",
                "Bob", "Proton: Robert", "Shares an e-mail address", "Not chosen",
                "Cat", "Proton: No name", "Shares a phone number", "Not chosen",
                "No name", "Proton: Dan", "Same name", "Not chosen",
                "Imported originals (0)",
            ),
            all.subList(all.indexOf("Already in Proton (4)"), all.indexOf("Imported originals (0)") + 1),
        )
    }

    @Test
    fun `tapping a pair shows the choices it offers and Not chosen, and OK picks one`() {
        pairs(pair(1, "Ann"), pair(2, "Bob", cantImport = MappingIssueCode.NO_NAME_OR_EMAIL), pair(3, "Cat", cantImport = MappingIssueCode.UNWRITABLE_LABEL))
        show()

        click("Ann")
        assertEquals(listOf("Ann", NOT_CHOSEN, COMBINE, KEEP_PROTON, KEEP_PHONE, KEEP_BOTH, "Cancel", "OK"), dialogTexts())
        pick(KEEP_PHONE)
        assertEquals(emptyList<String>(), dialogTexts())
        click("Bob")
        assertEquals(listOf("Bob", NOT_CHOSEN, COMBINE, KEEP_PROTON, "Cancel", "OK"), dialogTexts())
        click("Cancel", inDialog = true)
        assertEquals(emptyList<String>(), dialogTexts())
        click("Cat")
        assertEquals(listOf("Cat", NOT_CHOSEN, KEEP_PROTON, "Cancel", "OK"), dialogTexts())
        backOnDialog()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())

        val all = texts()
        assertEquals(all.toString(), KEEP_PHONE, all[all.indexOf("Ann") + 3])
        assertEquals(all.toString(), NOT_CHOSEN, all[all.indexOf("Bob") + 3])
        click("Ann")
        pick(NOT_CHOSEN)
        assertEquals(NOT_CHOSEN, texts()[texts().indexOf("Ann") + 3])
    }

    @Test
    fun `a pair opens its choices as a button, and Settle all as… waits for a pair`() {
        list(candidate(1, "Zed"))
        show()
        assertTrue(disabled(SETTLE_ALL))

        pairs(pair(1, "Ann"))
        model.refresh()
        idle()
        assertEquals(Role.Button, config(nodes().single { "Ann" in it.texts() && SemanticsActions.OnClick in it.config }, SemanticsProperties.Role))
        assertTrue(!disabled(SETTLE_ALL))
    }

    @Test
    fun `a pair's choice dialog closes when a read lists the pair changed or not at all`() {
        pairs(pair(1, "Ann"), pair(2, "Bob"))
        show()
        click("Ann")
        // A read lists Ann's pair with its Proton row at a new VERSION.
        val (ann, bob) = graph.importList.pairs
        pairs(ann.copy(proton = ann.proton.copy(row = ann.proton.row.copy(version = 6))), bob)
        model.refresh()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())

        click("Bob")
        pairs(graph.importList.pairs.first())
        model.refresh()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())

        // A read that keeps the pair as it was keeps its dialog, though another pair is listed.
        click("Ann")
        pairs(graph.importList.pairs.first(), bob)
        model.refresh()
        idle()
        assertEquals("Ann", dialogTexts().first())

        // One closed by a read stays closed when the pair is listed again as it was.
        val listed = graph.importList.pairs
        pairs(bob)
        model.refresh()
        idle()
        pairs(*listed.toTypedArray())
        model.refresh()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())
    }

    @Test
    fun `a pair's choice dialog starts at its choice`() {
        pairs(pair(1, "Ann"))
        show()
        choose("Ann" to COMBINE)
        click("Ann")
        val selected = dialogNodes().filter { config(it, SemanticsProperties.Selected) == true }.flatMap { it.texts() }
        assertEquals(listOf(COMBINE), selected)
    }

    @Test
    fun `ticks pick for Choose for selected, Settle all as picks for every pair, and each waits for what it needs`() {
        pairs(pair(1, "Ann"), pair(2, "Bob"), pair(3, "Cat"))
        show()
        assertTrue(disabled(CHOOSE_SELECTED))
        assertTrue(disabled(APPLY))
        assertTrue(!disabled(SETTLE_ALL))
        assertEquals(ToggleableState.Off, config(tick("Ann"), SemanticsProperties.ToggleableState))

        click(tick("Ann"))
        click(tick("Cat"))
        assertEquals(ToggleableState.On, config(tick("Ann"), SemanticsProperties.ToggleableState))
        assertTrue(!disabled(CHOOSE_SELECTED))
        click(CHOOSE_SELECTED)
        assertEquals(listOf("Choose for selected", NOT_CHOSEN, COMBINE, KEEP_PROTON, KEEP_PHONE, KEEP_BOTH, "Cancel", "OK"), dialogTexts())
        assertTrue("nothing is picked yet", SemanticsProperties.Disabled in dialogNodes().single { "OK" in it.texts() }.config)
        pick(KEEP_BOTH)
        assertEquals(mapOf(1L to MergeChoice.KEEP_BOTH, 3L to MergeChoice.KEEP_BOTH), model.state.value?.choices)
        assertTrue(!disabled(APPLY))

        click(SETTLE_ALL)
        assertEquals(listOf("Settle all as", NOT_CHOSEN, COMBINE, KEEP_PROTON, KEEP_PHONE, KEEP_BOTH, "Cancel", "OK"), dialogTexts())
        pick(COMBINE)
        assertEquals(mapOf(1L to MergeChoice.COMBINE, 2L to MergeChoice.COMBINE, 3L to MergeChoice.COMBINE), model.state.value?.choices)
    }

    @Test
    fun `the question's exact lines, only the ones that aren't 0`() {
        pairs(
            pair(1, "Ann"),
            pair(2, "Bob"),
            pair(3, "Cat", email = "cat@example.test", protonEmail = "proton-only@example.test"),
            pair(4, "Dan"),
            pair(5, "Eve"),
        )
        show()
        choose("Ann" to COMBINE, "Bob" to KEEP_PHONE, "Cat" to KEEP_PHONE, "Dan" to KEEP_BOTH, "Eve" to KEEP_PROTON)
        click(APPLY)
        assertEquals(
            listOf(
                "Apply 5 choices?",
                "1 will be combined with Proton's copy.",
                "1 will stay as Proton has it.",
                "2 will replace Proton's copy; 1 of them loses values only Proton has.",
                "1 will be imported as a new contact.",
                LATER, "Cancel", APPLY,
            ),
            dialogTexts(),
        )
    }

    @Test
    fun `each line's forms, and one choice asked about as one`() {
        pairs(
            pair(1, "Ann", email = "a@example.test", protonEmail = "proton-a@example.test"),
            pair(2, "Bob", email = "b@example.test", protonEmail = "proton-b@example.test"),
            pair(3, "Cat"),
        )
        show()
        choose("Ann" to KEEP_PHONE)
        click(APPLY)
        assertEquals(listOf("Apply 1 choice?", "1 will replace Proton's copy and lose values only Proton has.", LATER, "Cancel", APPLY), dialogTexts())
        click("Cancel", inDialog = true)

        choose("Bob" to KEEP_PHONE)
        click(APPLY)
        assertEquals("2 will replace Proton's copy; 2 of them lose values only Proton has.", dialogTexts()[1])
        click("Cancel", inDialog = true)

        choose("Cat" to KEEP_PHONE)
        click(APPLY)
        assertEquals("3 will replace Proton's copy; 2 of them lose values only Proton has.", dialogTexts()[1])
        click("Cancel", inDialog = true)

        choose("Ann" to NOT_CHOSEN, "Bob" to NOT_CHOSEN)
        click(APPLY)
        assertEquals("1 will replace Proton's copy.", dialogTexts()[1])
        click("Cancel", inDialog = true)

        choose("Ann" to KEEP_PROTON, "Bob" to KEEP_PROTON)
        click(SETTLE_ALL)
        pick(COMBINE)
        click(APPLY)
        assertEquals(listOf("Apply 3 choices?", "3 will be combined with Proton's copy.", LATER, "Cancel", APPLY), dialogTexts())
        click("Cancel", inDialog = true)

        click(SETTLE_ALL)
        pick(KEEP_PROTON)
        click(APPLY)
        assertEquals("3 will stay as Proton has them.", dialogTexts()[1])
        click("Cancel", inDialog = true)

        click(SETTLE_ALL)
        pick(KEEP_BOTH)
        click(APPLY)
        assertEquals("3 will be imported as new contacts.", dialogTexts()[1])
        click("Cancel", inDialog = true)

        choose("Ann" to NOT_CHOSEN, "Bob" to NOT_CHOSEN, "Cat" to NOT_CHOSEN)
        assertTrue(disabled(APPLY))
        click(SETTLE_ALL)
        pick(KEEP_PHONE)
        click(APPLY)
        assertEquals("3 will replace Proton's copy; 2 of them lose values only Proton has.", dialogTexts()[1])
    }

    @Test
    fun `Cancel and Back close the merge question and apply nothing`() {
        pairs(pair(1, "Ann"))
        show()
        choose("Ann" to COMBINE)
        click(APPLY)
        click("Cancel", inDialog = true)
        assertEquals(emptyList<String>(), dialogTexts())
        click(APPLY)
        backOnDialog()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())
        assertTrue(graph.calls.toString(), graph.calls.none { it.startsWith("applyMerges") })
        assertEquals(0, closed)
    }

    @Test
    fun `while it applies the screen says so, and Back and the arrow wait`() {
        pairs(pair(1, "Ann"))
        show()
        val gate = CompletableDeferred<Unit>()
        graph.mergeGate = { gate.await() }
        choose("Ann" to COMBINE)
        click(APPLY)
        click(APPLY, inDialog = true)
        assertTrue(texts().toString(), "Applying choices…" in texts())
        assertTrue("a pair can't be opened", disabled("Ann"))
        assertTrue("nor ticked", SemanticsProperties.Disabled in tick("Ann").config)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        click(nodes().single { config(it, SemanticsProperties.ContentDescription) == listOf("Back") })
        assertEquals(0, closed)

        gate.complete(Unit)
        idle()
        assertTrue(texts().toString(), "Applying choices…" !in texts())
    }

    @Test
    fun `all applied`() {
        show()
        // Every kind applied counts.
        assertTrue(texts().toString(), "Applied 4 choices." in merge(4, MergeResult(combined = 1, keptProton = 1, keptPhone = 1, keptBoth = 1)))
        assertTrue(texts().toString(), "Applied 1 choice." in merge(1, MergeResult(keptProton = 1)))
    }

    @Test
    fun `some not applied`() {
        show()
        assertTrue(texts().toString(), "Applied 1 of 3 choices; 2 changed since they were shown or couldn't be written." in merge(3, MergeResult(keptProton = 1, notApplied = 2)))
    }

    @Test
    fun `none applied, with the Contact Scopes hint`() {
        show()
        assertTrue(texts().toString(), "Couldn't apply any of the 3 choices. $SCOPES" in merge(3, MergeResult(notApplied = 3)))
        assertTrue(texts().toString(), "Couldn't apply the choice. $SCOPES" in merge(1, MergeResult(notApplied = 1)))
    }

    @Test
    fun `a merge that stopped`() {
        show()
        assertTrue(
            texts().toString(),
            "Merge stopped. Some choices may already be applied; check this screen before trying again." in merge(2, MergeResult(), IllegalStateException("made-up")),
        )
    }

    // Removing originals: the section's rows, the question, and the results in their exact words.

    private fun originals(vararg originals: ImportedOriginal) {
        graph.importList = ImportList(listOf(candidate(90, "Zed")), emptyList(), originals.toList())
    }

    /** Lists [count] removable originals, removes them with [result] (or [failure]), and returns the screen's words. */
    private fun remove(count: Int, result: ActionResult, failure: Exception? = null): List<String> {
        originals(*(1..count).map { original(it.toLong()) }.toTypedArray())
        model.refresh()
        idle()
        graph.removeResult = result
        graph.removeFailure = failure
        click(REMOVE_ORIGINALS)
        click(REMOVE, inDialog = true)
        return texts()
    }

    @Test
    fun `each original shows its name, and those that stay say why, under the caution and the button`() {
        originals(original(1, name = "Ann"), original(2, OriginalState.CHANGED, name = "Bob"), original(3, OriginalState.HOLDS_MORE, name = null))
        show()
        val all = texts()
        assertEquals(
            all.toString(),
            listOf("Imported originals (3)", CAUTION, REMOVE_ORIGINALS, "Ann", "Bob", "Changed since the import; it stays.", "No name", "Has values its copy doesn't; it stays."),
            // The top bar's title comes last.
            all.subList(all.indexOf("Imported originals (3)"), all.lastIndexOf(ROW)),
        )
    }

    @Test
    fun `Remove originals is off with none removable, and while removal waits it's off with the line under the caution`() {
        originals(original(1, OriginalState.CHANGED))
        show()
        assertTrue(disabled(REMOVE_ORIGINALS))
        assertTrue(texts().toString(), WAITS !in texts())

        originals(original(1))
        graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 5_000, true, null, 0, 0, 15, 0, 0, 0, 0, true, false, 0, 0, 0)
        model.refresh()
        idle()
        val all = texts()
        assertEquals(all.toString(), listOf(CAUTION, WAITS, REMOVE_ORIGINALS, "Original 1"), all.subList(all.indexOf(CAUTION), all.lastIndexOf(ROW)))
        assertTrue(disabled(REMOVE_ORIGINALS))

        graph.reports.clear()
        model.refresh()
        idle()
        assertTrue(texts().toString(), WAITS !in texts())
        assertTrue(!disabled(REMOVE_ORIGINALS))
    }

    @Test
    fun `the question's exact words, with the others staying only when some do`() {
        originals(original(1), original(2), original(3, OriginalState.CHANGED))
        show()
        click(REMOVE_ORIGINALS)
        assertEquals(
            listOf("Remove 2 originals from this phone?", "Their copies are in Proton with every value they hold, and stay in OpenSync for Proton. The others stay.", "Cancel", REMOVE),
            dialogTexts(),
        )
        click("Cancel", inDialog = true)

        originals(original(1), original(2))
        model.refresh()
        idle()
        click(REMOVE_ORIGINALS)
        assertEquals("Their copies are in Proton with every value they hold, and stay in OpenSync for Proton.", dialogTexts()[1])
    }

    @Test
    fun `one original is asked about as one`() {
        originals(original(1), original(2, OriginalState.HOLDS_MORE))
        show()
        click(REMOVE_ORIGINALS)
        assertEquals(
            listOf("Remove 1 original from this phone?", "Its copy is in Proton with every value it holds, and stays in OpenSync for Proton. The others stay."),
            dialogTexts().take(2),
        )
        click("Cancel", inDialog = true)

        originals(original(1))
        model.refresh()
        idle()
        click(REMOVE_ORIGINALS)
        assertEquals("Its copy is in Proton with every value it holds, and stays in OpenSync for Proton.", dialogTexts()[1])
    }

    @Test
    fun `Cancel and Back close the remove question and remove nothing`() {
        originals(original(1))
        show()
        click(REMOVE_ORIGINALS)
        click("Cancel", inDialog = true)
        assertEquals(emptyList<String>(), dialogTexts())
        click(REMOVE_ORIGINALS)
        backOnDialog()
        idle()
        assertEquals(emptyList<String>(), dialogTexts())
        assertTrue(graph.calls.toString(), graph.calls.none { it.startsWith("removeOriginals") })
        assertEquals(0, closed)
    }

    @Test
    fun `while it removes the screen says so, and Back and the arrow wait`() {
        originals(original(1))
        show()
        val gate = CompletableDeferred<Unit>()
        graph.removeGate = { gate.await() }
        click(REMOVE_ORIGINALS)
        click(REMOVE, inDialog = true)
        assertTrue(texts().toString(), "Removing originals…" in texts())
        assertTrue(disabled(REMOVE_ORIGINALS))

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        click(nodes().single { config(it, SemanticsProperties.ContentDescription) == listOf("Back") })
        assertEquals(0, closed)

        gate.complete(Unit)
        idle()
        assertTrue(texts().toString(), "Removing originals…" !in texts())
    }

    @Test
    fun `all removed`() {
        show()
        assertTrue(texts().toString(), "Removed 2 originals." in remove(2, ActionResult(2, 0)))
        assertTrue(texts().toString(), "Removed 1 original." in remove(1, ActionResult(1, 0)))
    }

    @Test
    fun `some not removed`() {
        show()
        assertTrue(texts().toString(), "Removed 1 of 3 originals; 2 changed since they were shown or couldn't be removed." in remove(3, ActionResult(1, 2)))
    }

    @Test
    fun `none removed, with the Contact Scopes hint`() {
        show()
        assertTrue(texts().toString(), "Couldn't remove any of the 3 originals. $SCOPES" in remove(3, ActionResult(0, 3)))
        assertTrue(texts().toString(), "Couldn't remove the original. $SCOPES" in remove(1, ActionResult(0, 1)))
    }

    @Test
    fun `none still removable when Remove is tapped says they changed`() {
        originals(original(1), original(2))
        show()
        click(REMOVE_ORIGINALS)
        originals(original(1, OriginalState.CHANGED), original(2, OriginalState.HOLDS_MORE))
        click(REMOVE, inDialog = true)
        assertTrue(texts().toString(), "Removed 0 of 2 originals; 2 changed since they were shown or couldn't be removed." in texts())

        originals(original(1))
        model.refresh()
        idle()
        click(REMOVE_ORIGINALS)
        originals(original(1, OriginalState.CHANGED))
        click(REMOVE, inDialog = true)
        assertTrue(texts().toString(), "Couldn't remove the original; it changed since it was shown." in texts())
    }

    @Test
    fun `a removal that stopped`() {
        show()
        assertTrue(texts().toString(), "Removing stopped. Some originals may already be removed." in remove(2, ActionResult(0, 0), IllegalStateException("made-up")))
    }

    private fun <T> config(node: SemanticsNode, key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>): T? = node.config.getOrNull(key)

    private companion object {
        const val NAME = "someone.made.up@example.test"
        const val ROW = "Import from this phone"
        const val DETAIL = "Copies this phone's own contacts to Proton."
        const val IMPORT = "Import"
        const val FIRST = "Copies them into OpenSync for Proton, which sends them to Proton at the next sync. The originals stay as they are."
        const val UNMAPPED = "fields that aren't copied, such as IM or groups; the originals keep them."
        const val SCOPES = "If Contact Scopes is on for OpenSync for Proton, turn it off and try again."
        const val CHOOSE_SELECTED = "Choose for selected…"
        const val SETTLE_ALL = "Settle all as…"
        const val APPLY = "Apply"
        const val NOT_CHOSEN = "Not chosen"
        const val COMBINE = "Combine both"
        const val KEEP_PROTON = "Keep Proton's"
        const val KEEP_PHONE = "Keep the phone's"
        const val KEEP_BOTH = "Keep both"
        const val LATER = "Proton changes at the next sync."
        const val REMOVE_ORIGINALS = "Remove originals"
        const val REMOVE = "Remove"
        const val WAITS = "Some contacts are missing from Proton and wait for your answer on the main screen. Remove originals waits until then."
        const val CAUTION = "The Contacts app may show an original and its copy as one contact. Deleting it there deletes both, on Proton " +
            "too; to keep only the copy, use Remove originals."
    }
}
