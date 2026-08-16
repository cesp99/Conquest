//! File-backed buffers: open, save, dirty state, and disk conflicts.
//!
//! A buffer is "dirty" when its content version has moved past the version
//! last written to (or read from) disk. That is a comparison of two integers,
//! not a hash of the text, so it is exact and free.
//!
//! Disk changes are *detected*, never silently resolved. The worktree's
//! existing file watcher (see `project.rs`) flags an open buffer whose file
//! moved underneath it; deciding what to do — reload, or keep local edits —
//! belongs to the UI, which is the only layer that can ask the user. That
//! also keeps blocking reads off the runtime thread: flagging costs one
//! `stat`, and [`Engine::reload_buffer`] does the reading from whatever
//! thread called it.

use std::path::{Path, PathBuf};
use std::time::SystemTime;

use crate::{BufferId, EngineError};

/// What the engine remembers about the file behind a buffer.
pub(crate) struct FileState {
    pub path: PathBuf,
    /// Buffer version as of the last successful load or save.
    pub saved_version: u64,
    /// Modification time and length as of that same moment. Together they
    /// distinguish "someone else wrote this file" from "we wrote it", which
    /// matters because our own save fires the watcher too.
    pub disk_mtime: Option<SystemTime>,
    pub disk_len: u64,
    /// The file changed on disk since we last loaded or saved it.
    pub external_change: bool,
    /// The file is no longer on disk.
    pub deleted: bool,
}

impl FileState {
    pub fn new(path: PathBuf) -> Self {
        FileState {
            path,
            saved_version: 0,
            disk_mtime: None,
            disk_len: 0,
            external_change: false,
            deleted: false,
        }
    }

    /// Record the file's current identity as the one we are in sync with.
    pub fn mark_synced(&mut self, version: u64) {
        let (mtime, len) = stat(&self.path);
        self.saved_version = version;
        self.disk_mtime = mtime;
        self.disk_len = len;
        self.external_change = false;
        self.deleted = mtime.is_none();
    }

    /// Compare the file on disk against what we last synced with, and record
    /// the verdict. Returns true if it differs — i.e. somebody else wrote it.
    pub fn note_disk_change(&mut self) -> bool {
        let (mtime, len) = stat(&self.path);
        if mtime.is_none() {
            // Absent files are reported as deleted rather than changed:
            // there is nothing to reload, so the UI shouldn't offer to.
            self.deleted = true;
            return false;
        }
        self.deleted = false;
        if mtime == self.disk_mtime && len == self.disk_len {
            return false;
        }
        self.external_change = true;
        true
    }
}

fn stat(path: &Path) -> (Option<SystemTime>, u64) {
    match std::fs::metadata(path) {
        Ok(metadata) => (metadata.modified().ok(), metadata.len()),
        Err(_) => (None, 0),
    }
}

fn io_error(path: &Path, err: std::io::Error) -> EngineError {
    EngineError::Io {
        path: path.display().to_string(),
        message: err.to_string(),
    }
}

impl crate::Engine {
    /// Read a file into a buffer, choosing the language from its name.
    /// Opening the same path twice returns the same buffer — tabs and the
    /// project panel must not be able to fork a file into two histories.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn open_file(&self, path: &Path) -> Result<BufferId, EngineError> {
        // Canonicalize so `a/../b.rs` and `b.rs` are recognised as one file.
        // A path that doesn't exist yet can't be canonicalized, and also
        // can't be read, so failing here loses nothing.
        let path = std::fs::canonicalize(path).map_err(|err| io_error(path, err))?;
        if let Some(id) = self.buffer_for_path(&path) {
            return Ok(id);
        }

        let text = std::fs::read_to_string(&path).map_err(|err| io_error(&path, err))?;
        let id = self.create_buffer(&text);
        if let Some(language) = language_for_path_str(&path) {
            let _ = self.set_language(id, language);
        }

