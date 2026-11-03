pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
    }
}

// D24: strict locking of every project's plugin classpath, `opensync.module` in every module, only
// build-logic on the settings plugin classpath, and no script plugins.
plugins {
    id("opensync.settings")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "opensync-for-proton"

// docs/architecture.md § Modules
include(":app", ":sync", ":account", ":proton", ":crypto", ":mapping")
