package dev.rboza.android.opensyncforproton

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.Credentials
import dev.rboza.android.opensyncforproton.account.LoginStep
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.account.PasswordMode
import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.login.FakeLoginInteractor
import dev.rboza.android.opensyncforproton.login.LoginUi
import dev.rboza.android.opensyncforproton.main.AccountUi
import dev.rboza.android.opensyncforproton.main.ModeChange
import dev.rboza.android.opensyncforproton.main.ReportLine
import dev.rboza.android.opensyncforproton.main.ReportName
import dev.rboza.android.opensyncforproton.main.toRow
import dev.rboza.android.opensyncforproton.settings.Question
import dev.rboza.android.opensyncforproton.settings.appVersion
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ImportCandidate
import dev.rboza.android.opensyncforproton.sync.provider.ImportList
import dev.rboza.android.opensyncforproton.sync.provider.ImportMark
import dev.rboza.android.opensyncforproton.sync.provider.MatchReason
import dev.rboza.android.opensyncforproton.sync.provider.MergeChoice
import dev.rboza.android.opensyncforproton.sync.provider.MergePair
import dev.rboza.android.opensyncforproton.sync.provider.ReadContact
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import dev.rboza.android.opensyncforproton.sync.lifecycle.LoginCommit
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The activity starts on the main screen, **Log in** opens the login screen and returns, the state is read each time
 * the screen starts, and contact names never reach saved state. Names and IDs are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = CredentialUiLifetimeTest.TestApp::class)
class MainActivityTest {
    private val graph = (RuntimeEnvironment.getApplication() as CredentialUiLifetimeTest.TestApp).graph

    private val launched = mutableListOf<ActivityController<MainActivity>>()

    /**
     * An activity left alive keeps Compose's frame clock from running in the next test of the class (its screens never
     * recompose), so each test's activities end with it.
     */
    @After
    fun destroy() {
        launched.filterNot { it.get().isDestroyed }.forEach { it.pause().stop().destroy() }
        idle()
    }

