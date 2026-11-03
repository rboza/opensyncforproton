package dev.rboza.android.opensyncforproton.account

import dev.rboza.android.opensyncforproton.proton.TokenStore
import dev.rboza.android.opensyncforproton.proton.Tokens

/**
 * The client's [TokenStore] for a signed-in account, kept in its credential envelope
 * (docs/protocol.md § Token refresh). Nothing is cached: every [current] opens the envelope, so a refresh by another
 * client is seen at once. Blocking, as [CredentialStore] is; [replace] and [onSessionRejected] throw
 * [CredentialStoreException] as [CredentialStore.updateTokens] and [CredentialStore.deleteSession] do.
 */
class PersistedTokenStore(private val account: AccountId, private val store: CredentialStore) : TokenStore {
    override fun current(): Tokens? = store.load(account)?.use { it.tokens }

    override fun replace(tokens: Tokens) = store.updateTokens(account, tokens)

    override fun onSessionRejected(uid: String) = store.deleteSession(account, uid)
}
