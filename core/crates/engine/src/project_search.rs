//! Search across a project's worktree.
//!
//! Unlike buffer search this is genuinely slow work — thousands of files read
//! off a phone's storage — so it does not answer on the caller's thread. It
//! runs on a thread of its own and publishes a generation counter the UI
//! polls, exactly the shape `git.rs` already uses and the project panel
//! already knows how to consume.
//!
//! That choice is worth spelling out, because the obvious alternative is a
//! blocking JNI call parked on a Kotlin coroutine. It would be *safe* — the
//! main thread would never see it — but it would also be worse in three ways
//! the polling shape gets for free: results appear as they are found instead
//! of all at once at the end; progress ("812 of 3400 files") is readable while
//! it runs; and cancelling is a flag the worker notices between files rather
//! than a coroutine cancellation that cannot actually stop native code.
//!
//! Which files exist, and which of them git ignores, is entirely the
//! worktree's answer — see `project.rs`. This module walks the same mirrored
//! snapshot the project panel draws, so the two can never disagree about what
//! is in the project. One consequence is worth knowing: Zed only scans an
//! ignored directory once it is expanded, so `include_ignored` reaches the
//! ignored files the panel can currently see, not the whole of `target/`.

use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use path::PathStyle;
use util::paths::PathMatcher;
use worktree::Snapshot;

use crate::EngineError;
use crate::project::ProjectId;
use crate::search::{SearchOptions, SearchQuery};

pub type SearchId = u64;

/// Caps, all three of them honest rather than silent. Zed stops at 5 000 files
/// and 10 000 ranges; these are lower because the results land in a panel on a
/// phone, where the thousandth file is not a result anyone is going to reach.
const MAX_RESULT_FILES: usize = 1_000;
const MAX_RESULT_MATCHES: usize = 5_000;
/// One minified bundle must not be allowed to eat the whole match budget.
const MAX_MATCHES_PER_FILE: usize = 500;

/// Files larger than this are skipped. Anything this big is generated, and
/// reading it costs more than the result is worth.
const MAX_FILE_BYTES: u64 = 4 * 1024 * 1024;

/// How much of a file to inspect for a NUL before calling it binary.
const BINARY_SNIFF_BYTES: usize = 8 * 1024;

/// A result line longer than this is windowed around its match. Long lines are
/// generated code, and shipping a megabyte of one to the UI to draw forty
/// pixels of it is pure waste.
const MAX_LINE_BYTES: usize = 512;
/// How much of a windowed line to keep before the match, so the hit is not
/// flush against the left edge.
const CONTEXT_BEFORE_MATCH: usize = 64;

/// How often the worker publishes what it has. Fast enough to feel live, slow
/// enough that the results lock is not the bottleneck.
const PUBLISH_INTERVAL: Duration = Duration::from_millis(100);

/// How far a search has got.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SearchState {
    Running,
    Done,
    /// Superseded by a newer search on the same project, or cancelled
    /// outright. Also what an id the engine has forgotten reports.
    Cancelled,
}

/// One hit, shaped for a results panel rather than for an editor: it carries
/// the line it lives on, because the panel draws that line and never opens the
/// file.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct LineMatch {
    /// 1-based, for display. To put a cursor on it, open the file and ask for
    /// `point_to_offset(line - 1, column)`.
    pub line: u32,
    /// Byte column of the match within the *whole* line, which is what
    /// `point_to_offset` wants. Equal to `start` unless `text` was windowed.
    pub column: usize,
    /// Byte range of the match within `text`.
    pub start: usize,
    pub end: usize,
    /// The same range in UTF-16 code units, which is how Kotlin will index
    /// `text` when it highlights the hit.
    pub start_utf16: usize,
    pub end_utf16: usize,
    /// The line, windowed around the match if it was very long.
    pub text: String,
    /// `text` starts / ends mid-line, so the UI should show an ellipsis.
    pub clipped_start: bool,
    pub clipped_end: bool,
}