    private fun launch(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup().also { launched += it }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun ActivityController<MainActivity>.main() = checkNotNull(get().screens.main.state.value)

    private fun signIn() {
        graph.records = listOf(AccountRecordEntity("a1", NAME, LoginState.ACTIVE))
        graph.settings["a1"] = SettingsEntity("a1")
        graph.readable += "a1"
    }

    @Test
    fun `the activity starts on the main screen, which reads the state`() {
        signIn()
        val controller = launch()
        idle()
        assertNull(controller.get().screens.login.value)
        assertEquals(AccountUi.SignedIn(NAME), controller.main().account)
    }

    @Test
    fun `the main screen reads the state again each time it starts`() {
        val controller = launch()
        idle()
        assertEquals(AccountUi.NotSignedIn, controller.main().account)

        controller.pause().stop()
        signIn()
        controller.start().resume()
        idle()
        assertEquals(AccountUi.SignedIn(NAME), controller.main().account)
    }

    @Test
    fun `while the main screen is started it shows each report as it's saved, and once stopped, at its next start`() {
        signIn()
        val controller = launch()
        idle()

        graph.reports["a1"] = RunReport(ReportKind.SYNC, 3_000, null, plan = null).toRow("a1")
        graph.reportSaved()
        idle()
        assertEquals(3_000L, controller.main().report?.finishedAt)

        controller.pause().stop()
        idle()
        graph.reports["a1"] = RunReport(ReportKind.SYNC, 4_000, null, plan = null).toRow("a1")
        graph.reportSaved()
        idle()
        assertEquals(3_000L, controller.main().report?.finishedAt)

        controller.start().resume()
        idle()
        assertEquals(4_000L, controller.main().report?.finishedAt)
    }

    @Test
    fun `Log in opens one attempt, however often it's tapped`() {
        val controller = launch()
        controller.get().screens.openLogin()
        val attempt = controller.get().screens.login.value
        controller.get().screens.openLogin()
        idle()
        assertSame(attempt, controller.get().screens.login.value)
        assertEquals(0, graph.interactors.size)
    }

    @Test
    fun `Android's add-account intent opens the login screen`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, MainActivity.loginIntent(RuntimeEnvironment.getApplication()))
            .setup().also { launched += it }
        idle()
        assertTrue(controller.get().screens.login.value != null)
    }

    @Test
    fun `a relaunch from Recents opens the main screen, not the login`() {
        // After the app crashed, say, Recents starts the task's first intent again, extras included, with no saved
        // state.
        val intent = MainActivity.loginIntent(RuntimeEnvironment.getApplication()).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().also { launched += it }
        idle()
        assertNull(controller.get().screens.login.value)
    }

    @Test
    fun `a login closed after the add-account intent stays closed when the activity is recreated`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, MainActivity.loginIntent(RuntimeEnvironment.getApplication()))
            .setup().also { launched += it }
        idle()
        controller.get().screens.loginClosed()

        controller.recreate()
        idle()
        assertNull(controller.get().screens.login.value)
    }

    @Test
    fun `a finished login returns to the main screen, which reads the state again`() {
        graph.next = { FakeLoginInteractor({ LoginStep.Success(session()) }) }
        graph.commit = {
            signIn()
            LoginCommit.Committed(resumed = false)
        }
        val controller = launch()
        idle()
        controller.get().screens.openLogin()
        idle()
        val login = checkNotNull(controller.get().screens.login.value)
        login.submit()
        idle()
        assertEquals(LoginUi.Done(resumed = false), login.state.value)

        login.cancel() // OK
        idle()
        assertNull(controller.get().screens.login.value)
        assertEquals(AccountUi.SignedIn(NAME), controller.main().account)
    }

    @Test
    fun `a new login after one closed is a new attempt`() {
        val controller = launch()
        controller.get().screens.openLogin()
        val first = checkNotNull(controller.get().screens.login.value)
        first.cancel()
        idle()

        controller.get().screens.openLogin()
        idle()
        val second = checkNotNull(controller.get().screens.login.value)
        assertTrue(first !== second)
        assertEquals(LoginUi.Credentials(), second.state.value)
    }

    @Test
    fun `back on Start syncing cancels it and stays in the app`() {
        signIn()
        graph.request = { _, _ ->
            graph.reports["a1"] = LastReportEntity("a1", ReportKind.DRY_RUN, 5_000, true, null, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0)
            RunReport(ReportKind.DRY_RUN, 5_000, null, SyncPlan(emptyList(), emptyList(), false, false))
        }
        val controller = launch()
        idle()
        controller.get().screens.main.turnOn()
        idle()
        assertEquals(ModeChange.Confirm, controller.main().modeChange)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(ModeChange.Idle, controller.main().modeChange)
        assertFalse(controller.get().isFinishing)
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `back on Delete N contacts on Proton cancels the question and stays in the app`() {
        signIn()
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        graph.request = { _, _ ->
            graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 5_000, true, null, 0, 0, 0, 0, 0, 12, 0, false, true, 0, 0, 0)
            RunReport(ReportKind.SYNC, 5_000, null, SyncPlan(emptyList(), emptyList(), false, true, phoneDeletionsWaiting = 12))
        }
        val controller = launch()
        idle()
        controller.get().screens.main.syncNow()
        idle()
        controller.get().screens.main.askDeletePhoneDeletions()
        idle()
        assertEquals(12, controller.main().confirmingDeletions)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(null, controller.main().confirmingDeletions)
        assertFalse(controller.get().isFinishing)
        assertEquals(listOf("reconcile", "request a1 FULL"), graph.calls)
    }

    @Test
    fun `back on Delete N contacts on this phone cancels the question and stays in the app`() {
        signIn()
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        graph.request = { _, _ ->
            graph.reports["a1"] = LastReportEntity("a1", ReportKind.SYNC, 5_000, true, null, 0, 0, 15, 0, 0, 0, 0, true, false, 0, 0, 0)
            RunReport(ReportKind.SYNC, 5_000, null, SyncPlan(emptyList(), emptyList(), true, false, remoteDeletionsWaiting = 15))
        }
        val controller = launch()
        idle()
        controller.get().screens.main.syncNow()
        idle()
        controller.get().screens.main.askDeleteRemoteDeletions()
        idle()
        assertEquals(15, controller.main().confirmingRemoteDeletions)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(null, controller.main().confirmingRemoteDeletions)
        assertFalse(controller.get().isFinishing)
        assertEquals(listOf("reconcile", "request a1 FULL"), graph.calls)
    }

    @Test
    fun `Settings opens the settings screen, and Back returns to the main screen`() {
        signIn()
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        assertTrue(controller.get().screens.settingsOpen.value)
        assertEquals(AccountUi.SignedIn(NAME), controller.get().screens.settings.state.value?.account)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse(controller.get().screens.settingsOpen.value)
        assertFalse(controller.get().isFinishing)
    }

    @Test
    fun `back on a settings question cancels it and stays on the settings screen`() {
        signIn()
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.settings.logOut()
        idle()

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertNull(controller.get().screens.settings.state.value?.question)
        assertTrue(controller.get().screens.settingsOpen.value)
    }

    @Test
    fun `back on the third-party licences closes them and stays on the settings screen, even while an action runs`() {
        signIn()
        val gate = CompletableDeferred<Unit>()
        graph.lifecycleGate = { gate.await() }
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.settings.logOut()
        controller.get().screens.settings.confirm()
        controller.get().screens.settings.openNotices()
        idle()
        assertTrue(controller.get().screens.settings.state.value!!.showsNotices)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse(controller.get().screens.settings.state.value!!.showsNotices)
        assertTrue(controller.get().screens.settingsOpen.value)
        gate.complete(Unit)
        idle()
    }

    @Test
    fun `back on the third-party licences closes them before a waiting question`() {
        signIn()
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.settings.logOut()
        controller.get().screens.settings.openNotices()
        idle()

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse(controller.get().screens.settings.state.value!!.showsNotices)
        assertEquals(Question.LogOut, controller.get().screens.settings.state.value?.question)
    }

    @Test
    fun `back while a settings action runs does nothing, so the main screen reads its result`() {
        signIn()
        val gate = CompletableDeferred<Unit>()
        graph.lifecycleGate = { gate.await() }
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.settings.logOut()
        controller.get().screens.settings.confirm()
        idle()

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(controller.get().screens.settingsOpen.value)

        gate.complete(Unit)
        idle()
        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse(controller.get().screens.settingsOpen.value)
        assertEquals(AccountUi.SignedOut(NAME), controller.main().account)
    }

    /** Signed in with sync on, one made-up local contact to import. */
    private fun importable() {
        signIn()
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON)
        graph.importList = ImportList(
            listOf(ImportCandidate(ReadContact(DeviceContact(21, null, false, false, 5, "Made Up"), emptyList()), ImportMark.NONE, null, false)),
            listOf(MergePair(ReadContact(DeviceContact(22, null, false, false, 5, "Made Up Pair"), emptyList()), ReadContact(DeviceContact(7, "p7", false, false, 5, "Made Up Pair"), emptyList()), MatchReason.NAME, null)),
            emptyList(),
        )
    }

    @Test
    fun `the merge choices and pair ticks go with the import screen, and Back on the merge question cancels it`() {
        importable()
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.openImport()
        idle()
        val import = checkNotNull(controller.get().screens.importScreen.value)
        import.choose(22, MergeChoice.COMBINE)
        import.togglePair(22)
        import.apply()
        idle()

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertNull(import.state.value?.mergeQuestion)
        assertSame(import, controller.get().screens.importScreen.value)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        controller.get().screens.openImport()
        idle()
        val reopened = controller.get().screens.importScreen.value?.state?.value
        assertEquals(emptyMap<Long, MergeChoice>(), reopened?.choices)
        assertEquals(emptySet<Long>(), reopened?.tickedPairs)
    }

    @Test
    fun `Import from this phone opens the import screen, which reads its list, and Back returns to settings without the ticks`() {
        importable()
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.openImport()
        idle()
        val import = checkNotNull(controller.get().screens.importScreen.value)
        assertEquals(listOf(21L), import.state.value?.candidates?.map { it.id })
        import.toggle(21)
        assertEquals(setOf(21L), import.state.value?.ticked)

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertNull(controller.get().screens.importScreen.value)
        assertTrue(controller.get().screens.settingsOpen.value)

        controller.get().screens.openImport()
        idle()
        assertEquals(emptySet<Long>(), controller.get().screens.importScreen.value?.state?.value?.ticked)
    }

    @Test
    fun `the import screen opens only from the settings screen, and not while the login screen is open`() {
        importable()
        val controller = launch()
        idle()
        controller.get().screens.openImport()
        idle()
        assertNull(controller.get().screens.importScreen.value)

        controller.get().screens.openSettings()
        controller.get().screens.openLogin()
        controller.get().screens.openImport()
        idle()
        assertNull(controller.get().screens.importScreen.value)
    }

    @Test
    fun `back on an import question cancels it and stays on the import screen`() {
        importable()
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.openImport()
        idle()
        val import = checkNotNull(controller.get().screens.importScreen.value)
        import.toggle(21)
        import.import()
        idle()

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertNull(import.state.value?.question)
        assertSame(import, controller.get().screens.importScreen.value)
    }

    @Test
    fun `back while an import runs does nothing, and once it ends returns to settings`() {
        importable()
        val gate = CompletableDeferred<Unit>()
        graph.importGate = { gate.await() }
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.openImport()
        idle()
        val import = checkNotNull(controller.get().screens.importScreen.value)
        import.toggle(21)
        import.import()
        import.confirm()
        idle()

        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertSame(import, controller.get().screens.importScreen.value)

        gate.complete(Unit)
        idle()
        controller.get().onBackPressedDispatcher.onBackPressed()
        idle()
        assertNull(controller.get().screens.importScreen.value)
        assertTrue(controller.get().screens.settingsOpen.value)
    }

    @Test
    fun `a finished removal returns to the main screen, which shows no account`() {
        signIn()
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.settings.remove()
        controller.get().screens.settings.confirm()
        idle()

        assertFalse(controller.get().screens.settingsOpen.value)
        assertEquals(AccountUi.NotSignedIn, controller.main().account)
        assertEquals(listOf("deviceContacts a1 $NAME", "removeFromPhone a1"), graph.calls)
    }

    @Test
    fun `after a finished removal, Settings opens again for the next account`() {
        signIn()
        val controller = launch()
        idle()
        controller.get().screens.openSettings()
        idle()
        controller.get().screens.settings.remove()
        controller.get().screens.settings.confirm()
        idle()

        signIn()
        controller.get().screens.openSettings()
        idle()
        assertTrue(controller.get().screens.settingsOpen.value)
    }

    @Test
    fun `Settings doesn't open while the login screen is open`() {
        val controller = launch()
        controller.get().screens.openLogin()
        controller.get().screens.openSettings()
        idle()
        assertFalse(controller.get().screens.settingsOpen.value)
    }

    @Test
    fun `About shows the app's version`() {
        assertEquals("1.0.0", appVersion(launch().get()))
    }

    @Test
    fun `back on the main screen leaves the app`() {
        val controller = launch()
        idle()
        controller.get().onBackPressedDispatcher.onBackPressed()
        assertTrue(controller.get().isFinishing)
    }

    // Android 17 answers a permission request itself, without a dialog, when none of its permissions can be asked
    // for; under Robolectric (which grants what the manifest declares) that answer is always "granted". So these
    // tests see the request and its answer reach the model; what a refusal means is PermissionAnswerTest's.

    @Test
    fun `turning on without the permission asks Android for both contacts permissions, and its answer reaches the screen`() {
        signIn()
        graph.permission = false
        val controller = launch()
        idle()

        controller.get().screens.main.turnOn()
        idle()

        val asked = checkNotNull(shadowOf(controller.get()).lastRequestedPermission)
        assertEquals(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), asked.requestedPermissions.toList())
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }

    @Test
    fun `a screen that starts with the request already sent doesn't send it again`() {
        signIn()
        graph.permission = false
        val controller = launch()
        idle()

        controller.get().screens.main.turnOn()
        controller.get().screens.main.permissionRequested() // as if sent before the activity was recreated
        idle()

        assertNull(shadowOf(controller.get()).lastRequestedPermission)
        assertEquals(ModeChange.AwaitingPermission(thenCheck = true), controller.main().modeChange)
    }

    @Test
    fun `Open app settings opens this app's page in Android's settings`() {
        val controller = launch()
        controller.get().openAppSettings()
        val started = checkNotNull(shadowOf(controller.get()).nextStartedActivity)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals(Uri.fromParts("package", controller.get().packageName, null), started.data)
    }

    @Test
    fun `contact names never reach saved state`() {
        signIn()
        val plan = SyncPlan(
            listOf(PlanItem(PlanCategory.NEW_FROM_PROTON, null, null, null, CONTACT_NAME, carriedOut = false)),
            emptyList(), bulkGuardRemote = false, bulkGuardPhone = false,
        )
        val run = RunReport(ReportKind.DRY_RUN, 5_000, null, plan)
        graph.request = { _, _ ->
            graph.reports["a1"] = LastReportEntity("a1", ReportKind.DRY_RUN, 5_000, true, null, 1, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0)
            run
        }
        val controller = launch()
        idle()
        controller.get().screens.main.syncNow()
        idle()
        assertEquals(listOf(ReportName(CONTACT_NAME)), controller.main().report?.names?.get(ReportLine.NEW_FROM_PROTON))

        val saved = Bundle().also { controller.saveInstanceState(it) }
        val parcel = Parcel.obtain()
        val bytes = try {
            parcel.writeBundle(saved)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
        assertFalse(listOf(Charsets.UTF_8, Charsets.UTF_16LE).any { bytes.contains(CONTACT_NAME.toByteArray(it)) })
        assertFalse(listOf(Charsets.UTF_8, Charsets.UTF_16LE).any { bytes.contains(NAME.toByteArray(it)) })
    }

    @Test
    fun `the scan finds a name in saved state`() {
        val saved = Bundle().apply { putString("k", CONTACT_NAME) }
        val parcel = Parcel.obtain()
        val bytes = try {
            parcel.writeBundle(saved)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
        assertTrue(listOf(Charsets.UTF_8, Charsets.UTF_16LE).any { bytes.contains(CONTACT_NAME.toByteArray(it)) })
    }

    private fun ByteArray.contains(part: ByteArray) = (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    private fun session() = NewSession(
        AccountId("a1"), NAME,
        Credentials(Tokens("uid-1", "access-1", "refresh-1"), PasswordMode.ONE, emptyMap(), LockedKeyBundle(emptyList()), emptyMap()),
    )

    private companion object {
        const val NAME = "someone.made.up@example.test"
        const val CONTACT_NAME = "Made-Up Contact Name"
    }
}
