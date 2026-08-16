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
`gradle.properties` (`conquest.ndkVersion`) and is shared with the
vendored terminal modules.

Two more Gradle modules live under `vendor/`: `terminal-emulator` and
`terminal-view`, Termux's terminal libraries vendored at a pinned commit
(`vendor/VENDOR.md`). The emulator builds a second small native library,
`libtermux.so`, via `ndk-build` — no extra setup beyond the NDK above.
Their unit tests run on the host:

```sh
./gradlew :terminal-emulator:testDebugUnitTest
```

The build emits one APK per ABI plus a universal one, since the Rust
engine is by far the largest thing in the package and no device can use
more than one architecture's copy:

```
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk    ← real devices
app/build/outputs/apk/debug/app-x86_64-debug.apk       ← emulators
app/build/outputs/apk/debug/app-universal-debug.apk    ← both
```

Release builds additionally run R8 (code shrinking + obfuscation) and
resource shrinking. **The JNI boundary must survive that**: a native
symbol name encodes the Java class and method it binds to, so
`CoreBridge` is kept verbatim by `app/src/main/keepRules/rules.keep`.
Adding a class that Android instantiates reflectively — an Activity,
a Service, a Parcelable — may need a keep rule of its own.

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

To try a release build locally, sign it with the debug key — an unsigned
APK cannot be installed:

```sh
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
```
