package dev.rboza.android.opensyncforproton.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The app's own violet scheme, light or dark as the system is, never the wallpaper's dynamic colours. */
@RunWith(RobolectricTestRunner::class)
class OspThemeTest {
    private fun scheme(night: Boolean): ColorScheme {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var seen: ColorScheme? = null
        controller.get().setContent { OspTheme { seen = MaterialTheme.colorScheme } }
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
        return checkNotNull(seen)
    }

    @Test
    fun `light, the violet scheme`() {
        val colors = scheme(night = false)
        assertEquals(Color(0xFF6D4AFF), colors.primary)
        assertEquals(Color(0xFFF6F4FB), colors.background)
    }

    @Test
    fun `dark, the violet scheme`() {
        val colors = scheme(night = true)
        assertEquals(Color(0xFFA895FF), colors.primary)
        assertEquals(Color(0xFF14121B), colors.background)
    }
}
