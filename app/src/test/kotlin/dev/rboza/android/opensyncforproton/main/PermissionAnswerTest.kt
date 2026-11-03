package dev.rboza.android.opensyncforproton.main

import android.Manifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// What Android's answer to the contacts permission request means (docs/sync.md § Sync mode).
class PermissionAnswerTest {
    private val read = Manifest.permission.READ_CONTACTS
    private val write = Manifest.permission.WRITE_CONTACTS

    @Test
    fun `granted only when both are`() {
        assertEquals(PermissionAnswer(granted = true, canAskAgain = false), permissionAnswer(mapOf(read to true, write to true)) { false })
        assertEquals(false, permissionAnswer(mapOf(read to true, write to false)) { false }.granted)
        assertEquals(false, permissionAnswer(mapOf(read to false, write to true)) { false }.granted)
        assertEquals(false, permissionAnswer(mapOf(read to true)) { false }.granted)
        assertEquals(false, permissionAnswer(emptyMap()) { false }.granted, "a request that ended without an answer")
    }

    @Test
    fun `Android asks again while it gives a rationale for either`() {
        val refused = mapOf(read to false, write to false)
        assertEquals(PermissionAnswer(granted = false, canAskAgain = false), permissionAnswer(refused) { false })
        assertEquals(true, permissionAnswer(refused) { it == read }.canAskAgain)
        assertEquals(true, permissionAnswer(refused) { it == write }.canAskAgain)
        assertEquals(false, permissionAnswer(refused) { it == "android.permission.CAMERA" }.canAskAgain)
    }
}