        let mut file = FileState::new(path);
        file.mark_synced(0);
        let mut buffers = self.buffers.lock().unwrap();
        if let Some(state) = buffers.get_mut(&id) {
            state.file = Some(file);
        }
        Ok(id)
    }

    /// The buffer already holding `path`, if any.
    pub fn buffer_for_path(&self, path: &Path) -> Option<BufferId> {
        self.buffers
            .lock()
            .unwrap()
            .iter()
            .find(|(_, state)| state.file.as_ref().is_some_and(|file| file.path == path))
            .map(|(id, _)| *id)
    }

    /// Absolute path of the file behind a buffer, if it has one.
    pub fn buffer_path(&self, id: BufferId) -> Option<PathBuf> {
        self.with_buffer(id, |state| {
            state.file.as_ref().map(|file| file.path.clone())
        })
        .ok()
        .flatten()
    }

    /// Whether the buffer has edits not yet written to disk. Buffers with no
    /// file are never dirty — there is nowhere for them to be dirty against.
    pub fn buffer_is_dirty(&self, id: BufferId) -> bool {
        self.with_buffer(id, |state| match &state.file {
            Some(file) => state.version != file.saved_version,
            None => false,
        })
        .unwrap_or(false)
    }

    /// Whether the file changed on disk since the buffer last synced with it.
    pub fn buffer_has_disk_change(&self, id: BufferId) -> bool {
        self.with_buffer(id, |state| {
            state.file.as_ref().is_some_and(|file| file.external_change)
        })
        .unwrap_or(false)
    }

    /// Whether the file behind the buffer has been deleted.
    pub fn buffer_file_deleted(&self, id: BufferId) -> bool {
        self.with_buffer(id, |state| {
            state.file.as_ref().is_some_and(|file| file.deleted)
        })
        .unwrap_or(false)
    }

    /// Write the buffer to its file. Returns the version that is now on disk.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn save_buffer(&self, id: BufferId) -> Result<u64, EngineError> {
        // Take the text and path under the lock, then write without holding
        // it: a save of a large file must not stall every other buffer query.
        let (path, text, version) = {
            let buffers = self.buffers.lock().unwrap();
            let state = buffers.get(&id).ok_or(EngineError::UnknownBuffer(id))?;
            let file = state.file.as_ref().ok_or(EngineError::NoFile(id))?;
            (file.path.clone(), state.buffer.text(), state.version)
        };

        write_atomically(&path, &text)?;

        let mut buffers = self.buffers.lock().unwrap();
        let state = buffers.get_mut(&id).ok_or(EngineError::UnknownBuffer(id))?;
        if let Some(file) = &mut state.file {
            // Record the version we actually wrote, not the current one: an
            // edit that landed during the write must leave the buffer dirty.
            file.mark_synced(version);
        }
        Ok(version)
    }

    /// Re-read the file into the buffer, discarding local edits. Applied as a
    /// single edit, so it lands in the undo history like anything else and a
    /// mistaken reload is recoverable.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn reload_buffer(&self, id: BufferId) -> Result<u64, EngineError> {
        let path = self.buffer_path(id).ok_or(EngineError::NoFile(id))?;
        let text = std::fs::read_to_string(&path).map_err(|err| io_error(&path, err))?;

        let mut buffers = self.buffers.lock().unwrap();
        let state = buffers.get_mut(&id).ok_or(EngineError::UnknownBuffer(id))?;
        let len = state.buffer.len();
        // `text`'s history groups edits that land close together in time, so
        // without these boundaries a reload would merge into whatever the
        // user typed a moment earlier and one undo would revert both. A
        // reload is a discrete event; make it a discrete transaction.
        state.buffer.finalize_last_transaction();
        state.buffer.edit([(0..len, text.as_str())]);
        state.buffer.finalize_last_transaction();
        state.version += 1;
        let needs_highlight = state.reset_highlighter();
        let version = state.version;
        if let Some(file) = &mut state.file {
            file.mark_synced(version);
        }
        drop(buffers);
        if needs_highlight {
            self.request_highlight(id);
        }
        Ok(version)
    }

    /// Ask every buffer backed by one of `paths` whether its file moved
    /// underneath it.
    pub fn note_disk_changes(&self, paths: &[PathBuf]) {
        note_disk_changes(&self.buffers, paths);
    }
}

/// Same, against the shared buffer map — this is what the worktree watcher
/// calls from the runtime thread, where there is no `&Engine` to be had.
/// Costs one `stat` per *matching* buffer and nothing at all for the paths we
/// don't have open, which is almost all of them.
pub(crate) fn note_disk_changes(
    buffers: &std::sync::Mutex<std::collections::HashMap<BufferId, crate::BufferState>>,
    paths: &[PathBuf],
) {
    if paths.is_empty() {
        return;
    }
    let mut buffers = buffers.lock().unwrap();
    for state in buffers.values_mut() {
        let Some(file) = &mut state.file else {
            continue;
        };
        if paths.iter().any(|path| *path == file.path) {
            file.note_disk_change();
        }
    }
}

fn language_for_path_str(path: &Path) -> Option<&'static str> {
    crate::language_for_path(&path.to_string_lossy())
}

/// Write via a temporary file in the same directory, then rename. A crash or
/// a full disk then leaves the previous contents intact rather than a
/// half-written source file.
fn write_atomically(path: &Path, text: &str) -> Result<(), EngineError> {
    let directory = path.parent().unwrap_or(Path::new("."));
    let temporary = directory.join(format!(
        ".{}.conquest-tmp",
        path.file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_else(|| "buffer".to_owned())
    ));
    std::fs::write(&temporary, text).map_err(|err| io_error(&temporary, err))?;
    std::fs::rename(&temporary, path).map_err(|err| {
        let _ = std::fs::remove_file(&temporary);
        io_error(path, err)
    })
}