/// Every hit in one file.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct FileMatches {
    /// Path relative to the project root, `/`-separated — the same spelling
    /// `TreeEntry::path` uses, so the UI can cross-reference a panel row.
    pub path: String,
    pub matches: Vec<LineMatch>,
    /// How many matches the file holds. Larger than `matches.len()` when the
    /// per-file cap bit.
    pub match_count: usize,
}

/// A snapshot of a search, from `from_file` onwards.
///
/// `files` is append-only for the life of a search, so the UI keeps what it
/// has and asks only for what is new.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct SearchResults {
    pub id: SearchId,
    pub state: SearchState,
    pub version: u64,
    /// Only ever set for a failure that stopped the search — a project that
    /// vanished, not a file that could not be read.
    pub error: Option<String>,
    pub files_searched: usize,
    /// Files the worktree offered to search, for a progress bar.
    pub total_files: usize,
    /// Files with at least one match, in all — not just in `files`.
    pub file_count: usize,
    pub match_count: usize,
    /// One of the caps bit, so this is not the whole truth.
    pub truncated: bool,
    /// The index `files[0]` sits at in the whole result list.
    pub from_file: usize,
    pub files: Vec<FileMatches>,
}

impl SearchResults {
    fn unknown(id: SearchId, from_file: usize) -> Self {
        Self {
            id,
            state: SearchState::Cancelled,
            version: 0,
            error: None,
            files_searched: 0,
            total_files: 0,
            file_count: 0,
            match_count: 0,
            truncated: false,
            from_file,
            files: Vec::new(),
        }
    }
}

/// What the worker writes and the poller reads.
#[derive(Default)]
struct Found {
    files: Vec<FileMatches>,
    version: u64,
    finished: bool,
    error: Option<String>,
    files_searched: usize,
    total_files: usize,
    match_count: usize,
    truncated: bool,
}

struct Search {
    id: SearchId,
    cancelled: AtomicBool,
    found: Mutex<Found>,
}

impl Search {
    fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }
}

/// At most one search per project: starting a new one supersedes the old,
/// which is what a search bar does anyway and what keeps worker threads from
/// accumulating behind a user who keeps typing.
#[derive(Default)]
pub(crate) struct ProjectSearches {
    searches: Mutex<HashMap<ProjectId, Arc<Search>>>,
    next_id: AtomicU64,
}

impl ProjectSearches {
    fn find(&self, id: SearchId) -> Option<Arc<Search>> {
        self.searches
            .lock()
            .unwrap()
            .values()
            .find(|search| search.id == id)
            .cloned()
    }

    /// Stop whatever is running for a project and forget it.
    pub(crate) fn cancel_project(&self, project: ProjectId) {
        if let Some(search) = self.searches.lock().unwrap().remove(&project) {
            search.cancelled.store(true, Ordering::Relaxed);
        }
    }
}

impl crate::Engine {
    /// Start searching a project. Returns an id to poll with; the search runs
    /// on its own thread and this call never waits for it.
    ///
    /// Any search already running for this project is cancelled, so the id
    /// returned here is the only live one for it.
    pub fn start_project_search(
        &self,
        project: ProjectId,
        options: &SearchOptions,
    ) -> Result<SearchId, EngineError> {
        let query = SearchQuery::new(options).map_err(EngineError::InvalidQuery)?;
        let include = path_matcher(&options.include_globs).map_err(EngineError::InvalidQuery)?;
        let exclude = path_matcher(&options.exclude_globs).map_err(EngineError::InvalidQuery)?;

        let Some((root, Some(snapshot))) = self.with_project(project, |state| {
            (state.root.clone(), state.snapshot.clone())
        }) else {
            return Err(EngineError::UnknownProject(project));
        };

        let id = self.searches.next_id.fetch_add(1, Ordering::Relaxed) + 1;
        let search = Arc::new(Search {
            id,
            cancelled: AtomicBool::new(false),
            found: Mutex::new(Found::default()),
        });
        if let Some(previous) = self
            .searches
            .searches
            .lock()
            .unwrap()
            .insert(project, search.clone())
        {
            previous.cancelled.store(true, Ordering::Relaxed);
        }

        // An empty query is a finished search with nothing in it, not an
        // error: the search bar hits this on every backspace to empty.
        let Some(query) = query else {
            let mut found = search.found.lock().unwrap();
            found.finished = true;
            found.version = 1;
            return Ok(id);
        };

        let include_ignored = options.include_ignored;
        let worker = search.clone();
        let spawned = thread::Builder::new()
            .name("conquest-project-search".to_owned())
            .spawn(move || {
                run(
                    &worker,
                    &snapshot,
                    &root,
                    &query,
                    &include,
                    &exclude,
                    include_ignored,
                )
            });
        if let Err(err) = spawned {
            let mut found = search.found.lock().unwrap();
            found.finished = true;
            found.error = Some(format!("could not start the search: {err}"));
            found.version += 1;
        }
        Ok(id)
    }

