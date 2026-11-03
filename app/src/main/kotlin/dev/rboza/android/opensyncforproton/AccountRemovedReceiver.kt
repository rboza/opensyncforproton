package dev.rboza.android.opensyncforproton

import android.accounts.AccountManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `ACCOUNT_REMOVED` (a protected broadcast only Android sends) starts reconciliation and nothing else; reconciliation
 * itself checks which accounts are gone (docs/sync.md § Account states). The broadcast stays open until it has run, so
 * the process isn't stopped halfway, but at most [BROADCAST_HOLD]: reconciliation can wait behind a login or logout or
 * a stopping run, and Android treats a background broadcast held about a minute as not responding and kills the
 * process. Reconciliation keeps running after the broadcast ends; it is crash-safe.
 */
class AccountRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AccountManager.ACTION_ACCOUNT_REMOVED) return
        val pending = goAsync()
        (context.applicationContext as OspApp).reconcileForBroadcast(pending::finish)
    }
}

/** Under Android's limit for a background broadcast (about 60 s). */
internal val BROADCAST_HOLD: Duration = 50.seconds

/** Calls [finish] in [scope] once [job] ends, or after [limit] if it hasn't; [job] is never cancelled here. */
internal fun finishWhenDone(scope: CoroutineScope, job: Job, limit: Duration, finish: () -> Unit) {
    scope.launch {
        withTimeoutOrNull(limit) { job.join() }
        finish()
    }
}
