package dev.rboza.android.opensyncforproton.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * docs/architecture.md § Android account: adding an account from Android opens the app's login, always; every other
 * request is refused as unsupported, and nothing the authenticator returns holds a token or a password. Every value is
 * made up for the test.
 */
@RunWith(RobolectricTestRunner::class)
class ProtonAuthenticatorTest {
    private val login = Intent("made.up.LOG_IN")
    private val authenticator = ProtonAuthenticator(RuntimeEnvironment.getApplication()) { login }
    private val account = Account("someone.made.up@example.test", TYPE)

    private fun assertRefused(result: Bundle?) {
        checkNotNull(result)
        assertEquals(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION, result.getInt(AccountManager.KEY_ERROR_CODE))
        assertNoSecrets(result)
        assertFalse(result.containsKey(AccountManager.KEY_INTENT))
    }

    private fun assertNoSecrets(result: Bundle) {
        assertFalse(result.containsKey(AccountManager.KEY_AUTHTOKEN))
        assertFalse(result.containsKey(AccountManager.KEY_PASSWORD))
    }

    @Test
    fun `adding an account opens the login, and gives nothing else`() {
        val result = checkNotNull(authenticator.addAccount(null, TYPE, null, null, Bundle()))
        assertSame(login, result.getParcelable(AccountManager.KEY_INTENT, Intent::class.java))
        assertEquals(setOf(AccountManager.KEY_INTENT), result.keySet())
    }

    @Test
    fun `adding an account builds a fresh login intent each time`() {
        var built = 0
        val counting = ProtonAuthenticator(RuntimeEnvironment.getApplication()) { built++; Intent("made.up.LOG_IN") }
        counting.addAccount(null, TYPE, null, null, Bundle())
        counting.addAccount(null, TYPE, null, null, Bundle())
        assertEquals(2, built)
    }

    @Test
    fun `an auth token is never handed out`() {
        assertRefused(authenticator.getAuthToken(null, account, "any", Bundle()))
        assertNull(authenticator.getAuthTokenLabel("any"))
    }

    @Test
    fun `credentials are never confirmed or updated through Android`() {
        assertRefused(authenticator.confirmCredentials(null, account, Bundle()))
        assertRefused(authenticator.updateCredentials(null, account, "any", Bundle()))
    }

    @Test
    fun `there are no properties to edit`() {
        assertRefused(authenticator.editProperties(null, TYPE))
    }

    @Test
    fun `the account has no features`() {
        val result = checkNotNull(authenticator.hasFeatures(null, account, arrayOf("any")))
        assertFalse(result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, true))
        assertNoSecrets(result)
    }

    @Test
    fun `the account can be removed from Android's settings`() {
        // ACCOUNT_REMOVED then starts reconciliation (docs/sync.md § Account states).
        val result = checkNotNull(authenticator.getAccountRemovalAllowed(null, account))
        assertTrue(result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false))
    }

    private companion object {
        const val TYPE = "made.up.type"
    }
}
