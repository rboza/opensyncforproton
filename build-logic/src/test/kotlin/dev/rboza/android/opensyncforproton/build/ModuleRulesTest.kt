package dev.rboza.android.opensyncforproton.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleRulesTest {
    private fun violations(
        path: String,
        projects: Set<String> = emptySet(),
        modules: Set<String> = emptySet(),
        sources: Map<String, String> = emptyMap(),
        android: Boolean = path !in setOf(":proton", ":mapping"),
        unresolved: Set<String> = emptySet(),
        files: Set<String> = emptySet(),
    ) = moduleRuleViolations(ModuleFacts(path, android, projects, modules, unresolved, files, sources))

    @Test
    fun `the planned module graph passes`() {
        val graph = mapOf(
            ":app" to setOf(":sync", ":account", ":proton", ":crypto", ":mapping"),
            ":sync" to setOf(":account", ":proton", ":crypto", ":mapping"),
            ":account" to setOf(":proton", ":crypto"),
            ":proton" to emptySet(),
            ":crypto" to emptySet(),
            ":mapping" to emptySet(),
        )
        graph.forEach { (path, projects) -> assertEquals(emptyList<String>(), violations(path, projects)) }
    }

    @Test
    fun `no module depends on app`() {
        assertEquals(listOf(":sync depends on :app"), violations(":sync", setOf(":account", ":app")))
    }

    @Test
    fun `proton depends on neither crypto nor account`() {
        assertEquals(
            listOf(":proton depends on :account", ":proton depends on :crypto"),
            violations(":proton", setOf(":crypto", ":account")),
        )
    }

    @Test
    fun `crypto depends on neither proton nor mapping`() {
        assertEquals(
            listOf(":crypto depends on :mapping", ":crypto depends on :proton"),
            violations(":crypto", setOf(":proton", ":mapping")),
        )
    }

    @Test
    fun `mapping does not depend on crypto`() {
        assertEquals(listOf(":mapping depends on :crypto"), violations(":mapping", setOf(":crypto")))
    }

    @Test
    fun `only the dependencies in the module table are allowed, except for app`() {
        assertEquals(listOf(":proton depends on :mapping"), violations(":proton", setOf(":mapping")))
        assertEquals(listOf(":account depends on :mapping"), violations(":account", setOf(":proton", ":crypto", ":mapping")))
        assertEquals(emptyList<String>(), violations(":app", setOf(":sync", ":account", ":proton", ":crypto", ":mapping")))
    }

    @Test
    fun `a module missing from the module table fails`() {
        assertEquals(listOf(":contacts is not a module in docs/architecture.md § Modules"), violations(":contacts"))
    }

    @Test
    fun `an unresolved dependency fails instead of being skipped`() {
        assertEquals(
            listOf(":sync has an unresolved dependency: com.google.android.gms:play-services-base:18.5.0"),
            violations(":sync", unresolved = setOf("com.google.android.gms:play-services-base:18.5.0")),
        )
    }

    @Test
    fun `google play, play services, firebase, and ml kit are refused`() {
        assertEquals(
            listOf(
                ":sync depends on com.google.android.gms:play-services-base",
                ":sync depends on com.google.android.play:integrity",
                ":sync depends on com.google.firebase:firebase-common",
                ":sync depends on com.google.mlkit:barcode-scanning",
            ),
            violations(
                ":sync",
                modules = setOf(
                    "com.google.android.gms:play-services-base",
                    "com.google.android.play:integrity",
                    "com.google.firebase:firebase-common",
                    "com.google.mlkit:barcode-scanning",
                    "com.google.android.material:material",
                ),
            ),
        )
    }

    @Test
    fun `proton core auth, account, and network are refused but crypto-android is not`() {
        assertEquals(
            listOf(
                ":crypto depends on me.proton.core:account-manager",
                ":crypto depends on me.proton.core:auth",
                ":crypto depends on me.proton.core:network-data",
            ),
            violations(
                ":crypto",
                modules = setOf(
                    "me.proton.core:crypto-android",
                    "me.proton.core:util-kotlin",
                    "me.proton.core:auth",
                    "me.proton.core:account-manager",
                    "me.proton.core:network-data",
                ),
            ),
        )
    }

    @Test
    fun `proton and mapping are plain JVM modules`() {
        assertEquals(listOf(":mapping is an Android module; it must be plain JVM"), violations(":mapping", android = true))
        assertEquals(emptyList<String>(), violations(":sync", android = true))
    }

    @Test
    fun `the android API jars are refused in proton and mapping`() {
        assertEquals(
            listOf(":proton depends on com.google.android:android", ":proton depends on org.robolectric:android-all"),
            violations(":proton", modules = setOf("com.google.android:android", "org.robolectric:android-all")),
        )
        assertEquals(emptyList<String>(), violations(":sync", modules = setOf("org.robolectric:android-all")))
    }

    @Test
    fun `android references hidden by wildcards, R classes, or comment-like strings are found`() {
        mapOf(
            "import android.*\n" to "wildcard import",
            "import android.R\n" to "R import",
            "val s = android.R.string.ok\n" to "R reference",
            "val m = android.Manifest.permission.INTERNET\n" to "Manifest reference",
            "val a = \"image/*\"\nfun f() = android.util.Log.d(\"x\", \"y\")\nval b = \"*/*\"\n" to "between comment-like strings",
        ).forEach { (source, case) ->
            assertEquals(listOf(":proton uses android.* in X.kt"), violations(":proton", sources = mapOf("X.kt" to source)), case)
        }
    }

    @Test
    fun `a file dependency fails, since nothing checks what it holds`() {
        assertEquals(
            listOf(":proton has a file dependency: libs/firebase-analytics.jar"),
            violations(":proton", files = setOf("libs/firebase-analytics.jar")),
        )
    }

    @Test
    fun `proton and mapping use nothing from android, imported or fully qualified`() {
        val qualified = "fun log() = android.util.Log.d(\"x\", \"y\")\n"
        assertEquals(listOf(":mapping uses android.* in main/kotlin/X.kt"), violations(":mapping", sources = mapOf("main/kotlin/X.kt" to qualified)))
        val source = "package x\n\nimport java.io.File\nimport android.util.Log\n"
        assertEquals(listOf(":proton uses android.* in main/kotlin/X.kt"), violations(":proton", sources = mapOf("main/kotlin/X.kt" to source)))
        assertEquals(emptyList<String>(), violations(":sync", sources = mapOf("main/kotlin/X.kt" to source)))
        assertEquals(
            emptyList<String>(),
            violations(
                ":mapping",
                sources = mapOf("main/kotlin/X.kt" to "import androidx.annotation.Keep\n// see android.util.Log\n/* android.os.Build */\nval myandroid.x = 1\n"),
            ),
        )
    }

    @Test
    fun `script plugins are recognised in build and settings scripts`() {
        listOf(
            """apply(from = "extra.gradle.kts")""",
            """apply(from = file("extra.gradle.kts"))""",
            """apply from: 'extra.gradle'""",
            """apply(mapOf("from" to "extra.gradle.kts"))""",
            "apply {\n    from(\"extra.gradle.kts\")\n}",
            """apply(to = project, from = "extra.gradle.kts")""",
            "project.apply {\n    from(\"extra.gradle.kts\")\n}",
        ).forEach { assertEquals(true, usesScriptPlugin(it), it) }
        listOf(
            """versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }""",
            """plugins { id("opensync.module") }""",
            """apply(plugin = "java")""",
            "// no apply(from = ...) here (D24)",
            "tasks.register<Copy>(\"c\") { spec.apply { from(\"src\") } }",
        ).forEach { assertEquals(false, usesScriptPlugin(it), it) }
    }

    @Test
    fun `every compile and runtime classpath is checked`() {
        val checked = listOf(
            "compileClasspath", "runtimeClasspath", "testRuntimeClasspath", "coreLibraryDesugaring",
            "releaseCompileClasspath", "releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath",
            "debugAndroidTestCompileClasspath",
        )
        val skipped = listOf(
            "implementation", "releaseRuntimeElements", "kotlinCompilerClasspath",
            "debugAnnotationProcessorClasspath", "releaseLintChecksClasspath",
        )
        assertEquals(checked, (checked + skipped).filter(::isCheckedClasspath))
    }

    @Test
    fun `no module renames the Android account`() {
        assertEquals(
            listOf(":account calls AccountManager.renameAccount in main/kotlin/Dir.kt"),
            violations(":account", sources = mapOf("main/kotlin/Dir.kt" to "manager.renameAccount(account, name, null, null)")),
        )
    }
}
