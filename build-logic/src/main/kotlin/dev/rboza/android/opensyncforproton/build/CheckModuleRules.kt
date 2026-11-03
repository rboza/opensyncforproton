package dev.rboza.android.opensyncforproton.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails the build on any [moduleRuleViolations]. */
abstract class CheckModuleRules : DefaultTask() {
    @get:Input abstract val modulePath: Property<String>
    @get:Input abstract val usesAndroidPlugin: Property<Boolean>
    @get:Input abstract val reachableProjects: SetProperty<String>
    @get:Input abstract val reachableModules: SetProperty<String>
    @get:Input abstract val unresolvedDependencies: SetProperty<String>
    @get:Input abstract val fileDependencies: SetProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val sourceRoot: DirectoryProperty

    @TaskAction
    fun check() {
        val root = sourceRoot.get().asFile
        val facts = ModuleFacts(
            path = modulePath.get(),
            usesAndroidPlugin = usesAndroidPlugin.get(),
            reachableProjects = reachableProjects.get(),
            reachableModules = reachableModules.get(),
            unresolvedDependencies = unresolvedDependencies.get(),
            fileDependencies = fileDependencies.get(),
            sources = sources.files.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() },
        )
        val violations = moduleRuleViolations(facts)
        if (violations.isNotEmpty()) throw GradleException("Module rules broken:\n" + violations.joinToString("\n") { "- $it" })
    }
}
