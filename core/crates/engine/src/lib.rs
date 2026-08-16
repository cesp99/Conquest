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

use std::collections::HashMap;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use rope::Point;
use sum_tree::Bias;

pub const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");

pub type BufferId = u64;

static NEXT_BUFFER_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Default)]
pub struct Engine {
    buffers: Mutex<HashMap<BufferId, BufferState>>,
}

struct BufferState {
    buffer: text::Buffer,
    /// Monotonic content version: bumped by edit/undo/redo. Not a CRDT
    /// vector clock — just a cheap staleness check for the UI layer.
    version: u64,
}

impl BufferState {
    fn line_count(&self) -> u32 {
        self.buffer.max_point().row + 1
    }
}

impl Engine {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn create_buffer(&self, initial_text: &str) -> BufferId {
        let id = NEXT_BUFFER_ID.fetch_add(1, Ordering::Relaxed);
        let remote_id = text::BufferId::new(id).expect("buffer ids start at 1");
        let buffer = text::Buffer::new(clock::ReplicaId::LOCAL, remote_id, initial_text);
        self.buffers
            .lock()
            .unwrap()
            .insert(id, BufferState { buffer, version: 0 });
        id
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
        state.buffer.edit([(start..end, text)]);
        state.version += 1;
        Ok(state.version)
    }

    /// Undo the most recent transaction. Returns the new version, or `None`
    /// if there was nothing to undo.
    pub fn undo(&self, id: BufferId) -> Result<Option<u64>, EngineError> {
        let mut buffers = self.buffers.lock().unwrap();
        let state = buffers.get_mut(&id).ok_or(EngineError::UnknownBuffer(id))?;
        Ok(state.buffer.undo().map(|_| {
            state.version += 1;
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
            state.version
        }))
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
    InvalidRange { start: usize, end: usize },
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EngineError::UnknownBuffer(id) => write!(f, "unknown buffer {id}"),
            EngineError::InvalidRange { start, end } => {
                write!(f, "invalid range {start}..{end}")
            }
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
    fn normalizes_crlf() {
        let engine = Engine::new();
        let id = engine.create_buffer("a\r\nb");
        assert_eq!(engine.text(id).unwrap(), "a\nb");
        assert_eq!(engine.line_count(id).unwrap(), 2);
    }
}
