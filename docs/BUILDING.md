# Building Conquest Code

Conquest Code is a hybrid project: a Rust engine (`core/`) compiled for
Android with the NDK, and a Kotlin/Jetpack Compose app (`app/`) that
embeds it. One Gradle command builds both.

## Prerequisites

- **JDK 17+**
- **Android SDK** with **NDK 28.x** (install via Android Studio's SDK
  Manager or `sdkmanager "ndk;28.2.13676358"`)
- **Rust** (via [rustup](https://rustup.rs)) with the Android targets:

  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  ```

- **cargo-ndk**:

  ```sh
  cargo install cargo-ndk
  ```

## Build

```sh
./gradlew assembleDebug
```

The `cargoNdkBuild` Gradle task cross-compiles `core/` to
`libconquestcore.so` for each supported ABI and drops it into
`app/src/main/jniLibs/` (generated, gitignored) before the APK is
packaged. The NDK path is derived from `sdk.dir` in `local.properties`
(falling back to `$ANDROID_HOME`); the NDK version pin lives in
`app/build.gradle.kts`.

## Rust-only iteration

```sh
cd core
cargo test          # host-side unit tests, no device needed
cargo clippy
```

To cross-compile just the native library:

```sh
cd core
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358 \
  cargo ndk -t arm64-v8a -o /tmp/jniLibs build --release -p jni-bridge
```

## Install on a device

```sh
./gradlew installDebug
```

Supported ABIs: `arm64-v8a` (all real devices) and `x86_64` (emulator).
