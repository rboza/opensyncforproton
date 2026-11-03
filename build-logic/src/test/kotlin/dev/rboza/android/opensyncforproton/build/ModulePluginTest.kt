package dev.rboza.android.opensyncforproton.build

import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Runs `checkModuleRules` in a throwaway build of plain JVM modules named like the app's. */
class ModulePluginTest {
    @TempDir
    lateinit var dir: File

    private fun build(dependenciesByModule: Map<String, String>) {
        val modules = dependenciesByModule.keys.joinToString { "\":$it\"" }
        dir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("opensync.settings") }
            rootProject.name = "modules"
            dependencyResolutionManagement { repositories { maven(url = uri("repo")) } }
            include($modules)
            """.trimIndent(),
        )
        // A local repository with one library, com.example:lib:1.0.
        dir.resolve("repo/com/example/lib/1.0").apply { mkdirs() }.resolve("lib-1.0.pom").writeText(
            """<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>""" +
                """<artifactId>lib</artifactId><version>1.0</version></project>""",
        )
        dependenciesByModule.forEach { (module, dependencies) ->
            dir.resolve(module).mkdirs()
            dir.resolve("$module/build.gradle.kts").writeText(
                """
                plugins {
                    `java-library`
                    id("opensync.module")
                }

                dependencies {
                    $dependencies
                }
                """.trimIndent(),
            )
        }
    }

    private fun checkModuleRules(writeLocks: Boolean = true): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(listOf("checkModuleRules", "--continue") + if (writeLocks) listOf("--write-locks") else emptyList())

    private fun violations(result: BuildResult) =
        result.output.lines().filter { it.startsWith("  - ") }.map { it.removePrefix("  - ") }.sorted()

    @Test
    fun `the planned graph passes`() {
        build(
            mapOf(
                "proton" to "",
                "crypto" to "",
                "mapping" to "",
                "account" to """implementation(project(":proton")); implementation(project(":crypto"))""",
            ),
        )
        checkModuleRules().build()
    }

    @Test
    fun `transitive and unresolved dependencies are caught`() {
        build(
            mapOf(
                "mapping" to "",
                "proton" to """implementation(project(":mapping"))""",
                "account" to """implementation(project(":proton"))""",
                "crypto" to """runtimeOnly("com.google.android.gms:play-services-base:18.5.0")""",
            ),
        )
        assertEquals(
            listOf(
                ":account depends on :mapping",
                ":crypto has an unresolved dependency: com.google.android.gms:play-services-base:18.5.0",
                ":proton depends on :mapping",
            ),
            violations(checkModuleRules().buildAndFail()),
        )
    }

    @Test
    fun `an android plugin is recognised by its class`() {
        // A stand-in plugin class under com.android., from an included build, so nothing is downloaded.
        dir.resolve("fake/src/main/java/com/android/fake").mkdirs()
        dir.resolve("fake/settings.gradle.kts").writeText("rootProject.name = \"fake\"\n")
        dir.resolve("fake/build.gradle.kts").writeText(
            """
            plugins { `java-gradle-plugin` }
            gradlePlugin { plugins { register("fake") { id = "fake.android"; implementationClass = "com.android.fake.FakePlugin" } } }
            """.trimIndent(),
        )
        dir.resolve("fake/src/main/java/com/android/fake/FakePlugin.java").writeText(
            """
            package com.android.fake;
            public class FakePlugin implements org.gradle.api.Plugin<org.gradle.api.Project> {
                public void apply(org.gradle.api.Project project) {}
            }
            """.trimIndent(),
        )
        build(mapOf("mapping" to ""))
        dir.resolve("settings.gradle.kts").writeText("pluginManagement { includeBuild(\"fake\") }\n" + dir.resolve("settings.gradle.kts").readText())
        dir.resolve("mapping/build.gradle.kts").writeText(
            dir.resolve("mapping/build.gradle.kts").readText().replace("id(\"opensync.module\")", "id(\"opensync.module\")\n    id(\"fake.android\")"),
        )
        assertEquals(listOf(":mapping is an Android module; it must be plain JVM"), violations(checkModuleRules().buildAndFail()))
    }

    @Test
    fun `a file dependency fails`() {
        build(mapOf("proton" to """implementation(files("libs/firebase-analytics.jar"))"""))
        assertEquals(
            listOf(":proton has a file dependency: libs/firebase-analytics.jar"),
            violations(checkModuleRules().buildAndFail()),
        )
    }

    @Test
    fun `a dependency missing from the lock state fails`() {
        build(mapOf("proton" to ""))
        checkModuleRules().build()
        dir.resolve("proton/build.gradle.kts").appendText("\ndependencies { implementation(\"com.example:lib:1.0\") }\n")
        val result = checkModuleRules(writeLocks = false).buildAndFail()
        assertEquals(listOf(":proton has an unresolved dependency: com.example:lib:1.0"), violations(result))
    }
}
