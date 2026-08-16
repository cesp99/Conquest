//! Fuzzy file finding over a project's worktree.
//!
//! Matching runs on Zed's `fuzzy` crate against the mirrored worktree
//! snapshot (see `project.rs`), which means the candidate list is already in
//! memory as a sum-tree and no directory is walked to answer a query.
//!
//! The work itself goes to the runtime's background executor — `fuzzy` shards
//! the candidate set across it — and the calling thread waits for the answer.
//! That makes [`Engine::find_files`] **blocking**, which is the right shape
//! for a JNI call made from a Kotlin worker: the alternative, streaming
//! partial results back, buys nothing when a query completes in single-digit
//! milliseconds.

use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::Duration;

use fuzzy::{PathMatchCandidate, PathMatchCandidateSet};
use path::PathStyle;
use path::rel_path::RelPath;
use worktree::{Snapshot, Traversal};

use crate::ProjectId;

/// One fuzzy hit, ready for the UI.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct FileMatch {
    /// Path relative to the project root, `/`-separated.
    pub path: String,
    /// Final path component, which is what the UI shows first.
    pub name: String,
    /// Offsets in `path` that matched, for highlighting. **UTF-16 code
    /// units**, not bytes: Kotlin strings are UTF-16, and handing the UI byte
    /// offsets would misplace every highlight after a non-ASCII character.
    pub positions: Vec<usize>,
    /// Higher is better. Only meaningful relative to other hits.
    pub score: f64,
}

/// Adapter letting `fuzzy` walk our mirrored snapshot. Mirrors Zed's own
/// implementation in its `project` crate, which we don't vendor.
struct SnapshotCandidates {
    snapshot: Snapshot,
    include_ignored: bool,
}

impl<'a> PathMatchCandidateSet<'a> for SnapshotCandidates {
    type Candidates = SnapshotCandidateIter<'a>;

    fn id(&self) -> usize {
        self.snapshot.id().to_usize()
    }

    fn len(&self) -> usize {
        if self.include_ignored {
            self.snapshot.file_count()
        } else {
            self.snapshot.visible_file_count()
        }
    }

    fn prefix(&self) -> Arc<RelPath> {
        if self
            .snapshot
            .root_entry()
            .is_some_and(|entry| entry.is_file())
        {
            self.snapshot.root_name().into()
        } else {
            RelPath::empty_arc()
        }
    }

    fn root_is_file(&self) -> bool {
        self.snapshot
            .root_entry()
            .is_some_and(|entry| entry.is_file())
    }

    fn path_style(&self) -> PathStyle {
        self.snapshot.path_style()
    }

    fn candidates(&'a self, start: usize) -> Self::Candidates {
        SnapshotCandidateIter {
            traversal: self.snapshot.files(self.include_ignored, start),
        }
    }
}

struct SnapshotCandidateIter<'a> {
    traversal: Traversal<'a>,
}

impl<'a> Iterator for SnapshotCandidateIter<'a> {
    type Item = PathMatchCandidate<'a>;

    fn next(&mut self) -> Option<Self::Item> {
        self.traversal.next().map(|entry| PathMatchCandidate {
            is_dir: entry.kind.is_dir(),
            path: &entry.path,
            char_bag: entry.char_bag,
        })
    }
}

/// Convert `fuzzy`'s byte offsets into UTF-16 code-unit offsets. Positions
/// arrive ascending and on character boundaries, since matching works in
/// chars.
fn to_utf16_positions(path: &str, byte_positions: &[usize]) -> Vec<usize> {
    if byte_positions.iter().all(|&position| position < path.len()) && path.is_ascii() {
        // The overwhelmingly common case: nothing to convert.
        return byte_positions.to_vec();
    }
    let mut converted = Vec::with_capacity(byte_positions.len());
    let mut remaining = byte_positions.iter().peekable();
    let mut utf16 = 0;
    for (byte, character) in path.char_indices() {
        while remaining.peek().is_some_and(|&&position| position == byte) {
            remaining.next();
            converted.push(utf16);
        }
        // Skip any position that fell inside this character rather than on
        // its boundary; it cannot be rendered and must not shift the rest.
        while remaining
            .peek()
            .is_some_and(|&&position| position < byte + character.len_utf8())
        {
            remaining.next();
        }
        utf16 += character.len_utf16();
    }
    converted
}

/// How long to wait for the runtime before giving up. Generous: a query over
/// a large project is milliseconds, so reaching this means something is
/// wedged, and returning nothing beats hanging the caller forever.
const FIND_TIMEOUT: Duration = Duration::from_secs(5);

impl crate::Engine {
    /// Fuzzy-match `query` against the project's files, best first.
    ///
    /// An empty query lists files in worktree order rather than matching
    /// nothing — that is what a file finder should show the moment it opens.
    /// Gitignored files are excluded, matching the panel.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn find_files(&self, id: ProjectId, query: &str, limit: usize) -> Vec<FileMatch> {
        let Some(Some(snapshot)) = self.with_project(id, |state| state.snapshot.clone()) else {
            return Vec::new();
        };
        if limit == 0 {
            return Vec::new();
        }

        if query.is_empty() {
            return snapshot
                .files(false, 0)
                .take(limit)
                .map(|entry| FileMatch {
                    path: entry.path.as_unix_str().to_owned(),
                    name: entry.path.file_name().unwrap_or_default().to_owned(),
                    positions: Vec::new(),
                    score: 0.0,
                })
                .collect();
        }

