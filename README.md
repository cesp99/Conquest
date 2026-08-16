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
- **Terminal included.** An embedded terminal emulator (building on the
  excellent work of [Termux](https://termux.dev)) with on-device shells
  and toolchains, shipped Play-compatibly inside the APK's native
  library directory.
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
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the long version and
[docs/BUILDING.md](docs/BUILDING.md) to build it yourself.

## Status

| Area | State |
|---|---|
| Rust core ↔ Kotlin JNI pipeline | ✅ working end-to-end |
| Adaptive workspace shell (tablet/phone) | ✅ first version |
| Rope/CRDT text engine (from Zed) | ✅ vendored & wired through JNI |
| Tree-sitter syntax highlighting | ⬜ planned |
| Custom high-performance editor surface | ⬜ planned |
| Terminal emulator | ⬜ planned |
| LSP on-device | ⬜ planned |
| ACP agent panel | ⬜ planned |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Testing on real foldables and
tablets is especially valuable.

## License & credits

Conquest Code is licensed under **GPL-3.0-or-later** (see
[LICENSE](LICENSE)). It stands on the shoulders of:

- **[Zed](https://github.com/zed-industries/zed)** (GPL-3.0 /
  Apache-2.0) — the engine crates this project reuses, and the design
  north star.
- **[Termux](https://github.com/termux/termux-app)** (GPLv3) — terminal
  emulation and the art of running a real userland on Android.
- **[VSCodium](https://github.com/VSCodium/vscodium)** (MIT) — proof
  that a community can keep an IDE honest.

This project is not affiliated with or endorsed by Zed Industries,
Termux, or VSCodium.
