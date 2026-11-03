package dev.rboza.android.opensyncforproton

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getAllSemanticsNodes
import org.robolectric.shadows.ShadowDialog

/**
 * The screens' semantics, read through Compose's own test roots (no UI test library): the activity's window, and each
 * dialog showing, since a Compose dialog has a window of its own.
 */
internal fun composeRoot(view: View): ViewRootForTest? = when (view) {
    is ViewRootForTest -> view
    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { composeRoot(view.getChildAt(it)) }
    else -> null
}

/** The merged nodes of the dialogs showing, newest last. */
internal fun dialogNodes(): List<SemanticsNode> = ShadowDialog.getShownDialogs().filter { it.isShowing }
    .mapNotNull { dialog -> dialog.window?.decorView?.let(::composeRoot) }
    .flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }

/** The merged nodes of the activity's window, then of the dialogs showing. */
internal fun screenNodes(activity: Activity): List<SemanticsNode> =
    checkNotNull(composeRoot(activity.window.decorView)).semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) + dialogNodes()

/** The newest dialog showing. */
internal fun shownDialog(): Dialog = ShadowDialog.getShownDialogs().last { it.isShowing }

/** Back on the dialog showing: its own window gets it, as the phone's Back does while a dialog shows. */
internal fun backOnDialog() = (shownDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
