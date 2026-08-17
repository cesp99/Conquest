//! Git status, computed by the `git` inside the Debian userland.
//!
//! There is no git library here on purpose. Zed's vendored `git` crate shells
//! out to a `git` binary, and ours lives inside the proot guest — Android will
//! not execute anything that arrived after install, so the only reachable git
//! is the one `apt` put in the rootfs, and the only way to reach it is through
//! proot (agent-docs/research/proot-spike.md, "Open items", item 4).
//!
//! Getting *into* the guest is not this module's job — `guest.rs` owns the
//! proot command line, and git's own share of it is the argv below and two
//! environment variables. What is worth knowing here is that guest.rs binds
//! every host path onto the identical guest path, so the `-C` going in and the
//! paths coming back are the same strings on both sides of the boundary, with
//! nothing to translate.
//!
//! Two more things shape this module.
//!
//! **Nothing waits on git.** A status run is a process spawn inside an
//! emulated filesystem: tens of milliseconds at best, seconds on a cold cache.
//! So [`Engine::git_status`] only ever reads a cache, and
//! [`Engine::git_status_version`] is the generation counter the UI polls —
//! deliberately the same shape as `project_version` in `project.rs`, so the
//! project panel watches two counters with one mechanism.
//!
//! **Silence when there is no userland.** The `play` flavour has no guest at
//! all, and the `full` flavour has none until the user installs one. Both must
//! look like "this repository has no changes", not like an error: every failure
//! path here logs at debug and yields an empty map.

use std::collections::{BTreeMap, HashMap};
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use crate::guest::{self, GuestCommand, Userland};
use crate::project::ProjectId;

/// How long to wait after a worktree change before asking git. A save, a
/// branch switch or a `cargo build` all produce bursts of file events; running
/// once at the end of the burst is the whole point.
const DEBOUNCE: Duration = Duration::from_millis(400);

/// A status run that takes longer than this is assumed wedged and killed.
/// Generous on purpose: the first proot spawn after boot pays for page cache
/// misses across the whole rootfs.
const RUN_TIMEOUT: Duration = Duration::from_secs(20);

/// How many times one refresh may immediately re-run because the worktree
/// moved again while it was running. See [`run_until_settled`].
const MAX_CHAINED_RUNS: u32 = 4;

/// What happened to one path, as the project panel needs to colour it.
///
/// Deliberately smaller than git's own vocabulary: the panel paints a row, it
/// does not explain a diff. Typechanges and copies fold into `Modified` and
/// `Renamed` respectively.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum GitStatus {
    Modified,
    Added,
    Deleted,
    Renamed,
    Conflicted,
    Untracked,
    /// Only ever produced if `--ignored` is passed, which it is not — see
    /// [`status_args`]. Kept so the mapping is complete rather than lossy.
    Ignored,
}

/// Cached status for one project.
#[derive(Default)]
struct ProjectGit {
    statuses: Arc<BTreeMap<String, GitStatus>>,
    /// Bumped only when [`ProjectGit::statuses`] actually changed, so a poll
    /// loop that sees a steady number can skip the JNI read entirely.
    version: u64,
    /// A run has completed at least once (successfully or not).
    scanned: bool,
    /// The worktree version the last completed run observed.
    scanned_worktree_version: u64,
    /// A run is in flight. Never more than one per project: git is the
    /// expensive part, and two concurrent runs would race to install results.
    running: bool,
}

#[derive(Default)]
pub(crate) struct GitStatuses {
    projects: Mutex<HashMap<ProjectId, Arc<Mutex<ProjectGit>>>>,
}

impl crate::Engine {
    /// Generation counter for a project's git status. Bumped when the statuses
    /// change; 0 until the first run produces something. Poll it the way the
    /// panel already polls `project_version`.
    ///
    /// Polling it is also what *drives* refreshing: this call notices that the
    /// worktree moved and schedules a run. It never waits for one.
    pub fn git_status_version(&self, id: ProjectId) -> u64 {
        self.refresh_git_status(id);
        self.with_git(id, |git| git.version).unwrap_or(0)
    }

    /// Every path with a status, plus every ancestor directory of one — see
    /// [`roll_up`]. Paths are project-relative and `/`-separated, matching
    /// `TreeEntry::path`, so the panel can look up a row directly.
    ///
    /// Reads the cache and returns immediately. Empty when there is no
    /// userland, no repository, or no git inside the guest.
    pub fn git_status(&self, id: ProjectId) -> BTreeMap<String, GitStatus> {
        self.refresh_git_status(id);
        self.with_git(id, |git| (*git.statuses).clone())
            .unwrap_or_default()
    }

