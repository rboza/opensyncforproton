package dev.rboza.android.opensyncforproton.build

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Fails the build on any [manifestViolations] of the merged manifest.
 * The backup rules and network security config are read from the built APK, so what's checked is what ships;
 * so are the APK's own manifest (neither debuggable nor test-only) and its native code (D1).
 */
abstract class CheckManifest : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mergedManifest: RegularFileProperty
    /** AGP's APK output directory for the variant. */
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val apkDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val aapt2: RegularFileProperty
    @get:Input abstract val allowedEntries: ListProperty<String>

    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun check() {
        val apk = apkDirectory.get().asFile.listFiles { file -> file.extension == "apk" }.orEmpty().singleOrNull()
            ?: throw GradleException("Expected exactly one APK in ${apkDirectory.get().asFile}")
        val files = xmlResourceFiles(dump(apk, "resources"))
        val xmlResource = { name: String ->
            files[name]?.let { versions ->
                // Only the default version's own file is read; an alias has no file, so it has no text.
                val file = versions.singleOrNull { it.version.isEmpty() }?.path
                XmlResource(versions.map { it.version }, file?.let { xmltreeToXml(dump(apk, "xmltree", "--file", it)) })
            }
        }
        val violations = manifestViolations(mergedManifest.get().asFile.readText(), xmlResource, allowedEntries.get()) +
            releaseApkManifestViolations(xmltreeToXml(dump(apk, "xmltree", "--file", "AndroidManifest.xml"))) +
            releaseApkNativeCodeViolations(ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }.toList() })
        if (violations.isNotEmpty()) throw GradleException("Merged manifest check failed:\n" + violations.joinToString("\n") { "- $it" })
    }

    private fun dump(apk: File, vararg what: String): String {
        val output = ByteArrayOutputStream()
        exec.exec {
            commandLine(aapt2.get().asFile.absolutePath, "dump", *what, apk.absolutePath)
            standardOutput = output
        }
        return output.toString(Charsets.UTF_8)
    }
}
