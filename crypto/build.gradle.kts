plugins {
    alias(libs.plugins.android.library)
    id("opensync.module")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "dev.rboza.android.opensyncforproton.crypto"
    compileSdk = libs.versions.sdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        minSdk = libs.versions.sdk.get().toInt()
        // The instrumented tests run the real crypto library and the Keystore on the API 37 emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

androidComponents {
    onVariants { variant ->
        // The committed crypto fixtures, read by the instrumented tests.
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory(rootProject.file("fixtures/crypto").path)
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
    // D6: Proton's crypto. android-golib is a runtime dependency in crypto-android's POM; :crypto calls its Srp.
    implementation(libs.proton.crypto.android)
    implementation(libs.proton.android.golib)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    robolectricAndroidAll(libs.robolectric.android.all)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars = tasks.register<Sync>("copyRobolectricJars") {
    from(robolectricAndroidAll)
    into(robolectricJars)
}

tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    // The committed crypto fixtures for the JVM tests too: the key checks read real key bytes.
    val fixtures = rootProject.file("fixtures/crypto")
    inputs.dir(fixtures).withPropertyName("cryptoFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("crypto.fixtures", fixtures.path)
    systemProperty("robolectric.offline", "true")
    // Robolectric's environment setup reaches a JDK-internal class (jdk.internal.access) that Java 17+ doesn't export.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.path)
}