    /// Start a run if one is warranted and none is in flight.
    fn refresh_git_status(&self, id: ProjectId) {
        // A project the engine no longer knows takes its cache with it, so a
        // long session of opening and closing projects doesn't accumulate.
        let Some(project) = self.projects.lock().unwrap().get(&id).cloned() else {
            self.git.projects.lock().unwrap().remove(&id);
            return;
        };
        let Some(userland) = self.userland() else {
            return;
        };

        let (root, worktree_version) = {
            let state = project.lock().unwrap();
            (state.root.clone(), state.version)
        };
        // Nothing mirrored yet: the panel has nothing to colour, and the scan
        // landing will bump the version and bring us back here.
        if worktree_version == 0 {
            return;
        }

        let cache = self
            .git
            .projects
            .lock()
            .unwrap()
            .entry(id)
            .or_default()
            .clone();
        {
            let mut git = cache.lock().unwrap();
            if git.running || (git.scanned && git.scanned_worktree_version == worktree_version) {
                return;
            }
            git.running = true;
        }

        let worker_cache = cache.clone();
        let spawned = thread::Builder::new()
            .name("conquest-git-status".to_owned())
            .spawn(move || run_until_settled(id, &userland, &root, &project, &worker_cache));
        if let Err(err) = spawned {
            // Leaving `running` set would wedge this project forever.
            log::debug!("project {id}: could not spawn a git status thread: {err}");
            cache.lock().unwrap().running = false;
        }
    }

    fn with_git<T>(&self, id: ProjectId, f: impl FnOnce(&ProjectGit) -> T) -> Option<T> {
        let cache = self.git.projects.lock().unwrap().get(&id).cloned()?;
        let git = cache.lock().unwrap();
        Some(f(&git))
    }
}

/// Debounce, run, install — and go round again if the worktree moved while we
/// were running, so the cache cannot end up describing a state that is already
/// stale. `running` stays set for the whole loop, which is what enforces "never
/// more than one run in flight per project".
///
/// The chain is capped rather than unbounded: something that writes files
/// continuously — a build inside the terminal — would otherwise keep this
/// thread spawning git forever, including after the user has navigated away
/// and nobody is polling. Giving up re-arms it on the next poll, so the only
/// cost of the cap is a slightly later refresh in exactly the case where the
/// answer was going to be stale anyway.
fn run_until_settled(
    id: ProjectId,
    userland: &Userland,
    root: &Path,
    project: &Arc<Mutex<crate::project::ProjectState>>,
    cache: &Arc<Mutex<ProjectGit>>,
) {
    for _ in 0..MAX_CHAINED_RUNS {
        thread::sleep(DEBOUNCE);

        // Read the version *after* sleeping: everything that happened during
        // the debounce is covered by the run we are about to do.
        let observed = project.lock().unwrap().version;
        let statuses = status_for(id, userland, root).unwrap_or_default();

        {
            let mut git = cache.lock().unwrap();
            if *git.statuses != statuses {
                git.statuses = Arc::new(statuses);
                git.version += 1;
            }
            git.scanned = true;
            git.scanned_worktree_version = observed;
        }

        let now = project.lock().unwrap().version;
        if now == observed {
            cache.lock().unwrap().running = false;
            return;
        }
    }
    log::debug!("project {id}: git status still chasing a moving worktree; pausing");
    cache.lock().unwrap().running = false;
}

/// One status run, or `None` when there is nothing to run (which is not an
/// error and is never shown to the user).
fn status_for(
    id: ProjectId,
    userland: &Userland,
    root: &Path,
) -> Option<BTreeMap<String, GitStatus>> {
    // Cheapest gate first, and it needs no guest at all: a handful of `stat`
    // calls up the host filesystem. A project that isn't in a repository never
    // pays for a proot spawn.
    let repo_root = repo_root_of(root)?;
    let prefix = relative_prefix(&repo_root, root)?;

    if !userland.is_installed() {
        return None;
    }

    let started = Instant::now();
    let output = capture(userland, &repo_root, root)?;
    let statuses = parse_porcelain(&output, &prefix);
    log::debug!(
        "project {id}: git status took {:?}, {} paths",
        started.elapsed(),
        statuses.len()
    );
    Some(statuses)
}

