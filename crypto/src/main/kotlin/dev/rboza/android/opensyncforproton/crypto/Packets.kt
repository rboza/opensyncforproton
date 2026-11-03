package dev.rboza.android.opensyncforproton.crypto

/**
 * One OpenPGP packet header (RFC 9580 § 4.2): the packet's tag, where its body starts, and its length, which is null
 * for a partial (new format) or indeterminate (legacy format) length. A definite length always fits in [bytes].
 */
internal class PacketHeader(val tag: Int, val bodyStart: Int, val length: Int?, internal val lengthStart: Int)

/** A new-format length (RFC 9580 § 4.2.1): its value, whether it's a partial body length, and where it ends. */
private class NewFormatLength(val value: Int, val partial: Boolean, val end: Int)

private fun newFormatLength(bytes: ByteArray, at: Int): NewFormatLength? {
    var i = at
    val l0 = bytes.getOrNull(i++)?.toInt()?.and(0xff) ?: return null
    return when {
        l0 < 192 -> NewFormatLength(l0, partial = false, i)
        l0 < 224 -> NewFormatLength(((l0 - 192) shl 8) + (bytes.getOrNull(i++)?.toInt()?.and(0xff) ?: return null) + 192, partial = false, i)
        l0 == 255 -> bigEndian(bytes, i, 4)?.let { NewFormatLength(it, partial = false, i + 4) }
        else -> NewFormatLength(1 shl (l0 and 0x1f), partial = true, i)
    }
}

/**
 * Where a new-format packet with partial body lengths ends: after each chunk comes the next length, until a definite
 * one. Null if a length can't be read; a result past the end of [bytes] means a chunk ran past it.
 */
internal fun partialPacketEnd(bytes: ByteArray, header: PacketHeader): Int? {
    var length = newFormatLength(bytes, header.lengthStart) ?: return null
    while (true) {
        // A chunk running past the end needs no check of its own: the next length can't be read there, and a final
        // chunk's end isn't the message's (an overflowing sum only makes it negative).
        val end = length.end + length.value
        if (!length.partial) return end
        length = newFormatLength(bytes, end) ?: return null
    }
}

/** The header at [at], or null if there's none or it's malformed. */
internal fun readPacketHeader(bytes: ByteArray, at: Int): PacketHeader? {
    val header = bytes.getOrNull(at)?.toInt()?.and(0xff) ?: return null
    if (header and 0x80 == 0) return null
    val lengthStart = at + 1
    var i = lengthStart
    val tag: Int
    val length: Int?
    if (header and 0x40 != 0) {
        // New format (RFC 9580 § 4.2.1).
        tag = header and 0x3f
        val newFormat = newFormatLength(bytes, i) ?: return null
        i = newFormat.end
        length = if (newFormat.partial) null else newFormat.value
    } else {
        // Legacy format (RFC 9580 § 4.2.2).
        tag = (header shr 2) and 0x0f
        val size = when (header and 0x03) {
            0 -> 1
            1 -> 2
            2 -> 4
            else -> 0
        }
        length = if (size == 0) null else bigEndian(bytes, i, size)?.also { i += size } ?: return null
    }
    // Compared without adding, so a length near Int.MAX_VALUE can't wrap.
    if (length != null && length > bytes.size - i) return null
    return PacketHeader(tag, i, length, lengthStart)
}

private fun bigEndian(bytes: ByteArray, at: Int, size: Int): Int? {
    if (size > bytes.size - at) return null
    var value = 0L
    for (k in 0 until size) value = (value shl 8) or (bytes[at + k].toLong() and 0xff)
    return if (value > Int.MAX_VALUE) null else value.toInt()
}

/**
 * Whether an armored card's packets are a message encrypted to keys (docs/protocol.md § Contact cards): one or more
 * public-key session key packets (tag 1), then integrity-protected encrypted data (tag 18), and nothing after it. The
 * library "decrypts" a message that holds only literal data, which was never encrypted or integrity-checked, and
 * decrypts the data and then reads on into a plain packet after it.
 */
internal fun isEncryptedToKeys(bytes: ByteArray): Boolean {
    var i = 0
    var sessionKeys = 0
    while (true) {
        val header = readPacketHeader(bytes, i) ?: return false
        when (header.tag) {
            PUBLIC_KEY_SESSION_KEY -> {
                sessionKeys++
                i = header.bodyStart + (header.length ?: return false)
            }
            // With partial lengths, every chunk is walked: the library reads on into any packet after the last one.
            INTEGRITY_PROTECTED_DATA -> {
                val end = if (header.length != null) header.bodyStart + header.length else partialPacketEnd(bytes, header)
                return sessionKeys > 0 && end == bytes.size
            }
            else -> return false
        }
    }
}

private const val PUBLIC_KEY_SESSION_KEY = 1
private const val INTEGRITY_PROTECTED_DATA = 18
