# Architecture

Conquest Code is a two-language project with one strict boundary.

```
┌─────────────────────────────────────────────────────────┐
│  app/  — Kotlin + Jetpack Compose                       │
│  workspace shell · editor surface · terminal view       │
│  fold/tablet adaptive layouts · IME · SAF storage       │
├────────────────── JNI (CoreBridge) ─────────────────────┤
│  core/ — Rust (cargo workspace, built via cargo-ndk)    │
│  crates/jni-bridge  → libconquestcore.so (cdylib)       │
│  crates/engine      → buffers, syntax, LSP, ACP, git    │
└─────────────────────────────────────────────────────────┘
```

## Why Rust below, Kotlin above

The "Zed feel" — instant startup, zero dropped frames while typing on a
50k-line file — requires predictable latency in the hot paths. Kotlin
runs on ART with a garbage collector; Rust compiles to machine code with
none. More importantly, the hardest components of an editor (the
rope/CRDT text engine, incremental tree-sitter parsing, LSP plumbing)
already exist as battle-tested Rust crates in
[Zed](https://github.com/zed-industries/zed), and this project reuses
them rather than re-deriving years of correctness work.

Kotlin/Compose does what it is genuinely best at: rendering, input,
window size classes and fold postures, the soft keyboard, and Android
storage — things no Rust crate does well on Android.

## The JNI boundary

The entire contract lives in two files that must change together:

- `core/crates/jni-bridge/src/lib.rs` (Rust exports)
- `app/src/main/java/to/eyed/conquest/code/core/CoreBridge.kt`
  (Kotlin `external` declarations)

Rules:

1. **Coarse-grained calls only.** A JNI crossing costs real time; the
   UI never loops over per-character calls. Batches, snapshots and
   diffs cross the boundary, not keystrokes.
2. **The engine owns truth.** Buffers, project state, agent sessions
   all live in Rust. Kotlin holds view state only.
3. **No Android types in Rust, no editor logic in Kotlin.**

## Reusing Zed

Zed's crates fall into tiers (verified against the Zed source):

- **UI-free, reusable as-is**: `sum_tree` (Apache-2.0), `rope`, `text`
  (the CRDT buffer), `clock`, `language_core`, `grammars` (tree-sitter
  queries + configs for 20+ languages), plus utility crates. This is
  the text engine.
- **Runtime-coupled**: `language`, `lsp`, `worktree`, `project`, etc.
  depend on GPUI — but on its *reactive runtime* (entities, tasks,
  executors), not its renderer. GPUI itself is Apache-2.0, pure Rust
  at its core, and supports pluggable platforms including headless
  operation. Reusing this tier means bringing up a minimal headless
  GPUI platform for Android — a large but bounded task.
- **UI crates** (`editor`, `workspace`, GPUI rendering): not reused.
  Their responsibilities are reimplemented natively in Compose.

Zed's own `remote_server` crate — a headless Zed engine driven over an
RPC protocol — is the in-tree proof that the engine runs without a UI.

## On-device execution (terminals, LSP servers, agents)

Android 10+ forbids executing files from an app's writable data
directory (W^X). Termux dodges this by targeting SDK 28; Conquest Code
instead targets modern SDKs and uses the Play-compatible approach:
executables ship inside the APK as `lib<name>.so` files in the native
library directory, which remains executable at any target SDK
(`extractNativeLibs=true`). Core tools (shell, busybox, common language
servers) are bundled; an optional package layer can add more later.

Terminal emulation builds on Termux's cleanly decoupled
`terminal-emulator` / `terminal-view` libraries (GPL-compatible),
embedded in Compose via `AndroidView`.

## ACP (Agent Client Protocol)

Agents are external processes speaking newline-delimited JSON-RPC over
stdio — the same model Zed uses, via the same
[`agent-client-protocol`](https://crates.io/crates/agent-client-protocol)
crate. The engine spawns and supervises agent processes and maintains
thread state (messages, tool calls, plans, permission requests); the
Compose agent panel renders that state. Node-based agents (Claude Code,
Gemini CLI) additionally require a bundled Node runtime — see the
roadmap.

## Privacy

No telemetry, no analytics, no phoning home. Network access happens
only for features the user explicitly invokes (cloning a repo,
downloading a language server or agent, an agent calling its own API).
