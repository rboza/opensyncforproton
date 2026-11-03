package dev.rboza.android.opensyncforproton.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.os.Bundle
import android.provider.ContactsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccountManager

/**
 * docs/architecture.md § Android account: the account is added with its `proton_user_id` in the same call and nothing
 * else, then the contacts authority is marked not syncable; only the app's own account type is read or removed. Every
 * value is made up for the test.
 */
@RunWith(RobolectricTestRunner::class)
class AccountDirectoryTest {
    private val accountManager: AccountManager = AccountManager.get(RuntimeEnvironment.getApplication())
    private lateinit var directory: AccountDirectory

    @Before
    fun setUp() {
        directory = AccountManagerDirectory(accountManager, TYPE)
        RefusingRemovalShadow.refused = null
    }

    private fun android(name: String, type: String = TYPE) = Account(name, type)

    private fun addRaw(name: String, protonUserId: String?, type: String = TYPE) {
        val userdata = Bundle().apply { if (protonUserId != null) putString("proton_user_id", protonUserId) }
        assertTrue(accountManager.addAccountExplicitly(android(name, type), null, userdata))
    }

    /**
     * AccountManager has no public way to list an account's userdata keys or auth tokens, so read the shadow's maps
     * (field names as in Robolectric 4.17; a rename fails loudly here).
     */
    private fun shadowMap(name: String, account: Account): Map<String, String>? {
        val f = ShadowAccountManager::class.java.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (f.get(Shadow.extract<ShadowAccountManager>(accountManager)) as Map<Account, Map<String, String>>)[account]
    }

    private fun syncable(account: Account) = ContentResolver.getIsSyncable(account, ContactsContract.AUTHORITY)

    @Test
    fun addStoresOnlyTheProtonUserIdNoPasswordAndNoTokens() {
        assertTrue(directory.add(AccountId("user-id-1"), "someone@example.test"))

        val account = android("someone@example.test")
        assertEquals(mapOf("proton_user_id" to "user-id-1"), shadowMap("userData", account))
        assertNull(accountManager.getPassword(account))
        assertTrue(shadowMap("authTokens", account).isNullOrEmpty())
    }

    @Test
    fun addRefusesABlankIdAndWritesNothing() {
        // An account live() and remove() could never see.
        assertThrows(IllegalArgumentException::class.java) { directory.add(AccountId(" "), "someone@example.test") }

        assertEquals(0, accountManager.accounts.size)
    }

    @Test
    fun theProtonUserIdIsThereWhenTheAccountAppears() {
        // In the same call: anything listening for new accounts never sees one without its proton_user_id.
        val seen = mutableListOf<String?>()
        accountManager.addOnAccountsUpdatedListener({ accounts ->
            accounts.filter { it.type == TYPE }.forEach { seen += accountManager.getUserData(it, "proton_user_id") }
        }, null, false)

        directory.add(AccountId("user-id-1"), "someone@example.test")

        assertEquals(listOf("user-id-1"), seen)
    }

    @Test
    fun addMarksTheContactsAuthorityNotSyncable() {
        directory.add(AccountId("user-id-1"), "someone@example.test")

        assertEquals(0, syncable(android("someone@example.test")))
    }

    @Test
    fun addOfATakenNameWritesNothing() {
        addRaw("someone@example.test", protonUserId = null)
        ContentResolver.setIsSyncable(android("someone@example.test"), ContactsContract.AUTHORITY, 1)

        assertFalse(directory.add(AccountId("user-id-1"), "someone@example.test"))

        assertNull(accountManager.getUserData(android("someone@example.test"), "proton_user_id"))
        assertEquals(1, syncable(android("someone@example.test")))
        assertEquals(emptyMap<AccountId, String>(), directory.live())
    }

    @Test
    fun theSameNameUnderAnotherTypeIsNotTaken() {
        addRaw("someone@example.test", protonUserId = "user-id-1", type = OTHER_TYPE)

        assertTrue(directory.add(AccountId("user-id-1"), "someone@example.test"))
        assertEquals(mapOf(AccountId("user-id-1") to "someone@example.test"), directory.live())
    }