#[cfg(test)]
mod tests {
    use crate::{Engine, EngineError};

    #[test]
    fn opening_the_same_file_twice_shares_one_buffer() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("main.rs");
        std::fs::write(&file, "fn main() {}\n").unwrap();

        let engine = Engine::new();
        let first = engine.open_file(&file).unwrap();
        let second = engine.open_file(&file).unwrap();
        assert_eq!(first, second);
        // …including through a path spelled differently.
        let indirect = dir.path().join("src/../main.rs");
        std::fs::create_dir_all(dir.path().join("src")).unwrap();
        assert_eq!(engine.open_file(&indirect).unwrap(), first);
    }

    #[test]
    fn dirty_tracking_follows_saves() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("notes.txt");
        std::fs::write(&file, "one").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert!(!engine.buffer_is_dirty(id));
        assert_eq!(
            engine.buffer_path(id).unwrap(),
            std::fs::canonicalize(&file).unwrap()
        );

        engine.edit(id, 3, 3, " two").unwrap();
        assert!(engine.buffer_is_dirty(id));

        engine.save_buffer(id).unwrap();
        assert!(!engine.buffer_is_dirty(id));
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "one two");

        // Undo is an edit like any other: it makes the buffer dirty again.
        engine.undo(id).unwrap();
        assert!(engine.buffer_is_dirty(id));
    }

    #[test]
    fn buffers_without_a_file_cannot_be_saved_or_dirty() {
        let engine = Engine::new();
        let id = engine.create_buffer("scratch");
        engine.edit(id, 7, 7, "!").unwrap();
        assert!(!engine.buffer_is_dirty(id));
        assert_eq!(engine.buffer_path(id), None);
        assert!(matches!(
            engine.save_buffer(id),
            Err(EngineError::NoFile(_))
        ));
    }

    #[test]
    fn disk_changes_are_flagged_but_not_applied() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("shared.txt");
        std::fs::write(&file, "original").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        let path = engine.buffer_path(id).unwrap();
        assert!(!engine.buffer_has_disk_change(id));

        // Our own save must not read as an external change.
        engine.edit(id, 8, 8, "!").unwrap();
        engine.save_buffer(id).unwrap();
        engine.note_disk_changes(&[path.clone()]);
        assert!(!engine.buffer_has_disk_change(id));

        // Somebody else's write must.
        std::fs::write(&file, "clobbered by someone else").unwrap();
        engine.note_disk_changes(&[path.clone()]);
        assert!(engine.buffer_has_disk_change(id));
        // Flagged, not applied: the buffer still holds what the user had.
        assert_eq!(engine.text(id).unwrap(), "original!");

        let version = engine.reload_buffer(id).unwrap();
        assert_eq!(engine.text(id).unwrap(), "clobbered by someone else");
        assert!(!engine.buffer_has_disk_change(id));
        assert!(!engine.buffer_is_dirty(id));
        assert_eq!(engine.version(id).unwrap(), version);

        // A reload is undoable, so a mistaken one is recoverable.
        engine.undo(id).unwrap();
        assert_eq!(engine.text(id).unwrap(), "original!");
    }

    #[test]
    fn deletion_is_reported_separately_from_change() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("doomed.txt");
        std::fs::write(&file, "here").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        let path = engine.buffer_path(id).unwrap();

        std::fs::remove_file(&file).unwrap();
        engine.note_disk_changes(&[path]);
        assert!(engine.buffer_file_deleted(id));
        // Nothing to reload to, so no change is offered.
        assert!(!engine.buffer_has_disk_change(id));
        // The content is still there — a deleted file is not a lost buffer.
        assert_eq!(engine.text(id).unwrap(), "here");

        // Saving recreates it.
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "here");
        assert!(!engine.buffer_file_deleted(id));
    }

    #[test]
    fn a_failed_save_leaves_the_previous_contents() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("readonly/file.txt");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, "safe").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        engine.edit(id, 4, 4, " edit").unwrap();

        // Make the directory unwritable so the temporary file can't be made.
        let directory = file.parent().unwrap();
        let mut permissions = std::fs::metadata(directory).unwrap().permissions();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            permissions.set_mode(0o555);
        }
        std::fs::set_permissions(directory, permissions).unwrap();

        let result = engine.save_buffer(id);

        let mut permissions = std::fs::metadata(directory).unwrap().permissions();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            permissions.set_mode(0o755);
        }
        std::fs::set_permissions(directory, permissions).unwrap();

        assert!(matches!(result, Err(EngineError::Io { .. })));
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "safe");
        // Still dirty: the edit was not written, and the UI must keep saying so.
        assert!(engine.buffer_is_dirty(id));
    }
}
