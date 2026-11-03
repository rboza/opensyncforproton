package dev.rboza.android.opensyncforproton.main

import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The held list in the main screen's model (docs/sync.md § Conflicts). Names and IDs are made up.
@OptIn(ExperimentalCoroutinesApi::class)
class MainModelHeldTest {
    private val errors = mutableListOf<Throwable>()
    private val scope = CoroutineScope(Job() + UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> errors += e })
    private val graph = FakeAppGraph()
    private val model = MainModel(graph, scope)

    @AfterEach
    fun noUncaughtErrors() = assertEquals(emptyList<Throwable>(), errors)

    private fun ui() = checkNotNull(model.state.value)

    private fun held() = checkNotNull(ui().held)

    private fun signedIn(mode: SyncMode = SyncMode.ON) {
        graph.records += AccountRecordEntity("a1", "one@example.test", LoginState.ACTIVE)
        graph.settings["a1"] = SettingsEntity("a1", mode, 60)
        graph.readable += "a1"
    }

    private fun hold(n: Int, raw: Long, kind: HoldKind = HoldKind.CONFLICT, choice: HoldChoice? = null, version: Long? = 1) =
        ConflictHoldEntity("a1", "proton-id-$n", raw, kind, choice, version, "fingerprint-$n")

    private fun row(raw: Long, n: Int?, name: String?, dirty: Boolean = true, deleted: Boolean = false) =
        DeviceContact(raw, n?.let { "proton-id-$it" }, dirty = dirty, deleted = deleted, version = 1, displayName = name)

    @Test
    fun `each held contact shows the name its row shows, labelled by the phone side alone, with its choice`() {
        signedIn()
        graph.holds += listOf(
            hold(1, 10),
            hold(2, 20, choice = HoldChoice.USE_PHONE),
            hold(3, 30, choice = HoldChoice.USE_PROTON),
            hold(4, 40, kind = HoldKind.EDITED_BEFORE_KEYS),
            hold(5, 50, kind = HoldKind.MISSING_ROW, version = null),
        )
        graph.rows = listOf(
            row(10, 1, "Changed Here"),
            row(20, 2, "Not Changed Here", dirty = false),
            row(30, 3, "Deleted Here", deleted = true),
            row(40, 4, "Edited Before Keys"),
            // The missing row's ID, now another contact's: never read for its name.
            row(50, 7, "Someone Else"),
        )

        model.refresh()

        assertEquals(
            listOf(
                HeldContact(hold(1, 10), "Changed Here", HeldLabel.CHANGED_HERE),
                HeldContact(hold(3, 30, choice = HoldChoice.USE_PROTON), "Deleted Here", HeldLabel.DELETED_HERE),
                HeldContact(hold(4, 40, kind = HoldKind.EDITED_BEFORE_KEYS), "Edited Before Keys", HeldLabel.EDITED_BEFORE_KEYS),
                HeldContact(hold(2, 20, choice = HoldChoice.USE_PHONE), "Not Changed Here", HeldLabel.NOT_CHANGED_HERE),
                HeldContact(hold(5, 50, kind = HoldKind.MISSING_ROW, version = null), null, HeldLabel.GONE_HERE),
            ),
            held().contacts,
            "by name, a contact without one last",
        )
        assertEquals(5, held().count)
        assertEquals(listOf("deviceContacts a1 one@example.test"), graph.calls)
    }

    @Test
    fun `a row deleted since its hold was written is labelled deleted, whatever its kind`() {
        // A row edited before keys, deleted on the phone since.
        signedIn()
        graph.holds += hold(4, 40, kind = HoldKind.EDITED_BEFORE_KEYS)
        graph.rows = listOf(row(40, 4, "Deleted Since", deleted = true))

        model.refresh()

        assertEquals(HeldLabel.DELETED_HERE, held().contacts!!.single().label)
    }

    @Test
    fun `a hold whose row no longer holds its Proton ID isn't shown`() {
        // Left by a crash between clearing SOURCE_ID and deleting the map, or a row gone since: the next complete
        // inventory removes the hold.
        signedIn()
        graph.holds += listOf(hold(1, 10), hold(2, 20), hold(3, 30))
        graph.rows = listOf(row(10, null, "Detached"), row(20, 2, "Still Held"), row(31, 3, "Another Row"))

        model.refresh()

        assertEquals(listOf(HeldContact(hold(2, 20), "Still Held", HeldLabel.CHANGED_HERE)), held().contacts)
        assertEquals(1, held().count)
    }

    @Test
    fun `nothing held shows no list, and doesn't read the rows`() {
        signedIn()
        graph.rows = listOf(row(10, 1, "Not Held"))
        model.refresh()
        assertNull(ui().held)

        // Every hold stale: nothing to show either.
        graph.holds += hold(2, 20)
        model.refresh()
        assertNull(ui().held)
        assertEquals(listOf("deviceContacts a1 one@example.test"), graph.calls)
    }

    @Test
    fun `a missing row shows a name only from this screen's Sync now report`() {
        signedIn()
        graph.holds += hold(5, 50, kind = HoldKind.MISSING_ROW, version = null)
        val missing = PlanItem(PlanCategory.MISSING_ROW, ProtonContactId("proton-id-5"), null, null, "Missing One", false)
        val other = PlanItem(PlanCategory.NEW_FROM_PROTON, ProtonContactId("proton-id-6"), null, null, "Someone Else", true)
        val run = RunReport(ReportKind.SYNC, 2_000, null, SyncPlan(listOf(other, missing), emptyList(), false, false))
        graph.request = { _, _ -> run.also { graph.reports["a1"] = it.toRow("a1") } }
        model.refresh()
        assertNull(held().contacts?.single()?.name, "a stored report has no names")

        model.syncNow()

        assertEquals("Missing One", held().contacts?.single()?.name)

        // A later report from elsewhere: the name goes, as report names do.
        graph.reports["a1"] = RunReport(ReportKind.SYNC, 3_000, null, null).toRow("a1")
        model.refresh()
        assertNull(held().contacts?.single()?.name)
    }

    @Test
    fun `rows that can't be read show the count alone`() {
        signedIn()
        graph.holds += listOf(hold(1, 10), hold(2, 20))
        graph.rowsFailure = SecurityException("made-up failure")

        model.refresh()

        assertEquals(HeldUi(count = 2, contacts = null), ui().held)
        assertFalse(ui().readFailed, "the rest of the screen still shows")
    }

    @Test
    fun `a choice is saved for the state shown, then the list is read again`() {
        signedIn()
        graph.holds += hold(1, 10)
        graph.rows = listOf(row(10, 1, "Changed Here"))
        model.refresh()

        model.choose(held().contacts!!.single(), HoldChoice.USE_PHONE)

        assertEquals(HoldChoice.USE_PHONE, held().contacts!!.single().hold.choice)
        model.choose(held().contacts!!.single(), null)
        assertNull(held().contacts!!.single().hold.choice, "Skip: no answer")
        assertEquals(
            listOf("deviceContacts a1 one@example.test", "choose proton-id-1 USE_PHONE", "deviceContacts a1 one@example.test", "choose proton-id-1 null", "deviceContacts a1 one@example.test"),
            graph.calls,
        )
        assertNull(ui().choiceOutcome)
    }

    @Test
    fun `a choice the hold no longer takes says the contact changed, and shows it as it now is`() {
        signedIn()
        graph.holds += hold(1, 10)
        graph.rows = listOf(row(10, 1, "Changed Here"))
        model.refresh()
        val shown = held().contacts!!.single()
        // A sync found the row edited again meanwhile.
        graph.holds[0] = hold(1, 10, version = 2)

        model.choose(shown, HoldChoice.USE_PROTON)

        assertEquals(ChoiceOutcome.CHANGED, ui().choiceOutcome)
        assertEquals(listOf(hold(1, 10, version = 2)), graph.holds, "nothing saved")
        assertEquals(hold(1, 10, version = 2), held().contacts!!.single().hold)

        // The next choice that's saved clears it.
        model.choose(held().contacts!!.single(), HoldChoice.USE_PROTON)
        assertNull(ui().choiceOutcome)
    }

    @Test
    fun `a choice that fails to save says so`() {
        signedIn()
        graph.holds += hold(1, 10)
        graph.rows = listOf(row(10, 1, "Changed Here"))
        model.refresh()
        graph.chooseFailure = IOException("made-up failure")

        model.choose(held().contacts!!.single(), HoldChoice.USE_PHONE)

        assertEquals(ChoiceOutcome.FAILED, ui().choiceOutcome)
        assertNull(graph.holds.single().choice)
    }

    @Test
    fun `a missing row takes only Restore here or Skip`() {
        signedIn()
        graph.holds += hold(5, 50, kind = HoldKind.MISSING_ROW, version = null)
        model.refresh()
        val missing = held().contacts!!.single()
        assertTrue(missing.restoreOnly)

        model.choose(missing, HoldChoice.USE_PHONE)
        assertEquals(listOf("deviceContacts a1 one@example.test"), graph.calls, "never stored")

        model.choose(missing, HoldChoice.USE_PROTON)
        model.choose(held().contacts!!.single(), null)
        assertEquals(
            listOf("deviceContacts a1 one@example.test", "choose proton-id-5 USE_PROTON", "deviceContacts a1 one@example.test", "choose proton-id-5 null", "deviceContacts a1 one@example.test"),
            graph.calls,
        )
        assertFalse(HeldContact(hold(1, 10), null, HeldLabel.CHANGED_HERE).restoreOnly)
    }

    @Test
    fun `the held list shows in dry run too, and a failed holds read is a failed read`() {
        signedIn(mode = SyncMode.DRY_RUN)
        graph.holds += hold(1, 10)
        graph.rows = listOf(row(10, 1, "Changed Here"))
        model.refresh()
        assertEquals(1, held().count)

        graph.holdsFailure = IOException("made-up failure")
        model.refresh()
        assertTrue(ui().readFailed)
        assertEquals(1, held().count, "what was shown stays")
    }

    @Test
    fun `a held contact's name stays out of its toString`() {
        val contact = HeldContact(hold(1, 10), "Ada Example", HeldLabel.CHANGED_HERE)
        assertFalse("Ada Example" in contact.toString())
        assertFalse("proton-id-1" in contact.toString())
        assertFalse("Ada Example" in HeldUi(1, listOf(contact)).toString())
    }
}