    @Test
    fun liveListsTheAppsAccountsByProtonUserId() {
        directory.add(AccountId("user-id-1"), "one@example.test")
        directory.add(AccountId("user-id-2"), "two@example.test")

        assertEquals(mapOf(AccountId("user-id-1") to "one@example.test", AccountId("user-id-2") to "two@example.test"), directory.live())
    }

    @Test
    fun liveIgnoresOtherTypesAndAccountsWithoutAProtonUserId() {
        addRaw("other@example.test", protonUserId = "user-id-1", type = OTHER_TYPE)
        addRaw("none@example.test", protonUserId = null)
        addRaw("empty@example.test", protonUserId = "")
        addRaw("blank@example.test", protonUserId = "  ")

        assertEquals(emptyMap<AccountId, String>(), directory.live())
    }

    @Test
    fun liveGivesTheFirstNameInSortOrderWhenTwoAccountsShareAnId() {
        addRaw("b@example.test", protonUserId = "user-id-1")
        addRaw("a@example.test", protonUserId = "user-id-1")
        addRaw("c@example.test", protonUserId = "user-id-1")

        assertEquals(mapOf(AccountId("user-id-1") to "a@example.test"), directory.live())
    }

    @Test
    fun namesListsEveryAccountOfTheAppsTypeWithOrWithoutAnId() {
        directory.add(AccountId("user-id-1"), "one@example.test")
        addRaw("none@example.test", protonUserId = null)
        addRaw("other@example.test", protonUserId = "user-id-2", type = OTHER_TYPE)

        assertEquals(setOf("one@example.test", "none@example.test"), directory.names())
    }

    @Test
    fun removeRemovesEveryAccountOfTheAppsTypeWithThatId() {
        addRaw("a@example.test", protonUserId = "user-id-1")
        addRaw("b@example.test", protonUserId = "user-id-1")
        addRaw("two@example.test", protonUserId = "user-id-2")
        addRaw("none@example.test", protonUserId = null)
        addRaw("other@example.test", protonUserId = "user-id-1", type = OTHER_TYPE)

        assertTrue(directory.remove(AccountId("user-id-1")))

        assertEquals(
            listOf(android("none@example.test"), android("other@example.test", OTHER_TYPE), android("two@example.test")),
            accountManager.accounts.sortedBy { it.name },
        )
    }

    @Test
    fun removeOfAnUnknownIdChangesNothing() {
        directory.add(AccountId("user-id-1"), "one@example.test")

        assertTrue(directory.remove(AccountId("user-id-9")))

        assertEquals(mapOf(AccountId("user-id-1") to "one@example.test"), directory.live())
    }

    @Test
    @Config(shadows = [RefusingRemovalShadow::class])
    fun removeIsFalseWhenAnAccountWithThatIdRemains() {
        // On a device a removal can be refused; the caller then keeps the account's other records.
        addRaw("a@example.test", protonUserId = "user-id-1")
        addRaw("b@example.test", protonUserId = "user-id-1")
        RefusingRemovalShadow.refused = android("a@example.test")

        assertFalse(directory.remove(AccountId("user-id-1")))

        assertEquals(listOf(android("a@example.test")), accountManager.accounts.toList())
    }

    /** Refuses to remove [refused], as AccountManager can on a device. */
    @Implements(AccountManager::class)
    class RefusingRemovalShadow : ShadowAccountManager() {
        @Implementation
        override fun removeAccountExplicitly(account: Account): Boolean = account != refused && super.removeAccountExplicitly(account)

        companion object {
            var refused: Account? = null
        }
    }

    private companion object {
        // D4: tests use only the `.debug` account type.
        const val TYPE = "dev.rboza.android.opensyncforproton.debug"
        const val OTHER_TYPE = "example.other.type"
    }
}