    /// Generation counter for a search, bumped whenever there is something new
    /// to read. 0 before the first results and for an id the engine has
    /// forgotten. Poll it the way the panel polls `project_version`.
    pub fn project_search_version(&self, id: SearchId) -> u64 {
        self.searches
            .find(id)
            .map(|search| search.found.lock().unwrap().version)
            .unwrap_or(0)
    }

    /// Everything found so far, from `from_file` onwards. Results only ever
    /// grow, so a caller that already holds `n` files passes `n` and gets what
    /// it is missing.
    pub fn project_search_results(&self, id: SearchId, from_file: usize) -> SearchResults {
        let Some(search) = self.searches.find(id) else {
            return SearchResults::unknown(id, from_file);
        };
        let found = search.found.lock().unwrap();
        let from_file = from_file.min(found.files.len());
        SearchResults {
            id,
            state: if search.is_cancelled() {
                SearchState::Cancelled
            } else if found.finished {
                SearchState::Done
            } else {
                SearchState::Running
            },
            version: found.version,
            error: found.error.clone(),
            files_searched: found.files_searched,
            total_files: found.total_files,
            file_count: found.files.len(),
            match_count: found.match_count,
            truncated: found.truncated,
            from_file,
            files: found.files[from_file..].to_vec(),
        }
    }

    /// Stop a search and forget it. False if the engine no longer knows the id.
    pub fn cancel_project_search(&self, id: SearchId) -> bool {
        let mut searches = self.searches.searches.lock().unwrap();
        let Some(project) = searches
            .iter()
            .find(|(_, search)| search.id == id)
            .map(|(project, _)| *project)
        else {
            return false;
        };
        if let Some(search) = searches.remove(&project) {
            search.cancelled.store(true, Ordering::Relaxed);
        }
        true
    }
}

fn path_matcher(globs: &[String]) -> Result<Option<PathMatcher>, String> {
    if globs.is_empty() {
        return Ok(None);
    }
    PathMatcher::new(globs, PathStyle::Unix)
        .map(Some)
        .map_err(|err| err.to_string())
}

