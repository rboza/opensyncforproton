package dev.rboza.android.opensyncforproton.build

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.kotlin.dsl.register

/** Applied by every module: strict dependency locking (D24) and the module-rule check. */
class ModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Only opensync.settings makes plugin classpaths strict, so this fails if it's removed from the settings.
        if (project.buildscript.dependencyLocking.lockMode.get() != LockMode.STRICT) {
            throw GradleException("${project.path}: the settings must apply opensync.settings (D24)")
        }
        project.dependencyLocking.lockAllConfigurations()
        project.dependencyLocking.lockMode.set(LockMode.STRICT)

        project.tasks.register<CheckModuleRules>("checkModuleRules") {
            modulePath.set(project.path)
            // By plugin class: the Kotlin Multiplatform Android plugin, for one, adds no `android` extension.
            usesAndroidPlugin.set(project.plugins.any { it.javaClass.name.startsWith("com.android.") })
            project.configurations
                .filter { it.isCanBeResolved && isCheckedClasspath(it.name) }
                .forEach { classpath ->
                    val graph = classpath.incoming.resolutionResult.rootComponent.map(::walk)
                    reachableProjects.addAll(graph.map { it.projects })
                    reachableModules.addAll(graph.map { it.modules })
                    unresolvedDependencies.addAll(graph.map { it.unresolved })
                    fileDependencies.addAll(
                        classpath.allDependencies.withType(FileCollectionDependency::class.java)
                            .flatMap { dependency -> dependency.files.files.map { it.relativeTo(project.projectDir).invariantSeparatorsPath } },
                    )
                }
            sources.from(project.fileTree("src") { include("**/*.kt", "**/*.java") })
            sourceRoot.set(project.layout.projectDirectory.dir("src"))
        }
    }

    private class Graph(val projects: Set<String>, val modules: Set<String>, val unresolved: Set<String>)

    private companion object {
        /** Everything reachable from [root]; an unresolved dependency is reported, never skipped. */
        fun walk(root: ResolvedComponentResult): Graph {
            val seen = mutableSetOf(root)
            val queue = ArrayDeque(listOf(root))
            val unresolved = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val dependencies = queue.removeFirst().dependencies
                dependencies.filterIsInstance<UnresolvedDependencyResult>().forEach { unresolved += it.requested.displayName }
                dependencies.filterIsInstance<ResolvedDependencyResult>()
                    .map { it.selected }
                    .filter { seen.add(it) }
                    .forEach { queue.addLast(it) }
            }
            val ids = (seen - root).map { it.id }
            return Graph(
                projects = ids.filterIsInstance<ProjectComponentIdentifier>().map { it.projectPath }.toSet(),
                modules = ids.filterIsInstance<ModuleComponentIdentifier>().map { "${it.group}:${it.module}" }.toSet(),
                unresolved = unresolved,
            )
        }
    }
}
