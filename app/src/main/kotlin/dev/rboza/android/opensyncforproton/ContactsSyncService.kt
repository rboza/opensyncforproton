package dev.rboza.android.opensyncforproton

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Hands the contacts sync adapter to Android: exported, but only Android, holding `ACCOUNT_MANAGER`, can bind it. Its
 * meta-data carries `sync_contacts.xml` and `contacts.xml`, which the Contacts app reads from here only. The adapter is
 * the composition root's one per process.
 */
class ContactsSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder = (application as OspApp).contactsSyncAdapter.syncAdapterBinder
}
