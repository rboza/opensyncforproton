package dev.rboza.android.opensyncforproton.diagnostics

import dev.rboza.android.opensyncforproton.account.AccountId
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

// The diagnostics log: typed records only, pseudonyms, the two rotating files, torn lines (D23;
// docs/architecture.md § Diagnostics). Values are made up.
class DiagnosticsLogTest {
    @TempDir
    lateinit var dir: File

    private enum class Op { RUN, LOGOUT }
    private enum class Outcome { CAPTCHA }
    private enum class Count { NEW_FROM_PROTON, UNREADABLE }

    @Suppress("EnumEntryName")
    private enum class Odd { `has space` }
    private enum class Other { NEW_FROM_PROTON }

    private fun log() = DiagnosticsLog(File(dir, "logs"))

    private fun lines(name: String) = File(File(dir, "logs"), name).takeIf { it.exists() }?.readLines().orEmpty()

    private fun record(op: Enum<*> = Op.RUN, account: Pseudonym? = null, outcome: Enum<*>? = null, counts: Map<out Enum<*>, Int> = emptyMap(), ms: Long? = null) =
        LogRecord(at = 1_767_225_600_000, operation = op, account = account, outcome = outcome, counts = counts, durationMs = ms)

    // Pseudonyms

    @Test
    fun `a pseudonym is the first 12 bytes of HMAC-SHA256(installKey, domain, 0, id) in lowercase hex`() {
        val key = ByteArray(32) { it.toByte() }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val expected = mac.doFinal("account".toByteArray() + byteArrayOf(0) + "user-id-1".toByteArray())
            .copyOf(12).joinToString("") { "%02x".format(it) }

        assertEquals(expected, Pseudonyms(key).account(AccountId("user-id-1")).hex)
        assertEquals(24, expected.length)
    }

    @Test
    fun `pseudonyms are stable for one key and differ across keys and IDs`() {
        val one = Pseudonyms(ByteArray(32) { 1 })
        assertEquals(one.account(AccountId("u1")), one.account(AccountId("u1")))
        assertNotEquals(one.account(AccountId("u1")), one.account(AccountId("u2")))
        assertNotEquals(one.account(AccountId("u1")), Pseudonyms(ByteArray(32) { 2 }).account(AccountId("u1")))
    }

    @Test
    fun `the install key is 32 random bytes kept in its file`() {
        val file = File(dir, "install.key")
        val first = installKey(file)
        assertEquals(32, first.size)
        assertArrayEquals(first, file.readBytes())
        assertArrayEquals(first, installKey(file))
        assertFalse(first.all { it == 0.toByte() })
        assertFalse(first.contentEquals(installKey(File(dir, "other.key"))))
    }

    @Test
    fun `a missing or corrupt install key is replaced`() {
        val file = File(dir, "install.key")
        file.writeBytes(ByteArray(7))
        val replaced = installKey(file)
        assertEquals(32, replaced.size)
        assertArrayEquals(replaced, file.readBytes())
    }

    @Test
    fun `a pseudonym's toString is its hex, nothing else`() {
        val pseudonym = Pseudonyms(ByteArray(32)).account(AccountId("someone.made.up@example.test"))
        assertEquals(pseudonym.hex, pseudonym.toString())
    }

    // Records

    @Test
    fun `a record is one JSON line of its typed fields, missing ones left out`() {
        val account = Pseudonyms(ByteArray(32)).account(AccountId("u1"))
        log().record(record(account = account, outcome = Outcome.CAPTCHA, counts = linkedMapOf(Count.NEW_FROM_PROTON to 3, Count.UNREADABLE to 0), ms = 125))
        log().record(record(op = Op.LOGOUT))

        assertEquals(
            listOf(
                """{"at":1767225600000,"op":"RUN","account":"${account.hex}","outcome":"CAPTCHA","counts":{"NEW_FROM_PROTON":3,"UNREADABLE":0},"ms":125}""",
                """{"at":1767225600000,"op":"LOGOUT"}""",
            ),
            lines("current.jsonl"),
        )
    }

    @Test
    fun `a record whose enum names aren't plain identifiers is dropped`() {
        log().record(record(op = Odd.`has space`))
        log().record(record(outcome = Odd.`has space`))
        log().record(record(counts = mapOf(Odd.`has space` to 1)))
        assertEquals(emptyList<String>(), lines("current.jsonl"))
    }

    @Test
    fun `a record whose pseudonym isn't one is dropped`() {
        log().record(record(account = Pseudonym("someone.made.up@example.test")))
        log().record(record(account = Pseudonym("0123456789abcdef0123456")))
        log().record(record(account = Pseudonym("0123456789ABCDEF01234567")))
        log().record(record(account = Pseudonym("0123456789abcdef012345678")))
        assertEquals(emptyList<String>(), lines("current.jsonl"))
    }

    @Test
    fun `a record with two counts of one name is dropped`() {
        log().record(record(counts = linkedMapOf(Count.NEW_FROM_PROTON to 1, Other.NEW_FROM_PROTON to 2)))
        assertEquals(emptyList<String>(), lines("current.jsonl"))
    }

    @Test
    fun `a record that can't be written is dropped without failing its caller`() {
        val notADirectory = File(dir, "logs").apply { writeText("a file") }
        DiagnosticsLog(notADirectory).record(record())
        assertEquals("a file", notADirectory.readText())
    }

