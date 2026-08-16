# Contributing to Conquest Code

Thanks for your interest! Conquest Code is an open-source (GPL-3.0)
attempt to bring a Zed-class IDE to Android. Contributions of all kinds
are welcome: code, docs, testing on real foldables/tablets, theme and
grammar work.

## Ground rules

- **License**: all contributions are accepted under GPL-3.0-or-later
  (see `LICENSE`). Code copied or adapted from other projects must be
  GPL-3.0-compatible and attributed in the commit message and in
  `README.md`'s credits section.
- **Architecture**: the split is strict — everything UI-free lives in
  the Rust engine (`core/`), everything visual/platform lives in Kotlin
  (`app/`). Don't put editor logic in Kotlin or Android types in Rust.
  The JNI boundary (`core/crates/jni-bridge` ↔ `CoreBridge.kt`) stays
  coarse-grained; both files must change together.
- **Performance is a feature.** No blocking calls on the main thread,
  no per-keystroke JNI chatter, no dropped frames while typing. When in
  doubt, measure on a mid-range device.
- **Privacy**: no telemetry, no analytics, no network calls the user
  didn't ask for. Ever.

## Workflow

1. Fork, branch from `master`.
2. `./gradlew assembleDebug` must pass (see `docs/BUILDING.md`).
3. `cd core && cargo test && cargo clippy` must pass.
4. Open a PR with a clear description of what and why.

## Code style

- Rust: `cargo fmt` defaults, clippy-clean.
- Kotlin: official Kotlin style, Compose idioms (state hoisting,
  unidirectional data flow).
