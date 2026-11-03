plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    id("opensync.module")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "dev.rboza.android.opensyncforproton.sync"
    compileSdk = libs.versions.sdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        minSdk = libs.versions.sdk.get().toInt()
        // The instrumented tests run on the API 37 emulator: photo scaling, the Contacts provider, and the migrations
        // need a real Android.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    // D1: JDK 25 runs the build; the code is Java 17 bytecode (Android's own API bounds what it can call).
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

room {
    // Exported and committed, so migrations can be tested against it.
    schemaDirectory("$projectDir/schemas")
}

// Robolectric's Android runtime comes through Gradle (locked and verified, D24), never its own downloader.
val robolectricAndroidAll by configurations.creating

dependencies {
    implementation(project(":account"))
    implementation(project(":proton"))
    implementation(project(":crypto"))
    implementation(project(":mapping"))

    // Coroutines: the engine, coordinator, lifecycle, and sync adapter.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    // The periodic job (D20).
    implementation(libs.work.runtime)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.work.testing)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    robolectricAndroidAll(libs.robolectric.android.all)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // MigrationTestHelper, for the migrations from every released version.
    androidTestImplementation(libs.room.testing)
}

val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars = tasks.register<Sync>("copyRobolectricJars") {
    from(robolectricAndroidAll)
    into(robolectricJars)
}

tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    systemProperty("robolectric.offline", "true")
    // Robolectric's environment setup reaches a JDK-internal class (jdk.internal.access) that Java 17+ doesn't export.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.path)
}
