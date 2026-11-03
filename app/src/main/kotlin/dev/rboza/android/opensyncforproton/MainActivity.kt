package dev.rboza.android.opensyncforproton

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rboza.android.opensyncforproton.importing.ImportModel
import dev.rboza.android.opensyncforproton.importing.ImportScreen
import dev.rboza.android.opensyncforproton.login.LoginModel
import dev.rboza.android.opensyncforproton.login.LoginScreen
import dev.rboza.android.opensyncforproton.main.MainModel
import dev.rboza.android.opensyncforproton.main.MainScreen
import dev.rboza.android.opensyncforproton.settings.SettingsModel
import dev.rboza.android.opensyncforproton.settings.SettingsNotes
import dev.rboza.android.opensyncforproton.settings.SettingsScreen
import dev.rboza.android.opensyncforproton.ui.OspTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The screens' state across configuration changes, never in saved state: the main screen, a login attempt while the
 * login screen is open, the settings screen, and the import screen's while it's open. Leaving the login screen, or the
 * activity finishing, ends the attempt.
 */
class ScreensViewModel(private val graph: AppGraph) : ViewModel() {
    val main = MainModel(graph, viewModelScope)

    private val attempt = MutableStateFlow<LoginModel?>(null)

    /** The open login screen's attempt; null on the main screen. */
    val login: StateFlow<LoginModel?> = attempt

    val settings = SettingsModel(graph, viewModelScope)

    private val inSettings = MutableStateFlow(false)

    /** The settings screen is open (the login screen, when open, comes first). */
    val settingsOpen: StateFlow<Boolean> = inSettings

    /** **Settings**: opens the settings screen, fresh, unless the login screen is open. */
    fun openSettings() {
        if (attempt.value != null) return
        inSettings.value = true
        settings.open()
    }

    private val importing = MutableStateFlow<ImportModel?>(null)

    /** The open import screen's state, over the settings screen; null while it's closed. */
    val importScreen: StateFlow<ImportModel?> = importing

    /** **Import from this phone**: opens the import screen, fresh, from the settings screen. */
    fun openImport() {
        if (attempt.value != null || !inSettings.value) return
        importing.value = ImportModel(graph, viewModelScope)
    }

    /**
     * Back: the settings screen again. Its lists and ticks go with it; an import it started runs to its end in this
     * view model's scope.
     */
    fun closeImport() {
        importing.value = null
    }

    /** Back, or a finished removal: the main screen, which reads its state again as it starts. */
    fun closeSettings() {
        inSettings.value = false
    }

    /** **Log in**: opens the login screen with a new attempt, unless one is open. */
    fun openLogin() {
        if (attempt.value == null) attempt.value = LoginModel(graph::newLoginInteractor, graph::completeLogin, viewModelScope, graph::log, System::nanoTime)
    }

    /**
     * The login screen closed (its attempt is over): back to the main screen, which reads the state again as it starts
     * (MainScreen's start effect runs each time it enters the composition).
     */
    fun loginClosed() {
        attempt.value = null
    }

    override fun onCleared() {
        attempt.value?.cancel()
    }
}

/** The one activity (launcher label "OSP"). FLAG_SECURE except in the debug build ([securesWindow]). */
class MainActivity : ComponentActivity() {
    internal companion object {
        /** Opens the login screen: what the authenticator hands Android when an account is added from its settings. */
        const val EXTRA_LOG_IN = "dev.rboza.android.opensyncforproton.extra.LOG_IN"

        /**
         * The launcher's own intent (no package; `Intent.filterEquals` ignores extras and flags): a task started with
         * it or with [loginIntent] comes forward as it is from the launcher or a notification, without a second main
         * screen on top.
         */
        fun launcherIntent(context: Context): Intent =
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java)

        /**
         * Settings starts it as a new task's first intent (clearing the app's task), so it must be [launcherIntent].
         */
        fun loginIntent(context: Context): Intent = launcherIntent(context).putExtra(EXTRA_LOG_IN, true)
    }

    internal val screens: ScreensViewModel by viewModels {
        viewModelFactory { initializer { ScreensViewModel(application as AppGraph) } }
    }

    /** This app's page in Android's settings, where a permission Android no longer asks for can be granted. */
    internal fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (securesWindow(packageName)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only for a fresh start: a recreated activity keeps whatever screen was open, a closed login included, and
        // Recents restarting the task's first intent (after the app crashed, say; extras don't survive a reboot) opens
        // the main screen.
        val fromRecents = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromRecents && intent.getBooleanExtra(EXTRA_LOG_IN, false)) screens.openLogin()
        setContent {
            OspTheme {
                val login by screens.login.collectAsStateWithLifecycle()
                val inSettings by screens.settingsOpen.collectAsStateWithLifecycle()
                val settings by screens.settings.state.collectAsStateWithLifecycle()
                val importing by screens.importScreen.collectAsStateWithLifecycle()
                val attempt = login
                val import = importing
                when {
                    attempt != null -> LoginScreen(attempt, onClosed = screens::loginClosed)
                    import != null -> ImportScreen(import, onClose = screens::closeImport)
                    inSettings -> SettingsScreen(screens.settings, onClose = screens::closeSettings, onOpenImport = screens::openImport)
                    else -> MainScreen(
                        screens.main,
                        onLogIn = screens::openLogin,
                        onOpenAppSettings = ::openAppSettings,
                        onOpenSettings = screens::openSettings,
                        notes = { SettingsNotes(settings?.notes.orEmpty(), screens.settings::dismiss) },
                    )
                }
            }
        }
    }
}

/**
 * FLAG_SECURE for every build but the debug one: debug builds (`.debug`) run only on the emulator (D4), where
 * screenshots help development. The release build on the Pixel shows passwords, codes, and contact names.
 */
internal fun securesWindow(packageName: String): Boolean = !packageName.endsWith(".debug")
