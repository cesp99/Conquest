# Vendored code

Crates copied from the Zed repository, per the vendoring decision in the
project docs (vendor, not git-deps). Licenses: each crate directory
carries its upstream LICENSE file (GPL-3.0-or-later or Apache-2.0);
upstream copyright headers are preserved.

## Upstream

- Source: https://github.com/zed-industries/zed
- Commit: `bc538def45` (local checkout, 2026-08-15)
- Toolchain upstream targets: rustc 1.97.1, edition 2024 (matches this
  workspace).

## Crates

Two tiers, vendored in two passes.

**Tier 0 — the UI-free text stack** (phase 1). These have no gpui
dependency outside their tests.

| Crate | License | Notes |
|---|---|---|
| `sum_tree` | Apache-2.0 | patched (see below) |
| `rope` | GPL-3.0-or-later | patched (see below) |
| `text` | GPL-3.0-or-later | unpatched |
| `clock` | GPL-3.0-or-later | patched (see below) |
| `collections` | Apache-2.0 | unpatched |
| `util` | Apache-2.0 | patched (see below) |
| `util_macros` | Apache-2.0 | unpatched |
| `path` | Apache-2.0 | unpatched |
| `gpui_shared_string` | Apache-2.0 | unpatched |
| `gpui_util` | Apache-2.0 | unpatched |
| `grammars` | GPL-3.0-or-later | unpatched; feature `load-grammars` compiles the embedded tree-sitter C grammars (works for Android via cargo-ndk) |
| `language_core` | GPL-3.0-or-later | unpatched |
| `zlog` | GPL-3.0-or-later | unpatched |
| `ztracing` | GPL-3.0-or-later / Apache-2.0 | unpatched |
| `ztracing_macro` | GPL-3.0-or-later / Apache-2.0 | unpatched |
| `perf` | Apache-2.0 | from `zed/tooling/perf`; patched (see below) |

**Tier 1 — the gpui-coupled runtime** (phase 3, P3-2). The dependency
closure of `worktree`, which is what the project layer needs.
`gpui` here is Zed's real framework, used purely as a reactive runtime:
the engine supplies a headless `Platform` that cannot draw (see
`core/crates/engine/src/platform.rs`).

| Crate | License | Notes |
|---|---|---|
| `gpui` | Apache-2.0 | patched (see below) |
| `gpui_macros` | Apache-2.0 | unpatched |
| `fs` | GPL-3.0-or-later | patched (see below) |
| `worktree` | GPL-3.0-or-later | unpatched — no OS-specific cfgs at all |
| `language` | GPL-3.0-or-later | unpatched |
| `lsp` | GPL-3.0-or-later | unpatched (unused until phase 5) |
| `git` | GPL-3.0-or-later | unpatched |
| `settings` | GPL-3.0-or-later | patched (see below) |
| `settings_content`, `settings_json`, `settings_macros` | GPL-3.0-or-later | unpatched |
| `release_channel` | GPL-3.0-or-later | patched (see below) |
| `theme`, `syntax_theme` | GPL-3.0-or-later | unpatched |
| `task`, `migrator`, `zed_actions`, `zeta_prompt` | GPL-3.0-or-later | unpatched |
| `paths`, `fuzzy`, `fuzzy_nucleo`, `watch`, `net`, `askpass` | GPL-3.0-or-later / Apache-2.0 | unpatched |
| `proto`, `rpc`, `http_client`, `scheduler` | GPL-3.0-or-later / Apache-2.0 | unpatched |
| `telemetry`, `telemetry_events` | GPL-3.0-or-later | unpatched; nothing calls them — the engine sends nothing anywhere |
| `cloud_llm_client`, `language_model_core` | GPL-3.0-or-later / Apache-2.0 | unpatched; pulled in by `settings_content`'s schema |
| `media` | Apache-2.0 | patched (see below); macOS-only content, inert here |
| `refineable`, `refineable/derive_refineable` | Apache-2.0 | unpatched |

`assets/settings/` is vendored from Zed's repo-root `assets/`: `settings`
embeds `default.json` with rust-embed and `SettingsStore` needs it.
Zed's `keymaps/` are deliberately *not* vendored (340 KB of bindings for
a desktop UI we don't have).

