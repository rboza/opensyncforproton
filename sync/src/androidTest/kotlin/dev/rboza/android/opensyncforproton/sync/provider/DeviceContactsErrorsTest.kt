package dev.rboza.android.opensyncforproton.sync.provider

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentValues
import android.content.OperationApplicationException
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.DisplayPhoto
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Failures the real provider can't be made to give: a stand-in provider whose batch throws, or whose photo maximum is
 * odd, or that gives no cursor. An IllegalStateException fails that contact; a cancellation and a missing permission
 * end the call; a maximum of 0 or less, or no cursor, is a provider that isn't answering.
 */
@RunWith(AndroidJUnit4::class)
class DeviceContactsErrorsTest {
    private class StandIn(
        private val error: Exception = IllegalStateException(),
        private val maxDimension: Int? = null,
        private val deleteError: Exception? = null,
        private val queryError: Exception? = null,
    ) : ContentProvider() {
        override fun onCreate() = true
        override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> = throw error
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            queryError?.let { throw it } ?: maxDimension?.let { MatrixCursor(arrayOf(DisplayPhoto.DISPLAY_MAX_DIM)).apply { addRow(arrayOf(it)) } }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = deleteError?.let { throw it } ?: 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private val account = PhoneAccount(AccountId("test-only-user-id"), "test-only@example.test")
    private val write = ContactWrite("proton-id-1", null, dataRows(ContactGraph("uid-1", "Ann"), null))
    private val original = ExistingRow(rawContactId = 1, version = 1)

    private fun contacts(error: Exception) = contacts(StandIn(error))
    private fun contacts(provider: ContentProvider) = ProviderDeviceContacts(
        ContentResolver.wrap(provider), "dev.rboza.android.opensyncforproton.debug", localAccountName = "test-only-local@example.test",
        localAccountType = "dev.rboza.android.opensyncforproton.debug.local-test-only",
    )

    @Test
    fun anIllegalStateFailsThatContact() {
        assertEquals(listOf(ContactWriteResult.Failed(FailureReason.REJECTED_VALUE)), contacts(IllegalStateException()).apply(account, listOf(write)))
    }

    @Test
    fun aCancellationIsNeverSwallowed() {
        assertThrows(CancellationException::class.java) { contacts(CancellationException()).apply(account, listOf(write)) }
    }

    @Test
    fun aMissingPermissionEndsTheCall() {
        assertThrows(SecurityException::class.java) { contacts(SecurityException()).apply(account, listOf(write)) }
    }

    @Test
    fun aRefusedLocalCopyIsRefusedWithoutCheckingAGuard() {
        // The stand-in gives no cursor, so a guard checked after the refusal would throw instead.
        assertEquals(ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED), contacts(OperationApplicationException()).insertLocal(write.rows, starred = false))
    }

    @Test
    fun anIllegalStateFailsALocalCopy() {
        assertEquals(ContactWriteResult.Failed(FailureReason.REJECTED_VALUE), contacts(IllegalStateException()).insertLocal(write.rows, starred = false))
    }

    @Test
    fun aMissingPermissionEndsALocalCopy() {
        assertThrows(SecurityException::class.java) { contacts(SecurityException()).insertLocal(write.rows, starred = false) }
    }

    @Test
    fun aRefusedImportWhoseGuardCantBeCheckedMeansTheProviderIsUnavailable() {
        // The stand-in gives no cursor, so the guard checked after the refusal can't tell stale from refused.
        assertThrows(ProviderUnavailableException::class.java) { contacts(OperationApplicationException()).insertImport(account, original, write.rows, starred = false) }
    }

    @Test
    fun anIllegalStateFailsAnImport() {
        assertEquals(ContactWriteResult.Failed(FailureReason.REJECTED_VALUE), contacts(IllegalStateException()).insertImport(account, original, write.rows, starred = false))
    }

    @Test
    fun aMissingPermissionEndsAnImport() {
        assertThrows(SecurityException::class.java) { contacts(SecurityException()).insertImport(account, original, write.rows, starred = false) }
    }

    @Test
    fun noCursorForTheAccountNamesMeansTheProviderIsUnavailable() {
        assertThrows(ProviderUnavailableException::class.java) { contacts(StandIn()).accountNamesWithRows() }
    }

    @Test
    fun noCursorWhenReadingAPhotoBackMeansTheProviderIsUnavailable() {
        // A missing cursor is never "no photo" or "untouched".
        assertThrows(ProviderUnavailableException::class.java) { contacts(StandIn()).photoHash(1) }
        assertThrows(ProviderUnavailableException::class.java) { contacts(StandIn()).untouched(1) }
    }

    @Test
    fun aMissingPermissionEndsAReadBack() {
        assertThrows(SecurityException::class.java) { contacts(StandIn(queryError = SecurityException())).photoHash(1) }
        assertThrows(SecurityException::class.java) { contacts(StandIn(queryError = SecurityException())).untouched(1) }
    }

    @Test
    fun aMissingPermissionEndsADeletion() {
        assertThrows(SecurityException::class.java) { contacts(StandIn(deleteError = SecurityException())).deleteAccountRows(account.name) }
    }

    @Test
    fun aMaximumPhotoSizeOfZeroOrLessMeansTheProviderIsUnavailable() {
        listOf(0, -1).forEach { odd ->
            assertThrows("$odd", ProviderUnavailableException::class.java) { contacts(StandIn(maxDimension = odd)).maxPhotoDimension() }
        }
        assertEquals(1, contacts(StandIn(maxDimension = 1)).maxPhotoDimension())
    }
}
