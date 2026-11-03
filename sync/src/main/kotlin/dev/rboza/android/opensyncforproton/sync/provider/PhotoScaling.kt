package dev.rboza.android.opensyncforproton.sync.provider

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** What [scalePhoto] made of a contact's photo. `toString` shows no content. */
sealed interface ScaledPhoto {
    /**
     * The photo to write: an upright JPEG no larger than the maximum dimension, the original's bytes when it needed no
     * change. Keeps its own copy of the bytes.
     */
    class Jpeg(bytes: ByteArray) : ScaledPhoto {
        private val data = bytes.copyOf()
        val bytes: ByteArray get() = data.copyOf()
        override fun toString() = "ScaledPhoto.Jpeg(size=${data.size})"
    }

    data object Malformed : ScaledPhoto

    data object Oversized : ScaledPhoto
}

/** [scalePhoto] behind an interface, so the engine's JVM tests can stand in for Android's image codecs. */
fun interface PhotoScaler {
    fun scale(encoded: ByteArray, maxDimension: Int): ScaledPhoto
}

/**
 * Every photo is decoded (whatever its media type says), turned upright per its EXIF orientation, downscaled so neither
 * side exceeds [maxDimension] (never upscaled), drawn on white (JPEG has no transparency), and re-encoded as JPEG at
 * quality 90. A JPEG that needs none of that (no larger than [maxDimension], upright, and no more than 512 KiB) goes as
 * it is once it decodes. A photo ImageDecoder reports an error for is [ScaledPhoto.Malformed] (a JPEG cut short at a
 * proper end marker isn't one: it decodes with its missing part grey); one still over 512 KiB after that is
 * [ScaledPhoto.Oversized]. Either rejects the contact's write. [maxDimension] is the provider's maximum display
 * dimension, which the caller reads; one of 0 or less throws IllegalArgumentException.
 */
fun scalePhoto(encoded: ByteArray, maxDimension: Int): ScaledPhoto {
    require(maxDimension > 0)
    var jpegWithinLimits = false
    val decoded = try {
        // ImageDecoder applies the EXIF orientation, and its header size is the upright one.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height)
            jpegWithinLimits = info.mimeType == "image/jpeg" && longest <= maxDimension && encoded.size <= MAX_PHOTO_BYTES
            if (longest > maxDimension) {
                val scale = maxDimension.toDouble() / longest
                // The decoder samples down first, then scales, so a large photo never needs its full size in memory.
                decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
            }
            // A software bitmap, so it can be drawn on the white canvas below.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: IOException) {
        // ImageDecoder.DecodeException is an IOException: not an image, or (with no partial-image listener, which
        // would let it fill the rest with blank) an incomplete one.
        return ScaledPhoto.Malformed
    }
    if (jpegWithinLimits && uprightAsStored(encoded)) {
        decoded.recycle()
        return ScaledPhoto.Jpeg(encoded)
    }
    try {
        val upright = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(upright).apply {
                drawColor(Color.WHITE)
                drawBitmap(decoded, 0f, 0f, null)
            }
            val jpeg = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            return if (jpeg.size > MAX_PHOTO_BYTES) ScaledPhoto.Oversized else ScaledPhoto.Jpeg(jpeg)
        } finally {
            upright.recycle()
        }
    } finally {
        decoded.recycle()
    }
}

/**
 * Whether [jpeg]'s EXIF orientation leaves it as stored: 1, or 0 (undefined), which ImageDecoder leaves so too and
 * ExifInterface reports for a JPEG without one. EXIF that ExifInterface throws on isn't: the photo is encoded again
 * rather than one card's photo ending every run.
 */
private fun uprightAsStored(jpeg: ByteArray): Boolean = try {
    ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) in
        setOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED)
} catch (_: RuntimeException) {
    false
}

private const val JPEG_QUALITY = 90

/** One contact's write must fit a single Binder transaction (about 1 MiB). */
private const val MAX_PHOTO_BYTES = 512 * 1024
