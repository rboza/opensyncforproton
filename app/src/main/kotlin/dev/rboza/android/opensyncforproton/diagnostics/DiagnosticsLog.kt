package dev.rboza.android.opensyncforproton.diagnostics

import dev.rboza.android.opensyncforproton.account.AccountId
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** An ID as it may appear in a log: a keyed hash, the same for one install, meaningless without its key (D23). */
@JvmInline
value class Pseudonym internal constructor(val hex: String) {
    override fun toString() = hex
}

/**
 * Lowercase hex of the first 12 bytes of `HMAC-SHA256(installKey, domain ‖ 0x00 ‖ id)`
 * (docs/architecture.md § Diagnostics). Records name only accounts (domain `account`). Pure; safe from any thread.
 */
class Pseudonyms(private val installKey: ByteArray) {
    fun account(id: AccountId): Pseudonym = of("account", id.value)

    private fun of(domain: String, id: String): Pseudonym {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(installKey, "HmacSHA256")) }
        val digest = mac.doFinal(domain.toByteArray() + byteArrayOf(0) + id.toByteArray())
        return Pseudonym(digest.copyOf(12).joinToString("") { "%02x".format(it) })
    }
}

/**
 * The install's pseudonym key: 32 random bytes in [file] (under no-backup storage). Missing or corrupt (any other
 * length), a new one is written, which only changes the pseudonyms from then on. The composition root calls it once per
 * process (its temporary file has a fixed name). Blocking; throws what reading, writing, or moving the file throws.
 */
fun installKey(file: File): ByteArray {
    val stored = if (file.isFile) file.readBytes() else null
    if (stored != null && stored.size == KEY_BYTES) return stored
    val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeBytes(key)
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    return key
}

private const val KEY_BYTES = 32

/**
 * One diagnostics record (D23): only typed values, so nothing personal can be put in one. Enum names must be plain
 * identifiers; a record with any other is dropped. [counts] keep their order.
 */
class LogRecord(
    /** Epoch milliseconds. */
    val at: Long,
    val operation: Enum<*>,
    val account: Pseudonym?,
    val outcome: Enum<*>?,
    val counts: Map<out Enum<*>, Int>,
    val durationMs: Long?,
)

/**
 * The app's diagnostics: two JSON-lines files in [dir] (no-backup storage), `current.jsonl` and `previous.jsonl`, each
 * at most 256 KiB; the current one becomes the previous one before a record would take it past that. A record over
 * 4 KiB (its newline included) is dropped. A torn last line (the process died mid-write) is dropped before the next
 * record and from an export. Nothing reaches logcat. A record that can't be written (a full disk, say) is dropped: a
 * log never fails what it describes. Blocking; safe from any thread, with one instance per process for a directory (the
 * composition root's), since the lock is the instance's. An export holds the lock while it writes.
 */
class DiagnosticsLog(private val dir: File) {
    private val current = File(dir, "current.jsonl")
    private val previous = File(dir, "previous.jsonl")

    @Synchronized
    fun record(record: LogRecord) {
        val line = encode(record)?.plus("\n")?.toByteArray() ?: return
        if (line.size > MAX_RECORD) return
        try {
            dir.mkdirs()
            dropTornLine(current)
            if (current.length() + line.size > MAX_FILE) {
                Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            current.appendBytes(line)
        } catch (_: IOException) {
            // Dropped, as an oversized record is; nothing of it is reported anywhere (D23).
        }
    }

    /** The previous file, then the current one, without a torn last line; nothing if there are none. */
    @Synchronized
    fun export(out: OutputStream) {
        listOf(previous, current).filter { it.isFile }.forEach { file ->
            dropTornLine(file)
            file.inputStream().use { it.copyTo(out) }
        }
    }

    private fun dropTornLine(file: File) {
        if (!file.isFile || file.length() == 0L) return
        RandomAccessFile(file, "rw").use { raf ->
            var end = raf.length()
            while (end > 0) {
                raf.seek(end - 1)
                if (raf.read() == '\n'.code) break
                end--
            }
            if (end != raf.length()) raf.setLength(end)
        }
    }

    private companion object {
        const val MAX_FILE = 256 * 1024
        const val MAX_RECORD = 4 * 1024
        val IDENTIFIER = Regex("[A-Za-z0-9_]+")
        val PSEUDONYM = Regex("[0-9a-f]{24}")

        /**
         * JSON written by hand: every value is a number, an identifier, or a pseudonym's hex, so nothing needs
         * escaping; a record that breaks that (a Pseudonym made from anything but Pseudonyms, two counts of one name)
         * is dropped.
         */
        fun encode(record: LogRecord): String? {
            val names = listOfNotNull(record.operation, record.outcome) + record.counts.keys
            if (names.any { !IDENTIFIER.matches(it.name) }) return null
            if (record.account != null && !PSEUDONYM.matches(record.account.hex)) return null
            if (record.counts.keys.map { it.name }.toSet().size != record.counts.size) return null
            return buildString {
                append("{\"at\":").append(record.at)
                append(",\"op\":\"").append(record.operation.name).append('"')
                record.account?.let { append(",\"account\":\"").append(it.hex).append('"') }
                record.outcome?.let { append(",\"outcome\":\"").append(it.name).append('"') }
                if (record.counts.isNotEmpty()) {
                    append(",\"counts\":{")
                    append(record.counts.entries.joinToString(",") { "\"${it.key.name}\":${it.value}" })
                    append('}')
                }
                record.durationMs?.let { append(",\"ms\":").append(it) }
                append('}')
            }
        }
    }
}
