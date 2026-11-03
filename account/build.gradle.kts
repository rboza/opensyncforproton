plugins {
    alias(libs.plugins.android.library)
    id("opensync.module")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "dev.rboza.android.opensyncforproton.account"
    compileSdk = libs.versions.sdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        minSdk = libs.versions.sdk.get().toInt()
    }
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // A test that hangs (a gate or a blocked scheduler) fails instead of stalling the build.
            it.systemProperty("junit.jupiter.execution.timeout.default", "60s")
        }
    }
}

kotlin {
    // D1: JDK 25 runs the build; the code is Java 17 bytecode (Android's own API bounds what it can call).
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Robolectric's Android runtime comes through Gradle (locked and verified, D24), never its own downloader.
val robolectricAndroidAll by configurations.creating

dependencies {
    implementation(project(":proton"))
    implementation(project(":crypto"))
    // The login's steps: their scopes, Retry-After waits, SRP and key work off the caller's thread, and cancel()'s
    // best-effort A5.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // AccountDirectoryTest and ProtonAuthenticatorTest run under Robolectric's JUnit 4 runner.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    robolectricAndroidAll(libs.robolectric.android.all)
}

val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars = tasks.register<Sync>("copyRobolectricJars") {
    from(robolectricAndroidAll)
    into(robolectricJars)
}

tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    // A custom Robolectric shadow (a nested class named *Shadow) only loads inside Robolectric's sandbox; JUnit's
    // discovery would fail on it.
    exclude("**/*\$*Shadow.class")
    systemProperty("robolectric.offline", "true")
    // Robolectric's environment setup reaches a JDK-internal class (jdk.internal.access) that Java 17+ doesn't export.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.path)
}
