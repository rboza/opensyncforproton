package dev.rboza.android.opensyncforproton.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import dev.rboza.android.opensyncforproton.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * One "Sync problems" channel, one notification per problem (a repeat replaces it), private on the lock screen, opening
 * the app (docs/sync.md § Screens and notifications).
 */
@RunWith(RobolectricTestRunner::class)
class SyncProblemNotifierTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = SyncProblemNotifier(context)

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun `problems are posted on the Sync problems channel`() {
        notifier.show(setOf(SyncProblem.Captcha))
        val channel = checkNotNull(manager.getNotificationChannel(SyncProblemNotifier.CHANNEL))
        assertEquals("Sync problems", channel.name)
        assertEquals(SyncProblemNotifier.CHANNEL, posted().single().channelId)
    }

    @Test
    fun `each problem says what the spec says`() {
        notifier.show(
            setOf(SyncProblem.SessionExpired, SyncProblem.Captcha, SyncProblem.DuplicateRows(3), SyncProblem.PermissionRevoked, SyncProblem.Held(4)),
        )
        val texts = posted().map { it.text() }.toSet()
        assertEquals(
            setOf(
                "Log in to resume.",
                "Open the app to verify.",
                "3 contacts with duplicate rows are held. Nothing was deleted.",
                "Sync is paused until the contacts permission is granted again.",
                // The held list's, without names.
                "4 contacts held: choose which copy to keep.",
            ),
            texts,
        )
    }

    @Test
    fun `a repeat replaces the problem's notification, and other problems keep theirs`() {
        notifier.show(setOf(SyncProblem.DuplicateRows(2)))
        notifier.show(setOf(SyncProblem.DuplicateRows(3)))
        assertEquals(listOf("3 contacts with duplicate rows are held. Nothing was deleted."), posted().map { it.text() })

        notifier.show(setOf(SyncProblem.Captcha))
        assertEquals(2, posted().size)
    }

    @Test
    fun `clearing cancels only the kinds shown gone`() {
        notifier.show(setOf(SyncProblem.Captcha, SyncProblem.PermissionRevoked, SyncProblem.DuplicateRows(2)))
        notifier.clear(setOf(ProblemKind.CAPTCHA, ProblemKind.PERMISSION_REVOKED, ProblemKind.SESSION_EXPIRED))
        assertEquals(listOf("2 contacts with duplicate rows are held. Nothing was deleted."), posted().map { it.text() })
    }

    @Test
    fun `the lock screen shows only that there's a sync problem`() {
        notifier.show(setOf(SyncProblem.SessionExpired))
        val notification = posted().single()
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val public = checkNotNull(notification.publicVersion)
        assertEquals("OSP", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Sync problem", public.text())
    }

    @Test
    fun `tapping opens the app and clears the notification`() {
        notifier.show(setOf(SyncProblem.Captcha))
        val notification = posted().single()
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val intent = checkNotNull(notification.contentIntent)
        assertTrue(intent.isImmutable)
        val shadow = shadowOf(intent)
        assertTrue(shadow.isActivity)
        // The launcher's own intent, so an open task comes forward as it is rather than gaining a second screen.
        val opened = shadow.savedIntent
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertEquals(Intent.ACTION_MAIN, opened.action)
        assertTrue(opened.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertTrue(opened.filterEquals(MainActivity.launcherIntent(context)))
        val launcherFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        assertEquals(launcherFlags, opened.flags and launcherFlags)
    }

    @Test
    fun `the held notification is one of its own, cleared by its kind`() {
        notifier.show(setOf(SyncProblem.Held(1), SyncProblem.DuplicateRows(2)))
        assertEquals(setOf("1 contact held: choose which copy to keep.", "2 contacts with duplicate rows are held. Nothing was deleted."), posted().map { it.text() }.toSet())
        notifier.show(setOf(SyncProblem.Held(2)))
        notifier.clear(setOf(ProblemKind.DUPLICATE_ROWS))
        assertEquals(listOf("2 contacts held: choose which copy to keep."), posted().map { it.text() })
        notifier.clear(setOf(ProblemKind.HELD))
        assertEquals(emptyList<Notification>(), posted())
    }

    @Test
    fun `nothing is posted for no problems`() {
        notifier.show(emptySet())
        assertFalse(posted().isNotEmpty())
    }
}