/// Walk the snapshot, search each file, publish as we go.
fn run(
    search: &Search,
    snapshot: &Snapshot,
    root: &Path,
    query: &SearchQuery,
    include: &Option<PathMatcher>,
    exclude: &Option<PathMatcher>,
    include_ignored: bool,
) {
    {
        // Publish the denominator before reading a single file, so the UI has
        // a progress bar from the first poll rather than after the first
        // batch.
        let mut found = search.found.lock().unwrap();
        found.total_files = if include_ignored {
            snapshot.file_count()
        } else {
            snapshot.visible_file_count()
        };
        found.version += 1;
    }

    let mut batch: Vec<FileMatches> = Vec::new();
    let mut searched = 0usize;
    let mut matches = 0usize;
    let mut files_with_matches = 0usize;
    let mut truncated = false;
    let mut last_publish = Instant::now();

    let publish = |batch: &mut Vec<FileMatches>, searched: usize, matches: usize, truncated| {
        let mut found = search.found.lock().unwrap();
        found.files.append(batch);
        found.files_searched = searched;
        found.match_count = matches;
        found.truncated = truncated;
        found.version += 1;
    };

    for entry in snapshot.files(include_ignored, 0) {
        if search.is_cancelled() {
            return;
        }
        searched += 1;

        let path = entry.path.as_unix_str();
        if include
            .as_ref()
            .is_some_and(|matcher| !matcher.is_match(entry.path.as_ref()))
            || exclude
                .as_ref()
                .is_some_and(|matcher| matcher.is_match(entry.path.as_ref()))
            || entry.size > MAX_FILE_BYTES
        {
            continue;
        }

        if let Some(found) = search_file(&root.join(entry.path.as_std_path()), query, path) {
            matches += found.matches.len();
            files_with_matches += 1;
            batch.push(found);

            if files_with_matches >= MAX_RESULT_FILES || matches >= MAX_RESULT_MATCHES {
                truncated = true;
                publish(&mut batch, searched, matches, truncated);
                break;
            }
        }

        if last_publish.elapsed() >= PUBLISH_INTERVAL {
            truncated |= batch
                .iter()
                .any(|file| file.matches.len() < file.match_count);
            publish(&mut batch, searched, matches, truncated);
            last_publish = Instant::now();
        }
    }

    truncated |= batch
        .iter()
        .any(|file| file.matches.len() < file.match_count);
    publish(&mut batch, searched, matches, truncated);
    let mut found = search.found.lock().unwrap();
    found.finished = true;
    found.version += 1;
}

/// Read one file and collect its hits, or `None` when there is nothing to
/// report — no matches, or nothing searchable in the first place.
///
/// A file that cannot be read is skipped in silence. During a search of a
/// whole tree, a permission error or a file deleted a moment ago is ordinary,
/// and there is no useful place to put a thousand of them.
fn search_file(path: &Path, query: &SearchQuery, relative: &str) -> Option<FileMatches> {
    let bytes = std::fs::read(path).ok()?;
    // A NUL byte is the same binary test `grep` uses, and it saves the far
    // more expensive UTF-8 validation below on the files it catches.
    if bytes[..bytes.len().min(BINARY_SNIFF_BYTES)].contains(&0) {
        return None;
    }
    let mut text = String::from_utf8(bytes).ok()?;
    // The query was normalised when it was compiled, so a file with CRLF
    // endings has to be too or a multi-line query could never match it.
    text::LineEnding::normalize(&mut text);

    let (ranges, match_count) = query.matches_in(&text, MAX_MATCHES_PER_FILE);
    if ranges.is_empty() {
        return None;
    }

    let bytes = text.as_bytes();
    let mut line = 1u32;
    let mut line_start = 0usize;
    let mut cursor = 0usize;
    let matches = ranges
        .iter()
        .map(|range| {
            while cursor < range.start {
                if bytes[cursor] == b'\n' {
                    line += 1;
                    line_start = cursor + 1;
                }
                cursor += 1;
            }
            let line_end = text[line_start..]
                .find('\n')
                .map(|offset| line_start + offset)
                .unwrap_or(text.len());
            // A match may run past its line, either because the query spans
            // lines or because a regex crossed one. It is reported where it
            // starts, clipped to that line — a results panel draws one line.
            line_match(
                &text[line_start..line_end],
                line,
                range.start - line_start,
                range.end.min(line_end) - line_start,
            )
        })
        .collect();

    Some(FileMatches {
        path: relative.to_owned(),
        matches,
        match_count,
    })
}

/// Package one hit for display, windowing the line if it is long enough that
/// shipping all of it would be waste.
fn line_match(line: &str, number: u32, start: usize, end: usize) -> LineMatch {
    let (from, to) = if line.len() <= MAX_LINE_BYTES {
        (0, line.len())
    } else {
        let from = floor_char_boundary(line, start.saturating_sub(CONTEXT_BEFORE_MATCH));
        let to = floor_char_boundary(line, (from + MAX_LINE_BYTES).min(line.len()));
        (from, to)
    };
    let text = &line[from..to];
    let window_start = start - from;
    let window_end = end.min(to) - from;
    LineMatch {
        line: number,
        // `column` stays relative to the whole line, because that is what
        // `point_to_offset` needs to place a cursor; `start`/`end` are
        // relative to `text`, because that is what the panel draws.
        column: start,
        start: window_start,
        end: window_end,
        start_utf16: text[..window_start].chars().map(char::len_utf16).sum(),
        end_utf16: text[..window_end].chars().map(char::len_utf16).sum(),
        text: text.to_owned(),
        clipped_start: from > 0,
        clipped_end: to < line.len(),
    }
}

