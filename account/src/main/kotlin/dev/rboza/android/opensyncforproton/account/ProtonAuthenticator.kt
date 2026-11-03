package dev.rboza.android.opensyncforproton.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The app's account authenticator (docs/architecture.md § Android account). Adding an account from Android's settings
 * opens the app's login ([loginIntent], built fresh for each request), even when an account is signed in: the login's
 * commit then refuses it. Token, credential, and property requests are refused as unsupported (a token label is
 * `null`), and [hasFeatures] answers `false`, so nothing it returns holds a Proton token or a password. Removal keeps
 * Android's default (allowed), and the `ACCOUNT_REMOVED` broadcast that follows starts reconciliation. Called on binder
 * threads; each call only builds its reply.
 */
class ProtonAuthenticator(context: Context, private val loginIntent: () -> Intent) : AbstractAccountAuthenticator(context) {
    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = Bundle().apply { putParcelable(AccountManager.KEY_INTENT, loginIntent()) }

    override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle =
        unsupported()

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle = unsupported()

    override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle =
        unsupported()

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle = unsupported()

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }

    private fun unsupported() = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, "unsupported")
    }
}