/// `git status` arguments, minus the `-C`.
///
/// `--porcelain=v1 -z` is the only machine format that is both stable and
/// unambiguous: `-z` means NUL-separated records with **raw** path bytes, so
/// `core.quotePath` never applies and there is no C-style unquoting to get
/// wrong (see the tests).
///
/// Keeping this query from writing `index.lock` is `--no-optional-locks`,
/// which is *git's* option and not `git status`'s: passed after the
/// subcommand, real git exits 129 with "unknown option", every run produces
/// nothing, and the panel is silently colourless. It lives in [`git_argv`],
/// before the subcommand, next to the other git-level flags.
///
/// `--ignored` is *not* passed. It would list every file under `target/` and
/// `node_modules/`, which is the opposite of cheap, and the worktree already
/// knows what is ignored (`TreeEntry::is_ignored`) from the same `.gitignore`
/// files.
fn status_args() -> [&'static str; 4] {
    ["status", "--porcelain=v1", "-z", "--untracked-files=normal"]
}

/// Everything from `git` onwards, in order: the git-level options first, then
/// the subcommand and its own.
///
/// Assembled in one place so the host tests can run the real git binary over
/// the very argv the device uses. That is the only thing that catches a
/// git-level option written after the subcommand: git exits 129 with "unknown
/// option" while every parser test in this file still passes.
fn git_argv(project: &Path) -> Vec<OsString> {
    let mut argv: Vec<OsString> = vec![OsString::from("git")];
    argv.push(OsString::from("-C"));
    argv.push(project.as_os_str().to_owned());
    // Belt and braces with proot's fake_id0, and harmless if the "dubious
    // ownership" check would have passed anyway. Supported since git 2.35.3;
    // Debian stable is well past that.
    argv.extend(["-c", "safe.directory=*"].map(OsString::from));
    // Read-only query: don't refresh the index, don't write index.lock. The
    // panel polls this, so a lock here would fight the user's own git.
    argv.push(OsString::from("--no-optional-locks"));
    argv.extend(status_args().map(OsString::from));
    argv
}

/// Run git inside the guest and return its stdout.
///
/// The proot half of this — the flags, the binds, the guest environment, the
/// deadline and the pipe draining — belongs to [`guest::capture`]. What is
/// git's own is the argv, a bind for a repository that lives outside the
/// projects directory, and these two variables.
fn capture(userland: &Userland, repo_root: &Path, project: &Path) -> Option<Vec<u8>> {
    let command = GuestCommand::new("git status", git_argv(project))
        .bind(repo_root)
        // `--no-optional-locks` in its environment form, which git(1) gives as
        // equivalent; unlike the flag it is inherited, so a git that runs
        // another git still writes no lock.
        .env("GIT_OPTIONAL_LOCKS", "0")
        // There is nobody on this end to answer a credential prompt, and a git
        // waiting for one would sit there until the deadline killed it.
        .env("GIT_TERMINAL_PROMPT", "0");
    guest::capture(userland, &command, RUN_TIMEOUT)
}

/// The enclosing repository's root, or `None` if there isn't one.
///
/// `.git` is a directory in a normal clone and a *file* in a worktree or
/// submodule, so this only asks whether the name exists. A few `stat` calls,
/// no subprocess: this is what makes "not a repository" free.
fn repo_root_of(project: &Path) -> Option<PathBuf> {
    let mut dir = Some(project);
    while let Some(candidate) = dir {
        if candidate.join(".git").exists() {
            return Some(candidate.to_path_buf());
        }
        dir = candidate.parent();
    }
    None
}

/// Where the project sits inside its repository, as a `/`-terminated prefix
/// (empty when the project *is* the repository root).
///
/// Porcelain paths are always relative to the repository root, not to `-C`, so
/// a project that is a subdirectory of a bigger repository needs this to turn
/// them back into project-relative paths.
fn relative_prefix(repo_root: &Path, project: &Path) -> Option<String> {
    let relative = project.strip_prefix(repo_root).ok()?;
    let relative = relative.to_string_lossy();
    if relative.is_empty() {
        Some(String::new())
    } else {
        Some(format!("{}/", relative.trim_end_matches('/')))
    }
}

