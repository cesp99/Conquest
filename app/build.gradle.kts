import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "to.eyed.conquest.code"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "to.eyed.conquest.code"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// The Rust engine (core/) is compiled to libconquestcore.so by cargo-ndk and
// packaged from src/main/jniLibs, which is generated and gitignored.
val rustAbis = listOf("arm64-v8a", "x86_64")
val rustJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

val ndkVersion = "28.2.13676358"
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}