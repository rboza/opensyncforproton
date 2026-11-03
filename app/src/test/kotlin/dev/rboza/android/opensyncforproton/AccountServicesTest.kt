package dev.rboza.android.opensyncforproton

import android.accounts.Account
import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The account's Android services (docs/architecture.md § Android account, § Security): the authenticator is declared
 * for the app's account type (the app's ID; D4) and opens the login when Android adds an account; `ACCOUNT_REMOVED`
 * starts reconciliation and nothing else. Names and IDs are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OspApp::class)
class AccountServicesTest {
    private val app = RuntimeEnvironment.getApplication() as OspApp

    @Test
    fun `the account type resource is the app's ID`() {
        assertEquals(app.packageName, app.getString(R.string.account_type))
    }

    @Test
    fun `the authenticator is declared for the account type, with the app's name and icon`() {
        val parser = app.resources.getXml(R.xml.authenticator)
        while (parser.next() != XmlPullParser.START_TAG) Unit
        assertEquals("account-authenticator", parser.name)
        assertEquals(app.packageName, app.getString(parser.getAttributeResourceValue(ANDROID, "accountType", 0)))
        assertEquals(app.getString(R.string.app_name), app.getString(parser.getAttributeResourceValue(ANDROID, "label", 0)))
        assertEquals(R.mipmap.ic_launcher, parser.getAttributeResourceValue(ANDROID, "icon", 0))
        assertEquals(R.mipmap.ic_launcher, parser.getAttributeResourceValue(ANDROID, "smallIcon", 0))
    }

    @Test
    fun `Android finds the authenticator service, with its XML`() {
        val services = app.packageManager.queryIntentServices(
            Intent(AccountManager.ACTION_AUTHENTICATOR_INTENT).setPackage(app.packageName), PackageManager.GET_META_DATA,
        )
        val service = services.single().serviceInfo
        assertEquals(AuthenticatorService::class.java.name, service.name)
        assertEquals(R.xml.authenticator, service.metaData.getInt(AccountManager.AUTHENTICATOR_META_DATA_NAME))
    }

    @Test
    fun `the authenticator service binds the authenticator, which opens the login`() {
        val bind = Intent(AccountManager.ACTION_AUTHENTICATOR_INTENT)
        val service = Robolectric.buildService(AuthenticatorService::class.java, bind).create().get()
        assertNotNull(service.onBind(bind))

        val result = checkNotNull(service.authenticator.addAccount(null, app.packageName, null, null, Bundle()))
        val login = checkNotNull(result.getParcelable(AccountManager.KEY_INTENT, Intent::class.java))
        assertEquals(ComponentName(app, MainActivity::class.java), login.component)
        assertTrue(login.getBooleanExtra(MainActivity.EXTRA_LOG_IN, false))
        assertTrue("not the launcher's intent", login.filterEquals(launcher()))
    }

    @Test
    fun `the login intent is the launcher's, so the launcher and notifications bring that task forward later`() {
        // Settings starts it as a new task's first intent; the launcher's own intent must match it
        // (Intent.filterEquals).
        assertTrue(MainActivity.loginIntent(app).filterEquals(launcher()))
        assertTrue(MainActivity.launcherIntent(app).filterEquals(launcher()))
    }

    @Test
    fun `Android delivers ACCOUNT_REMOVED to the app's receiver`() {
        val receivers = app.packageManager.queryBroadcastReceivers(Intent(AccountManager.ACTION_ACCOUNT_REMOVED).setPackage(app.packageName), 0)
        assertEquals(listOf(AccountRemovedReceiver::class.java.name), receivers.map { it.activityInfo.name })
    }

    @Test
    fun `ACCOUNT_REMOVED starts reconciliation, and the broadcast ends once it has run`() = runBlocking {
        awaitReconciliation()
        val before = app.reconciling
        AccountManager.get(app).addAccountExplicitly(Account(NAME, app.packageName), null, Bundle().apply { putString("proton_user_id", "a1") })

        // Runs only after the app's receiver has finished the broadcast.
        var reconciledWhenEnded: Boolean? = null
        val last = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                reconciledWhenEnded = app.reconciling.let { it !== before && it?.isCompleted == true }
            }
        }
        app.sendOrderedBroadcast(Intent(AccountManager.ACTION_ACCOUNT_REMOVED).setPackage(app.packageName), null, last, null, 0, null, null)
        idleUntil { reconciledWhenEnded != null }

        assertEquals(true, reconciledWhenEnded)
        assertEquals(listOf("a1"), app.accounts().map { it.accountId })
    }

    @Test
    fun `any other action starts nothing`() {
        awaitReconciliation()
        val before = app.reconciling

        AccountRemovedReceiver().onReceive(app, Intent("made.up.ACTION"))

        assertSame(before, app.reconciling)
    }

    /** What the launcher (Launcher3) sends. */
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(app, MainActivity::class.java))

    private fun awaitReconciliation() {
        val job = checkNotNull(app.reconciling)
        idleUntil { job.isCompleted }
        assertTrue("reconciliation still running", job.isCompleted)
    }

    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val NAME = "someone.made.up@example.test"
    }
}
