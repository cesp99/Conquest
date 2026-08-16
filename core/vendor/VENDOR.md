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

From `zed/crates/` unless noted:

| Crate | License | Notes |
|---|---|---|
| `sum_tree` | Apache-2.0 | patched (see below) |
| `rope` | GPL-3.0-or-later | patched (see below) |
| `text` | GPL-3.0-or-later | patched (see below) |
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

Not vendored from Zed:

- `gpui_test_shim/` (package name **`gpui`**) — written for Conquest
  Code. Provides only the `#[gpui::test]` attribute macro (plain and
  `iterations = N` forms) so the vendored crates' randomized tests run
  without Zed's real gpui. `SEED` / `ITERATIONS` env vars are honored
  like upstream's runner.

All `X.workspace = true` references resolve against
`core/Cargo.toml`, whose `[workspace.dependencies]` external pins
mirror Zed's workspace `Cargo.toml` at the commit above. Keep them in
sync when syncing vendor/.

Exception to "no git dependencies": several tree-sitter grammar crates
(`cpp`, `gitcommit`, `go-mod`, `gowork`, `md`, `typescript`, `yaml`)
are rev-pinned git dependencies because that is how Zed itself pins
them (forks/unreleased fixes). They are small repositories, cached by
cargo after first fetch — nothing like the full-Zed-clone problem the
vendoring decision avoids.

## Local patches

All patches are marked with `CONQUEST PATCH` comments in the touched
files.

- `sum_tree`: removed `src/property_test.rs` and its module
  declaration + the optional `proptest` dependency (upstream pins a
  git fork of proptest; not worth a git dependency for property tests).
  `test-support` feature is now empty.
- `rope`: dropped `benches/` and the `criterion` dev-dependency;
  `gpui` dev-dependency resolves to the test shim.
- `text`: `gpui` dev-dependency resolves to the test shim.
- `clock`: added `parking_lot` as a dev-dependency so
  `cargo test -p clock` builds standalone (upstream only gets it via
  feature unification from other crates).
- `util`: removed macOS/Windows target-dependency sections (`mach2`,
  `tendril`, `windows`) — we build only for Linux hosts and Android.
- `perf`: removed `src/main.rs` (the profiler CLI binary; we only need
  the library that `util_macros` consumes).

## Sync procedure

1. Update the Zed checkout, note the new commit.
2. Re-copy crate directories (`cp -rL` to dereference LICENSE
   symlinks), re-apply the patches above (search for `CONQUEST PATCH`
   in the old tree first).
3. Diff Zed's workspace `Cargo.toml` pins against
   `core/Cargo.toml` `[workspace.dependencies]` and update.
4. `cd core && cargo test` must be green; update this file's commit
   pin.