        // A query with an uppercase letter is treated as case-sensitive, the
        // convention every editor with this feature uses.
        let smart_case = query.chars().any(|c| c.is_uppercase());
        let query = query.to_owned();
        let (sender, receiver) = std::sync::mpsc::channel();

        self.runtime().spawn(move |cx| {
            let executor = cx.background_executor().clone();
            cx.background_executor()
                .spawn(async move {
                    let sets = [SnapshotCandidates {
                        snapshot,
                        include_ignored: false,
                    }];
                    let matches = fuzzy::match_path_sets(
                        &sets,
                        &query,
                        &None,
                        smart_case,
                        limit,
                        &AtomicBool::new(false),
                        executor,
                    )
                    .await;
                    let results = matches
                        .into_iter()
                        .map(|found| {
                            let path = found.path.as_unix_str().to_owned();
                            FileMatch {
                                positions: to_utf16_positions(&path, &found.positions),
                                name: found.path.file_name().unwrap_or_default().to_owned(),
                                score: found.score,
                                path,
                            }
                        })
                        .collect::<Vec<_>>();
                    let _ = sender.send(results);
                })
                .detach();
        });

        receiver.recv_timeout(FIND_TIMEOUT).unwrap_or_else(|err| {
            log::warn!("find_files timed out or the runtime went away: {err}");
            Vec::new()
        })
    }
}

#[cfg(test)]
mod tests {
    use crate::Engine;
    use std::time::{Duration, Instant};

    fn project() -> (Engine, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("src/parser")).unwrap();
        std::fs::create_dir_all(root.join(".git")).unwrap();
        std::fs::create_dir_all(root.join("target/debug")).unwrap();
        std::fs::write(root.join(".gitignore"), "target\n").unwrap();
        std::fs::write(root.join("Cargo.toml"), "").unwrap();
        std::fs::write(root.join("src/main.rs"), "").unwrap();
        std::fs::write(root.join("src/parser/lexer.rs"), "").unwrap();
        std::fs::write(root.join("src/parser/parser_tests.rs"), "").unwrap();
        std::fs::write(root.join("target/debug/build.log"), "").unwrap();

        let engine = Engine::new();
        let id = engine.open_project(root);
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !engine.project_scan_complete(id) {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(engine.project_scan_complete(id));
        (engine, dir)
    }

    #[test]
    fn finds_files_by_fuzzy_subsequence() {
        let (engine, _dir) = project();
        let paths: Vec<String> = engine
            .find_files(1, "lexer", 10)
            .into_iter()
            .map(|found| found.path)
            .collect();
        assert_eq!(paths, vec!["src/parser/lexer.rs"]);

        // Non-contiguous subsequence, the whole point of fuzzy matching.
        let paths: Vec<String> = engine
            .find_files(1, "sprlx", 10)
            .into_iter()
            .map(|found| found.path)
            .collect();
        assert_eq!(paths, vec!["src/parser/lexer.rs"]);
    }

    #[test]
    fn reports_match_positions_for_highlighting() {
        let (engine, _dir) = project();
        let found = engine.find_files(1, "main", 10);
        let first = found.first().expect("a hit for main");
        assert_eq!(first.path, "src/main.rs");
        assert_eq!(first.name, "main.rs");
        // The positions must index into `path`, and spell the query.
        let matched: String = first
            .positions
            .iter()
            .map(|&index| first.path.as_bytes()[index] as char)
            .collect();
        assert_eq!(matched, "main");
    }

    #[test]
    fn positions_are_utf16_offsets() {
        use super::to_utf16_positions;
        // "é" is 2 bytes but 1 UTF-16 unit; "𝄞" is 4 bytes but 2 units.
        let path = "é/𝄞/ab";
        assert_eq!(path.len(), 2 + 1 + 4 + 1 + 2);
        // Byte offsets of 'é', '𝄞', 'a', 'b'.
        assert_eq!(to_utf16_positions(path, &[0, 3, 8, 9]), vec![0, 2, 5, 6]);
        // ASCII is passed straight through.
        assert_eq!(to_utf16_positions("src/main.rs", &[0, 4]), vec![0, 4]);
    }

    #[test]
    fn excludes_gitignored_files() {
        let (engine, _dir) = project();
        let paths: Vec<String> = engine
            .find_files(1, "build", 10)
            .into_iter()
            .map(|found| found.path)
            .collect();
        assert!(
            paths.is_empty(),
            "target/ is gitignored, so build.log must not be offered: {paths:?}"
        );
    }

    #[test]
    fn an_empty_query_lists_files() {
        let (engine, _dir) = project();
        let found = engine.find_files(1, "", 10);
        assert!(!found.is_empty());
        assert!(found.iter().all(|entry| entry.positions.is_empty()));
        // Still respects the limit, and still excludes ignored files.
        assert_eq!(engine.find_files(1, "", 2).len(), 2);
        assert!(
            !found.iter().any(|entry| entry.path.starts_with("target/")),
            "{found:?}"
        );
    }

    #[test]
    fn unknown_projects_and_zero_limits_find_nothing() {
        let (engine, _dir) = project();
        assert!(engine.find_files(999, "main", 10).is_empty());
        assert!(engine.find_files(1, "main", 0).is_empty());
    }
}
