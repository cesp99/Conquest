//! Conquest Code engine.
//!
//! This crate is the UI-free heart of the IDE: buffers, files, syntax,
//! language intelligence and agent (ACP) integration all live here.
//! The Android app talks to it exclusively through the `jni-bridge` crate.
//!
//! The current buffer implementation is a deliberate placeholder backed by
//! `String`. Phase 1 of the roadmap replaces it with Zed's rope/`text`
//! engine (see agent-docs/ROADMAP.md) without changing this public API.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

pub const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");

pub type BufferId = u64;

static NEXT_BUFFER_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Default)]
pub struct Engine {
    buffers: Mutex<HashMap<BufferId, Buffer>>,
}

pub struct Buffer {
    text: String,
}

impl Engine {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn create_buffer(&self, initial_text: &str) -> BufferId {
        let id = NEXT_BUFFER_ID.fetch_add(1, Ordering::Relaxed);
        self.buffers.lock().unwrap().insert(
            id,
            Buffer {
                text: initial_text.to_owned(),
            },
        );
        id
    }

    pub fn close_buffer(&self, id: BufferId) -> bool {
        self.buffers.lock().unwrap().remove(&id).is_some()
    }

    /// Replace the byte range `start..end` with `text`. Offsets are in bytes
    /// and must lie on UTF-8 character boundaries.
    pub fn edit(&self, id: BufferId, start: usize, end: usize, text: &str) -> Result<(), EngineError> {
        let mut buffers = self.buffers.lock().unwrap();
        let buffer = buffers.get_mut(&id).ok_or(EngineError::UnknownBuffer(id))?;
        if end > buffer.text.len()
            || start > end
            || !buffer.text.is_char_boundary(start)
            || !buffer.text.is_char_boundary(end)
        {
            return Err(EngineError::InvalidRange { start, end });
        }
        buffer.text.replace_range(start..end, text);
        Ok(())
    }

    pub fn text(&self, id: BufferId) -> Result<String, EngineError> {
        let buffers = self.buffers.lock().unwrap();
        let buffer = buffers.get(&id).ok_or(EngineError::UnknownBuffer(id))?;
        Ok(buffer.text.clone())
    }

    pub fn len(&self, id: BufferId) -> Result<usize, EngineError> {
        let buffers = self.buffers.lock().unwrap();
        let buffer = buffers.get(&id).ok_or(EngineError::UnknownBuffer(id))?;
        Ok(buffer.text.len())
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
    }
}
