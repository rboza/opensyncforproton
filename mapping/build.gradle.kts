import com.android.tools.r8.ClassFileConsumer
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.JdkClassFileProvider
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import java.io.Closeable

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("opensync.module")
}

kotlin {
    // D1: JDK 25 runs the build; the code is Java 17 bytecode and may call only Java 17's library, so nothing
    // that compiles here can be missing on the phone.
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    implementation(libs.ezvcard) {
        // Only hCard and jCard need these, and the app uses neither. FreeMarker stays: VCard catches one
        // of its exceptions, so the JVM loads it on every parse.
        exclude(group = "org.jsoup")
        exclude(group = "com.fasterxml.jackson.core")
    }
    implementation(libs.vinnie)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

sourceSets.test {
    // The hand-written vCard fixtures (fixtures/vcard/README.md).
    resources.srcDir(rootProject.layout.projectDirectory.dir("fixtures/vcard"))
}

tasks.test {
    useJUnitPlatform()
}

/**
 * The mapper and its libraries as a release build's R8 shrinks them: R8 drops what's reached only by reflection, as
 * ez-vcard reaches its parameter values. Class files, not dex, so the JVM can run them; R8 is the one AGP bundles, from
 * the root project's plugin classpath.
 */
abstract class ShrinkWithR8 : DefaultTask() {
    @get:Classpath
    abstract val program: ConfigurableFileCollection

    @get:Classpath
    abstract val library: ConfigurableFileCollection

    @get:Nested
    abstract val jdk: Property<JavaLauncher>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun shrink() {
        val jdkClasses = JdkClassFileProvider.fromJdkHome(jdk.get().metadata.installationPath.asFile.toPath())
        try {
            // The rules come from the module's jar (META-INF/com.android.tools/r8/), as in the app.
            val command = R8Command.builder(QuietManifests)
                .addProgramFiles(program.files.map { it.toPath() })
                .addLibraryResourceProvider(jdkClasses)
                .addLibraryFiles(library.files.map { it.toPath() })
                // This module's classes stay whole, so the tests can reach what they test, and the libraries shrink
                // to what it uses, as in the app. Except its subclasses of ez-vcard's: kept whole, their public
                // overrides (writing XML or HTML, say) would keep ez-vcard code the app drops, which would hide a
                // broken photo. The list is by name: a new subclass joins it. The hCard and jCard libraries are left
                // out above.
                .addProguardConfiguration(
                    listOf(
                        "-keep class !**Scribe,!**.mapping.AppleDate,!**.mapping.NameComponents," +
                            "dev.rboza.android.opensyncforproton.mapping.** { *; }",
                        "-dontwarn org.jsoup.**",
                        "-dontwarn com.fasterxml.jackson.**",
                    ),
                    Origin.unknown(),
                )
                .setMode(CompilationMode.RELEASE)
                .setProgramConsumer(ClassFileConsumer.ArchiveConsumer(output.get().asFile.toPath(), true))
                .build()
            R8.run(command)
        } finally {
            (jdkClasses as Closeable).close()
        }
    }

    /** Each library's jar has a manifest; R8 keeps the first and says so. */
    private object QuietManifests : DiagnosticsHandler {
        override fun warning(warning: Diagnostic) {
            if (!warning.diagnosticMessage.startsWith("Resource 'META-INF/MANIFEST.MF' already exists")) {
                super.warning(warning)
            }
        }
    }
}

// Kotlin's own library stays whole: the tests call more of it than the mapper does.
val unshrunk = configurations.runtimeClasspath.get().incoming.artifactView {
    componentFilter { it is ModuleComponentIdentifier && it.group in setOf("org.jetbrains.kotlin", "org.jetbrains") }
}.files
val shrunkLibraries = configurations.runtimeClasspath.get() - unshrunk

val shrinkWithR8 = tasks.register<ShrinkWithR8>("shrinkWithR8") {
    program.from(tasks.jar, shrunkLibraries)
    library.from(unshrunk)
    jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    output = layout.buildDirectory.file("r8/mapping.jar")
}

tasks.register<Test>("testOnR8") {
    description = "Runs the mapping tests on the mapper and ez-vcard as R8 shrinks them in a release build."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + files(shrinkWithR8.flatMap { it.output }) +
        (configurations.testRuntimeClasspath.get() - shrunkLibraries)
}
