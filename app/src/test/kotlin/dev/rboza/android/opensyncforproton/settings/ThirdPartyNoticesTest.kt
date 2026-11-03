package dev.rboza.android.opensyncforproton.settings

import java.io.File
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The notices list exactly what the release APK bundles.
class ThirdPartyNoticesTest {
    private val notices = File("src/main/assets/$NOTICES_ASSET").readText()

    /** The release runtime classpath, as `app/gradle.lockfile` pins it. */
    private val locked: Set<String> = File("gradle.lockfile").readLines()
        .filter { "releaseRuntimeClasspath" in it.substringAfter('=', "").split(',') }
        .map { it.substringBefore('=') }
        .toSet()

    private fun section(title: String): List<String> {
        val lines = notices.lines()
        val start = lines.indexOf("== $title ==")
        check(start >= 0) { "no section $title" }
        return lines.drop(start + 1).takeWhile { !it.startsWith("== ") }.filter { it.isNotBlank() }
    }

    /** `name — licence, licence (note)` lines: name to licences. */
    private fun entries(lines: List<String>): Map<String, List<String>> {
        val pairs = lines.map { line ->
            val (name, licences) = line.split(" — ", limit = 2).also { check(it.size == 2) { "not `name — licence`: $line" } }
            name to licences.substringBefore(" (").split(", ")
        }
        val twice = pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), twice, "listed twice")
        return pairs.toMap()
    }

    private val golib: String get() = locked.single { it.startsWith("me.proton.crypto:android-golib:") }

    private val lines: Map<String, List<String>> by lazy { entries(section("Libraries")) + entries(section("Go code in $golib")) }

    /** A Go module by its path, a library without its version. */
    private fun who(name: String) = if (' ' in name) name.substringBefore(' ') else name.substringBeforeLast(':')

    /** `--- licence: who, who ---` headers, then the text up to the next header. */
    private val texts: Map<String, String> by lazy {
        val header = Regex("^--- (.+?): (.+) ---$", RegexOption.MULTILINE)
        val found = header.findAll(notices).toList()
        val pairs = found.mapIndexed { i, m ->
            val end = if (i + 1 < found.size) found[i + 1].range.first else notices.length
            "${m.groupValues[1]}: ${m.groupValues[2]}" to notices.substring(m.range.last + 2, end)
        }
        val twice = pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), twice, "a text header twice")
        for ((name, text) in pairs) assertTrue(text.isNotBlank(), "$name: no text")
        pairs.toMap()
    }

    /** The libraries a text's header names. */
    private fun named(header: String): List<String> = header.substringAfter(": ").split(", ")

    @Test
    fun `the libraries are exactly the release runtime classpath`() {
        val listed = entries(section("Libraries")).keys
        assertEquals(emptySet<String>(), locked - listed, "bundled but not listed")
        assertEquals(emptySet<String>(), listed - locked, "listed but not bundled")
    }

    @Test
    fun `the Go code is the android-golib version the lock has`() {
        assertTrue(notices.lines().contains("== Go code in $golib =="), "read the Go modules again from $golib's libgojni.so")
    }

    @Test
    fun `each licence a line names has its text, and copyright-bearing texts name their library`() {
        for ((name, licences) in lines) {
            for (licence in licences) {
                val withText = texts.keys.filter { it.startsWith("$licence: ") }
                assertTrue(withText.isNotEmpty(), "$name: no text for $licence")
                if (licence != "Apache-2.0" && licence != "GPL-3.0") {
                    assertTrue(withText.any { who(name) in named(it) }, "$name: no $licence text naming it")
                }
            }
        }
    }

    @Test
    fun `each library a text names lists that licence, and each NOTICE belongs to an Apache-2_0 library`() {
        val byWho = lines.entries.associate { (name, licences) -> who(name) to licences }
        for (header in texts.keys) {
            val licence = header.substringBefore(": ")
            if (licence == "GPL-3.0" || header.startsWith("Apache-2.0: ")) continue
            val wanted = licence.removeSuffix(" NOTICE")
            for (library in named(header)) {
                val listed = byWho[library]
                assertTrue(listed != null && wanted in listed, "$header names $library, whose line doesn't list $wanted")
            }
        }
    }

    @Test
    fun `what was found by hand inside the libraries keeps its licence and its text`() {
        // On no POM: what scanning the bundled jars and AARs, and the projects' own licence notes, found. Each: the
        // library, the licence its line lists, and the SHA-256 of the text that names it (as upstream ships it, without
        // leading and trailing blank lines).
        val found = listOf(
            Triple("com.squareup.okhttp3:okhttp-android", "MPL-2.0", "86cf9656479f1edb82245b985f4a2cc0d503b945766ebeda4269c60b307699ae"),
            Triple("androidx.webkit:webkit", "BSD-3-Clause", "29f9cb338cf938718a1e534cb84d436eb5f82f165f6ea8f7b9f074de25d80cc8"),
            Triple("androidx.emoji2:emoji2", "BSD-3-Clause", "7171224f4067f20d06598cff1f01fa4af1ad745562393dc6700f8c3c3d199d38"),
            Triple("org.jetbrains.kotlin:kotlin-stdlib", "BSD-3-Clause", "e9cb92944c765eedfdfb52be7fc8020fa4dcbf66d8272b33165c3d7b70978ac6"),
            Triple("org.jetbrains.kotlin:kotlin-stdlib", "BSL-1.0", "8d8291caf1cee26d23acf3eb67c9f9a2d58f1c681b16a4fbe8cbfb9e3c0b5a9b"),
            Triple("github.com/google/certificate-transparency-go", "BSD-3-Clause", "4214d8afcc3620b7fb7aa9c7695e8f659a90011a830caf0e0c72a385d7d3b2a5"),
            Triple("com.googlecode.ez-vcard:ez-vcard", "Apache-2.0 NOTICE", "a02b83f426215f484df5ac16f1185d5171133b956d7b0a8fec77965c9f699990"),
            Triple("com.github.mangstadt:vinnie", "Apache-2.0 NOTICE", "a02b83f426215f484df5ac16f1185d5171133b956d7b0a8fec77965c9f699990"),
            Triple("org.freemarker:freemarker", "Apache-2.0 NOTICE", "0eb7ca33c4986a9ee9347fa605ed503e215d62751649e2e1a45d43962a63541c"),
            Triple("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm", "Apache-2.0 NOTICE", "21dfc18c694d51aee3e4ff7bf4cadb8d63e2af6ff7d3b07d36a5f5c8af69a7f1"),
            Triple("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm", "Apache-2.0 NOTICE", "6a3090159af6ad9a8d2a4ff78315cb4c8f7cbecc3217d44da2288eff1f763987"),
        )
        val byWho = lines.entries.associate { (name, licences) -> who(name) to licences }
        for ((library, licence, sha256) in found) {
            assertTrue(licence.removeSuffix(" NOTICE") in byWho.getValue(library), "$library: $licence")
            val text = texts.entries.filter { it.key.startsWith("$licence: ") && library in named(it.key) }
            assertEquals(listOf(sha256), text.map { sha256(it.value.trim('\n')) }, "$library: its $licence text")
        }
    }

    @Test
    fun `the Apache text is the Apache License 2_0`() {
        val apache = texts.entries.single { it.key.startsWith("Apache-2.0: ") }.value.trim('\n')
        assertEquals(APACHE_2_0_SHA256, sha256(apache))
    }

    @Test
    fun `the GPL text is the repository's LICENSE, byte for byte`() {
        val gpl = texts.entries.single { it.key.startsWith("GPL-3.0: ") }.value
        // The text, then the blank line before the next header.
        assertEquals(File("../LICENSE").readText() + "\n", gpl)
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** apache.org's LICENSE-2.0.txt without its leading blank line and final newline. */
        const val APACHE_2_0_SHA256 = "43070e2d4e532684de521b885f385d0841030efa2b1a20bafb76133a5e1379c1"
    }
}

@RunWith(RobolectricTestRunner::class)
class ThirdPartyNoticesLoadTest {
    @org.junit.Test
    fun `the screen's loader reads the notices from the app`() {
        val text = thirdPartyNotices(RuntimeEnvironment.getApplication())
        org.junit.Assert.assertTrue(text.startsWith("OpenSync for Proton — third-party licences\n"))
    }
}
