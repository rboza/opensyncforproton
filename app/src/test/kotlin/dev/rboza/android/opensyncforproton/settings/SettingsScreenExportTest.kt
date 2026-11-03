package dev.rboza.android.opensyncforproton.settings

import android.net.Uri
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
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import java.io.File
import java.nio.file.Files
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

/**
 * Export logs opens the system save dialog with the day's name and writes the log to the file chosen
 * (docs/architecture.md § Diagnostics), through a result registry that answers the dialog itself.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenExportTest {
    private val graph = FakeAppGraph().apply {
        records = listOf(AccountRecordEntity("a1", "someone.made.up@example.test", LoginState.ACTIVE))
        settings["a1"] = SettingsEntity("a1")
        readable += "a1"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = SettingsModel(graph, scope)
    private val target: File = Files.createTempFile("made-up-export", ".jsonl").toFile()
    private val names = mutableListOf<String>()
    private lateinit var controller: ActivityController<ComponentActivity>

    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            names += input.toString()
            dispatchResult(requestCode, Uri.fromFile(target))
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
        target.delete()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `Export logs asks for a file named for the day and writes the log there`() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { SettingsScreen(model, onClose = {}, onOpenImport = {}) }
        }
        idle()

        model.requestExport()
        // The file opens on a real IO thread; wait for its result to come back to the main looper.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (model.state.value?.message == null && System.nanoTime() < deadline) {
            idle()
            Thread.sleep(5)
        }

        assertEquals(1, names.size)
        assertTrue(names.single(), Regex("osp-logs-\\d{4}-\\d{2}-\\d{2}\\.jsonl").matches(names.single()))
        assertEquals("made-up log line\n", target.readText())
        assertEquals(SettingsMessage.LOGS_SAVED, model.state.value?.message)
    }
}