    @Test
    fun `a record that fills the current file to exactly 256 KiB stays, and the next rotates it`() {
        val logs = File(dir, "logs").apply { mkdirs() }
        val line = """{"at":1767225600000,"op":"RUN"}""" + "\n"
        val filler = 256 * 1024 - line.length
        File(logs, "current.jsonl").writeText("x".repeat(filler - 1) + "\n")

        log().record(record())
        assertEquals(256L * 1024, File(logs, "current.jsonl").length())
        assertFalse(File(logs, "previous.jsonl").exists())

        log().record(record())
        assertEquals(256L * 1024, File(logs, "previous.jsonl").length())
        assertEquals(listOf(line.trimEnd()), lines("current.jsonl"))
    }

    @Test
    fun `a record over 4 KiB is dropped, one of 4 KiB is kept`() {
        val big = LongNames.entries.associateWith { 1 }
        log().record(record(counts = big))
        assertEquals(emptyList<String>(), lines("current.jsonl"))

        // Exactly 4096 bytes with its newline: as many counts of 1 as fit, then wider values fill the rest.
        val head = """{"at":1767225600000,"op":"RUN","counts":{"""
        fun line(counts: Map<LongNames, Int>) = head + counts.entries.joinToString(",") { "\"${it.key.name}\":${it.value}" } + "}}\n"
        val counts = linkedMapOf<LongNames, Int>()
        for (name in LongNames.entries) {
            if (line(counts + (name to 1)).length > 4096) break
            counts[name] = 1
        }
        for (name in counts.keys.toList()) {
            val room = 4096 - line(counts).length
            if (room == 0) break
            counts[name] = "1${"0".repeat(minOf(room, 9))}".toInt()
        }
        assertEquals(4096, line(counts).length)
        log().record(record(counts = counts))
        assertEquals(4096L, File(File(dir, "logs"), "current.jsonl").length())
    }

    @Test
    fun `the current file rotates to previous before it would pass 256 KiB`() {
        val log = log()
        val counts = LongNames.entries.take(20).associateWith { 1 }
        repeat(400) { log.record(record(counts = counts)) }

        val current = File(File(dir, "logs"), "current.jsonl")
        val previous = File(File(dir, "logs"), "previous.jsonl")
        assertTrue(previous.exists())
        assertTrue(current.length() <= 256 * 1024, "current ${current.length()}")
        assertTrue(previous.length() <= 256 * 1024, "previous ${previous.length()}")
        assertTrue(previous.length() > 200 * 1024, "previous was full: ${previous.length()}")
        val all = lines("previous.jsonl") + lines("current.jsonl")
        assertTrue(all.size in 1..400)
        assertTrue(all.all { it.startsWith("{") && it.endsWith("}") })
    }

    @Test
    fun `a torn last line is dropped before the next record`() {
        log().record(record(op = Op.RUN))
        File(File(dir, "logs"), "current.jsonl").appendText("""{"at":17672""")

        log().record(record(op = Op.LOGOUT))

        assertEquals(listOf("""{"at":1767225600000,"op":"RUN"}""", """{"at":1767225600000,"op":"LOGOUT"}"""), lines("current.jsonl"))
    }

    @Test
    fun `export writes previous then current, without a torn line`() {
        val logs = File(dir, "logs").apply { mkdirs() }
        File(logs, "previous.jsonl").writeText("""{"at":1,"op":"RUN"}""" + "\n")
        File(logs, "current.jsonl").writeText("""{"at":2,"op":"LOGOUT"}""" + "\n" + """{"at":3,"o""")

        val out = ByteArrayOutputStream()
        log().export(out)

        assertEquals("""{"at":1,"op":"RUN"}""" + "\n" + """{"at":2,"op":"LOGOUT"}""" + "\n", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `export with no logs writes nothing`() {
        val out = ByteArrayOutputStream()
        log().export(out)
        assertEquals(0, out.size())
    }

    // The API

    @Test
    fun `the logging API takes only typed records`() {
        val allowed = setOf(LogRecord::class.java, java.io.OutputStream::class.java)
        DiagnosticsLog::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.forEach { method ->
            method.parameterTypes.forEach { assertTrue(it in allowed, "${method.name} takes ${it.name}") }
        }
        // Every field, exactly: a new or changed one fails here until it's reviewed. The one String is the
        // account's Pseudonym (an inline class); reflection can't tell it from a plain String, so what keeps free
        // text out of it is encode's check (24 lowercase hex digits), tested above.
        val fields = LogRecord::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.associate { it.name to it.genericType.typeName }
        assertEquals(
            mapOf(
                "at" to "long",
                "operation" to "java.lang.Enum<?>",
                "account" to "java.lang.String",
                "outcome" to "java.lang.Enum<?>",
                "counts" to "java.util.Map<? extends java.lang.Enum<?>, java.lang.Integer>",
                "durationMs" to "java.lang.Long",
            ),
            fields,
        )
    }

    @Test
    fun `no app source logs to logcat or standard output`() {
        val sources = File("src/main").walk().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        val patterns = listOf("android\\.util\\.(Log|\\*)", "\\bLog\\.[a-z]+\\(", "\\bprint(ln)?\\(", "printStackTrace\\(", "System\\.(out|err)", "java\\.util\\.logging")
            .map(::Regex)
        val found = sources.flatMap { file -> patterns.filter { it.containsMatchIn(file.readText()) }.map { "${file.name}: ${it.pattern}" } }
        assertEquals(emptyList<String>(), found)
    }
}

/** Enum names long enough to fill a record past 4 KiB. */
@Suppress("unused")
private enum class LongNames {
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA0,
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1,
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA2,
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA3,
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4,
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA5,
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA6,
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA7,
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA8,
    AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA9,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA0,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA2,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA3,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA5,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA6,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA7,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA8,
    BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA9,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA0,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA2,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA3,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA5,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA6,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA7,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA8,
    CAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA9,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA0,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA2,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA3,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA5,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA6,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA7,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA8,
    DAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA9,
}
