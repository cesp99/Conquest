//! Projects: a directory on disk, scanned by Zed's `Worktree`.
//!
//! The worktree entity itself lives on the runtime thread (see `runtime.rs`).
//! Everything the UI needs is *mirrored* out of it into [`ProjectState`], an
//! ordinary mutex-guarded struct holding the latest `worktree::Snapshot`.
//! Queries (children of a directory, entry metadata) run against that
//! snapshot, so they are pure in-memory sum-tree lookups: no locking against
//! the runtime, no risk of blocking the Android main thread.
//!
//! Scanning is asynchronous. [`Engine::open_project`] returns an id
//! immediately; [`Engine::project_version`] bumps every time the mirrored
//! snapshot changes, which is the UI's cue to re-read. That polling shape
//! (rather than a JNI callback) keeps the bridge one-directional.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicUsize;
use std::sync::{Arc, Mutex};

use fs::{Fs, RealFs};
use gpui::{App, Entity, Global, Subscription};
use path::rel_path::RelPath;
use settings::WorktreeId;
use worktree::{Snapshot, Worktree};

use crate::runtime::next_worktree_handle;

pub type ProjectId = u64;

/// A directory entry as the UI sees it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct TreeEntry {
    /// Path relative to the project root, `/`-separated. Empty for the root.
    pub path: String,
    /// Final path component.
    pub name: String,
    pub is_dir: bool,
    /// Ignored by git. Zed scans ignored directories only once expanded.
    pub is_ignored: bool,
    /// Dot-file, or inside a dot-directory.
    pub is_hidden: bool,
    /// True for a directory whose children have not been scanned yet; the UI
    /// must call [`Engine::expand_directory`] before it can show them.
    pub is_unloaded: bool,
    /// Size in bytes; 0 for directories.
    pub size: u64,
}

/// What the UI can read about a project without touching the runtime.
#[derive(Default)]
pub struct ProjectState {
    pub root: PathBuf,
    pub snapshot: Option<Snapshot>,
    /// Bumped on every mirrored change, including the first.
    pub version: u64,
    /// The initial scan has finished. Entries are readable before this, they
    /// are just still arriving.
    pub scan_complete: bool,
    /// Set when the project could not be opened at all.
    pub error: Option<String>,
}

/// Worktree entities, held on the runtime thread only.
#[derive(Default)]
struct WorktreeRegistry {
    worktrees: HashMap<ProjectId, Entity<Worktree>>,
    /// Event subscriptions, kept alive by holding them.
    subscriptions: HashMap<ProjectId, Subscription>,
}

impl Global for WorktreeRegistry {}

/// The `Fs` implementation and shared entry-id counter the worktrees use.
struct FsGlobal {
    fs: Arc<dyn Fs>,
    next_entry_id: Arc<AtomicUsize>,
}

impl Global for FsGlobal {}

/// Runtime-thread setup: called once from `Runtime::new`.
pub(crate) fn init_globals(cx: &mut App) {
    settings::init(cx);
    let fs = Arc::new(RealFs::new(None, cx.background_executor().clone()));
    cx.set_global(FsGlobal {
        fs,
        next_entry_id: Arc::new(AtomicUsize::new(0)),
    });
    cx.set_global(WorktreeRegistry::default());
}

/// Copy the worktree's current snapshot into the mirrored state.
fn mirror(worktree: &Entity<Worktree>, state: &Arc<Mutex<ProjectState>>, cx: &App) {
    let snapshot = worktree.read(cx).snapshot();
    let mut state = state.lock().unwrap();
    state.snapshot = Some(snapshot);
    state.version += 1;
}

/// Directories first, then case-insensitive name — the order Zed's project
/// panel uses, applied here so every caller agrees.
fn sort_entries(entries: &mut [TreeEntry]) {
    entries.sort_by(|a, b| {
        b.is_dir
            .cmp(&a.is_dir)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
            .then_with(|| a.name.cmp(&b.name))
    });
}

