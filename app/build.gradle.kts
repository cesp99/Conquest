import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Rust engine (core/) is compiled to libconquestcore.so by cargo-ndk and
// packaged from src/main/jniLibs, which is generated and gitignored.
val rustAbis = listOf("arm64-v8a", "x86_64")
val rustJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

android {
    namespace = "to.eyed.conquest.code"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "to.eyed.conquest.code"
        minSdk = 31
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two editions of the same app, differing in one thing that changes
    // everything downstream: whether Android will let a downloaded program
    // run. See docs/BUILDING.md and agent-docs/DECISIONS.md.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // The old SELinux domain still permits executing files from app
            // storage, which is what a Debian userland under proot needs.
            // Measured on Android 17; see agent-docs/archive/research/android-exec-policy.md.
            targetSdk = 28
            versionNameSuffix = "-full"
            buildConfigField("boolean", "USERLAND", "true")
        }
        create("play") {
            dimension = "distribution"
            targetSdk = 37
            versionNameSuffix = "-play"
            buildConfigField("boolean", "USERLAND", "false")
        }
    }

    buildTypes {
        release {
            // R8: shrink and obfuscate the DEX, and drop unreferenced
            // resources. Worth far more here than it looks — an unminified
            // build carries ~29 MB of Compose/AndroidX classes we barely
            // touch. Keep rules live in src/main/keepRules; the JNI surface
            // must survive renaming (see rules.keep).
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }

    // One APK per ABI instead of one fat APK carrying every ABI. The Rust
    // engine dominates this app's size — tens of MB per architecture — so a
    // universal APK makes every user download an engine they cannot run.
    // Play prefers an app bundle, which splits this way on its own; the
    // per-ABI APKs are what F-Droid and direct installs want.
    splits {
        abi {
            isEnable = true
            reset()
            include(*rustAbis.toTypedArray())
            // Still emit the every-ABI APK: it is what `adb install` on an
            // unknown device and a plain "download the APK" link need.
            isUniversalApk = true
        }
    }
    // Extract native libraries to nativeLibraryDir on install instead of
    // mapping them straight out of the APK. Measured, not assumed: with the
    // modern default, nativeLibraryDir is an *empty* directory, so nothing
    // there can be executed. Everything on-device that is a process rather
    // than a library — the shell, git, language servers, agent runtimes —
    // depends on this flag.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Pinned in gradle.properties so the app and the vendored terminal modules
// (which build libtermux.so with ndk-build) cannot drift apart.
val ndkVersion = providers.gradleProperty("conquest.ndkVersion").get()
val sdkDir: String = run {
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { stream -> localProps.load(stream) }
    }
    localProps.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: "${System.getProperty("user.home")}/Android/Sdk"
}

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Builds the Rust core (libconquestcore.so) for Android ABIs"
    workingDir = rootProject.file("core")
    inputs.dir(rootProject.file("core/crates"))
    inputs.file(rootProject.file("core/Cargo.toml"))
    outputs.dir(rustJniLibsDir)
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/$ndkVersion")
    environment(
        "PATH",
        "${System.getProperty("user.home")}/.cargo/bin:${System.getenv("PATH")}"
    )
    commandLine(
        "cargo", "ndk",
        *rustAbis.flatMap { listOf("-t", it) }.toTypedArray(),
        "-o", rustJniLibsDir.asFile.absolutePath,
        "build", "--release", "-p", "jni-bridge"
    )
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    // The real org.json, for host tests only. Android ships it, but the
    // android.jar the unit tests compile against holds stubs that throw at
    // runtime — and the language configs arrive from the engine as JSON.
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}