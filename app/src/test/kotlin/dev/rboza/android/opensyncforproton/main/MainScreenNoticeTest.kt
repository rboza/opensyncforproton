package dev.rboza.android.opensyncforproton.main

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.rboza.android.opensyncforproton.FakeAppGraph
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * MainScreen shows the upgrade notice while it's set (docs/sync.md § Sync mode), and **Dismiss** dismisses it. The
 * screen's semantics are read through Compose's own test root (no UI test library).
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenNoticeTest {
    private val graph = FakeAppGraph().apply {
        records = listOf(AccountRecordEntity("a1", "someone.made.up@example.test", LoginState.ACTIVE))
        settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60, upgradeNotice = true)
        readable += "a1"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = MainModel(graph, scope)
    private lateinit var controller: ActivityController<ComponentActivity>

    @After
    fun end() {
        controller.pause().stop().destroy()
        idle()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun show() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { MainScreen(model, onLogIn = {}, onOpenAppSettings = {}, onOpenSettings = {}, notes = {}) }
        idle()
    }

    private fun root(view: View): ViewRootForTest? = when (view) {
        is ViewRootForTest -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { root(view.getChildAt(it)) }
        else -> null
    }

    private fun nodes(): List<SemanticsNode> = checkNotNull(root(controller.get().window.decorView)).semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)

    private fun texts(): List<String> = nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun text(id: Int) = controller.get().getString(id)

    @Test
    fun `the notice shows while it's set, and Dismiss dismisses it`() {
        show()
        assertTrue(texts().toString(), text(R.string.main_upgrade_notice) in texts())

        val dismiss = nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text(R.string.main_dismiss) } }
        checkNotNull(dismiss.config[SemanticsActions.OnClick].action).invoke()
        idle()

        assertEquals(listOf("dismissUpgradeNotice a1"), graph.calls)
        assertFalse(text(R.string.main_upgrade_notice) in texts())
        assertFalse(text(R.string.main_dismiss) in texts())
    }

    @Test
    fun `with sync on there's no download-only notice`() {
        // Sync on uploads phone edits, so no line says it's download only.
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.ON, 60)
        show()
        assertTrue(texts().toString(), text(R.string.main_sync_now) in texts())
        assertTrue(texts().toString(), texts().none { "Download only" in it || "not uploaded" in it })
    }

    @Test
    fun `without the notice, neither shows`() {
        graph.settings["a1"] = SettingsEntity("a1", SyncMode.DRY_RUN, 60)
        show()
        assertTrue(texts().toString(), text(R.string.main_sync_now) in texts())
        assertFalse(text(R.string.main_upgrade_notice) in texts())
        assertFalse(text(R.string.main_dismiss) in texts())
    }
}
