package dev.rboza.android.opensyncforproton.build

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Runs a throwaway build whose settings apply `opensync.settings`. */
class SettingsPluginTest {
    @TempDir
    lateinit var dir: File

    private fun build(
        settingsBuildscript: String = "",
        modulePlugins: String = "id(\"opensync.module\")",
        settingsPlugins: String = "id(\"opensync.settings\")",
        pluginManagement: String = "",
    ) {
        dir.resolve("settings.gradle.kts").writeText(
            """
            $pluginManagement
            $settingsBuildscript
            plugins { $settingsPlugins }
            rootProject.name = "modules"
            include(":proton")
            """.trimIndent(),
        )
        dir.resolve("proton").mkdirs()
        dir.resolve("proton/build.gradle.kts").writeText("plugins {\n    `java-library`\n    $modulePlugins\n}\n")
    }

    private fun gradle(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*arguments)

    private fun assertContains(output: String, text: String) = assertTrue(text in output, output)

    @Test
    fun `a module that doesn't apply opensync_module fails`() {
        build(modulePlugins = "")
        assertContains(gradle("help", "--write-locks").buildAndFail().output, ":proton must apply the opensync.module plugin")
    }

    @Test
    fun `every project's plugin classpath is locked strictly`() {
        build()
        gradle("help", "--write-locks").build()
        assertTrue(dir.resolve("proton/buildscript-gradle.lockfile").delete())
        assertContains(gradle("help").buildAndFail().output, "Locking strict mode: Configuration 'classpath' is locked but does not have lock state")
    }

    @Test
    fun `a file on the settings plugin classpath fails`() {
        build(settingsBuildscript = """buildscript { dependencies { classpath(files("lib.jar")) } }""")
        assertContains(
            gradle("help", "--write-locks").buildAndFail().output,
            "The settings plugin classpath must hold only build-logic",
        )
    }

    @Test
    fun `another included build on the settings plugin classpath fails`() {
        // A second included build with its own settings plugin, written in Java so nothing is downloaded.
        dir.resolve("other/src/main/java/other").mkdirs()
        dir.resolve("other/settings.gradle.kts").writeText("rootProject.name = \"other\"\n")
        dir.resolve("other/build.gradle.kts").writeText(
            """
            plugins { `java-gradle-plugin` }
            gradlePlugin { plugins { register("other") { id = "other.settings"; implementationClass = "other.OtherPlugin" } } }
            """.trimIndent(),
        )
        dir.resolve("other/src/main/java/other/OtherPlugin.java").writeText(
            """
            package other;
            public class OtherPlugin implements org.gradle.api.Plugin<org.gradle.api.initialization.Settings> {
                public void apply(org.gradle.api.initialization.Settings settings) {}
            }
            """.trimIndent(),
        )
        build(
            pluginManagement = """pluginManagement { includeBuild("other") }""",
            settingsPlugins = "id(\"opensync.settings\")\n    id(\"other.settings\")",
        )
        assertContains(
            gradle("help", "--write-locks").buildAndFail().output,
            "The settings plugin classpath must hold only build-logic",
        )
    }

    @Test
    fun `a script plugin in the settings fails`() {
        dir.resolve("extra.settings.gradle.kts").writeText("")
        build(pluginManagement = "")
        dir.resolve("settings.gradle.kts").appendText("\napply(from = \"extra.settings.gradle.kts\")\n")
        assertContains(gradle("help", "--write-locks").buildAndFail().output, "settings.gradle.kts must not apply script plugins")
    }

    @Test
    fun `a script plugin in a module fails`() {
        dir.resolve("proton").mkdirs()
        dir.resolve("proton/extra.gradle.kts").writeText("")
        build()
        dir.resolve("proton/build.gradle.kts").appendText("\napply(from = \"extra.gradle.kts\")\n")
        assertContains(gradle("help", "--write-locks").buildAndFail().output, "build.gradle.kts must not apply script plugins")
    }

    @Test
    fun `a buildSrc directory fails`() {
        build()
        dir.resolve("buildSrc").mkdirs()
        dir.resolve("buildSrc/build.gradle.kts").writeText("")
        assertContains(gradle("help", "--write-locks").buildAndFail().output, "buildSrc is not allowed")
    }

    @Test
    fun `a module plugin without the settings plugin fails`() {
        build(settingsPlugins = "")
        assertContains(
            gradle("help", "--write-locks").buildAndFail().output,
            ":proton: the settings must apply opensync.settings",
        )
    }
}
