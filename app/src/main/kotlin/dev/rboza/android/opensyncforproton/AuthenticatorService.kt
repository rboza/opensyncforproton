package dev.rboza.android.opensyncforproton

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.rboza.android.opensyncforproton.account.ProtonAuthenticator

/**
 * Hands the account authenticator to Android: exported, but only Android, holding `ACCOUNT_MANAGER`, can bind it.
 * Adding an account opens the login screen.
 */
class AuthenticatorService : Service() {
    internal val authenticator by lazy { ProtonAuthenticator(this) { MainActivity.loginIntent(this) } }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
