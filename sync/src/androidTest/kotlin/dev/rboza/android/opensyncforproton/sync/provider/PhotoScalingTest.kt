package dev.rboza.android.opensyncforproton.sync.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Photo scaling (docs/mapping.md § Photos): every photo is decoded, turned upright, downscaled to the maximum (never
 * upscaled), and re-encoded as JPEG, unless it's a JPEG that needs none of that, which goes as it is. Every image is
 * drawn by the test; none is a real photo.
 */
@RunWith(AndroidJUnit4::class)
class PhotoScalingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** [width]×[height], red on the left half and blue on the right, so a test can tell which way is up. */
    private fun halves(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap =
        Bitmap.createBitmap(width, height, config).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED })
            canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.BLUE })
        }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int = 95): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()

    /** A JPEG with an EXIF orientation, written the way a camera would. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val file = File.createTempFile("test-only-photo", ".jpg", context.cacheDir)
        try {
            file.writeBytes(jpeg)
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /**
     * A greyscale PNG claiming [size]×[size] pixels, all black. It compresses to about 1 MB, but decoding it at full
     * size needs far more than a bitmap may have, so only a decoder that samples as it decodes can read it.
     */
    private fun hugePng(size: Int): ByteArray {
        fun DataOutputStream.chunk(type: String, data: ByteArray) {
            writeInt(data.size)
            val typed = type.encodeToByteArray() + data
            write(typed)
            writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        val idat = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { deflate ->
                val row = ByteArray(size + 1) // filter byte 0, then the row
                repeat(size) { deflate.write(row) }
            }
        }.toByteArray()
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(size); writeInt(size); write(byteArrayOf(8, 0, 0, 0, 0)) } }
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
                chunk("IHDR", ihdr.toByteArray())
                chunk("IDAT", idat)
                chunk("IEND", ByteArray(0))
            }
        }.toByteArray()
    }

    private fun noise(size: Int, seed: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, quality: Int = 95): ByteArray {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(IntArray(size * size) { random.nextInt() or Color.BLACK }, size, size, Bitmap.Config.ARGB_8888)
        return encode(bitmap, format, quality)
    }

    private fun jpeg(result: ScaledPhoto): Bitmap {
        val bytes = (result as ScaledPhoto.Jpeg).bytes
        assertEquals("a JPEG", listOf(0xFF.toByte(), 0xD8.toByte()), bytes.take(2))
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun assertColor(expected: Int, actual: Int, where: String) {
        // JPEG is lossy: each channel within 40.
        listOf(Color::red, Color::green, Color::blue).forEach {
            assertTrue("$where: ${Integer.toHexString(actual)} isn't ${Integer.toHexString(expected)}", kotlin.math.abs(it(expected) - it(actual)) <= 40)
        }
    }

    @Test
    fun aLargeJpegIsScaledToTheMaximum() {
        val photo = jpeg(scalePhoto(encode(halves(4000, 3000), Bitmap.CompressFormat.JPEG), maxDimension = 720))

        assertEquals(720, photo.width)
        assertEquals(540, photo.height)
        assertColor(Color.RED, photo.getPixel(100, 270), "left")
        assertColor(Color.BLUE, photo.getPixel(620, 270), "right")
    }

    @Test
    fun theMaximumIsAParameter() {
        val photo = jpeg(scalePhoto(encode(halves(400, 300), Bitmap.CompressFormat.PNG), maxDimension = 100))

        assertEquals(100, photo.width)
        assertEquals(75, photo.height)
    }

    @Test
    fun aTallPhotoIsLimitedByItsHeight() {
        val photo = jpeg(scalePhoto(encode(halves(300, 1200), Bitmap.CompressFormat.PNG), maxDimension = 600))

        assertEquals(150, photo.width)
        assertEquals(600, photo.height)
    }

    @Test
    fun theShorterSideIsRounded() {
        // 1999 × 0.24 = 479.76.
        val photo = jpeg(scalePhoto(encode(halves(3000, 1999), Bitmap.CompressFormat.JPEG), maxDimension = 720))

        assertEquals(720, photo.width)
        assertEquals(480, photo.height)
    }

    @Test
    fun aThinStripKeepsAtLeastOnePixel() {
        val photo = jpeg(scalePhoto(encode(halves(4000, 2), Bitmap.CompressFormat.PNG), maxDimension = 720))

        assertEquals(720, photo.width)
        assertEquals(1, photo.height)
    }

    @Test
    fun aSmallPhotoIsNeverUpscaled() {
        val photo = jpeg(scalePhoto(encode(halves(300, 200), Bitmap.CompressFormat.PNG), maxDimension = 720))

        assertEquals(300, photo.width)
        assertEquals(200, photo.height)
    }

    @Test
    fun aPhotoAtTheMaximumKeepsItsSize() {
        val photo = jpeg(scalePhoto(encode(halves(720, 400), Bitmap.CompressFormat.JPEG), maxDimension = 720))

        assertEquals(720, photo.width)
        assertEquals(400, photo.height)
    }

    @Test
    fun theExifOrientationIsApplied() {
        // 6: the camera was turned 90° clockwise, so the stored left edge is the top of the picture.
        val rotated = withOrientation(encode(halves(4000, 3000), Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_ROTATE_90)

        val photo = jpeg(scalePhoto(rotated, maxDimension = 720))

        assertEquals(540, photo.width)
        assertEquals(720, photo.height)
        assertColor(Color.RED, photo.getPixel(270, 100), "top")
        assertColor(Color.BLUE, photo.getPixel(270, 620), "bottom")
    }

    @Test
    fun everyRotationAndMirrorIsApplied() {
        // 200×100, white, with a green square in its stored top-left corner; where the square ends up shows how the
        // photo was turned (5 transposes, 6 turns 90° clockwise, 7 transverses, 8 turns 90° anticlockwise).
        val marked = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(0f, 0f, 40f, 40f, Paint().apply { color = Color.GREEN })
        }
        val corners = mapOf(
            ExifInterface.ORIENTATION_TRANSPOSE to (10 to 10),
            ExifInterface.ORIENTATION_ROTATE_90 to (89 to 10),
            ExifInterface.ORIENTATION_TRANSVERSE to (89 to 189),
            ExifInterface.ORIENTATION_ROTATE_270 to (10 to 189),
        )
        corners.forEach { (orientation, corner) ->
            val photo = jpeg(scalePhoto(withOrientation(encode(marked, Bitmap.CompressFormat.JPEG), orientation), maxDimension = 720))

            assertEquals("$orientation", 100, photo.width)
            assertColor(Color.GREEN, photo.getPixel(corner.first, corner.second), "orientation $orientation")
        }
    }

    @Test
    fun theOtherOrientationsAreAppliedToo() {
        // As above, for the turns that keep the photo's shape: 2 mirrors it, 3 turns it 180°, 4 flips it upside down.
        val marked = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(0f, 0f, 40f, 40f, Paint().apply { color = Color.GREEN })
        }
        val corners = mapOf(
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to (189 to 10),
            ExifInterface.ORIENTATION_ROTATE_180 to (189 to 89),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to (10 to 89),
        )
        corners.forEach { (orientation, corner) ->
            val photo = jpeg(scalePhoto(withOrientation(encode(marked, Bitmap.CompressFormat.JPEG), orientation), maxDimension = 720))

            assertEquals("$orientation", 200, photo.width)
            assertColor(Color.GREEN, photo.getPixel(corner.first, corner.second), "orientation $orientation")
        }
    }

    @Test
    fun aSmallRotatedPhotoIsTurnedUprightToo() {
        val rotated = withOrientation(encode(halves(200, 100), Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_ROTATE_90)

        val photo = jpeg(scalePhoto(rotated, maxDimension = 720))

        assertEquals(100, photo.width)
        assertEquals(200, photo.height)
        assertColor(Color.RED, photo.getPixel(50, 20), "top")
    }

    @Test
    fun anUprightJpegWithinTheLimitsGoesAsItIs() {
        // A photo that needs no change loses nothing to the app, so the phone's photo uploads to Proton byte for byte.
        val small = encode(halves(300, 200), Bitmap.CompressFormat.JPEG)
        val cases = mapOf(
            "small" to small,
            "at the maximum" to encode(halves(720, 400), Bitmap.CompressFormat.JPEG, quality = 75),
            "marked upright" to withOrientation(small, ExifInterface.ORIENTATION_NORMAL),
            "marked undefined" to withOrientation(small, ExifInterface.ORIENTATION_UNDEFINED),
        )
        cases.forEach { (name, bytes) -> assertArrayEquals(name, bytes, (scalePhoto(bytes, maxDimension = 720) as ScaledPhoto.Jpeg).bytes) }
    }

    @Test
    fun aJpegOver512KibIsMadeAgain() {
        // At quality 100, 720×720 noise is over 512 KiB; at quality 90 it fits (above).
        val large = noise(720, seed = 4, Bitmap.CompressFormat.JPEG, quality = 100)
        assertTrue(large.size > 512 * 1024)

        val photo = scalePhoto(large, maxDimension = 720) as ScaledPhoto.Jpeg

        assertTrue(photo.bytes.size <= 512 * 1024)
    }

    @Test
    fun otherFormatsBecomeJpeg() {
        listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP_LOSSLESS).forEach { format ->
            val photo = jpeg(scalePhoto(encode(halves(64, 32), format), maxDimension = 720))

            assertEquals("$format", 64, photo.width)
        }
    }

    @Test
    fun theJpegQualityIs90() {
        // Unscaled and opaque, the photo's pixels are the original's, so the encoder gives the same bytes.
        val original = halves(64, 32)

        val result = scalePhoto(encode(original, Bitmap.CompressFormat.PNG), maxDimension = 720) as ScaledPhoto.Jpeg

        assertTrue(result.bytes.contentEquals(encode(original, Bitmap.CompressFormat.JPEG, quality = 90)))
    }

    @Test
    fun transparentPixelsBecomeWhite() {
        val clear = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }

        val photo = jpeg(scalePhoto(encode(clear, Bitmap.CompressFormat.PNG), maxDimension = 720))

        assertColor(Color.WHITE, photo.getPixel(20, 20), "centre")
    }

    @Test
    fun malformedPhotosAreRejected() {
        val whole = encode(halves(400, 300), Bitmap.CompressFormat.JPEG)
        val cases = mapOf(
            "empty" to ByteArray(0),
            "not an image" to "test-only, not an image".encodeToByteArray(),
            "random bytes" to Random(1).nextBytes(4096),
            "truncated JPEG" to whole.copyOf(whole.size / 2),
            "JPEG header only" to whole.copyOf(20),
        )
        cases.forEach { (name, bytes) -> assertSame(name, ScaledPhoto.Malformed, scalePhoto(bytes, maxDimension = 720)) }
    }

    @Test
    fun aJpegCutShortAtAnEndMarkerStillDecodes() {
        // ImageDecoder takes data that stops at a proper end marker as complete, its missing part grey; only an error
        // it reports makes a photo malformed.
        val whole = encode(halves(400, 300), Bitmap.CompressFormat.JPEG)
        val cut = whole.copyOf(whole.size / 2) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val photo = jpeg(scalePhoto(cut, maxDimension = 720))

        assertEquals(400, photo.width)
    }

    @Test
    fun aHugePhotoIsSampledAsItDecodes() {
        val photo = jpeg(scalePhoto(hugePng(20_000), maxDimension = 720))

        assertEquals(720, photo.width)
        assertEquals(720, photo.height)
    }

    @Test
    fun aPhotoStillOver512KibAfterScalingIsOversized() {
        // Noise barely compresses, and at its own size nothing smooths it. At quality 90, 720×720 noise is about
        // 420 KB and 820×820 about 550 KB, so these two pin the limit near 512 KiB.
        assertSame(ScaledPhoto.Oversized, scalePhoto(noise(820, seed = 2), maxDimension = 820))
        assertTrue(scalePhoto(noise(720, seed = 3), maxDimension = 720) is ScaledPhoto.Jpeg)
    }

    @Test
    fun theMaximumMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { scalePhoto(encode(halves(64, 32), Bitmap.CompressFormat.PNG), maxDimension = 0) }
    }

    @Test
    fun theJpegIsHandedOutAsCopies() {
        val result = scalePhoto(encode(halves(64, 32), Bitmap.CompressFormat.PNG), maxDimension = 720) as ScaledPhoto.Jpeg
        val first = result.bytes[0]

        result.bytes[0] = (first + 1).toByte()

        assertEquals(first, result.bytes[0])
    }

    @Test
    fun theResultShowsOnlyItsSize() {
        val result = scalePhoto(encode(halves(64, 32), Bitmap.CompressFormat.PNG), maxDimension = 720)

        assertEquals("ScaledPhoto.Jpeg(size=${(result as ScaledPhoto.Jpeg).bytes.size})", result.toString())
        assertFalse(result.bytes.contentEquals(ByteArray(0)))
    }
}
