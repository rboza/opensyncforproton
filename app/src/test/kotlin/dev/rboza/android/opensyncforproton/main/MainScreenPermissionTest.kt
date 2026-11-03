package dev.rboza.android.opensyncforproton.main

import android.Manifest
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * MainScreen's permission request and Android's answer (docs/sync.md § Sync mode), through a result registry that
 * answers the request itself (Android 17 answers a real one under Robolectric with "granted", without asking).
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenPermissionTest {
    private val graph = FakeAppGraph().apply {
        records = listOf(AccountRecordEntity("a1", "someone.made.up@example.test", LoginState.ACTIVE))
        settings["a1"] = SettingsEntity("a1")
        readable += "a1"
        permission = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = MainModel(graph, scope)
    private val launched = mutableListOf<List<String>>()
    private var answer: Map<String, Boolean> = emptyMap()
    private lateinit var controller: ActivityController<ComponentActivity>

    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            when (input) {
                is Array<*> -> {
                    launched += input.map { it.toString() }
                    dispatchResult(requestCode, answer)
                }
                // The notification permission's answer changes nothing, so the tests don't give one.
                else -> launched += listOf(input.toString())
            }
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @After
    fun end() {
        controller.pause().stop().destroy()
        idle()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun show() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MainScreen(model, onLogIn = {}, onOpenAppSettings = {}, onOpenSettings = {}, notes = {}) }
        }
        idle()
    }

    @Test
    fun `the screen asks for both contacts permissions, once`() {
        answer = mapOf(Manifest.permission.READ_CONTACTS to false, Manifest.permission.WRITE_CONTACTS to false)
        show()
        model.turnOn()
        idle()
        idle()
        assertEquals(listOf(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)), launched)
    }

    @Test
    fun `refused with no rationale, the screen offers the app's settings`() {
        answer = mapOf(Manifest.permission.READ_CONTACTS to false, Manifest.permission.WRITE_CONTACTS to false)
        show()
        model.turnOn()
        idle()
        assertEquals(ModeChange.PermissionDeclined(openSettings = true), model.state.value?.modeChange)
    }

    @Test
    fun `refused while Android gives a rationale, the screen doesn't offer the settings`() {
        answer = mapOf(Manifest.permission.READ_CONTACTS to false, Manifest.permission.WRITE_CONTACTS to false)
        show()
        shadowOf(controller.get().packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.WRITE_CONTACTS, true)
        model.turnOn()
        idle()
        assertEquals(ModeChange.PermissionDeclined(openSettings = false), model.state.value?.modeChange)
    }

    @Test
    fun `once sync is on, the screen asks for the notification permission, once`() {
        graph.permission = true
        graph.notificationPermission = false
        graph.request = { _, _ ->
            graph.reports["a1"] = LastReportEntity("a1", ReportKind.DRY_RUN, 5_000, true, null, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0)
            RunReport(ReportKind.DRY_RUN, 5_000, null, SyncPlan(emptyList(), emptyList(), false, false))
        }
        show()
        model.turnOn()
        idle()
        model.confirm()
        idle()
        idle()
        assertEquals(listOf(listOf(Manifest.permission.POST_NOTIFICATIONS)), launched)
        assertEquals(false, model.state.value?.asksNotifications) // so a recreated screen doesn't ask again
    }

    @Test
    fun `granted, the screen's answer runs the fresh dry run`() {
        answer = mapOf(Manifest.permission.READ_CONTACTS to true, Manifest.permission.WRITE_CONTACTS to true)
        show()
        model.turnOn()
        idle()
        assertEquals(listOf("reconcile", "request a1 DRY_RUN"), graph.calls)
    }
}