/// Parse `git status --porcelain=v1 -z` output into project-relative paths,
/// with directories rolled up (see [`roll_up`]).
pub(crate) fn parse_porcelain(output: &[u8], strip_prefix: &str) -> BTreeMap<String, GitStatus> {
    let mut files = parse_records(output);
    if !strip_prefix.is_empty() {
        files.retain(|(path, _)| path.starts_with(strip_prefix));
        for (path, _) in &mut files {
            *path = path[strip_prefix.len()..].to_owned();
        }
    }
    roll_up(&files)
}

/// The records themselves: one `(path, status)` per changed file, in git's
/// order.
///
/// Each record is `XY<space><path>`, NUL-terminated. A rename or copy emits
/// **two** paths — the new one in its own record, the original in the record
/// immediately after — so the loop is index-based rather than a plain
/// iterator: it has to consume that second record itself, or the old path
/// would be read back as a garbled status line.
pub(crate) fn parse_records(output: &[u8]) -> Vec<(String, GitStatus)> {
    let records: Vec<&[u8]> = output
        .split(|byte| *byte == 0)
        .filter(|record| !record.is_empty())
        .collect();

    let mut out = Vec::new();
    let mut index = 0;
    while index < records.len() {
        let record = records[index];
        index += 1;
        // "XY path": two code letters, a space, and at least one path byte.
        if record.len() < 4 || record[2] != b' ' {
            continue;
        }
        let x = record[0];
        let y = record[1];
        let status = classify(x, y);

        // Paths arrive as raw bytes. They are UTF-8 in every case we can
        // create, but a repository cloned from elsewhere can hold anything, and
        // a status query is not the place to fail over it.
        let path = String::from_utf8_lossy(&record[3..]).into_owned();

        // Whether a second (source) record follows is a property of the raw
        // X byte, not of the classified status: git emits `RD` and `CD` for a
        // rename whose destination was then deleted, and those classify as
        // Deleted. Deciding from the status desynchronises the whole parse and
        // invents a path — covered by the RD/CD tests below.
        if x == b'R' || x == b'C' {
            // Skip the source path. It is *not* reported as deleted: the file
            // moved, and painting its old location would be showing the user
            // something that no longer exists.
            index += 1;
        }
        out.push((path, status));
    }
    out
}

/// One porcelain code pair to one status.
///
/// `X` is the index against HEAD, `Y` the worktree against the index. The
/// order below is the precedence we show, and it is a UI decision as much as a
/// git one:
///
/// 1. Unmerged pairs are conflicts, and nothing outranks a conflict.
/// 2. `?` untracked, `!` ignored — these never combine with anything.
/// 3. A `D` on either side means the file is not there any more, which the
///    user needs to know before they need to know how it got that way (so
///    `AD`, staged-then-deleted, reads as deleted).
/// 4. `R`/`C` — a rename, or a copy shown as one.
/// 5. `A` — new to the index.
/// 6. anything left (`M`, `T`, and combinations) — modified.
fn classify(x: u8, y: u8) -> GitStatus {
    // The seven unmerged combinations from git-status(1).
    let unmerged = matches!(
        (x, y),
        (b'D', b'D')
            | (b'A', b'U')
            | (b'U', b'D')
            | (b'U', b'A')
            | (b'D', b'U')
            | (b'A', b'A')
            | (b'U', b'U')
    );
    if unmerged {
        return GitStatus::Conflicted;
    }
    if x == b'?' || y == b'?' {
        return GitStatus::Untracked;
    }
    if x == b'!' || y == b'!' {
        return GitStatus::Ignored;
    }
    if x == b'D' || y == b'D' {
        return GitStatus::Deleted;
    }
    if x == b'R' || x == b'C' {
        return GitStatus::Renamed;
    }
    if x == b'A' {
        return GitStatus::Added;
    }
    GitStatus::Modified
}