impl crate::Engine {
    /// Start scanning `path` as a project. Returns its id immediately; watch
    /// [`Engine::project_version`] for the scan filling in.
    pub fn open_project(&self, path: &Path) -> ProjectId {
        let id = self.next_project_id();
        let state = Arc::new(Mutex::new(ProjectState {
            root: path.to_path_buf(),
            ..Default::default()
        }));
        self.projects.lock().unwrap().insert(id, state.clone());

        let path: Arc<Path> = Arc::from(path);
        self.runtime().spawn(move |cx| {
            let global = cx.global::<FsGlobal>();
            let fs = global.fs.clone();
            let next_entry_id = global.next_entry_id.clone();
            let worktree_id = WorktreeId::from_usize(next_worktree_handle());

            cx.spawn(async move |cx| {
                let fail = |message: String| {
                    let mut state = state.lock().unwrap();
                    state.error = Some(message);
                    state.version += 1;
                };

                // `Worktree::local` happily accepts a path that isn't there
                // (Zed opens worktrees for paths yet to be created), which
                // would leave the UI staring at an empty tree with no
                // explanation. Say so instead.
                match fs.metadata(&path).await {
                    Ok(Some(metadata)) if metadata.is_dir => {}
                    Ok(Some(_)) => {
                        fail(format!("{} is not a directory", path.display()));
                        return;
                    }
                    Ok(None) => {
                        fail(format!("{} does not exist", path.display()));
                        return;
                    }
                    Err(err) => {
                        fail(format!("{err:#}"));
                        return;
                    }
                }

                let worktree =
                    match Worktree::local(path, true, fs, next_entry_id, true, worktree_id, cx)
                        .await
                    {
                        Ok(worktree) => worktree,
                        Err(err) => {
                            fail(format!("{err:#}"));
                            return;
                        }
                    };

                let scan_complete = cx.update(|cx| {
                    mirror(&worktree, &state, cx);
                    let mirrored = state.clone();
                    let subscription =
                        cx.subscribe(&worktree, move |worktree, _event: &worktree::Event, cx| {
                            mirror(&worktree, &mirrored, cx);
                        });
                    let registry = cx.global_mut::<WorktreeRegistry>();
                    registry.worktrees.insert(id, worktree.clone());
                    registry.subscriptions.insert(id, subscription);
                    worktree
                        .read(cx)
                        .as_local()
                        .map(|local| local.scan_complete())
                });

                if let Some(scan_complete) = scan_complete {
                    scan_complete.await;
                }
                cx.update(|cx| {
                    if let Some(worktree) = cx.global::<WorktreeRegistry>().worktrees.get(&id) {
                        let snapshot = worktree.read(cx).snapshot();
                        let mut state = state.lock().unwrap();
                        state.snapshot = Some(snapshot);
                        state.scan_complete = true;
                        state.version += 1;
                    }
                });
            })
            .detach();
        });

        id
    }

    /// Stop scanning a project and forget its mirrored state.
    pub fn close_project(&self, id: ProjectId) -> bool {
        let existed = self.projects.lock().unwrap().remove(&id).is_some();
        if existed {
            self.runtime().spawn(move |cx| {
                let registry = cx.global_mut::<WorktreeRegistry>();
                registry.worktrees.remove(&id);
                registry.subscriptions.remove(&id);
            });
        }
        existed
    }

    /// Monotonic counter, bumped whenever the mirrored snapshot changes.
    /// Returns 0 for an unknown project — which is also the value before the
    /// first mirror, so "unknown" and "not scanned yet" are deliberately not
    /// distinguished: both mean "nothing to show".
    pub fn project_version(&self, id: ProjectId) -> u64 {
        self.with_project(id, |state| state.version).unwrap_or(0)
    }

    pub fn project_scan_complete(&self, id: ProjectId) -> bool {
        self.with_project(id, |state| state.scan_complete)
            .unwrap_or(false)
    }

    /// The error that stopped the project from opening, if any.
    pub fn project_error(&self, id: ProjectId) -> Option<String> {
        self.with_project(id, |state| state.error.clone()).flatten()
    }

