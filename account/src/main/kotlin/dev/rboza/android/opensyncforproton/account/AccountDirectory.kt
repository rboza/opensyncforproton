package dev.rboza.android.opensyncforproton.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.os.Bundle
import android.provider.ContactsContract

/**
 * The app's Android accounts (docs/architecture.md § Android account), found by their `proton_user_id`
 * userdata; other account types are never read or changed. Blocking: callers run it on `Dispatchers.IO`.
 */
interface AccountDirectory {
    /**
     * Adds [name] with [account] (never blank) as its `proton_user_id` in the same call, and nothing else, then marks
     * the contacts authority not syncable. `false` when it wasn't added: an account of the app's type already has that
     * name, or AccountManager refused (on a device, for example while storage is still locked); nothing is written then.
     */
    fun add(account: AccountId, name: String): Boolean

    /**
     * Removes every account of the app's type whose `proton_user_id` is [account]. `true` when none is left afterwards
     * (none to begin with included); `false` when AccountManager refused one, so the caller doesn't treat the account as
     * gone.
     */
    fun remove(account: AccountId): Boolean

    /**
     * The app's accounts by `proton_user_id`. One without it (or with a blank one) is left out, never removed. Two
     * sharing an ID are never added by the app ([add] checks only the name); if they exist, the name first in sort
     * order is given.
     */
    fun live(): Map<AccountId, String>

    /**
     * The name of every account of the app's type, with or without a `proton_user_id`: reconciliation never deletes
     * provider rows under one of these names (docs/sync.md § Account states).
     */
    fun names(): Set<String>
}

/** [accountType] is the app's own (D4: `.debug` in debug and test builds). */
class AccountManagerDirectory(private val accountManager: AccountManager, private val accountType: String) : AccountDirectory {
    override fun add(account: AccountId, name: String): Boolean {
        require(account.value.isNotBlank()) { "blank account ID" }
        val android = Account(name, accountType)
        val userdata = Bundle().apply { putString(PROTON_USER_ID, account.value) }
        if (!accountManager.addAccountExplicitly(android, null, userdata)) return false
        // Left unknown, the authority would get Android's initialization sync request.
        ContentResolver.setIsSyncable(android, ContactsContract.AUTHORITY, 0)
        return true
    }

    override fun remove(account: AccountId): Boolean {
        accounts().filter { it.second == account }.forEach { accountManager.removeAccountExplicitly(it.first) }
        return accounts().none { it.second == account }
    }

    // associate keeps the last pair for each ID, so sorting backwards keeps the first name.
    override fun live(): Map<AccountId, String> =
        accounts().sortedByDescending { it.first.name }.associate { (android, id) -> id to android.name }

    override fun names(): Set<String> = accountManager.getAccountsByType(accountType).mapTo(mutableSetOf()) { it.name }

    private fun accounts(): List<Pair<Account, AccountId>> = accountManager.getAccountsByType(accountType).mapNotNull { android ->
        accountManager.getUserData(android, PROTON_USER_ID)?.takeIf { it.isNotBlank() }?.let { android to AccountId(it) }
    }

    private companion object {
        const val PROTON_USER_ID = "proton_user_id"
    }
}