/// Give every ancestor directory of a changed file a status of its own.
///
/// **We do roll up, and we do it here, once per run.** The alternative — the
/// panel asking "does anything under this directory have a status?" per drawn
/// row — is a prefix scan per row on every frame. Rolling up costs one pass
/// over the changed paths (which is a *small* list; that is the whole nature of
/// a status) times their depth, and leaves the panel with a single map lookup
/// per row, directories included.
///
/// A directory does not get its descendant's exact status, because there isn't
/// one — a directory can easily hold a deletion and an addition at once.
/// It gets a summary, in three tiers:
///
/// * anything conflicted below → `Conflicted`
/// * anything modified, deleted or renamed below → `Modified`
/// * only new files below → `Added` if any is staged, else `Untracked`
///
/// `Ignored` never rolls up: a directory holding an ignored file is not itself
/// ignored, and the worktree already reports real ignore status per entry.
pub(crate) fn roll_up(files: &[(String, GitStatus)]) -> BTreeMap<String, GitStatus> {
    let mut out = BTreeMap::new();
    for (path, status) in files {
        out.insert(path.clone(), *status);
    }
    for (path, status) in files {
        let Some(tier) = tier(*status) else { continue };
        // Walk the ancestors by slicing at each '/', so no allocation happens
        // for a directory that is already recorded at the same or a higher
        // tier.
        for (index, byte) in path.bytes().enumerate() {
            if byte != b'/' {
                continue;
            }
            let dir = &path[..index];
            match out.get(dir) {
                Some(existing) if tier_of_summary(*existing) >= tier => {}
                _ => {
                    out.insert(dir.to_owned(), summary(tier, *status));
                }
            }
        }
    }
    out
}

/// How loudly a file status speaks for its ancestors. `None` for statuses that
/// don't propagate at all.
fn tier(status: GitStatus) -> Option<u8> {
    match status {
        GitStatus::Conflicted => Some(3),
        GitStatus::Modified | GitStatus::Deleted | GitStatus::Renamed => Some(2),
        GitStatus::Added => Some(1),
        GitStatus::Untracked => Some(0),
        GitStatus::Ignored => None,
    }
}

/// The tier a directory summary already represents, for the "is this louder
/// than what's there?" comparison.
fn tier_of_summary(status: GitStatus) -> u8 {
    tier(status).unwrap_or(0)
}

