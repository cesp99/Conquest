//! Conquest Code engine.
//!
//! This crate is the UI-free heart of the IDE: buffers, files, syntax,
//! language intelligence and agent (ACP) integration all live here.
//! The Android app talks to it exclusively through the `jni-bridge` crate.
//!
//! Buffers are backed by Zed's `text::Buffer` (a CRDT over a rope), vendored
//! in `core/vendor/`. Single-replica for now — collaboration features simply
//! lie dormant. Edits are grouped into undo transactions by `text`'s history
//! (time-based grouping), and every content mutation bumps a per-buffer
//! version counter so the UI can cheaply detect staleness.
//!
//! Projects are backed by Zed's `Worktree`, which needs GPUI's reactive
//! runtime. That runtime lives on a thread of its own (`runtime.rs`) behind a
//! headless `Platform` (`platform.rs`) that cannot draw; see `project.rs` for
//! how its state reaches the UI without blocking anything.

use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use rope::Point;
use sum_tree::Bias;

mod config;
mod file;
mod find;
mod highlight;
mod platform;
mod project;
mod runtime;

pub use config::{ProjectPanelSettings, Settings, ThemeMode};
pub use find::FileMatch;
pub use highlight::{HighlightSpan, STYLE_NAMES, language_for_path};
pub use project::{ProjectId, TreeEntry};

pub const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Tell the engine where the app's private storage is. Call this once, from
/// the platform layer, before anything else touches the engine.
///
/// Android runs apps without `$HOME`, but the vendored Zed crates assume a
/// home directory exists — `util::paths::home_dir()` panics outright without
/// one, taking a worktree scan down with it, and `dirs`' config/data lookups
/// derive from it too. An app does have a home; the OS simply doesn't export
/// it, so we do. The same directory anchors the trash, which must sit on the
/// same filesystem as the projects it swallows.
pub fn initialize(files_dir: &Path) {
    if std::env::var_os("HOME").is_none() {
        // SAFETY: `set_var` is unsound only against concurrent environment
        // access. This runs from the platform layer at startup, before the
        // engine has spawned a thread of its own.
        unsafe { std::env::set_var("HOME", files_dir) };
    }
    trash::set_root(files_dir.join(".trash"));
    config::set_directory(files_dir.to_path_buf());
}

pub type BufferId = u64;

static NEXT_BUFFER_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Default)]
pub struct Engine {
    /// Shared so the worktree watcher can reach open buffers from the
    /// runtime thread without a handle on the whole engine (see file.rs).
    buffers: Arc<Mutex<HashMap<BufferId, BufferState>>>,
    projects: Mutex<HashMap<ProjectId, Arc<Mutex<project::ProjectState>>>>,
    next_project_id: AtomicU64,
    /// Started on the first `open_project`, so buffer-only use (and most
    /// tests) never pays for a gpui App.
    runtime: OnceLock<runtime::Runtime>,
}

struct BufferState {
    buffer: text::Buffer,
    /// Monotonic content version: bumped by edit/undo/redo. Not a CRDT
    /// vector clock — just a cheap staleness check for the UI layer.
    version: u64,
    /// Present once a language has been assigned (interim tree-sitter
    /// highlighting; see highlight.rs).
    highlight: Option<highlight::HighlightState>,
    /// Present for buffers backed by a file on disk (see file.rs).
    file: Option<file::FileState>,
}

impl BufferState {
    fn line_count(&self) -> u32 {
        self.buffer.max_point().row + 1
    }

    /// Full reparse after history operations, where the edit shape isn't
    /// readily available for an incremental tree edit.
    fn reset_highlighter(&mut self) {
        if let Some(highlighter) = &mut self.highlight {
            let rope = self.buffer.as_rope().clone();
            highlighter.invalidate(&rope);
        }
    }
}

impl Engine {
    pub fn new() -> Self {
        Self::default()
    }

    /// The gpui runtime, started on first use.
    fn runtime(&self) -> &runtime::Runtime {
        self.runtime
            .get_or_init(|| runtime::Runtime::new(project::init_globals))
    }

