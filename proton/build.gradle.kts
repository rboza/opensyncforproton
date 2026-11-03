plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    // Retrofit's suspend functions need it at runtime.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

sourceSets.test {
    // Proton's redacted response shapes (fixtures/http/README.md).
    resources.srcDir(rootProject.layout.projectDirectory.dir("fixtures/http"))
}

tasks.test {
    useJUnitPlatform()
    // A test that hangs (a latch or a poll that never ends) fails instead of stalling the build.
    systemProperty("junit.jupiter.execution.timeout.default", "60s")
}
