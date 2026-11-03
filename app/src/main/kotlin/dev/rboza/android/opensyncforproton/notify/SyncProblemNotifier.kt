package dev.rboza.android.opensyncforproton.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.rboza.android.opensyncforproton.MainActivity
import dev.rboza.android.opensyncforproton.R

/**
 * Posts [SyncProblem]s on the one "Sync problems" channel (docs/sync.md § Screens and notifications): one notification
 * per kind of problem (a repeat replaces it, a run that shows it gone clears it), private on the lock screen, no
 * account name or contact names, opening the app when tapped. Without POST_NOTIFICATIONS Android drops them, and the
 * main screen says the same things.
 */
class SyncProblemNotifier(private val context: Context) : ProblemNotices {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** The app keeps one account, so a problem's notification doesn't name it. */
    override fun show(problems: Set<SyncProblem>) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        problems.forEach { manager.notify(id(it.kind), notification(text(it))) }
    }

    override fun clear(kinds: Set<ProblemKind>) {
        kinds.forEach { manager.cancel(id(it)) }
    }

    private fun notification(text: String): Notification {
        // The launcher's own intent: an open task comes forward as it is, without a second main screen on top.
        val launch = MainActivity.launcherIntent(context)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(R.string.launcher_label))
            .setContentText(context.getString(R.string.notify_public))
            .build()
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(R.string.notify_title))
            .setContentText(text)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    private fun text(problem: SyncProblem): String = when (problem) {
        SyncProblem.SessionExpired -> context.getString(R.string.notify_session_expired)
        SyncProblem.Captcha -> context.getString(R.string.notify_captcha)
        SyncProblem.PermissionRevoked -> context.getString(R.string.notify_permission_revoked)
        is SyncProblem.DuplicateRows -> context.resources.getQuantityString(R.plurals.notify_duplicate_rows, problem.count, problem.count)
        is SyncProblem.Held -> context.resources.getQuantityString(R.plurals.notify_held, problem.count, problem.count)
    }

    private fun id(kind: ProblemKind): Int = when (kind) {
        ProblemKind.SESSION_EXPIRED -> 1
        ProblemKind.CAPTCHA -> 2
        ProblemKind.PERMISSION_REVOKED -> 3
        ProblemKind.DUPLICATE_ROWS -> 4
        ProblemKind.HELD -> 5
    }

    companion object {
        const val CHANNEL = "sync_problems"
    }
}