fn floor_char_boundary(text: &str, mut index: usize) -> usize {
    index = index.min(text.len());
    while !text.is_char_boundary(index) {
        index -= 1;
    }
    index
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;
    use std::time::{Duration, Instant};

    fn options(query: &str) -> SearchOptions {
        SearchOptions {
            query: query.to_owned(),
            ..Default::default()
        }
    }

    /// Block until a project has finished scanning, as `project.rs`'s own
    /// tests do — the worktree is genuinely concurrent.
    fn wait_for_scan(engine: &Engine, id: ProjectId) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !engine.project_scan_complete(id) {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(
            engine.project_scan_complete(id),
            "project {id} never scanned"
        );
    }

    /// A project holding everything the search has to be careful about: an
    /// ignored directory, a binary file, and multi-byte text.
    fn project() -> (Engine, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("src")).unwrap();
        std::fs::create_dir_all(root.join(".git")).unwrap();
        std::fs::create_dir_all(root.join("target")).unwrap();
        std::fs::write(root.join(".gitignore"), "target\n").unwrap();
        std::fs::write(
            root.join("src/main.rs"),
            "fn main() {\n    let needle = 1;\n    println!(\"needle\");\n}\n",
        )
        .unwrap();
        std::fs::write(root.join("src/lib.rs"), "// no NEEDLE here, uppercase\n").unwrap();
        std::fs::write(root.join("src/unicode.rs"), "let héllo = \"needle\";\n").unwrap();
        std::fs::write(root.join("README.md"), "A needle in a haystack.\n").unwrap();
        std::fs::write(root.join("target/build.log"), "needle in the ignored dir\n").unwrap();
        std::fs::write(root.join("blob.bin"), b"needle\0\0\0needle").unwrap();

        let engine = Engine::new();
        let id = engine.open_project(root);
        wait_for_scan(&engine, id);
        (engine, dir)
    }

    /// Run a search to completion and hand back everything it found.
    #[track_caller]
    fn search(engine: &Engine, options: &SearchOptions) -> SearchResults {
        let id = engine.start_project_search(1, options).unwrap();
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            let results = engine.project_search_results(id, 0);
            if results.state != SearchState::Running {
                return results;
            }
            assert!(Instant::now() < deadline, "the search never finished");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn paths(results: &SearchResults) -> Vec<&str> {
        results
            .files
            .iter()
            .map(|file| file.path.as_str())
            .collect()
    }

    #[test]
    fn finds_matches_across_the_worktree() {
        let (engine, _dir) = project();
        let results = search(&engine, &options("needle"));

        assert_eq!(results.state, SearchState::Done);
        assert_eq!(results.error, None);
        assert!(!results.truncated);
        // Case-insensitive by default, so lib.rs's NEEDLE counts. The ignored
        // directory and the binary file do not.
        assert_eq!(
            paths(&results),
            vec!["README.md", "src/lib.rs", "src/main.rs", "src/unicode.rs"]
        );
        assert_eq!(results.file_count, 4);
        assert_eq!(results.match_count, 5);
        assert_eq!(results.files_searched, results.total_files);

        let main = &results.files[2];
        assert_eq!(main.match_count, 2);
        let first = &main.matches[0];
        assert_eq!(first.line, 2);
        assert_eq!(first.text, "    let needle = 1;");
        assert_eq!(&first.text[first.start..first.end], "needle");
        assert_eq!(first.column, first.start);
        assert!(!first.clipped_start && !first.clipped_end);
    }

    #[test]
    fn honours_case_whole_word_and_regex() {
        let (engine, _dir) = project();

        let sensitive = search(
            &engine,
            &SearchOptions {
                case_sensitive: true,
                ..options("NEEDLE")
            },
        );
        assert_eq!(paths(&sensitive), vec!["src/lib.rs"]);

        // "haystack" is a word; "haystac" is only part of one.
        assert_eq!(
            paths(&search(&engine, &options("haystac"))),
            vec!["README.md"]
        );
        assert!(
            search(
                &engine,
                &SearchOptions {
                    whole_word: true,
                    ..options("haystac")
                }
            )
            .files
            .is_empty()
        );

        let regex = search(
            &engine,
            &SearchOptions {
                regex: true,
                ..options(r#"println!\("\w+"\)"#)
            },
        );
        assert_eq!(paths(&regex), vec!["src/main.rs"]);
    }

    #[test]
    fn line_ranges_are_utf8_safe_and_carry_utf16_offsets() {
        let (engine, _dir) = project();
        let results = search(&engine, &options("needle"));
        let unicode = results
            .files
            .iter()
            .find(|file| file.path == "src/unicode.rs")
            .expect("the unicode file matched");
        let found = &unicode.matches[0];

        // "héllo" is 6 bytes but 5 UTF-16 units, so the two offsets part
        // company — and both have to address the same text.
        assert_eq!(found.text, "let héllo = \"needle\";");
        assert_eq!(&found.text[found.start..found.end], "needle");
        assert_eq!(found.start, 14);
        assert_eq!(found.start_utf16, 13);
        assert_eq!(found.end_utf16, 19);
        let utf16: Vec<u16> = found.text.encode_utf16().collect();
        assert_eq!(
            String::from_utf16(&utf16[found.start_utf16..found.end_utf16]).unwrap(),
            "needle"
        );
    }

    #[test]
    fn include_and_exclude_globs() {
        let (engine, _dir) = project();

        let only_rust = search(
            &engine,
            &SearchOptions {
                include_globs: vec!["*.rs".to_owned()],
                ..options("needle")
            },
        );
        assert_eq!(
            paths(&only_rust),
            vec!["src/lib.rs", "src/main.rs", "src/unicode.rs"]
        );

        let not_src = search(
            &engine,
            &SearchOptions {
                exclude_globs: vec!["src/**".to_owned()],
                ..options("needle")
            },
        );
        assert_eq!(paths(&not_src), vec!["README.md"]);

        // Exclusion wins over inclusion.
        let both = search(
            &engine,
            &SearchOptions {
                include_globs: vec!["*.rs".to_owned()],
                exclude_globs: vec!["**/lib.rs".to_owned(), "**/unicode.rs".to_owned()],
                ..options("needle")
            },
        );
        assert_eq!(paths(&both), vec!["src/main.rs"]);

        assert!(matches!(
            engine.start_project_search(
                1,
                &SearchOptions {
                    include_globs: vec!["[".to_owned()],
                    ..options("needle")
                }
            ),
            Err(EngineError::InvalidQuery(_))
        ));
    }

    #[test]
    fn gitignored_files_are_searched_only_when_asked_for() {
        let (engine, _dir) = project();
        assert!(
            !paths(&search(&engine, &options("ignored dir"))).contains(&"target/build.log"),
            "the ignored directory must stay out by default"
        );

        // The worktree only scans an ignored directory once it is expanded, so
        // reaching into `target/` takes the step the project panel takes.
        assert!(engine.expand_directory(1, "target"));
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && engine.project_entries(1, "target").is_empty() {
            std::thread::sleep(Duration::from_millis(10));
        }
        let results = search(
            &engine,
            &SearchOptions {
                include_ignored: true,
                ..options("ignored dir")
            },
        );
        assert_eq!(paths(&results), vec!["target/build.log"]);
    }

    #[test]
    fn long_lines_are_windowed_around_the_match() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        let padding = "x".repeat(2000);
        std::fs::write(
            dir.path().join("bundle.js"),
            format!("{padding}needle{padding}\n"),
        )
        .unwrap();
        let engine = Engine::new();
        wait_for_scan(&engine, engine.open_project(dir.path()));

        let results = search(&engine, &options("needle"));
        let found = &results.files[0].matches[0];
        assert!(found.text.len() <= MAX_LINE_BYTES);
        assert_eq!(&found.text[found.start..found.end], "needle");
        assert!(found.clipped_start && found.clipped_end);
        // The column still addresses the real line, so the editor can jump.
        assert_eq!(found.column, 2000);
        assert_eq!(found.start, CONTEXT_BEFORE_MATCH);
    }

    #[test]
    fn per_file_matches_are_capped_and_the_count_stays_honest() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        std::fs::write(
            dir.path().join("many.txt"),
            "needle\n".repeat(MAX_MATCHES_PER_FILE + 50),
        )
        .unwrap();
        let engine = Engine::new();
        wait_for_scan(&engine, engine.open_project(dir.path()));

        let results = search(&engine, &options("needle"));
        let file = &results.files[0];
        assert_eq!(file.matches.len(), MAX_MATCHES_PER_FILE);
        assert_eq!(file.match_count, MAX_MATCHES_PER_FILE + 50);
        assert!(
            results.truncated,
            "truncation has to be reported, not hidden"
        );
        // Lines are numbered from the file, not from the matches kept.
        assert_eq!(file.matches[0].line, 1);
        assert_eq!(
            file.matches[MAX_MATCHES_PER_FILE - 1].line,
            MAX_MATCHES_PER_FILE as u32
        );
    }

    #[test]
    fn results_can_be_read_incrementally() {
        let (engine, _dir) = project();
        let all = search(&engine, &options("needle"));

        let id = engine.start_project_search(1, &options("needle")).unwrap();
        let deadline = Instant::now() + Duration::from_secs(20);
        while engine.project_search_results(id, 0).state == SearchState::Running {
            assert!(Instant::now() < deadline, "the search never finished");
            std::thread::sleep(Duration::from_millis(5));
        }
        let tail = engine.project_search_results(id, 2);
        assert_eq!(tail.from_file, 2);
        assert_eq!(tail.file_count, all.file_count);
        assert_eq!(tail.files, all.files[2..]);
        // Asking past the end is empty rather than an error.
        assert!(engine.project_search_results(id, 99).files.is_empty());
        assert!(engine.project_search_version(id) > 0);
    }

    #[test]
    fn a_new_search_supersedes_the_one_before_it() {
        let (engine, _dir) = project();
        let first = engine.start_project_search(1, &options("needle")).unwrap();
        let second = engine
            .start_project_search(1, &options("haystack"))
            .unwrap();
        assert_ne!(first, second);
        assert_eq!(
            engine.project_search_results(first, 0).state,
            SearchState::Cancelled
        );
        assert_eq!(engine.project_search_version(first), 0);

        assert!(engine.cancel_project_search(second));
        assert!(!engine.cancel_project_search(second));
        assert_eq!(
            engine.project_search_results(second, 0).state,
            SearchState::Cancelled
        );
    }

    #[test]
    fn empty_queries_and_unknown_projects() {
        let (engine, _dir) = project();
        let empty = search(&engine, &options(""));
        assert_eq!(empty.state, SearchState::Done);
        assert!(empty.files.is_empty());

        assert_eq!(
            engine.start_project_search(999, &options("needle")),
            Err(EngineError::UnknownProject(999))
        );
        assert!(matches!(
            engine.start_project_search(
                1,
                &SearchOptions {
                    regex: true,
                    ..options("(")
                }
            ),
            Err(EngineError::InvalidQuery(_))
        ));
    }

    #[test]
    fn closing_a_project_stops_its_search() {
        let (engine, _dir) = project();
        let id = engine.start_project_search(1, &options("needle")).unwrap();
        assert!(engine.close_project(1));
        assert_eq!(
            engine.project_search_results(id, 0).state,
            SearchState::Cancelled
        );
    }
}