Not vendored, but living next to the vendor tree:
`core/crates/trash-android` is a Conquest-written `trash` crate — Zed's
`trash-rs` fork has no Android backend — wired in through a
`[patch."https://github.com/zed-industries/trash-rs"]` entry.

All `X.workspace = true` references resolve against
`core/Cargo.toml`, whose `[workspace.dependencies]` external pins
mirror Zed's workspace `Cargo.toml` at the commit above. Keep them in
sync when syncing vendor/.

Exception to "no git dependencies": several tree-sitter grammar crates
(`cpp`, `gitcommit`, `go-mod`, `gowork`, `md`, `typescript`, `yaml`)
are rev-pinned git dependencies because that is how Zed itself pins
them (forks/unreleased fixes), as are `async-task`, `notify` and
`notify-types` (Zed's `[patch.crates-io]` forks, kept because gpui and
`fs` depend on the fork behaviour). They are small repositories, cached
by cargo after first fetch — nothing like the full-Zed-clone problem the
vendoring decision avoids.

## Local patches

All patches are marked with `CONQUEST PATCH` comments in the touched
files.

Tier 0:

- `sum_tree`: removed `src/property_test.rs` and its module
  declaration + the optional `proptest` dependency (upstream pins a
  git fork of proptest; not worth a git dependency for property tests).
  `test-support` feature is now empty.
- `rope`: dropped `benches/` and the `criterion` dev-dependency.
- `clock`: added `parking_lot` as a dev-dependency so
  `cargo test -p clock` builds standalone (upstream only gets it via
  feature unification from other crates).
- `util`: removed macOS/Windows target-dependency sections (`mach2`,
  `tendril`, `windows`) — we build only for Linux hosts and Android.
- `perf`: removed `src/main.rs` (the profiler CLI binary; we only need
  the library that `util_macros` consumes).

Tier 1 — Android support, from the P3-1 spike
(`agent-docs/research/p3-1-spike-artifacts/android-cfg-patches.diff`):

- `gpui/src/gpui.rs`: two cfg lists gain `target_os = "android"` so the
  `queue` module and its `PriorityQueueSender/Receiver` exports exist on
  Android. Any `PlatformDispatcher` needs them.
- `fs/src/fs.rs`: seven cfg sites gain `target_os = "android"`, so
  Android behaves as Linux. Without the first, `FileHandle::current_path`
  is missing and the `Fs` impl fails to compile; `/proc/self/fd/N` and
  `renameat2` both work verbatim on Android.
- `settings/src/vscode_import.rs`: same rule for the terminal-env
  platform key.

Tier 1 — de-Zed-ing:

- `settings/src/settings.rs`: the `SettingsAssets` rust-embed folder
  points at `../assets` (ours) instead of Zed's repo root.
- `release_channel/src/lib.rs`: the channel name is the literal `"dev"`
  instead of `include_str!` of `crates/zed/RELEASE_CHANNEL`, which lives
  in the Zed app crate we don't vendor.
- `gpui`: the `windows-manifest` feature and its `embed-resource`
  build-dependency are dropped, and `build.rs` with them. We never build
  for Windows.
- Every Tier-1 crate lost its `[dev-dependencies]`, `[[test]]`,
  `[[example]]`, `[[bench]]` and (except `proto`'s prost codegen)
  `[build-dependencies]` sections, plus the matching `tests/`,
  `examples/` and `benches/` directories, and gained
  `[lib] test = false, doctest = false` so their remaining inline
  `#[cfg(test)]` modules aren't built either. Their harnesses need
  `gpui/test-support` and crates outside this closure
  (`gpui_platform`, `reqwest_client`, `theme_settings`). Tier-0 crates
  keep their tests, which is where the vendoring confidence comes from.

Also relevant, though not a source patch: `rust-embed` gains the
`debug-embed` feature in `core/Cargo.toml`. Without it, debug builds
read assets from the host path baked in at compile time, which doesn't
exist on a device — `settings::init` then panics on
`settings/default.json`.

## Sync procedure

1. Update the Zed checkout, note the new commit.
2. Re-copy crate directories (`cp -rL` to dereference LICENSE
   symlinks), re-apply the patches above (search for `CONQUEST PATCH`
   in the old tree first).
3. Diff Zed's workspace `Cargo.toml` pins against
   `core/Cargo.toml` `[workspace.dependencies]` and update.
4. `cd core && cargo test` must be green; update this file's commit
   pin.
