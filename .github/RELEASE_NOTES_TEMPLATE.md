# Release notes template

<!--
How to use: copy everything below the horizontal line into the body of the
new GitHub release (or `gh release create vX.Y.Z --notes-file …`), attach the
six release APKs from app/build/outputs/apk/{full,play}/release/, and fill
the "What's new" section — the "Generate release notes" button does it for
you, configured by .github/release.yml.

The "Which APK" section is boilerplate: leave it verbatim in every release.
It exists because a release carries six files and most people only need one.
-->

---

## 📥 Which APK should I install?

Every release ships **six APKs**: two editions × three architectures.
Answer two questions and you know yours.

### 1 · Pick an edition

| | `full` | `play` |
|---|---|---|
| Terminal | real Debian — `apt install` works | Android's own shell |
| Language servers & tools via `apt` | ✅ | ❌ |
| Available on Google Play | ❌ (impossible by Android's rules, see note) | ✅ |

Everything else — editor, git, themes, agent panel — is identical in both.

- **I want everything this app can do** → `full`
- **I use Google Play** → nothing to download; Play already serves the `play` edition, and the `play` APKs here are the same build for sideloading
- **I need a build Play would accept** (modern target SDK, store policies) → `play`

### 2 · Pick an architecture

| APK contains | Install it on |
|---|---|
| `arm64-v8a` | virtually every real device — phones, tablets, foldables |
| `x86_64` | emulators (Android Studio AVD on an Intel/AMD machine) |
| `universal` | both at once — when in doubt, or `adb install` on an unknown device |

`universal` always works but makes you download both engines (~12 MB more);
the single-architecture APK is the lean choice when you know yours.

### Cheat sheet

| Your situation | Install |
|---|---|
| Real device, full features | `app-full-arm64-v8a-release.apk` |
| Real device, Play edition | `app-play-arm64-v8a-release.apk` |
| Emulator, full features | `app-full-x86_64-release.apk` |
| Emulator, Play edition | `app-play-x86_64-release.apk` |
| Architecture unknown, full features | `app-full-universal-release.apk` |
| Architecture unknown, Play edition | `app-play-universal-release.apk` |

> ℹ️ Android may flag the `full` edition as "built for an older version of
> Android". That is intentional, not a defect: executing programs installed
> by `apt` is only possible at the older target SDK, which is exactly what
> makes the Debian userland work. The APK is signed and safe to install.

## ✨ What's new

<!--
Press "Generate release notes" to fill this from merged PRs (categories
come from .github/release.yml), and add a one-line summary of the release
on top if you like.
-->