    fn next_project_id(&self) -> ProjectId {
        self.next_project_id.fetch_add(1, Ordering::Relaxed) + 1
    }

    pub fn create_buffer(&self, initial_text: &str) -> BufferId {
        let id = NEXT_BUFFER_ID.fetch_add(1, Ordering::Relaxed);
        let remote_id = text::BufferId::new(id).expect("buffer ids start at 1");
        let buffer = text::Buffer::new(clock::ReplicaId::LOCAL, remote_id, initial_text);
        self.buffers.lock().unwrap().insert(
            id,
            BufferState {
                buffer,
                version: 0,
                highlight: None,
                file: None,
            },
        );
        id
    }

    /// Assign a tree-sitter language (by grammar name, e.g. "rust") to the
    /// buffer and parse it. Returns false for unknown language names.
    pub fn set_language(&self, id: BufferId, language: &str) -> Result<bool, EngineError> {
        let mut buffers = self.buffers.lock().unwrap();
        let state = buffers.get_mut(&id).ok_or(EngineError::UnknownBuffer(id))?;
        let rope = state.buffer.as_rope().clone();
        state.highlight = highlight::HighlightState::new(language, &rope);
        Ok(state.highlight.is_some())
    }

    pub fn close_buffer(&self, id: BufferId) -> bool {
        self.buffers.lock().unwrap().remove(&id).is_some()
    }

    /// Replace the byte range `start..end` with `text`, returning the new
    /// buffer version. Offsets are in bytes and must lie on UTF-8 character
    /// boundaries.
    pub fn edit(
        &self,
        id: BufferId,
        start: usize,
        end: usize,
        text: &str,
    ) -> Result<u64, EngineError> {
        let mut buffers = self.buffers.lock().unwrap();
        let state = buffers.get_mut(&id).ok_or(EngineError::UnknownBuffer(id))?;
        let snapshot = state.buffer.snapshot();
        if start > end
            || end > snapshot.len()
            || snapshot.clip_offset(start, Bias::Left) != start
            || snapshot.clip_offset(end, Bias::Left) != end
        {
            return Err(EngineError::InvalidRange { start, end });
        }
        let start_point = snapshot.offset_to_point(start);
        let old_end_point = snapshot.offset_to_point(end);
        state.buffer.edit([(start..end, text)]);
        state.version += 1;
        if let Some(highlighter) = &mut state.highlight {
            let new_end = start + text.len();
            let rope = state.buffer.as_rope().clone();
            let new_end_point = rope.offset_to_point(new_end);
            highlighter.edited(
                &rope,
                start,
                end,
                new_end,
                start_point,
                old_end_point,
                new_end_point,
            );
        }
        Ok(state.version)
    }

    /// Undo the most recent transaction. Returns the new version, or `None`
    /// if there was nothing to undo.
    pub fn undo(&self, id: BufferId) -> Result<Option<u64>, EngineError> {
        let mut buffers = self.buffers.lock().unwrap();
        let state = buffers.get_mut(&id).ok_or(EngineError::UnknownBuffer(id))?;
        Ok(state.buffer.undo().map(|_| {
            state.version += 1;
            state.reset_highlighter();
            state.version
        }))
    }

    /// Redo the most recently undone transaction. Returns the new version,
    /// or `None` if there was nothing to redo.
    pub fn redo(&self, id: BufferId) -> Result<Option<u64>, EngineError> {
        let mut buffers = self.buffers.lock().unwrap();
        let state = buffers.get_mut(&id).ok_or(EngineError::UnknownBuffer(id))?;
        Ok(state.buffer.redo().map(|_| {
            state.version += 1;
            state.reset_highlighter();
            state.version
        }))
    }

