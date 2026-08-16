# Conquest Code

**A fast, native, open-source IDE for Android — in the spirit of
[Zed](https://zed.dev), built for foldables and tablets, running entirely
on your device.**

> ⚠️ Early development. The foundations (Rust engine, JNI bridge,
> adaptive UI shell) are in place; the editor itself is being built.
> Nothing here is usable as a daily driver yet.

## What this is

Conquest Code aims to be for Android what Zed is for the desktop: an IDE
that opens instantly, never drops a frame while you type, and treats AI
agents as first-class citizens through the
[Agent Client Protocol](https://agentclientprotocol.com) (ACP).

- **Truly local.** Everything — editing, syntax highlighting, language
  servers, terminals, agents — runs on the Android device. No cloud
  workspace, no remote VS instance, no account.
- **Zed's engine, not a lookalike.** The core reuses Zed's actual Rust
  crates (rope/CRDT text engine, tree-sitter integration, grammar
  assets) compiled for Android with the NDK.
- **Built for big and small screens.** Foldables and tablets get a full
  workspace (project panel, splits, docks); phones get a slimmer,
  focused layout. Same app, adaptive UI.
- **A real Linux userland.** The terminal runs a genuine Debian through
  `proot` — `apt install` whatever you need, from Debian's own
  repositories, on your phone. The terminal emulation builds on the
  excellent work of [Termux](https://termux.dev).
- **AI-native via ACP.** Any ACP-speaking agent (Claude Code, Gemini
  CLI, custom agents) can run against your project, with the same
  panel-based agent UX Zed pioneered.
- **No telemetry. No analytics. Ever.** In the tradition of
  [VSCodium](https://vscodium.com): the user's code and behavior are
  nobody's business.

## Architecture in one paragraph

A Rust engine (`core/`) owns everything that isn't pixels: buffers
(Zed's rope/CRDT), syntax (tree-sitter), language intelligence (LSP),
project state, git, and ACP agent sessions. A Kotlin/Jetpack Compose app
(`app/`) owns everything visual and platform-specific: rendering, input,
window/fold awareness, storage access. The two meet at one deliberately
narrow, coarse-grained JNI boundary. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the long version,
[docs/BUILDING.md](docs/BUILDING.md) to build it yourself, and
[docs/SHORTCUTS.md](docs/SHORTCUTS.md) for the keyboard and mouse
bindings — the app is meant to be driven from a keyboard, including in
Samsung DeX.

## Status

| Area | State |
|---|---|
| Rust core ↔ Kotlin JNI pipeline | ✅ working end-to-end |
| Adaptive workspace shell (tablet/phone) | ✅ first version |
| Rope/CRDT text engine (from Zed) | ✅ vendored & wired through JNI |
| Tree-sitter syntax highlighting | ✅ in-engine, 21 languages |
| Custom high-performance editor surface | ✅ v1: virtualized canvas, IME editing, selection & clipboard, Zed themes |
| Project tree (Zed worktree in-engine) | ✅ lazy, gitignore-aware; open files from the panel |
| Tabs, save & external change detection | ✅ dirty dots, atomic save, live reload |
| Projects: create, switch, import & export | ✅ app-private storage, SAF folder import/export |
| Fuzzy file finder | ✅ Ctrl+P, match highlighting |
| Settings | ✅ JSONC file that keeps your comments, settings screen |
| Integrated terminal | ✅ shells in the project directory, tabs, theme colours, keyboard/mouse/touch |
| Debian userland (`apt`) | ✅ in the `full` edition — installs on demand, ~30 MB |
| Clone a repository into a project | ✅ `Ctrl+Shift+G`, progress and cancel (`full` edition) |
| Git status colours in the project panel | ✅ engine-side, from the theme's own colours |
| LSP on-device | ⬜ planned |
| ACP agent panel | ⬜ planned |

## Editions

Two builds come out of this repository, and the difference is worth
knowing before you download one:

- **`full`** — includes the Debian userland, so `apt` works. Android only
  permits that at an older target SDK, which Google Play does not accept,
  so this edition comes from F-Droid or a direct APK.
- **`play`** — Play-compatible. Everything else is identical; the terminal
  runs Android's own shell and there is no `apt`.

See [docs/BUILDING.md](docs/BUILDING.md) for the details and
[docs/USERLAND.md](docs/USERLAND.md) for what the userland can do.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Testing on real foldables and
tablets is especially valuable.

## License & credits

Conquest Code is licensed under **GPL-3.0-or-later** (see
[LICENSE](LICENSE)). It stands on the shoulders of:

- **[Zed](https://github.com/zed-industries/zed)** (GPL-3.0 /
  Apache-2.0) — the engine crates this project reuses, and the design
  north star.
- **[Termux](https://github.com/termux/termux-app)** (GPLv3, with an
  Apache-2.0 heritage from
  [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator))
  — its `terminal-emulator` and `terminal-view` libraries are vendored
  here under `vendor/`, and its work is the reference for running a real
  userland on Android.
- **[proot](https://github.com/termux/proot)** (GPL-2.0) — the userspace
  chroot that lets a Linux distribution run without root.
- **[Debian](https://www.debian.org)** — the userland itself, and the
  package archive behind it.
- **[VSCodium](https://github.com/VSCodium/vscodium)** (MIT) — proof
  that a community can keep an IDE honest.

Full provenance, licences and the source offer for the binaries we ship
are in [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).

This project is not affiliated with or endorsed by Zed Industries,
Termux, Debian, or VSCodium.