    /// Absolute path of the project root.
    pub fn project_root(&self, id: ProjectId) -> Option<PathBuf> {
        self.with_project(id, |state| state.root.clone())
    }

    /// Display name of the project root (its final path component).
    pub fn project_root_name(&self, id: ProjectId) -> Option<String> {
        self.with_project(id, |state| {
            state
                .snapshot
                .as_ref()
                .map(|snapshot| snapshot.root_name_str().to_owned())
                .or_else(|| {
                    state
                        .root
                        .file_name()
                        .map(|name| name.to_string_lossy().into_owned())
                })
                .unwrap_or_default()
        })
    }

    /// Direct children of `dir` (relative to the root, `""` for the root
    /// itself), sorted directories-first. Empty for unknown projects, unknown
    /// directories, and directories that have not been scanned yet.
    pub fn project_entries(&self, id: ProjectId, dir: &str) -> Vec<TreeEntry> {
        let Some(Some(snapshot)) = self.with_project(id, |state| state.snapshot.clone()) else {
            return Vec::new();
        };
        let Ok(dir) = RelPath::from_unix_str(dir) else {
            return Vec::new();
        };
        let mut entries: Vec<TreeEntry> = snapshot
            .child_entries(&dir)
            .map(|entry| TreeEntry {
                path: entry.path.as_unix_str().to_owned(),
                name: entry
                    .path
                    .file_name()
                    .unwrap_or(snapshot.root_name_str())
                    .to_owned(),
                is_dir: entry.is_dir(),
                is_ignored: entry.is_ignored,
                is_hidden: entry.is_hidden,
                is_unloaded: entry.kind.is_unloaded(),
                size: if entry.is_dir() { 0 } else { entry.size },
            })
            .collect();
        sort_entries(&mut entries);
        entries
    }

    /// Scan a directory Zed deferred — an ignored or hidden one, or one past
    /// `file_scan_depth`. Asynchronous: the results show up as a version bump.
    /// Returns false if the project or path is unknown.
    pub fn expand_directory(&self, id: ProjectId, dir: &str) -> bool {
        let Some(Some(snapshot)) = self.with_project(id, |state| state.snapshot.clone()) else {
            return false;
        };
        let Ok(path) = RelPath::from_unix_str(dir) else {
            return false;
        };
        let Some(entry_id) = snapshot.entry_for_path(&path).map(|entry| entry.id) else {
            return false;
        };
        self.runtime().spawn(move |cx| {
            let Some(worktree) = cx.global::<WorktreeRegistry>().worktrees.get(&id).cloned() else {
                return;
            };
            let task = worktree.update(cx, |worktree, cx| worktree.expand_entry(entry_id, cx));
            if let Some(task) = task {
                task.detach();
            }
        });
        true
    }

    /// Absolute path of a project-relative entry.
    pub fn project_entry_abs_path(&self, id: ProjectId, path: &str) -> Option<PathBuf> {
        let root = self.project_root(id)?;
        if path.is_empty() {
            return Some(root);
        }
        // Reject anything that could escape the root; the UI only ever passes
        // paths it got from `project_entries`, so this is a guard, not a
        // feature.
        if path.split('/').any(|part| part == ".." || part.is_empty()) {
            return None;
        }
        Some(root.join(path))
    }

    fn with_project<T>(&self, id: ProjectId, f: impl FnOnce(&ProjectState) -> T) -> Option<T> {
        let state = self.projects.lock().unwrap().get(&id).cloned()?;
        let state = state.lock().unwrap();
        Some(f(&state))
    }
}

#[cfg(test)]
mod tests {
    use crate::Engine;
    use std::time::{Duration, Instant};