    /// Highlight spans for rows `first_row..last_row` (end-exclusive,
    /// clipped). Empty when the buffer has no language assigned.
    pub fn highlights(
        &self,
        id: BufferId,
        first_row: u32,
        last_row: u32,
    ) -> Result<Vec<HighlightSpan>, EngineError> {
        self.with_buffer(id, |state| {
            let Some(highlighter) = &state.highlight else {
                return Vec::new();
            };
            let rope = state.buffer.as_rope();
            let line_count = state.line_count();
            let first = first_row.min(line_count);
            let last = last_row.min(line_count);
            if first >= last {
                return Vec::new();
            }
            let start = rope.point_to_offset(Point::new(first, 0));
            let end = rope.point_to_offset(Point::new(last - 1, rope.line_len(last - 1)));
            highlighter.highlights(rope, start..end)
        })
    }

    pub fn version(&self, id: BufferId) -> Result<u64, EngineError> {
        self.with_buffer(id, |state| state.version)
    }

    pub fn text(&self, id: BufferId) -> Result<String, EngineError> {
        self.with_buffer(id, |state| state.buffer.text())
    }

    pub fn len(&self, id: BufferId) -> Result<usize, EngineError> {
        self.with_buffer(id, |state| state.buffer.len())
    }

    pub fn line_count(&self, id: BufferId) -> Result<u32, EngineError> {
        self.with_buffer(id, |state| state.line_count())
    }

    /// The text of rows `first_row..last_row` (end-exclusive, clipped to the
    /// buffer), joined with `\n` and without a trailing newline.
    pub fn lines(
        &self,
        id: BufferId,
        first_row: u32,
        last_row: u32,
    ) -> Result<String, EngineError> {
        self.with_buffer(id, |state| {
            let snapshot = state.buffer.snapshot();
            let line_count = state.line_count();
            let first = first_row.min(line_count);
            let last = last_row.min(line_count);
            if first >= last {
                return String::new();
            }
            let start = Point::new(first, 0);
            let end = Point::new(last - 1, snapshot.line_len(last - 1));
            snapshot.text_for_range(start..end).collect()
        })
    }

    /// Convert a (row, column) position to a byte offset, clipping to the
    /// buffer contents.
    pub fn point_to_offset(
        &self,
        id: BufferId,
        row: u32,
        column: u32,
    ) -> Result<usize, EngineError> {
        self.with_buffer(id, |state| {
            let snapshot = state.buffer.snapshot();
            let point = snapshot.clip_point(Point::new(row, column), Bias::Left);
            snapshot.point_to_offset(point)
        })
    }

    /// Convert a byte offset to a (row, column) position, clipping to the
    /// buffer contents.
    pub fn offset_to_point(&self, id: BufferId, offset: usize) -> Result<(u32, u32), EngineError> {
        self.with_buffer(id, |state| {
            let snapshot = state.buffer.snapshot();
            let offset = snapshot.clip_offset(offset, Bias::Left);
            let point = snapshot.offset_to_point(offset);
            (point.row, point.column)
        })
    }

    fn with_buffer<T>(
        &self,
        id: BufferId,
        f: impl FnOnce(&BufferState) -> T,
    ) -> Result<T, EngineError> {
        let buffers = self.buffers.lock().unwrap();
        let state = buffers.get(&id).ok_or(EngineError::UnknownBuffer(id))?;
        Ok(f(state))
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum EngineError {
    UnknownBuffer(BufferId),
    InvalidRange {
        start: usize,
        end: usize,
    },
    /// The operation needs a file behind the buffer, and there isn't one.
    NoFile(BufferId),
    /// The engine was never told where settings live (see `initialize`).
    NoSettingsFile,
    /// Settings text that doesn't parse; carries the parser's complaint.
    InvalidSettings(String),
    Io {
        path: String,
        message: String,
    },
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EngineError::UnknownBuffer(id) => write!(f, "unknown buffer {id}"),
            EngineError::InvalidRange { start, end } => {
                write!(f, "invalid range {start}..{end}")
            }
            EngineError::NoFile(id) => write!(f, "buffer {id} is not backed by a file"),
            EngineError::NoSettingsFile => write!(f, "no settings directory configured"),
            EngineError::InvalidSettings(message) => write!(f, "invalid settings: {message}"),
            EngineError::Io { path, message } => write!(f, "{path}: {message}"),
        }
    }
}

