// The build's own checks: dependency locking and the script-plugin ban (D24), the module rules, and the release
// manifest rules.
// Not an app module: nothing here ships in the APK.

buildscript {
    dependencyLocking {
        lockAllConfigurations()
        lockMode = LockMode.STRICT
    }
}

plugins {
    `kotlin-dsl`
}

dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("module") {
            id = "opensync.module"
            implementationClass = "dev.rboza.android.opensyncforproton.build.ModulePlugin"
        }
        register("settings") {
            id = "opensync.settings"
            implementationClass = "dev.rboza.android.opensyncforproton.build.SettingsPlugin"
        }
    }
}
