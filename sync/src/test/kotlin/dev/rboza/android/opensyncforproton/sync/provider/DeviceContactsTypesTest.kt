package dev.rboza.android.opensyncforproton.sync.provider

import dev.rboza.android.opensyncforproton.account.AccountId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

// Neither contact content nor the account's e-mail and Proton IDs show in these types' `toString`. Values are made up.
class DeviceContactsTypesTest {
    @Test
    fun `no e-mail or Proton ID in toString`() {
        val text = listOf(
            PhoneAccount(AccountId("user-id-1"), "ann@example.test"),
            DeviceContact(7, "proton-id-1", dirty = false, deleted = false, version = 3, displayName = "Ann Lee"),
            ContactWrite("proton-id-1", ExistingRow(7, 3), listOf(DataRow("vnd.android.cursor.item/note", mapOf("data1" to ColumnValue.Text("hello"))))),
        ).joinToString()

        listOf("user-id-1", "ann@example.test", "proton-id-1", "hello", "Ann Lee").forEach { assertFalse(text.contains(it), it) }
    }
}