impl std::error::Error for EngineError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buffer_roundtrip() {
        let engine = Engine::new();
        let id = engine.create_buffer("hello world");
        engine.edit(id, 6, 11, "conquest").unwrap();
        assert_eq!(engine.text(id).unwrap(), "hello conquest");
        assert!(engine.close_buffer(id));
        assert_eq!(engine.text(id), Err(EngineError::UnknownBuffer(id)));
    }

    #[test]
    fn rejects_bad_ranges() {
        let engine = Engine::new();
        let id = engine.create_buffer("héllo");
        assert_eq!(
            engine.edit(id, 2, 3, "x"),
            Err(EngineError::InvalidRange { start: 2, end: 3 })
        );
        assert_eq!(
            engine.edit(id, 4, 3, "x"),
            Err(EngineError::InvalidRange { start: 4, end: 3 })
        );
        assert_eq!(
            engine.edit(id, 0, 100, "x"),
            Err(EngineError::InvalidRange { start: 0, end: 100 })
        );
    }

    #[test]
    fn versions_are_monotonic() {
        let engine = Engine::new();
        let id = engine.create_buffer("abc");
        assert_eq!(engine.version(id).unwrap(), 0);
        let v1 = engine.edit(id, 3, 3, "d").unwrap();
        let v2 = engine.edit(id, 4, 4, "e").unwrap();
        assert!(v2 > v1);
        assert_eq!(engine.version(id).unwrap(), v2);
    }

    #[test]
    fn undo_redo() {
        let engine = Engine::new();
        let id = engine.create_buffer("hello");
        engine.edit(id, 5, 5, " world").unwrap();
        assert_eq!(engine.text(id).unwrap(), "hello world");

        let undo_version = engine.undo(id).unwrap();
        assert!(undo_version.is_some());
        assert_eq!(engine.text(id).unwrap(), "hello");

        let redo_version = engine.redo(id).unwrap();
        assert!(redo_version.is_some());
        assert_eq!(engine.text(id).unwrap(), "hello world");

        // Nothing left to redo.
        assert_eq!(engine.redo(id).unwrap(), None);
    }

    #[test]
    fn line_windows() {
        let engine = Engine::new();
        let id = engine.create_buffer("one\ntwo\nthree\nfour");
        assert_eq!(engine.line_count(id).unwrap(), 4);
        assert_eq!(engine.lines(id, 0, 4).unwrap(), "one\ntwo\nthree\nfour");
        assert_eq!(engine.lines(id, 1, 3).unwrap(), "two\nthree");
        assert_eq!(engine.lines(id, 3, 4).unwrap(), "four");
        // Ranges are clipped, not rejected.
        assert_eq!(engine.lines(id, 2, 100).unwrap(), "three\nfour");
        assert_eq!(engine.lines(id, 100, 200).unwrap(), "");
        assert_eq!(engine.lines(id, 3, 3).unwrap(), "");
    }

    #[test]
    fn point_offset_conversions() {
        let engine = Engine::new();
        let id = engine.create_buffer("ab\ncd");
        assert_eq!(engine.point_to_offset(id, 1, 0).unwrap(), 3);
        assert_eq!(engine.offset_to_point(id, 4).unwrap(), (1, 1));
        // Clipped past the end of a line / buffer.
        assert_eq!(engine.point_to_offset(id, 0, 99).unwrap(), 2);
        assert_eq!(engine.offset_to_point(id, 99).unwrap(), (1, 2));
    }

    #[test]
    fn highlights_rust() {
        let engine = Engine::new();
        let id = engine.create_buffer("fn main() {\n    let x = 42; // answer\n}\n");
        // No language yet: no spans.
        assert!(engine.highlights(id, 0, 3).unwrap().is_empty());
        assert!(!engine.set_language(id, "not-a-language").unwrap());
        assert!(engine.set_language(id, "rust").unwrap());

        let spans = engine.highlights(id, 0, 3).unwrap();
        let style_at = |row: u32, col: u32| {
            spans
                .iter()
                .filter(|s| s.row == row && s.start_col_utf16 <= col && col < s.end_col_utf16)
                .map(|s| STYLE_NAMES[s.style as usize])
                .last()
        };
        // "fn" and "let" are keywords, "42" a number, the comment a comment.
        assert_eq!(style_at(0, 0), Some("keyword"));
        assert_eq!(style_at(1, 4), Some("keyword"));
        assert_eq!(style_at(1, 12), Some("number"));
        assert_eq!(style_at(1, 17), Some("comment"));

        // Window clipping: row 1 only.
        assert!(
            engine
                .highlights(id, 1, 2)
                .unwrap()
                .iter()
                .all(|s| s.row == 1)
        );

        // Incremental reparse after an edit: "42" -> "\"hi\"" becomes a string.
        let offset = engine.point_to_offset(id, 1, 12).unwrap();
        engine.edit(id, offset, offset + 2, "\"hi\"").unwrap();
        let spans = engine.highlights(id, 1, 2).unwrap();
        assert!(
            spans
                .iter()
                .any(|s| STYLE_NAMES[s.style as usize] == "string")
        );

        // Undo falls back to a full reparse and the number returns.
        engine.undo(id).unwrap();
        let spans = engine.highlights(id, 1, 2).unwrap();
        assert!(
            spans
                .iter()
                .any(|s| STYLE_NAMES[s.style as usize] == "number")
        );
    }

    #[test]
    fn highlight_columns_are_utf16() {
        let engine = Engine::new();
        // '€' is 3 UTF-8 bytes but 1 UTF-16 unit; the string after it must
        // report UTF-16 columns.
        let id = engine.create_buffer("let e = \"€\"; let n = 7;");
        assert!(engine.set_language(id, "rust").unwrap());
        let spans = engine.highlights(id, 0, 1).unwrap();
        let number = spans
            .iter()
            .find(|s| STYLE_NAMES[s.style as usize] == "number")
            .expect("number span");
        // "let e = \"€\"; let n = " is 21 UTF-16 units; the 7 sits at col 21.
        assert_eq!(number.start_col_utf16, 21);
        assert_eq!(number.end_col_utf16, 22);
    }

    #[test]
    fn languages_come_from_file_names() {
        assert_eq!(language_for_path("src/main.rs"), Some("rust"));
        assert_eq!(language_for_path("Cargo.toml"), None); // no toml grammar
        assert_eq!(language_for_path("README.md"), Some("markdown"));
        assert_eq!(language_for_path("script.PY"), Some("python"));
        // JavaScript is parsed with the tsx grammar, as in Zed.
        assert_eq!(language_for_path("app.jsx"), Some("tsx"));
        // Whole-file-name suffixes beat bare extensions.
        assert_eq!(language_for_path("/p/tsconfig.json"), Some("jsonc"));
        assert_eq!(language_for_path("/p/data.json"), Some("json"));
        assert_eq!(language_for_path("Makefile"), None);
    }

    #[test]
    fn open_file_reads_and_detects_language() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("main.rs");
        std::fs::write(&file, "fn main() {}\n").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert_eq!(engine.text(id).unwrap(), "fn main() {}\n");
        // The language stuck, so highlighting is live without a second call.
        assert!(!engine.highlights(id, 0, 1).unwrap().is_empty());

        assert!(matches!(
            engine.open_file(&dir.path().join("absent.rs")),
            Err(EngineError::Io { .. })
        ));
    }

    #[test]
    fn normalizes_crlf() {
        let engine = Engine::new();
        let id = engine.create_buffer("a\r\nb");
        assert_eq!(engine.text(id).unwrap(), "a\nb");
        assert_eq!(engine.line_count(id).unwrap(), 2);
    }
}
