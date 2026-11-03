package dev.rboza.android.opensyncforproton.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import dev.rboza.android.opensyncforproton.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/**
 * The app's icon, "contact in a loop" (a person inside two chasing sync arrows), light violet on a dark tile; an
 * adaptive launcher icon with a monochrome layer for themed icons, and the same mark in the app. Pixels are read at
 * mdpi, 1 dp to 1 px; the design's 72-unit visible square sits at 18..90 on the 108-unit adaptive canvas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun launcherIcon() = app.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable

    private fun Drawable.pixels(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { setBounds(0, 0, size, size); draw(Canvas(it)) }

    @Test
    fun `the app's icon is the launcher icon`() {
        assertEquals(R.mipmap.ic_launcher, app.applicationInfo.icon)
    }

    @Test
    fun `the launcher icon is adaptive, the glyph on the dark tile, with a monochrome layer`() {
        val icon = launcherIcon()
        assertEquals(TILE, (icon.background as ColorDrawable).color)
        val glyph = icon.foreground.pixels(108)
        assertEquals("the ring's top", GLYPH, glyph.getPixel(54, 36))
        assertEquals("the head", GLYPH, glyph.getPixel(54, 49))
        assertEquals("inside the ring, above the head", 0, glyph.getPixel(54, 40))
        val monochrome = checkNotNull(icon.monochrome) { "no monochrome layer" }.pixels(108)
        for (x in 0 until 108) for (y in 0 until 108) {
            assertEquals("the monochrome layer's shape at $x,$y", glyph.getPixel(x, y) ushr 24, monochrome.getPixel(x, y) ushr 24)
        }
    }

    @Test
    fun `the glyph stays inside the adaptive icon's safe zone`() {
        val glyph = launcherIcon().foreground.pixels(108)
        for (x in 0 until 108) for (y in 0 until 108) {
            if (glyph.getPixel(x, y) ushr 24 != 0) {
                assertTrue("glyph at $x,$y", hypot(x + 0.5 - 54, y + 0.5 - 54) <= 33)
            }
        }
    }

    @Test
    fun `the mark in the app is the launcher icon's visible square, in its colours whatever the theme`() {
        for (theme in listOf("+notnight", "+night")) {
            RuntimeEnvironment.setQualifiers(theme)
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            controller.get().setContent { OspTheme { Box(Modifier.size(72.dp)) { BrandMark(size = 72.dp) } } }
            shadowOf(Looper.getMainLooper()).idle()
            val view = checkNotNull(composeView(controller.get().window.decorView))
            val drawn = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            controller.pause().stop().destroy()

            assertEquals("$theme: the ring's top", GLYPH, drawn.getPixel(36, 18))
            assertEquals("$theme: the ring's left", GLYPH, drawn.getPixel(18, 36))
            assertEquals("$theme: the head", GLYPH, drawn.getPixel(36, 31))
            assertEquals("$theme: inside the ring, above the head", TILE, drawn.getPixel(36, 22))
            assertEquals("$theme: outside the ring", TILE, drawn.getPixel(8, 36))
            assertEquals("$theme: the tile's rounded corner", 0, drawn.getPixel(1, 1))
        }
    }

    private fun composeView(view: View): ComposeView? = when (view) {
        is ComposeView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { composeView(view.getChildAt(it)) }
        else -> null
    }

    private companion object {
        const val TILE = 0xFF1F1C28.toInt()
        const val GLYPH = 0xFFA895FF.toInt()
    }
}
