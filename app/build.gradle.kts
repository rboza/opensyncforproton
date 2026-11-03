import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ResValue
import dev.rboza.android.opensyncforproton.build.CheckManifest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("opensync.module")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "dev.rboza.android.opensyncforproton"
    compileSdk = libs.versions.sdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "dev.rboza.android.opensyncforproton" // D4: permanent
        minSdk = libs.versions.sdk.get().toInt()
        targetSdk = libs.versions.sdk.get().toInt()
        versionCode = 10
        versionName = "1.0.0"
        // The instrumented tests (the captcha WebView's message path on the emulator, and the end-to-end test) run in
        // E2eApp, the real composition root with a test's Proton server.
        testInstrumentationRunner = "dev.rboza.android.opensyncforproton.E2eRunner"
    }

    buildFeatures {
        compose = true
        // The account type resource below.
        resValues = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
            // A JUnit 5 test that hangs (a gate or a blocked scheduler) fails instead of stalling the build; the
            // Robolectric tests run as JUnit 4 through the vintage engine, which this default doesn't reach.
            it.systemProperty("junit.jupiter.execution.timeout.default", "60s")
            // ThirdPartyNoticesTest reads these outside the sources, so a change to them must rerun the tests.
            it.inputs.file(rootDir.resolve("LICENSE")).withPathSensitivity(PathSensitivity.RELATIVE)
            it.inputs.file("gradle.lockfile").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug" // D4
            // D1: the Pixel's ABI, plus the emulator's for instrumented tests.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += "arm64-v8a" } // D1
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // The debug key only so `mise run check` builds a release with R8. Never install that APK on the Pixel:
            // under the permanent app ID it would block the real release until an uninstall. `mise run release` asks
            // for an unsigned APK and signs it outside Gradle (tools/release; docs/releasing.md § Steps).
            val unsigned = providers.gradleProperty("osp.unsignedRelease").orNull == "true"
            signingConfig = if (unsigned) null else signingConfigs.getByName("debug")
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
    implementation(project(":sync"))
    implementation(project(":account"))
    // The composition root builds every module's pieces: the Proton client, the crypto library's implementations, the
    // vCard mapper, Room, WorkManager's configuration, and OkHttp's start-up call. All already ship in the app through
    // the modules; these only declare them here.
    implementation(project(":proton"))
    implementation(project(":crypto"))
    implementation(project(":mapping"))
    implementation(libs.room.runtime)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)

    // The UI: Compose with Material 3.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    // The screens' icons.
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    // The captcha WebView (docs/architecture.md § Captcha WebView).
    implementation(libs.webkit)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // The activity tests run under Robolectric.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    robolectricAndroidAll(libs.robolectric.android.all)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // The end-to-end test: MockWebServer over HTTPS with a test-only certificate, and go-srp's own server from
    // android-golib (already in the APK through :crypto) to answer the login.
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.proton.android.golib)
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

androidComponents {
    // D4: the account type is the app's ID (`.debug` in debug builds); the account and sync XML name it through this
    // string, so it can't drift from the package.
    onVariants { variant ->
        variant.resValues.put(variant.makeResValueKey("string", "account_type"), variant.applicationId.map { ResValue(it) })
        // The committed crypto fixtures (fixtures/crypto), read by the end-to-end test.
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory(rootProject.file("fixtures/crypto").path)
    }
    onVariants(selector().withBuildType("release")) { variant ->
        tasks.register<CheckManifest>("checkReleaseManifestRules") {
            mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            apkDirectory = variant.artifacts.get(SingleArtifact.APK)
            aapt2 = androidComponents.sdkComponents.sdkDirectory.map {
                it.file("build-tools/${libs.versions.buildTools.get()}/aapt2")
            }
            // Every component, permission, and other entry of the merged manifest, library entries included
            // (docs/architecture.md § Security).
            allowedEntries = listOf(
                // The launcher activity, the only activity started from outside.
                "activity dev.rboza.android.opensyncforproton.MainActivity exported=true permission=none",
                // The account authenticator, bound only by Android (ACCOUNT_MANAGER is signature-level), and the
                // receiver for the protected ACCOUNT_REMOVED broadcast.
                "service dev.rboza.android.opensyncforproton.AuthenticatorService exported=true permission=android.permission.ACCOUNT_MANAGER",
                "receiver dev.rboza.android.opensyncforproton.AccountRemovedReceiver exported=true permission=none",
                // The contacts sync adapter, bound only by Android (ACCOUNT_MANAGER).
                "service dev.rboza.android.opensyncforproton.ContactsSyncService exported=true permission=android.permission.ACCOUNT_MANAGER",
                // OkHttp's Android library declares it; the app reaches Proton with it.
                "uses-permission android.permission.INTERNET",
                // Room's library declares it; not exported, and only started by enableMultiInstanceInvalidation(),
                // which the app never calls.
                "service androidx.room.MultiInstanceInvalidationService exported=false permission=none",
                // androidx.core declares and uses it (through crypto-android) to keep its dynamic receivers private;
                // signature-level, so only this app holds it.
                "permission dev.rboza.android.opensyncforproton.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION protectionLevel=signature",
                "uses-permission dev.rboza.android.opensyncforproton.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                // WorkManager runs the periodic job (D20): the system binds its job service; the receivers aren't
                // exported (reschedule after a reboot, recover after a force stop); the permissions keep the job awake,
                // check the network constraint, and hear the reboot.
                "service androidx.work.impl.background.systemjob.SystemJobService exported=true permission=android.permission.BIND_JOB_SERVICE",
                "receiver androidx.work.impl.background.systemalarm.RescheduleReceiver exported=false permission=none",
                "receiver androidx.work.impl.utils.ForceStopRunnable\$BroadcastReceiver exported=false permission=none",
                "uses-permission android.permission.WAKE_LOCK",
                "uses-permission android.permission.ACCESS_NETWORK_STATE",
                "uses-permission android.permission.RECEIVE_BOOT_COMPLETED",
                // The periodic job skips while Android's per-account sync switch is off.
                "uses-permission android.permission.READ_SYNC_SETTINGS",
                // The scheduler and the SyncAdapter set Android's sync flags.
                "uses-permission android.permission.WRITE_SYNC_SETTINGS",
                // Reading and writing the account's contacts; asked for before sync is turned on.
                "uses-permission android.permission.READ_CONTACTS",
                "uses-permission android.permission.WRITE_CONTACTS",
                // The "Sync problems" notifications; asked for once sync is on.
                "uses-permission android.permission.POST_NOTIFICATIONS",
            )
        }
    }
}
