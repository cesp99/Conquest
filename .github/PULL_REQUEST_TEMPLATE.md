## Summary

<!-- One or two sentences: what does this change do, and why? -->

## Type of change

<!-- Check all that apply -->

- [ ] Bug fix
- [ ] New feature
- [ ] Performance improvement
- [ ] Refactor / cleanup
- [ ] Documentation
- [ ] Build / CI
- [ ] Other

## Architecture & conventions checklist

<!-- See CONTRIBUTING.md for the rules behind these. -->

- [ ] Editor logic stays in Rust (`core/`); UI/platform stays in Kotlin (`app/`)
- [ ] No Android types leaked into Rust, no editor logic in Kotlin
- [ ] If the JNI boundary changed, both `core/crates/jni-bridge/src/lib.rs` and `app/…/core/CoreBridge.kt` were updated together
- [ ] No blocking calls on the main thread; no per-keystroke JNI chatter
- [ ] No telemetry, analytics, or network calls the user didn't ask for
- [ ] Interactive features ship touch **and** keyboard **and** mouse support
- [ ] `docs/SHORTCUTS.md` updated if new shortcuts/commands were added
- [ ] No private info (personal emails, keys, machine paths) in committed files
- [ ] Code copied/adapted from elsewhere is GPL-3.0-compatible and attributed

## Edition impact

<!-- Which editions are affected? Both should be considered. -->

- [ ] `full` (Debian userland, `apt`, F-Droid/direct APK)
- [ ] `play` (Play-compatible, no userland)
- [ ] Both equally
- [ ] Neither (docs / tooling only)

## Testing

<!-- Check what was run. All three gates must pass before merge. -->

- [ ] `./gradlew assembleFullDebug assemblePlayDebug`
- [ ] `./gradlew :app:testFullDebugUnitTest`
- [ ] `cd core && cargo test`
- [ ] `cd core && cargo clippy`
- [ ] Tested on a real device (specify model / form factor below)
- [ ] Tested on the foldable emulator (`tools/fold-emulator.sh`)

**Device tested on** (if applicable):

<!-- Model, Android version, form factor (phone / tablet / foldable) -->

## Screenshots / recordings

<!-- For UI changes, show before and after. Foldable layouts especially. -->

## Related issues

<!-- "Closes #123", "Refs #456", etc. -->