/// The single status that stands for a tier. `Added` and `Untracked` share a
/// tier — both mean "new here" — and keep whichever one produced it.
fn summary(tier: u8, from: GitStatus) -> GitStatus {
    match tier {
        3 => GitStatus::Conflicted,
        2 => GitStatus::Modified,
        1 => GitStatus::Added,
        _ => from,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;

    /// Build porcelain output the way git does: NUL after every record.
    fn porcelain(records: &[&str]) -> Vec<u8> {
        let mut out = Vec::new();
        for record in records {
            out.extend_from_slice(record.as_bytes());
            out.push(0);
        }
        out
    }

    fn statuses(records: &[&str]) -> Vec<(String, GitStatus)> {
        parse_records(&porcelain(records))
    }

    #[test]
    fn empty_output_is_a_clean_repository() {
        assert!(parse_records(b"").is_empty());
        assert!(parse_porcelain(b"", "").is_empty());
        // git emits nothing at all when clean, but a stray trailing NUL must
        // not become a phantom entry either.
        assert!(parse_records(b"\0\0").is_empty());
    }

    #[test]
    fn maps_the_ordinary_codes() {
        assert_eq!(
            statuses(&[
                " M src/main.rs",
                "M  staged.rs",
                "MM both.rs",
                "A  new.rs",
                " D gone.rs",
                "D  staged-delete.rs",
                "?? untracked.rs",
                "T  typechange.rs",
            ]),
            vec![
                ("src/main.rs".to_owned(), GitStatus::Modified),
                ("staged.rs".to_owned(), GitStatus::Modified),
                ("both.rs".to_owned(), GitStatus::Modified),
                ("new.rs".to_owned(), GitStatus::Added),
                ("gone.rs".to_owned(), GitStatus::Deleted),
                ("staged-delete.rs".to_owned(), GitStatus::Deleted),
                ("untracked.rs".to_owned(), GitStatus::Untracked),
                ("typechange.rs".to_owned(), GitStatus::Modified),
            ]
        );
    }

    #[test]
    fn a_rename_consumes_its_source_record() {
        // The trap: `R  new` is followed by a bare record holding the old
        // path. Read naively it looks like a status line whose code letters
        // are the first two characters of a filename.
        let parsed = statuses(&[
            "R  src/new.rs",
            "src/old.rs",
            " M after.rs",
            "C  copy.rs",
            "src/source.rs",
            "?? last.rs",
        ]);
        assert_eq!(
            parsed,
            vec![
                ("src/new.rs".to_owned(), GitStatus::Renamed),
                ("after.rs".to_owned(), GitStatus::Modified),
                ("copy.rs".to_owned(), GitStatus::Renamed),
                ("last.rs".to_owned(), GitStatus::Untracked),
            ]
        );
        // The source path is gone from the report entirely — not reported as
        // deleted, and above all not mistaken for a status record.
        assert!(!parsed.iter().any(|(path, _)| path == "src/old.rs"));
        assert!(!parsed.iter().any(|(path, _)| path.contains("source")));
    }

    #[test]
    fn a_rename_with_a_modification_is_still_a_rename() {
        assert_eq!(
            statuses(&["RM moved.rs", "original.rs"]),
            vec![("moved.rs".to_owned(), GitStatus::Renamed)]
        );
    }

    #[test]
    fn every_unmerged_pair_is_a_conflict() {
        for code in ["DD", "AU", "UD", "UA", "DU", "AA", "UU"] {
            let record = format!("{code} conflict.rs");
            assert_eq!(
                statuses(&[&record]),
                vec![("conflict.rs".to_owned(), GitStatus::Conflicted)],
                "{code} should be a conflict"
            );
        }
    }

    #[test]
    fn paths_are_raw_bytes_because_of_minus_z() {
        // `-z` turns off git's C-style quoting, so a path with a quote, a
        // backslash, a space or a newline arrives verbatim and must survive
        // verbatim. Without `-z` this record would read `"od\303\251on"`.
        let parsed = statuses(&[
            " M répertoire/fichier éé.rs",
            "?? 日本語/ファイル.txt",
            " M weird \"quoted\"\\name.rs",
            " M with\nnewline.rs",
        ]);
        assert_eq!(
            parsed,
            vec![
                ("répertoire/fichier éé.rs".to_owned(), GitStatus::Modified),
                ("日本語/ファイル.txt".to_owned(), GitStatus::Untracked),
                ("weird \"quoted\"\\name.rs".to_owned(), GitStatus::Modified),
                ("with\nnewline.rs".to_owned(), GitStatus::Modified),
            ]
        );
    }

    #[test]
    fn invalid_bytes_do_not_lose_the_record() {
        // 0xff is not UTF-8. The path is mangled; the status still arrives.
        let mut output = b" M bad".to_vec();
        output.push(0xff);
        output.extend_from_slice(b".rs");
        output.push(0);
        let parsed = parse_records(&output);
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].1, GitStatus::Modified);
        assert!(parsed[0].0.starts_with("bad"));
    }

    #[test]
    fn truncated_records_are_dropped_not_panicked_on() {
        assert!(parse_records(b"M\0").is_empty());
        assert!(parse_records(b"M  \0").is_empty());
        assert!(parse_records(b"?\0").is_empty());
        // A record whose third byte isn't a space isn't a status line.
        assert!(parse_records(b"MMMnot-a-record\0").is_empty());
    }

    #[test]
    fn directories_roll_up_to_a_summary() {
        let rolled = parse_porcelain(
            &porcelain(&[
                " M src/deep/nested/edited.rs",
                "?? docs/new.md",
                "A  assets/icons/added.svg",
                "UU merge/conflicted.rs",
            ]),
            "",
        );

        // Files keep their own status.
        assert_eq!(
            rolled.get("src/deep/nested/edited.rs"),
            Some(&GitStatus::Modified)
        );
        // Every ancestor is present, so the panel needs one lookup per row.
        assert_eq!(rolled.get("src"), Some(&GitStatus::Modified));
        assert_eq!(rolled.get("src/deep"), Some(&GitStatus::Modified));
        assert_eq!(rolled.get("src/deep/nested"), Some(&GitStatus::Modified));
        // New-only directories stay in the "new" tier.
        assert_eq!(rolled.get("docs"), Some(&GitStatus::Untracked));
        assert_eq!(rolled.get("assets"), Some(&GitStatus::Added));
        assert_eq!(rolled.get("assets/icons"), Some(&GitStatus::Added));
        // A conflict outranks everything.
        assert_eq!(rolled.get("merge"), Some(&GitStatus::Conflicted));
        // The project root is never an entry: "" has no separator before it.
        assert!(!rolled.contains_key(""));
    }

    #[test]
    fn the_loudest_descendant_wins() {
        let rolled = roll_up(&[
            ("a/untracked.rs".to_owned(), GitStatus::Untracked),
            ("a/added.rs".to_owned(), GitStatus::Added),
            ("a/deleted.rs".to_owned(), GitStatus::Deleted),
        ]);
        // Deleted sits in the "changed" tier, which beats both new ones.
        assert_eq!(rolled.get("a"), Some(&GitStatus::Modified));

        let rolled = roll_up(&[
            ("b/modified.rs".to_owned(), GitStatus::Modified),
            ("b/conflict.rs".to_owned(), GitStatus::Conflicted),
        ]);
        assert_eq!(rolled.get("b"), Some(&GitStatus::Conflicted));

        // Order must not matter: the conflict is seen first here.
        let rolled = roll_up(&[
            ("b/conflict.rs".to_owned(), GitStatus::Conflicted),
            ("b/modified.rs".to_owned(), GitStatus::Modified),
        ]);
        assert_eq!(rolled.get("b"), Some(&GitStatus::Conflicted));
    }

    #[test]
    fn ignored_files_never_colour_their_parents() {
        let rolled = roll_up(&[("target/debug/thing".to_owned(), GitStatus::Ignored)]);
        assert_eq!(rolled.get("target/debug/thing"), Some(&GitStatus::Ignored));
        assert_eq!(rolled.get("target"), None);
        assert_eq!(rolled.get("target/debug"), None);
    }

    #[test]
    fn a_project_below_the_repository_root_gets_relative_paths() {
        // Porcelain paths are relative to the repository root, so a project
        // that is a subdirectory of one has to be re-based — and anything
        // outside the project simply isn't its business.
        let rolled = parse_porcelain(
            &porcelain(&[
                " M apps/editor/src/main.rs",
                " M apps/other/src/main.rs",
                "?? README.md",
            ]),
            "apps/editor/",
        );
        assert_eq!(rolled.get("src/main.rs"), Some(&GitStatus::Modified));
        assert_eq!(rolled.get("src"), Some(&GitStatus::Modified));
        assert_eq!(rolled.len(), 2);
    }

    /// git's own half of the command line, spelled out. guest.rs pins the
    /// proot half the same way, and between them the whole thing is covered —
    /// which matters more now that the two halves are written in different
    /// files, because a flag lost in either is invisible until a phone shows
    /// an uncoloured panel.
    #[test]
    fn the_git_argv_is_exactly_this() {
        let argv: Vec<String> = git_argv(Path::new("/files/projects/thing"))
            .iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        assert_eq!(
            argv,
            vec![
                "git",
                "-C",
                "/files/projects/thing",
                "-c",
                "safe.directory=*",
                "--no-optional-locks",
                "status",
                "--porcelain=v1",
                "-z",
                "--untracked-files=normal",
            ]
        );
    }

    #[test]
    fn prefixes_come_from_the_repository_layout() {
        assert_eq!(
            relative_prefix(Path::new("/p/repo"), Path::new("/p/repo")),
            Some(String::new())
        );
        assert_eq!(
            relative_prefix(Path::new("/p/repo"), Path::new("/p/repo/apps/editor")),
            Some("apps/editor/".to_owned())
        );
        assert_eq!(
            relative_prefix(Path::new("/p/repo"), Path::new("/elsewhere")),
            None
        );
    }

    #[test]
    fn a_directory_without_a_git_is_not_a_repository() {
        let dir = tempfile::tempdir().unwrap();
        let project = dir.path().join("project/src");
        std::fs::create_dir_all(&project).unwrap();
        assert_eq!(repo_root_of(&project), None);

        // The repository root is found by walking up, not by assuming the
        // project is one.
        std::fs::create_dir_all(dir.path().join("project/.git")).unwrap();
        assert_eq!(repo_root_of(&project), Some(dir.path().join("project")));

        // A worktree or submodule has a `.git` *file*, which counts too.
        let sub = dir.path().join("sub");
        std::fs::create_dir_all(&sub).unwrap();
        std::fs::write(sub.join(".git"), "gitdir: /elsewhere\n").unwrap();
        assert_eq!(repo_root_of(&sub), Some(sub));
    }

    #[test]
    fn status_is_empty_without_a_userland() {
        // The play flavour, and the full flavour before the rootfs lands: the
        // query answers "nothing to show", never an error.
        let engine = crate::Engine::new();
        let dir = tempfile::tempdir().unwrap();
        let id = engine.open_project(dir.path());
        assert!(engine.git_status(id).is_empty());
        assert_eq!(engine.git_status_version(id), 0);
    }

    #[test]
    fn a_missing_proot_is_silent_too() {
        let engine = crate::Engine::new();
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        engine.set_userland(
            &dir.path().join("no-such-proot"),
            &dir.path().join("no-such-rootfs"),
            dir.path(),
            dir.path(),
        );
        let id = engine.open_project(dir.path());

        // Give the worktree time to mirror and the refresh to run and fail.
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !engine.project_scan_complete(id) {
            thread::sleep(Duration::from_millis(10));
        }
        thread::sleep(DEBOUNCE * 2);

        assert!(engine.git_status(id).is_empty());
        assert_eq!(engine.git_status_version(id), 0);

        engine.clear_userland();
        assert!(engine.git_status(id).is_empty());
    }

    /// `RD` is "renamed in the index, deleted in the worktree". Its second
    /// record is the *source* path; a parser that decides whether to consume
    /// it from the classified status (Deleted) reads that source as the next
    /// status line and invents a path from it.
    #[test]
    fn rename_then_delete_does_not_desync_the_parse() {
        let porcelain = b"RD new.rs\0old.rs\0 M other.rs\0";
        let parsed = parse_records(porcelain);
        assert_eq!(
            parsed,
            vec![
                ("new.rs".to_string(), GitStatus::Deleted),
                ("other.rs".to_string(), GitStatus::Modified),
            ],
            "the rename source must be consumed, not parsed as a record"
        );
    }

    #[test]
    fn copy_then_delete_does_not_desync_the_parse() {
        let parsed = parse_records(b"CD copy.rs\0source.rs\0?? new.txt\0");
        assert_eq!(
            parsed,
            vec![
                ("copy.rs".to_string(), GitStatus::Deleted),
                ("new.txt".to_string(), GitStatus::Untracked),
            ]
        );
    }

    /// Run the host's git hermetically: no user, system or global config, and
    /// an identity of our own, so the result cannot depend on the machine.
    fn host_git(dir: &Path, args: &[&str]) -> std::process::Output {
        Command::new("git")
            .args(args)
            .current_dir(dir)
            .env("GIT_CONFIG_GLOBAL", "/dev/null")
            .env("GIT_CONFIG_SYSTEM", "/dev/null")
            // Run the suite from a git hook or `git rebase --exec` and these
            // are set, pointing every `git` below at the *outer* repository:
            // the argv would be accepted, the paths would be someone else's,
            // and the failure would read as an argv bug.
            .env_remove("GIT_DIR")
            .env_remove("GIT_WORK_TREE")
            .env_remove("GIT_INDEX_FILE")
            .env("GIT_AUTHOR_NAME", "test")
            .env("GIT_AUTHOR_EMAIL", "test@example.invalid")
            .env("GIT_COMMITTER_NAME", "test")
            .env("GIT_COMMITTER_EMAIL", "test@example.invalid")
            .output()
            .expect("failed to run git")
    }

    /// Every other test in this file feeds `parse_records` bytes we wrote
    /// ourselves, so all of them pass no matter what argv git is handed. That
    /// is exactly how `--no-optional-locks` came to sit *after* the
    /// subcommand: it is a git-level option, real git answered "unknown
    /// option" and exit 129 for months of runs, and the panel stayed
    /// colourless with nothing failing anywhere.
    ///
    /// So: run the real binary over the real argv, and read the real output.
    #[test]
    fn real_git_accepts_the_argv_and_the_output_parses() {
        // No git on this machine (a CI image can be that bare) — say so rather
        // than fail, and rather than pretend the check ran.
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }

        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();
        assert!(
            host_git(repo, &["init", "--quiet", "-b", "main"])
                .status
                .success()
        );
        std::fs::write(repo.join("README"), "Hello World!\n").unwrap();
        assert!(host_git(repo, &["add", "README"]).status.success());
        assert!(
            host_git(repo, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );

        // The device's reproduction, exactly: one tracked file changed, one
        // new file that git has never seen.
        std::fs::write(repo.join("README"), "Hello World!\nx\n").unwrap();
        std::fs::write(repo.join("new.txt"), "").unwrap();

        let argv = git_argv(repo);
        let args: Vec<&str> = argv
            .iter()
            .skip(1) // the program name; `host_git` supplies it
            .map(|arg| arg.to_str().expect("argv is UTF-8 in this test"))
            .collect();
        let out = host_git(repo, &args);
        assert!(
            out.status.success(),
            "git rejected the argv we send on device: {:?}\n{}",
            args,
            String::from_utf8_lossy(&out.stderr)
        );

        let statuses = parse_porcelain(&out.stdout, "");
        assert_eq!(statuses.get("README"), Some(&GitStatus::Modified));
        assert_eq!(statuses.get("new.txt"), Some(&GitStatus::Untracked));
    }
}
