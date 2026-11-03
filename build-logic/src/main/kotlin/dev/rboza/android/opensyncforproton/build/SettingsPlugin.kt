package dev.rboza.android.opensyncforproton.build

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.initialization.Settings

/**
 * Applied by the settings (D24): no script uses script plugins, every project's plugin classpath is locked strictly, every module applies
 * `opensync.module`, and the settings plugin classpath, which Gradle can't lock, holds only this plugin's own
 * `build-logic`: no other included build, and nothing external, not even through `build-logic`.
 */
class SettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val classpath = settings.buildscript.configurations.getByName("classpath")
        val result = classpath.incoming.resolutionResult
        val foreign = result.allComponents.filter { it != result.root }.map { it.id }
            .filterNot { it is ProjectComponentIdentifier && it.buildTreePath.let { path -> path == ":build-logic" || path.startsWith(":build-logic:") } }
        if (classpath.dependencies.any { it !is ProjectDependency } || foreign.isNotEmpty()) {
            throw GradleException("The settings plugin classpath must hold only build-logic: Gradle can't lock it (D24)")
        }
        // buildSrc is built outside these checks: no locking, no script-plugin ban, no module rules.
        if (settings.settingsDir.resolve("buildSrc").exists()) {
            throw GradleException("buildSrc is not allowed: build logic lives in build-logic (D24)")
        }
        val settingsFile = settings.buildscript.sourceFile
        if (settingsFile != null && usesScriptPlugin(settingsFile.readText())) {
            throw GradleException("${settingsFile.name} must not apply script plugins: Gradle can't lock their classpaths (D24)")
        }
        settings.gradle.lifecycle.beforeProject {
            if (buildFile.isFile && usesScriptPlugin(buildFile.readText())) {
                throw GradleException("$path: ${buildFile.name} must not apply script plugins: Gradle can't lock their classpaths (D24)")
            }
            buildscript.dependencyLocking.lockAllConfigurations()
            buildscript.dependencyLocking.lockMode.set(LockMode.STRICT)
        }
        settings.gradle.lifecycle.afterProject {
            if (path != ":" && !pluginManager.hasPlugin("opensync.module")) {
                throw GradleException("$path must apply the opensync.module plugin")
            }
        }
    }
}
