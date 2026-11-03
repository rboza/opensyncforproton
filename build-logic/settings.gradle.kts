pluginManagement {
    repositories {
        gradlePluginPortal {
            content {
                includeGroupAndSubgroups("org.gradle.kotlin")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

// D24: Gradle can't lock this script's plugin classpath, so nothing may be added to it.
check(buildscript.configurations.getByName("classpath").dependencies.isEmpty()) {
    "The settings plugin classpath must stay empty: Gradle can't lock it (D24)"
}

// D24: no script plugins in build-logic's own scripts either; their classpaths can't be locked.
listOf("settings.gradle.kts", "build.gradle.kts").map { settingsDir.resolve(it) }.forEach { script ->
    check(!Regex("""(?m)^\s*(project\.)?apply\s*[({]|\bapply\s+from\b""").containsMatchIn(script.readText())) {
        "${script.name} must not apply script plugins (D24)"
    }
}

rootProject.name = "build-logic"