    /// Block until the scan finishes. The runtime is genuinely concurrent, so
    /// tests wait on it rather than assuming a synchronous open.
    fn wait_for_scan(engine: &Engine, id: u64) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline {
            if engine.project_scan_complete(id) {
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        panic!("project {id} did not finish scanning");
    }

    fn fixture() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("src/nested")).unwrap();
        // A worktree outside a git repository defers directories deeper than
        // `file_scan_depth`; a .git makes this a repo, as real projects are.
        std::fs::create_dir_all(root.join(".git")).unwrap();
        std::fs::write(root.join("Cargo.toml"), "[package]\n").unwrap();
        std::fs::write(root.join(".gitignore"), "target\n").unwrap();
        std::fs::write(root.join("src/main.rs"), "fn main() {}\n").unwrap();
        std::fs::write(root.join("src/nested/deep.rs"), "// deep\n").unwrap();
        std::fs::create_dir_all(root.join("target")).unwrap();
        std::fs::write(root.join("target/artifact.bin"), "binary").unwrap();
        dir
    }

    #[test]
    fn scans_a_project_tree() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);

        let root = engine.project_entries(id, "");
        let names: Vec<&str> = root.iter().map(|entry| entry.name.as_str()).collect();
        // Directories first, then files, each alphabetically. `.git` is
        // absent: Zed's default `file_scan_exclusions` drop it.
        assert_eq!(names, vec!["src", "target", ".gitignore", "Cargo.toml"]);
        assert!(root.iter().find(|e| e.name == "src").unwrap().is_dir);
        assert!(root.iter().find(|e| e.name == "target").unwrap().is_ignored);
        assert!(!root.iter().find(|e| e.name == "src").unwrap().is_ignored);
        assert!(
            root.iter()
                .find(|e| e.name == ".gitignore")
                .unwrap()
                .is_hidden
        );

        let src = engine.project_entries(id, "src");
        let names: Vec<&str> = src.iter().map(|entry| entry.name.as_str()).collect();
        assert_eq!(names, vec!["nested", "main.rs"]);
        let main = src.iter().find(|e| e.name == "main.rs").unwrap();
        assert_eq!(main.path, "src/main.rs");
        assert_eq!(main.size, "fn main() {}\n".len() as u64);

        assert_eq!(
            engine
                .project_entries(id, "src/nested")
                .iter()
                .map(|e| e.name.as_str())
                .collect::<Vec<_>>(),
            vec!["deep.rs"]
        );

        assert_eq!(
            engine.project_root_name(id).as_deref(),
            dir.path().file_name().unwrap().to_str()
        );
        assert!(engine.project_version(id) > 0);
        assert_eq!(engine.project_error(id), None);
    }

    #[test]
    fn ignored_directories_are_expanded_on_demand() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);

        // `target` is gitignored, so Zed lists it but not its contents.
        let target = engine
            .project_entries(id, "")
            .into_iter()
            .find(|entry| entry.name == "target")
            .unwrap();
        assert!(target.is_ignored);
        assert!(engine.project_entries(id, "target").is_empty());

        assert!(engine.expand_directory(id, "target"));
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && engine.project_entries(id, "target").is_empty() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(
            engine
                .project_entries(id, "target")
                .iter()
                .map(|e| e.name.as_str())
                .collect::<Vec<_>>(),
            vec!["artifact.bin"]
        );
    }

    #[test]
    fn reports_a_missing_project() {
        let engine = Engine::new();
        let id = engine.open_project(std::path::Path::new("/definitely/not/here"));
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && engine.project_error(id).is_none() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(engine.project_error(id).is_some());
        assert!(engine.project_entries(id, "").is_empty());
    }

    #[test]
    fn abs_paths_cannot_escape_the_root() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        assert_eq!(
            engine.project_entry_abs_path(id, "src/main.rs"),
            Some(dir.path().join("src/main.rs"))
        );
        assert_eq!(
            engine.project_entry_abs_path(id, ""),
            Some(dir.path().to_path_buf())
        );
        assert_eq!(engine.project_entry_abs_path(id, "../secrets"), None);
        assert_eq!(engine.project_entry_abs_path(id, "src//main.rs"), None);
    }

    #[test]
    fn closing_a_project_forgets_it() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);
        assert!(engine.close_project(id));
        assert!(!engine.close_project(id));
        assert_eq!(engine.project_version(id), 0);
        assert!(engine.project_entries(id, "").is_empty());
    }
}
