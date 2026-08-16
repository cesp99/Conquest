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
  the text engine. These are vendored into `core/vendor/` at a pinned
  upstream commit (see `core/vendor/VENDOR.md` for the crate list and
  local patches); `engine` buffers are `text::Buffer` instances —
  single-replica CRDTs whose collaboration machinery simply lies
  dormant.
- **Runtime-coupled**: `fs`, `worktree`, `language`, `lsp` and friends
  depend on GPUI — but on its *reactive runtime* (entities, tasks,
  executors), not its renderer. GPUI itself is Apache-2.0 and pure Rust
  at its core, with a pluggable `Platform` trait. This tier is now
  vendored too, and the engine supplies its own headless platform: a
  thread pool, a timer thread and a foreground queue, with every
  window, display and menu method left `unimplemented!()`. GPUI runs on
  a thread of the engine's own and **never draws a pixel** — if a
  vendored crate ever reached for a window it would panic rather than
  silently misbehave.
- **UI crates** (`editor`, `workspace`, GPUI rendering): not reused.
  Their responsibilities are reimplemented natively in Compose.

Zed's own `remote_server` crate — a headless Zed engine driven over an
RPC protocol — is the in-tree proof that the engine runs without a UI.

Because the worktree scans asynchronously on that runtime, its state
reaches the UI by being *mirrored* into an ordinary snapshot the JNI
layer reads directly. Kotlin polls a version counter to know when to
re-read; no JNI call ever waits on the Rust runtime, and the Android
main thread is never blocked by it.

## Where projects live

Projects are directories in the app's private storage, and content
brought in from elsewhere on the device is **copied in** rather than
opened where it sits.

That is the engine's constraint rather than a preference. The worktree is
Zed's: it walks a real filesystem path and watches it with inotify. A
Storage Access Framework tree is a stream of content URIs with no path
behind it, so a project left on shared storage could not be scanned,
watched or opened by the engine at all. Import and export therefore copy,
which costs real time on a large tree and is the honest trade for the
engine working.

`MANAGE_EXTERNAL_STORAGE` would give real paths anywhere and so genuine
open-in-place, but Google Play restricts that permission to a short list
of app categories an IDE is not in, and shared storage is FUSE-backed and
slower to scan. It stays off the table for now, and would only ever be a
flavor-specific capability.

## On-device execution (terminals, LSP servers, agents)

Android only executes programs that arrived through the package installer.
On a modern target SDK that rules out any package manager: a downloaded
binary cannot run, whatever permissions it is given. This shapes the whole
tooling story, and the project answers it with two editions (see
[BUILDING.md](BUILDING.md)).

The **`full`** edition targets SDK 28, where the restriction does not
apply, and runs a real **Debian** through
[proot](https://github.com/termux/proot): the rootfs is downloaded on first
use into app-private storage, proot fakes the filesystem layout its
binaries expect, and `apt install` works against Debian's own servers. The
project directory is bound into the namespace, so the shell and the editor
see the same files. Nothing about the packages is maintained by this
project — that is the point of borrowing a distribution. See
[USERLAND.md](USERLAND.md).

The **`play`** edition keeps a modern target SDK and has no userland. Its
terminal runs Android's own shell (mksh, with toybox's ~210 commands), and
anything else it needs must be compiled into the APK as a
`lib<name>_exec.so` in the native library directory, which stays executable
at any target SDK.

Terminal emulation builds on Termux's cleanly decoupled
`terminal-emulator` / `terminal-view` libraries (GPL-compatible),
embedded in Compose via `AndroidView`. They are vendored as Gradle
modules under `vendor/` at a pinned upstream commit — source, tests and
all — for the same reasons the Zed crates are: see `vendor/VENDOR.md`.
The emulator carries a small C shim (`libtermux.so`) that opens
`/dev/ptmx` and forks the child process; everything above it, including
the VT/xterm state machine, is plain Java.

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
