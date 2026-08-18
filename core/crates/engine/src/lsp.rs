//! Language servers, run inside the Debian userland.
//!
//! There is no LSP client here. Zed's `lsp` crate is vendored (`core/vendor/lsp`,
//! in the workspace since P3-2) and it owns the whole protocol: framing,
//! request ids, the response and notification tables, capability negotiation,
//! the shutdown handshake. What this module owns is everything *around* it —
//! which server to start for a language, how to reach it through proot, when a
//! document is opened, changed, saved and closed, and how the answers get to a
//! UI that must never block.
//!
//! Four things shape it.
//!
//! **The server is started by Zed's own code.** `LanguageServer::new`
//! (vendor/lsp/src/lsp.rs:429) spawns `binary.path` with `binary.arguments`,
//! and the only executable Android will run for us is proot — so *proot is the
//! binary*, and the server's own argv is the tail of proot's. That command line
//! is `guest.rs`'s, exposed as [`guest::Invocation`] rather than copied, so a
//! language server enters the guest through the exact flags, binds and
//! environment `git status` does. Nothing in the vendored crate is patched.
//!
//! **It lives on the gpui runtime.** `LanguageServer` wants an `AsyncApp`: its
//! reader, writer and timeout tasks are gpui tasks. We already run a headless
//! `App` on a thread of its own (`runtime.rs`), which is where every other
//! vendored crate's work happens, so that is where the client goes too.
//!
//! **Nothing the UI calls waits for a server.** Diagnostics are pushed by the
//! server, cached here, and published behind a generation counter the UI polls
//! — deliberately the same shape as `git.rs` and `project_search.rs`, so a
//! third feature does not mean a third mechanism. The three request wrappers
//! ([`Engine::lsp_request_completion`] and friends) return an id immediately
//! and fill their answer in later, and a newer request of the same kind
//! supersedes the older one exactly the way a newer project search does —
//! which, because Zed's request future sends `$/cancelRequest` when it is
//! dropped (lsp.rs:1518), also tells the server to stop working on it.
//!
//! **Silence when there is nothing to run.** No userland (the `play` flavour,
//! or a `full` build before the user installs one), no server for the language,
//! a server that is not installed, a server that dies on startup: all of them
//! are "this buffer has no language intelligence", never an error and never a
//! panic. It is the contract `git.rs` already has, and it is the normal state
//! of a fresh Debian.

use std::collections::HashMap;
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use gpui::{AsyncApp, Task};
use lsp::{
    CompletionParams, CompletionResponse, CompletionTextEdit, DidChangeTextDocumentParams,
    DidSaveTextDocumentParams, Documentation, GotoDefinitionParams, GotoDefinitionResponse,
    HoverContents, HoverParams, LanguageServer, LanguageServerBinary, LanguageServerId,
    LanguageServerName, MarkedString, PartialResultParams, Position, PublishDiagnosticsParams,
    Range, TextDocumentContentChangeEvent, TextDocumentIdentifier, TextDocumentPositionParams,
    TextDocumentSyncCapability, TextDocumentSyncKind, TextDocumentSyncSaveOptions, Uri,
    VersionedTextDocumentIdentifier, WorkDoneProgressParams,
};
use rope::PointUtf16;

use crate::guest::{self, GuestCommand};
use crate::project::ProjectId;
use crate::{BufferId, BufferState};

/// How long `initialize` gets. rust-analyzer on a cold page cache, inside
/// proot, on a phone, is slow enough that Zed's own 120 s default is not
/// obviously too generous — but a server that has not answered in a minute is
/// not going to, and the UI has been saying "starting" all that time.
const INITIALIZE_TIMEOUT: Duration = Duration::from_secs(60);

/// Per-request deadlines. A completion the user has already typed past is
/// worthless, so it gets the shortest; a definition may need an index built.
const COMPLETION_TIMEOUT: Duration = Duration::from_secs(4);
const HOVER_TIMEOUT: Duration = Duration::from_secs(4);
const DEFINITION_TIMEOUT: Duration = Duration::from_secs(8);

// ---------------------------------------------------------------------------
// Which server serves which language
// ---------------------------------------------------------------------------

/// A language server we know how to start.
///
/// Keyed by [`Server::name`] rather than by language, so one clangd serves both
/// `c` and `cpp` in a project and one typescript-language-server serves
/// `typescript` and `tsx` — which is what those servers expect, and what a
/// phone with a cap on background processes needs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Server {
    /// The program name, also the key in the per-project registry and the name
    /// shown in the UI.
    pub name: &'static str,
    /// The guest argv, program included. `--stdio` and friends belong here.
    pub argv: &'static [&'static str],
}

/// Grammar name (what `highlight::language_for_path` answers, and what
/// `Engine::buffer_language` reports) → the server for it and the `languageId`
/// the LSP spec wants in `didOpen`.
///
/// Deliberately short and deliberately explicit. Every entry is a Debian
/// package the user has to install themselves — P5-2 is what installs them, and
/// until it exists every one of these is simply absent, which this module
/// treats as a normal state rather than a failure. A grammar missing from this
/// table has no server, which is also normal: we highlight far more languages
/// than Debian packages a server for.
fn server_for(grammar: &str) -> Option<(Server, &'static str)> {
    const CLANGD: Server = Server {
        name: "clangd",
        argv: &["clangd", "--background-index"],
    };
    const TYPESCRIPT: Server = Server {
        name: "typescript-language-server",
        argv: &["typescript-language-server", "--stdio"],
    };
    Some(match grammar {
        // Debian: rust-analyzer
        "rust" => (
            Server {
                name: "rust-analyzer",
                argv: &["rust-analyzer"],
            },
            "rust",
        ),
        // Debian: clangd
        "c" => (CLANGD, "c"),
        "cpp" => (CLANGD, "cpp"),
        // Debian: gopls
        "go" => (
            Server {
                name: "gopls",
                argv: &["gopls", "serve"],
            },
            "go",
        ),
        // Debian: python3-pylsp
        "python" => (
            Server {
                name: "pylsp",
                argv: &["pylsp"],
            },
            "python",
        ),
        // Debian: node-typescript-language-server
        "typescript" => (TYPESCRIPT, "typescript"),
        "tsx" => (TYPESCRIPT, "typescriptreact"),
        _ => return None,
    })
}

/// proot's command line for a server, as Zed's `LanguageServerBinary`.
///
/// This is the whole of route (1) in agent-docs/research/lsp-approach.md: the
/// binary is proot, its arguments are proot's flags followed by the server's
/// own argv, and `LanguageServer::new` spawns it without knowing any of that.
/// Split out from [`start_server`] because it is the one part of starting a
/// server that can be tested on a host with no rootfs — and losing a flag here
/// fails exactly as quietly as losing one in `guest.rs` does.
pub(crate) fn server_binary(
    userland: &guest::Userland,
    server: &Server,
    root: &Path,
) -> LanguageServerBinary {
    let argv = server.argv.iter().map(OsString::from).collect();
    // `workdir` both binds the project and starts the server inside it: unlike
    // git, which carries `-C`, a language server finds its own manifest by
    // looking around the directory it was started in.
    let command = GuestCommand::new(server.name.to_owned(), argv).workdir(root);
    let invocation = guest::invocation(userland, &command);
    LanguageServerBinary {
        path: invocation.program,
        arguments: invocation.args,
        env: Some(
            invocation
                .env
                .into_iter()
                .map(|(key, value)| {
                    (
                        key.to_string_lossy().into_owned(),
                        value.to_string_lossy().into_owned(),
                    )
                })
                .collect(),
        ),
    }
}

// ---------------------------------------------------------------------------
// What the UI reads
// ---------------------------------------------------------------------------

/// LSP's four severities, under the names the UI paints with. `severity` is
/// never absent in what we hand out: a diagnostic without one is a warning,
/// which is what every editor assumes and is the safer of the two guesses.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Severity {
    Error,
    Warning,
    Info,
    Hint,
}

/// One diagnostic, in the coordinates the editor draws in.
///
/// Columns are UTF-16 code units, like `HighlightSpan` and `outline_path` — and
/// unlike them this costs nothing to arrange, because UTF-16 is the position
/// encoding we negotiate with the server in the first place (`initialize`'s
/// `general.positionEncodings`, vendor/lsp/src/lsp.rs:808).
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct DiagnosticRow {
    pub row: u32,
    pub col_utf16: u32,
    pub end_row: u32,
    pub end_col_utf16: u32,
    pub severity: Severity,
    pub message: String,
    /// Which analysis produced it ("rustc", "clippy", "clangd"), when the
    /// server says.
    pub source: Option<String>,
    /// The server's own code for it ("E0308", "unused-variable"), as a string
    /// whichever of LSP's two forms it arrived in.
    pub code: Option<String>,
}

/// Everything the editor needs to underline one buffer.
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct BufferDiagnostics {
    /// The value [`Engine::buffer_diagnostics_version`] returns; 0 means no
    /// server has ever published for this file.
    pub version: u64,
    /// The buffer version the rows describe. `null` when the server dated its
    /// publish against a document version we no longer recognise — it is
    /// describing text that has already been replaced. Note that 0 is a real
    /// buffer version (a file just opened), which is why this is nullable
    /// rather than sentinelled.
    pub buffer_version: Option<u64>,
    /// The buffer has moved since the server saw it, so the rows are in the
    /// right shape but possibly the wrong place. Zed dims them; so should we.
    pub stale: bool,
    pub rows: Vec<DiagnosticRow>,
}

/// How many of each severity a file holds.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize)]
pub struct Counts {
    pub errors: usize,
    pub warnings: usize,
    pub infos: usize,
    pub hints: usize,
}

impl Counts {
    fn add(&mut self, severity: Severity) {
        match severity {
            Severity::Error => self.errors += 1,
            Severity::Warning => self.warnings += 1,
            Severity::Info => self.infos += 1,
            Severity::Hint => self.hints += 1,
        }
    }

    fn merge(&mut self, other: Counts) {
        self.errors += other.errors;
        self.warnings += other.warnings;
        self.infos += other.infos;
        self.hints += other.hints;
    }

    fn is_empty(&self) -> bool {
        *self == Counts::default()
    }
}

/// Everything the status bar and a diagnostics panel need for a project.
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct ProjectDiagnostics {
    /// The value [`Engine::lsp_version`] returns.
    pub version: u64,
    #[serde(flatten)]
    pub totals: Counts,
    /// Files with at least one diagnostic, sorted by path.
    pub files: Vec<FileDiagnosticCounts>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct FileDiagnosticCounts {
    /// Project-relative, `/`-separated — the spelling `TreeEntry::path` and
    /// `ChangedFile::path` use, so a panel row can be cross-referenced.
    pub path: String,
    #[serde(flatten)]
    pub counts: Counts,
}

/// What one server in a project is doing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ServerState {
    /// Spawned, waiting for the `initialize` response.
    Starting,
    Running,
    /// It could not be started, or it stopped answering. `error` says why, at
    /// the level of detail worth showing: usually "not installed".
    Unavailable,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct ServerStatus {
    pub name: String,
    pub state: ServerState,
    pub error: Option<String>,
    /// The grammars this instance is serving, sorted.
    pub languages: Vec<String>,
}

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RequestKind {
    Completion,
    Hover,
    Definition,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RequestState {
    /// In flight.
    Pending,
    /// Answered. `payload` holds the answer, which may legitimately be empty.
    Done,
    /// The server did not answer inside its deadline, and has been told to
    /// stop. Distinct from `done` with nothing in it, because the UI should
    /// not cache "no completions here" from a timeout.
    Timeout,
    /// There is no server for this buffer — no userland, no server for the
    /// language, or one that failed to start. Not an error; the normal state
    /// of the `play` flavour.
    Unavailable,
    /// Superseded by a newer request of the same kind, cancelled outright, or
    /// an id the engine has forgotten.
    Cancelled,
}

/// One request's answer, whatever kind it is.
#[derive(Debug, Clone, serde::Serialize)]
pub struct RequestResult {
    pub id: u64,
    pub kind: RequestKind,
    pub state: RequestState,
    /// Bumped once, when the answer lands. 1 while pending, 2 once settled, 0
    /// for an id the engine has forgotten — the same liveness signal
    /// `projectSearchVersion` gives.
    pub version: u64,
    pub buffer_id: BufferId,
    /// Where it was asked, echoed back so a late answer can be discarded by a
    /// UI whose caret has moved.
    pub row: u32,
    pub col_utf16: u32,
    /// The buffer version it was asked at, for the same reason.
    pub buffer_version: u64,
    /// The answer. Shape depends on `kind`; see [`Engine::lsp_request_result`].
    /// `null` until the request settles.
    pub payload: serde_json::Value,
}

impl RequestResult {
    /// The answer for an id the engine no longer holds. Every field but `id`
    /// and `state` is a placeholder — there is nothing left to report — and
    /// `kind` in particular is not to be believed: the caller knows what it
    /// asked for, and this is the one shape that cannot.
    fn forgotten(id: u64) -> Self {
        Self {
            id,
            kind: RequestKind::Completion,
            state: RequestState::Cancelled,
            version: 0,
            buffer_id: 0,
            row: 0,
            col_utf16: 0,
            buffer_version: 0,
            payload: serde_json::Value::Null,
        }
    }
}

struct Pending {
    id: u64,
    kind: RequestKind,
    buffer: BufferId,
    row: u32,
    col_utf16: u32,
    buffer_version: u64,
    answer: Mutex<(RequestState, u64, serde_json::Value)>,
}

impl Pending {
    fn settle(&self, state: RequestState, payload: serde_json::Value) {
        let mut answer = self.answer.lock().unwrap();
        // A superseded request must not overwrite its successor's slot — it no
        // longer owns one — and must not un-cancel itself either.
        if answer.0 != RequestState::Pending {
            return;
        }
        *answer = (state, 2, payload);
    }

    fn result(&self) -> RequestResult {
        let answer = self.answer.lock().unwrap();
        RequestResult {
            id: self.id,
            kind: self.kind,
            state: answer.0,
            version: answer.1,
            buffer_id: self.buffer,
            row: self.row,
            col_utf16: self.col_utf16,
            buffer_version: self.buffer_version,
            payload: answer.2.clone(),
        }
    }
}

#[derive(Default)]
struct Requests {
    live: HashMap<u64, Arc<Pending>>,
    /// At most one live request per kind, so a completion popup that re-asks on
    /// every keystroke cannot accumulate work — the same rule
    /// `ProjectSearches` applies per project.
    latest: HashMap<RequestKind, u64>,
    /// Held only to cancel: dropping a gpui `Task` drops Zed's request future,
    /// which sends `$/cancelRequest` on the way out.
    tasks: HashMap<u64, Task<()>>,
}

impl Requests {
    /// Retire whatever was running for `kind` and make `id` the live one.
    fn supersede(&mut self, kind: RequestKind, id: u64) {
        if let Some(previous) = self.latest.insert(kind, id) {
            self.retire(previous);
        }
    }

    fn retire(&mut self, id: u64) -> bool {
        self.tasks.remove(&id);
        match self.live.remove(&id) {
            Some(pending) => {
                pending.settle(RequestState::Cancelled, serde_json::Value::Null);
                true
            }
            None => false,
        }
    }
}

// ---------------------------------------------------------------------------
// Cached state
// ---------------------------------------------------------------------------

/// Diagnostics as they arrive, and the counters that publish them.
///
/// Separate from [`LspState`] and behind an `Arc` because the
/// `publishDiagnostics` handler outlives every borrow of the engine: it is
/// owned by the `LanguageServer`, called on the runtime thread, and must keep
/// working while the caller that started the server is long gone.
#[derive(Default)]
pub(crate) struct DiagnosticStore {
    files: Mutex<HashMap<PathBuf, FileDiagnostics>>,
    /// Per project, bumped whenever anything above — or any server's state —
    /// moves.
    versions: Mutex<HashMap<ProjectId, u64>>,
    /// The last version pair we sent for a document: the LSP document version,
    /// and the engine buffer version it corresponded to. A publish carrying a
    /// different LSP version describes text we have already replaced.
    sent: Mutex<HashMap<PathBuf, (i32, u64)>>,
}

struct FileDiagnostics {
    project: ProjectId,
    version: u64,
    /// The engine buffer version the rows describe; `None` when the publish
    /// could not be dated against anything we sent.
    buffer_version: Option<u64>,
    counts: Counts,
    rows: Arc<Vec<DiagnosticRow>>,
}

impl DiagnosticStore {
    fn bump(&self, project: ProjectId) -> u64 {
        let mut versions = self.versions.lock().unwrap();
        let version = versions.entry(project).or_insert(0);
        *version += 1;
        *version
    }

    fn version(&self, project: ProjectId) -> u64 {
        self.versions
            .lock()
            .unwrap()
            .get(&project)
            .copied()
            .unwrap_or(0)
    }

    /// Remember what we last told the server, so a publish can be dated.
    fn note_sent(&self, path: &Path, lsp_version: i32, buffer_version: u64) {
        self.sent
            .lock()
            .unwrap()
            .insert(path.to_path_buf(), (lsp_version, buffer_version));
    }

    /// A document has been closed: we can no longer date what the server said
    /// about it, but what it said still stands.
    ///
    /// Deliberately *not* a removal. Servers whose analysis is workspace-wide —
    /// rust-analyzer's `cargo check` is the one that matters — go on reporting
    /// a file after `didClose`, and Zed's own diagnostics are project-wide for
    /// exactly that reason: closing a tab must not empty a diagnostics panel.
    /// A server that does drop a closed file publishes an empty list for it,
    /// and *that* is what clears these rows.
    ///
    /// The version mapping does go, so the rows read as stale until the server
    /// republishes — which is honest, because after a close and a reopen there
    /// is no buffer version they can be said to describe.
    fn undate(&self, path: &Path) {
        self.sent.lock().unwrap().remove(path);
        if let Some(file) = self.files.lock().unwrap().get_mut(path) {
            file.buffer_version = None;
        }
    }

    fn forget_project(&self, project: ProjectId) {
        let mut files = self.files.lock().unwrap();
        let mut sent = self.sent.lock().unwrap();
        files.retain(|path, file| {
            let ours = file.project == project;
            if ours {
                sent.remove(path);
            }
            !ours
        });
        drop(files);
        drop(sent);
        self.versions.lock().unwrap().remove(&project);
    }

    /// Install what a server just published for one file.
    fn publish(&self, project: ProjectId, path: PathBuf, params: PublishDiagnosticsParams) {
        let buffer_version = match (params.version, self.sent.lock().unwrap().get(&path)) {
            // The server dated its publish and it matches what we last sent:
            // the rows describe exactly that buffer version.
            (Some(published), Some(&(sent, buffer_version))) if published == sent => {
                Some(buffer_version)
            }
            // It dated it and it does not match — the text has moved on since.
            (Some(_), _) => None,
            // Many servers do not date publishes at all. Then the newest thing
            // we sent is the best answer available, and `stale` below still
            // catches the buffer moving afterwards.
            (None, Some(&(_, buffer_version))) => Some(buffer_version),
            (None, None) => None,
        };

        let mut rows: Vec<DiagnosticRow> = params
            .diagnostics
            .into_iter()
            .map(|diagnostic| DiagnosticRow {
                row: diagnostic.range.start.line,
                col_utf16: diagnostic.range.start.character,
                end_row: diagnostic.range.end.line,
                end_col_utf16: diagnostic.range.end.character,
                severity: severity_of(diagnostic.severity),
                message: diagnostic.message,
                source: diagnostic.source,
                code: diagnostic.code.map(|code| match code {
                    lsp::NumberOrString::Number(number) => number.to_string(),
                    lsp::NumberOrString::String(string) => string,
                }),
            })
            .collect();
        // Sorted so the editor can walk the rows it is painting in one pass,
        // and so "the next diagnostic" (Zed's F8) is a scan rather than a sort.
        rows.sort_by_key(|row| (row.row, row.col_utf16, row.end_row, row.end_col_utf16));

        let mut counts = Counts::default();
        for row in &rows {
            counts.add(row.severity);
        }

        let version = self.bump(project);
        let mut files = self.files.lock().unwrap();
        if rows.is_empty() {
            // An empty publish is how a server retracts everything it said
            // about a file. Dropping the entry is what keeps the summary from
            // listing files with nothing wrong with them.
            files.remove(&path);
        } else {
            files.insert(
                path,
                FileDiagnostics {
                    project,
                    version,
                    buffer_version,
                    counts,
                    rows: Arc::new(rows),
                },
            );
        }
    }
}

fn severity_of(severity: Option<lsp::DiagnosticSeverity>) -> Severity {
    match severity {
        Some(lsp::DiagnosticSeverity::ERROR) => Severity::Error,
        Some(lsp::DiagnosticSeverity::INFORMATION) => Severity::Info,
        Some(lsp::DiagnosticSeverity::HINT) => Severity::Hint,
        // WARNING, and anything the server left out or invented.
        _ => Severity::Warning,
    }
}

/// One document we have told a server about.
struct OpenDoc {
    project: ProjectId,
    server: &'static str,
    path: PathBuf,
    uri: Uri,
    grammar: &'static str,
    language_id: &'static str,
    /// LSP document version. First `didOpen` is 1 and every `didChange`
    /// increments, which is the whole of the spec's requirement.
    lsp_version: i32,
    /// `didOpen` has actually been sent. False while the server is still
    /// initializing: edits before that only move `lsp_version` forward, and the
    /// `didOpen` that eventually goes out carries the current text.
    opened: bool,
}

/// One server instance for one project.
struct Slot {
    project: ProjectId,
    server: Server,
    state: ServerState,
    error: Option<String>,
    /// Live once `initialize` has answered.
    handle: Option<Arc<LanguageServer>>,
    sync: TextDocumentSyncKind,
    /// The server asked to be told about saves.
    wants_save: bool,
    /// Kept so the `publishDiagnostics` handler is removed with the server
    /// rather than left dangling on a shared map.
    subscriptions: Vec<lsp::Subscription>,
}

/// The server registry, shared so a runtime job can install the server it
/// finished starting without holding a borrow on the engine.
type Slots = Arc<Mutex<HashMap<(ProjectId, &'static str), Slot>>>;

/// The open-document table, shared for the same reason: the `didOpen` for a
/// document registered while its server was still initializing is sent by that
/// server's own start job.
type Docs = Arc<Mutex<HashMap<BufferId, OpenDoc>>>;

/// Lock order, wherever two of these are wanted at once: **servers, then docs,
/// then the store**. Nothing here takes them in the other order, and nothing
/// holds one across an `await`.
#[derive(Default)]
pub(crate) struct LspState {
    servers: Slots,
    docs: Docs,
    store: Arc<DiagnosticStore>,
    requests: Arc<Mutex<Requests>>,
    next_request_id: AtomicU64,
    next_server_id: AtomicU64,
    /// True once a server has actually been started for something. Read on the
    /// keystroke path by [`Engine::edit`], where even a hash lookup per edit is
    /// a cost the `play` flavour has no reason to pay — and it never sets this,
    /// because it never has a userland to start anything in.
    live: AtomicBool,
    /// A live server negotiated whole-document `didChange`. Almost never true —
    /// the servers we start all take incremental sync — and checked before
    /// [`Engine::edit`] pays `Buffer::text()`, which is O(file). Shared,
    /// because the server that sets it is installed by a runtime job.
    wants_full_text: Arc<AtomicBool>,
    /// The last change [`Engine::edit`] and friends produced. Test-only: it is
    /// the only way to see what a server *would* have been told on a host that
    /// has no server to tell.
    #[cfg(test)]
    last_change: Mutex<Option<TextChange>>,
}

// ---------------------------------------------------------------------------
// The edit that has to reach the server
// ---------------------------------------------------------------------------

/// One change to a document, in the coordinates LSP speaks.
///
/// Built by [`Engine::edit`] from the snapshot it already took *before*
/// applying the edit, because the range a `didChange` carries is a range in the
/// old text and there is no way to recover it afterwards.
#[derive(Clone)]
pub(crate) struct TextChange {
    pub start: PointUtf16,
    pub old_end: PointUtf16,
    pub text: String,
    /// The whole new document, for a server that negotiated full sync.
    /// Filled in only when [`LspState::wants_full_text`] says somebody needs
    /// it.
    pub whole: Option<String>,
    /// The engine buffer version after the change.
    pub buffer_version: u64,
}

fn position(point: PointUtf16) -> Position {
    Position::new(point.row, point.column)
}

/// A change, as the `didChange` the negotiated sync kind asks for.
///
/// `None` means "send nothing": a server that took `NONE` does not want
/// changes at all, and one that took `FULL` cannot be told anything useful
/// without the whole document — only reachable in the instant between such a
/// server initializing and [`LspState::wants_full_text`] being observed by the
/// next edit, which then carries it.
fn content_changes(
    change: TextChange,
    sync: TextDocumentSyncKind,
) -> Option<Vec<TextDocumentContentChangeEvent>> {
    if sync == TextDocumentSyncKind::NONE {
        return None;
    }
    if sync == TextDocumentSyncKind::FULL {
        return Some(vec![TextDocumentContentChangeEvent {
            range: None,
            range_length: None,
            text: change.whole?,
        }]);
    }
    Some(vec![TextDocumentContentChangeEvent {
        // The range is in the *old* text, and in UTF-16 code units because
        // that is the encoding `initialize` negotiated. Getting either wrong
        // desynchronizes the server silently: it keeps answering, about a
        // document that is no longer the one on screen.
        range: Some(Range::new(position(change.start), position(change.old_end))),
        range_length: None,
        text: change.text,
    }])
}

// ---------------------------------------------------------------------------
// Engine surface
// ---------------------------------------------------------------------------

impl crate::Engine {
    /// Generation counter for everything LSP knows about a project:
    /// diagnostics for any of its files, and the state of its servers. Poll it
    /// exactly like `git_status_version`.
    ///
    /// Polling is also what *starts* servers, for the same reason it is what
    /// refreshes git: the userland can appear while files are already open —
    /// the user installs Debian, or `apt install clangd`, with the editor
    /// running — and a client that only ever started servers on `open_file`
    /// would stay silent until the file was closed and reopened. It never
    /// waits for one.
    pub fn lsp_version(&self, project: ProjectId) -> u64 {
        self.start_pending_servers(project);
        self.lsp.store.version(project)
    }

    /// What each server for this project is doing, for a status-bar item.
    /// Reads a cache; never blocks.
    pub fn lsp_servers(&self, project: ProjectId) -> Vec<ServerStatus> {
        let mut languages: HashMap<&'static str, Vec<String>> = HashMap::new();
        for doc in self.lsp.docs.lock().unwrap().values() {
            if doc.project == project {
                languages
                    .entry(doc.server)
                    .or_default()
                    .push(doc.grammar.to_owned());
            }
        }
        let mut statuses: Vec<ServerStatus> = self
            .lsp
            .servers
            .lock()
            .unwrap()
            .values()
            .filter(|slot| slot.project == project)
            .map(|slot| {
                let mut languages = languages.remove(slot.server.name).unwrap_or_default();
                languages.sort();
                languages.dedup();
                ServerStatus {
                    name: slot.server.name.to_owned(),
                    state: slot.state,
                    error: slot.error.clone(),
                    languages,
                }
            })
            .collect();
        statuses.sort_by(|a, b| a.name.cmp(&b.name));
        statuses
    }

    /// Every file in the project with a diagnostic, and the totals. Reads a
    /// cache; never blocks; empty when no server has ever published.
    pub fn lsp_diagnostics(&self, project: ProjectId) -> ProjectDiagnostics {
        let root = self.project_root(project);
        let files = self.lsp.store.files.lock().unwrap();
        let mut totals = Counts::default();
        let mut rows: Vec<FileDiagnosticCounts> = files
            .iter()
            .filter(|(_, file)| file.project == project && !file.counts.is_empty())
            .map(|(path, file)| {
                totals.merge(file.counts);
                FileDiagnosticCounts {
                    path: relative_path(root.as_deref(), path),
                    counts: file.counts,
                }
            })
            .collect();
        rows.sort_by(|a, b| a.path.cmp(&b.path));
        ProjectDiagnostics {
            version: self.lsp.store.version(project),
            totals,
            files: rows,
        }
    }

    /// Generation counter for one buffer's diagnostics; 0 until a server has
    /// published for its file. Cheaper than [`Engine::buffer_diagnostics`] by
    /// enough that an editor with ten tabs open should poll this per tab and
    /// only read the rows that moved.
    pub fn buffer_diagnostics_version(&self, buffer: BufferId) -> u64 {
        let Some(path) = self.buffer_path(buffer) else {
            return 0;
        };
        self.lsp
            .store
            .files
            .lock()
            .unwrap()
            .get(&path)
            .map(|file| file.version)
            .unwrap_or(0)
    }

    /// Everything a server has said about this buffer's file, in UTF-16
    /// columns. Reads a cache; never blocks; empty for a buffer with no file,
    /// no server, or nothing wrong with it.
    pub fn buffer_diagnostics(&self, buffer: BufferId) -> BufferDiagnostics {
        let Some(path) = self.buffer_path(buffer) else {
            return BufferDiagnostics::default();
        };
        let files = self.lsp.store.files.lock().unwrap();
        let Some(file) = files.get(&path) else {
            return BufferDiagnostics::default();
        };
        let current = self.version(buffer).unwrap_or(0);
        BufferDiagnostics {
            version: file.version,
            buffer_version: file.buffer_version,
            // Unknown counts as stale: the rows may describe text nobody has
            // any more, and a wrong underline is worse than a dimmed one.
            stale: file.buffer_version != Some(current),
            rows: (*file.rows).clone(),
        }
    }

    /// Ask for completions at a caret. Returns a request id to poll with —
    /// never blocks, never fails: a buffer with no server gets an id that
    /// reports `unavailable` straight away, so the UI has one code path.
    ///
    /// Supersedes whatever completion request was already in flight, and tells
    /// the server to stop working on it.
    pub fn lsp_request_completion(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::Completion, buffer, row, col_utf16)
    }

    /// Hover documentation at a caret. See [`Engine::lsp_request_completion`]
    /// for the contract.
    pub fn lsp_request_hover(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::Hover, buffer, row, col_utf16)
    }

    /// Where the symbol under the caret is defined. See
    /// [`Engine::lsp_request_completion`] for the contract.
    pub fn lsp_request_definition(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::Definition, buffer, row, col_utf16)
    }

    /// Generation counter for a request: 1 while it is in flight, 2 once it has
    /// settled, 0 for an id the engine has forgotten. Poll it like
    /// `project_search_version`.
    pub fn lsp_request_version(&self, id: u64) -> u64 {
        self.lsp
            .requests
            .lock()
            .unwrap()
            .live
            .get(&id)
            .map(|pending| pending.answer.lock().unwrap().1)
            .unwrap_or(0)
    }

    /// A request's answer. Never fails: a forgotten id reports itself
    /// cancelled with nothing in it.
    pub fn lsp_request_result(&self, id: u64) -> RequestResult {
        self.lsp
            .requests
            .lock()
            .unwrap()
            .live
            .get(&id)
            .map(|pending| pending.result())
            .unwrap_or_else(|| RequestResult::forgotten(id))
    }

    /// Stop a request and forget it — how a closed completion popup frees its
    /// slot, and how the server is told to stop indexing for an answer nobody
    /// will read. False if the id was already gone.
    pub fn lsp_cancel_request(&self, id: u64) -> bool {
        let mut requests = self.lsp.requests.lock().unwrap();
        requests.latest.retain(|_, live| *live != id);
        requests.retire(id)
    }

    // -----------------------------------------------------------------------
    // Hooks the rest of the engine calls
    // -----------------------------------------------------------------------

    /// A buffer with a file and a language has been opened. Starts the server
    /// for its language if this is the first such buffer in the project, and
    /// registers the document either way.
    ///
    /// Everything about it is best-effort: no project, no language, no
    /// userland, no server for the language — all of them return quietly.
    pub(crate) fn lsp_did_open(&self, buffer: BufferId) {
        let Some(path) = self.buffer_path(buffer) else {
            return;
        };
        let Some(grammar) = self.buffer_language(buffer) else {
            return;
        };
        let Some((server, language_id)) = server_for(grammar) else {
            return;
        };
        let Some(project) = self.project_for_path(&path) else {
            return;
        };
        let Ok(uri) = Uri::from_file_path(&path) else {
            log::debug!("lsp: {} is not a file URI", path.display());
            return;
        };

        self.lsp.docs.lock().unwrap().insert(
            buffer,
            OpenDoc {
                project,
                server: server.name,
                path,
                uri,
                grammar,
                language_id,
                lsp_version: 1,
                opened: false,
            },
        );
        self.ensure_server(project, server);
        // A server that is already running gets the `didOpen` now; one still
        // starting gets it from its own start job, which flushes the same way
        // the moment `initialize` answers.
        if let Some(handle) = self.server_handle(project, server.name) {
            let docs = self.lsp.docs.clone();
            let buffers = self.buffers.clone();
            let store = self.lsp.store.clone();
            self.runtime().spawn(move |_| {
                open_pending_docs(&handle, project, server.name, &docs, &buffers, &store);
            });
        }
    }

    /// A buffer has changed. Costs one relaxed atomic load when no server has
    /// ever run, which is the case this must not slow down.
    pub(crate) fn lsp_did_change(&self, buffer: BufferId, change: TextChange) {
        if !self.lsp.live.load(Ordering::Relaxed) {
            return;
        }
        #[cfg(test)]
        {
            // The one seam the tests need in the real path: a host with no
            // server can still prove that `Engine::edit` measured the range in
            // UTF-16 units on the text as it was *before* the edit, which is
            // the only place that information exists.
            *self.lsp.last_change.lock().unwrap() = Some(change.clone());
        }
        let (project, server, uri, path, version) = {
            let mut docs = self.lsp.docs.lock().unwrap();
            let Some(doc) = docs.get_mut(&buffer) else {
                return;
            };
            doc.lsp_version += 1;
            if !doc.opened {
                // The server has not been told the document exists yet. The
                // `didOpen` that eventually goes out carries the current text,
                // so the change is already in it — only the version has to keep
                // moving, and it just did.
                return;
            }
            (
                doc.project,
                doc.server,
                doc.uri.clone(),
                doc.path.clone(),
                doc.lsp_version,
            )
        };
        let sync = self
            .sync_kind(project, server)
            .unwrap_or(TextDocumentSyncKind::INCREMENTAL);
        self.lsp
            .store
            .note_sent(&path, version, change.buffer_version);

        let Some(content_changes) = content_changes(change, sync) else {
            return;
        };

        let Some(handle) = self.server_handle(project, server) else {
            return;
        };
        self.runtime().spawn(move |_| {
            handle
                .notify::<lsp::notification::DidChangeTextDocument>(DidChangeTextDocumentParams {
                    text_document: VersionedTextDocumentIdentifier::new(uri, version),
                    content_changes,
                })
                .ok();
        });
    }

    /// A buffer has been written to disk. Servers that asked to hear about it
    /// use it to re-run the slow checks — rust-analyzer's `cargo check` runs
    /// here and nowhere else, which is where most of its diagnostics come from.
    pub(crate) fn lsp_did_save(&self, buffer: BufferId) {
        if !self.lsp.live.load(Ordering::Relaxed) {
            return;
        }
        let Some((project, server, uri)) = self
            .lsp
            .docs
            .lock()
            .unwrap()
            .get(&buffer)
            .filter(|doc| doc.opened)
            .map(|doc| (doc.project, doc.server, doc.uri.clone()))
        else {
            return;
        };
        if !self.wants_save(project, server) {
            return;
        }
        let Some(handle) = self.server_handle(project, server) else {
            return;
        };
        self.runtime().spawn(move |_| {
            handle
                .notify::<lsp::notification::DidSaveTextDocument>(DidSaveTextDocumentParams {
                    text_document: TextDocumentIdentifier::new(uri),
                    text: None,
                })
                .ok();
        });
    }

    /// A buffer has been closed. The server is told; what it has already said
    /// about the file stays, because for a workspace-wide analysis it is still
    /// true — see [`DiagnosticStore::undate`].
    pub(crate) fn lsp_did_close(&self, buffer: BufferId) {
        // Deliberately not gated on `live`: a document registered before any
        // server started still has to be forgotten, or a `play` build would
        // accumulate one entry per file the user ever opened.
        let Some(doc) = self.lsp.docs.lock().unwrap().remove(&buffer) else {
            return;
        };
        self.lsp.store.undate(&doc.path);
        // Requests against a buffer that is gone can never be read again.
        let mut requests = self.lsp.requests.lock().unwrap();
        let stale: Vec<u64> = requests
            .live
            .iter()
            .filter(|(_, pending)| pending.buffer == buffer)
            .map(|(id, _)| *id)
            .collect();
        for id in stale {
            requests.latest.retain(|_, live| *live != id);
            requests.retire(id);
        }
        drop(requests);

        if doc.opened {
            let handle = self.server_handle(doc.project, doc.server);
            if let Some(server) = handle {
                let uri = doc.uri.clone();
                self.runtime().spawn(move |_| server.unregister_buffer(uri));
            }
        }
    }

    /// A project is closing: stop its servers and forget everything they said.
    ///
    /// Dropping the `Arc<LanguageServer>` is the shutdown — Zed's `Drop`
    /// (lsp.rs:1755) spawns the `shutdown`/`exit` handshake and only then kills
    /// the process, so the server exits politely and proot follows it out.
    pub(crate) fn lsp_close_project(&self, project: ProjectId) {
        let dropped: Vec<Slot> = {
            let mut servers = self.lsp.servers.lock().unwrap();
            let keys: Vec<(ProjectId, &'static str)> = servers
                .keys()
                .filter(|(id, _)| *id == project)
                .copied()
                .collect();
            keys.into_iter()
                .filter_map(|key| servers.remove(&key))
                .collect()
        };
        self.lsp
            .docs
            .lock()
            .unwrap()
            .retain(|_, doc| doc.project != project);
        self.lsp.store.forget_project(project);
        if dropped.is_empty() {
            return;
        }
        // The handshake needs the runtime's executor, so the last references
        // are released there rather than on the caller's thread.
        self.runtime().spawn(move |_| drop(dropped));
    }

    /// Whether anything at all should be told about edits. Lets
    /// [`Engine::edit`] skip building a [`TextChange`] entirely.
    pub(crate) fn lsp_is_live(&self) -> bool {
        self.lsp.live.load(Ordering::Relaxed)
    }

    /// Whether some live server negotiated whole-document sync, and therefore
    /// whether [`Engine::edit`] has to pay for `Buffer::text()`.
    pub(crate) fn lsp_wants_full_text(&self) -> bool {
        self.lsp.wants_full_text.load(Ordering::Relaxed)
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /// The project whose root contains `path`, deepest root first — a project
    /// opened inside another one owns its files.
    fn project_for_path(&self, path: &Path) -> Option<ProjectId> {
        let projects = self.projects.lock().unwrap();
        let mut best: Option<(ProjectId, usize)> = None;
        for (id, state) in projects.iter() {
            let root = state.lock().unwrap().root.clone();
            if path.starts_with(&root) {
                let depth = root.components().count();
                if best.is_none_or(|(_, deepest)| depth > deepest) {
                    best = Some((*id, depth));
                }
            }
        }
        best.map(|(id, _)| id)
    }

    fn sync_kind(&self, project: ProjectId, server: &'static str) -> Option<TextDocumentSyncKind> {
        self.lsp
            .servers
            .lock()
            .unwrap()
            .get(&(project, server))
            .map(|slot| slot.sync)
    }

    fn wants_save(&self, project: ProjectId, server: &'static str) -> bool {
        self.lsp
            .servers
            .lock()
            .unwrap()
            .get(&(project, server))
            .is_some_and(|slot| slot.wants_save)
    }

    fn server_handle(
        &self,
        project: ProjectId,
        server: &'static str,
    ) -> Option<Arc<LanguageServer>> {
        self.lsp
            .servers
            .lock()
            .unwrap()
            .get(&(project, server))
            .and_then(|slot| slot.handle.clone())
    }

    /// Start the server for `project` unless it is already there.
    fn ensure_server(&self, project: ProjectId, server: Server) {
        let Some(userland) = self.userland() else {
            return;
        };
        if !userland.is_installed() {
            return;
        }
        let Some(root) = self.project_root(project) else {
            return;
        };
        {
            let mut servers = self.lsp.servers.lock().unwrap();
            if servers.contains_key(&(project, server.name)) {
                return;
            }
            servers.insert(
                (project, server.name),
                Slot {
                    project,
                    server,
                    state: ServerState::Starting,
                    error: None,
                    handle: None,
                    sync: TextDocumentSyncKind::INCREMENTAL,
                    wants_save: false,
                    subscriptions: Vec::new(),
                },
            );
        }
        // Only now is anything actually going to talk to a server, so only now
        // does `Engine::edit` need to do any work at all. The `play` flavour
        // never reaches this line.
        self.lsp.live.store(true, Ordering::Relaxed);
        self.lsp.store.bump(project);

        let id = LanguageServerId(self.lsp.next_server_id.fetch_add(1, Ordering::Relaxed) as usize);
        let binary = server_binary(&userland, &server, &root);
        log::info!(
            "lsp: starting {} for project {project} in {}",
            server.name,
            root.display()
        );

        let started = StartRequest {
            project,
            server,
            id,
            binary,
            root,
            store: self.lsp.store.clone(),
            buffers: self.buffers.clone(),
            slots: self.lsp.servers.clone(),
            docs: self.lsp.docs.clone(),
            wants_full_text: self.lsp.wants_full_text.clone(),
        };
        self.runtime().spawn(move |cx| {
            cx.spawn(async move |cx| start_server(started, cx).await)
                .detach();
        });
    }

    /// Servers for languages already open in a project that has none yet — the
    /// `apt install` that happened while the editor was running.
    fn start_pending_servers(&self, project: ProjectId) {
        if self.userland().is_none() {
            return;
        }
        self.adopt_open_buffers(project);
        let known: Vec<&'static str> = self
            .lsp
            .servers
            .lock()
            .unwrap()
            .keys()
            .filter(|(id, _)| *id == project)
            .map(|(_, name)| *name)
            .collect();
        let wanted: Vec<Server> = self
            .lsp
            .docs
            .lock()
            .unwrap()
            .values()
            .filter(|doc| doc.project == project && !known.contains(&doc.server))
            .filter_map(|doc| server_for(doc.grammar).map(|(server, _)| server))
            .collect();
        for server in wanted {
            self.ensure_server(project, server);
        }
    }

    /// Register file-backed buffers that this project did not have when they
    /// were opened.
    ///
    /// [`Engine::lsp_did_open`] runs at `open_file`, and a file opened *before*
    /// its enclosing project — a recent-files entry restored at launch, a
    /// project opened around a file already on screen — has no project to
    /// belong to at that moment and is skipped. This is the other half of
    /// "polling is what drives it": one `starts_with` per open tab per poll,
    /// against a list that is the number of tabs long.
    fn adopt_open_buffers(&self, project: ProjectId) {
        let Some(root) = self.project_root(project) else {
            return;
        };
        let known: Vec<BufferId> = self.lsp.docs.lock().unwrap().keys().copied().collect();
        let candidates: Vec<BufferId> = self
            .buffers
            .lock()
            .unwrap()
            .keys()
            .copied()
            .filter(|buffer| !known.contains(buffer))
            .collect();
        for buffer in candidates {
            if self
                .buffer_path(buffer)
                .is_some_and(|path| path.starts_with(&root))
            {
                self.lsp_did_open(buffer);
            }
        }
    }

    fn start_request(&self, kind: RequestKind, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        let id = self.lsp.next_request_id.fetch_add(1, Ordering::Relaxed) + 1;
        let buffer_version = self.version(buffer).unwrap_or(0);
        let pending = Arc::new(Pending {
            id,
            kind,
            buffer,
            row,
            col_utf16,
            buffer_version,
            answer: Mutex::new((RequestState::Pending, 1, serde_json::Value::Null)),
        });
        {
            let mut requests = self.lsp.requests.lock().unwrap();
            requests.supersede(kind, id);
            requests.live.insert(id, pending.clone());
        }

        let doc = self
            .lsp
            .docs
            .lock()
            .unwrap()
            .get(&buffer)
            .filter(|doc| doc.opened)
            .map(|doc| (doc.project, doc.server, doc.uri.clone()));
        let Some((project, server, uri)) = doc else {
            pending.settle(RequestState::Unavailable, serde_json::Value::Null);
            return id;
        };
        let Some(handle) = self.server_handle(project, server) else {
            pending.settle(RequestState::Unavailable, serde_json::Value::Null);
            return id;
        };

        let requests = self.lsp.requests.clone();
        self.runtime().spawn(move |cx| {
            let task = cx.spawn(async move |_| {
                let (state, payload) = perform(&handle, kind, uri, row, col_utf16).await;
                pending.settle(state, payload);
            });
            // Superseded before the runtime got to it: dropping the task here
            // is what tells the server to forget the request.
            let mut requests = requests.lock().unwrap();
            if requests.live.contains_key(&id) {
                requests.tasks.insert(id, task);
            }
        });
        id
    }
}

/// A path relative to a project root, `/`-separated, or the absolute path when
/// it lies outside — which a header file pulled in from `/usr/include` will.
fn relative_path(root: Option<&Path>, path: &Path) -> String {
    match root.and_then(|root| path.strip_prefix(root).ok()) {
        Some(relative) => relative
            .components()
            .map(|component| component.as_os_str().to_string_lossy())
            .collect::<Vec<_>>()
            .join("/"),
        // Outside the project — a header from /usr/include, a dependency's
        // source — keeps its absolute name. A relative one would point nowhere.
        None => path.to_string_lossy().into_owned(),
    }
}

/// Everything `start_server` needs, gathered on the caller's thread so the
/// runtime job owns no borrows.
struct StartRequest {
    project: ProjectId,
    server: Server,
    id: LanguageServerId,
    binary: LanguageServerBinary,
    root: PathBuf,
    store: Arc<DiagnosticStore>,
    buffers: Arc<Mutex<HashMap<BufferId, BufferState>>>,
    slots: Slots,
    docs: Docs,
    wants_full_text: Arc<AtomicBool>,
}

async fn start_server(request: StartRequest, cx: &mut AsyncApp) {
    let StartRequest {
        project,
        server,
        id,
        binary,
        root,
        store,
        buffers,
        slots,
        docs,
        wants_full_text,
    } = request;

    let fail = |message: String| {
        log::info!("lsp: {} unavailable: {message}", server.name);
        if let Some(slot) = slots.lock().unwrap().get_mut(&(project, server.name)) {
            slot.state = ServerState::Unavailable;
            slot.error = Some(message);
        }
        store.bump(project);
    };

    // Zed keeps the last of stderr for its language-server log; we keep it for
    // exactly one purpose, which is to be able to say *why* a server is
    // unavailable rather than only that it is.
    let stderr = Arc::new(parking_lot::Mutex::new(Some(String::new())));
    let server_handle = match LanguageServer::new(
        stderr.clone(),
        id,
        LanguageServerName(server.name.into()),
        binary,
        &root,
        None,
        None,
        cx,
    ) {
        Ok(handle) => handle,
        Err(err) => {
            // proot itself is missing, or the rootfs went away. Not the same
            // as the server not being installed, which shows up as an exit
            // below, but the user cannot act differently on either.
            fail(format!("{err:#}"));
            return;
        }
    };

    // Registered before `initialize` consumes the server: a server that
    // publishes diagnostics the instant it is initialized — clangd does — would
    // otherwise have them logged as unhandled and thrown away.
    let subscription = {
        let store = store.clone();
        server_handle.on_notification::<lsp::notification::PublishDiagnostics, _>(
            move |params: PublishDiagnosticsParams, _cx: &mut AsyncApp| {
                let Ok(path) = params.uri.to_file_path() else {
                    return;
                };
                // Buffers hold canonical paths (see `Engine::open_file`), and
                // on Android /data/user/0/<pkg> is a symlink to
                // /data/data/<pkg> — so a server's spelling of a path and ours
                // would otherwise never match.
                let path = std::fs::canonicalize(&path).unwrap_or(path);
                store.publish(project, path, params);
            },
        )
    };

    // `initialize` with the capabilities Zed advertises, which is where UTF-16
    // positions are agreed (vendor/lsp/src/lsp.rs:808) — the reason every
    // column this module hands the UI is already in the units the UI wants.
    // Pull diagnostics are off: we take the pushed ones, which is what every
    // server we start does anyway, and it keeps the UI to one path.
    let params = cx.update(|cx| server_handle.default_initialize_params(false, false, cx));
    let initialize = cx.update(move |cx| {
        server_handle.initialize(
            params,
            Arc::new(lsp::DidChangeConfigurationParams {
                settings: serde_json::Value::Null,
            }),
            INITIALIZE_TIMEOUT,
            cx,
        )
    });
    let handle = match initialize.await {
        Ok(handle) => handle,
        Err(err) => {
            // The overwhelmingly common cause: the package is not installed,
            // so proot exited with "command not found" and the request never
            // got an answer. Exactly the state a fresh Debian is in, and the
            // user's cue to install it.
            let captured = stderr.lock().clone().unwrap_or_default();
            let detail = captured.lines().next_back().unwrap_or_default().trim();
            fail(if detail.is_empty() {
                format!("{err:#}")
            } else {
                detail.to_owned()
            });
            return;
        }
    };

    let capabilities = handle.capabilities();
    let (sync, wants_save) = sync_of(&capabilities);
    if sync == TextDocumentSyncKind::FULL {
        // Latching rather than reference-counted: it stays set for the life of
        // the process once any server has ever wanted it. The cost of being
        // wrong is one `Buffer::text()` per edit, and getting the bookkeeping
        // wrong the other way is a server told nothing at all.
        wants_full_text.store(true, Ordering::Relaxed);
    }
    log::info!(
        "lsp: {} initialized for project {project} (sync {sync:?}, save {wants_save})",
        server.name
    );
    {
        let mut servers = slots.lock().unwrap();
        let Some(slot) = servers.get_mut(&(project, server.name)) else {
            // The project closed while we were starting. Dropping `handle`
            // here runs the shutdown handshake, which is what we want.
            return;
        };
        slot.state = ServerState::Running;
        slot.handle = Some(handle.clone());
        slot.sync = sync;
        slot.wants_save = wants_save;
        slot.subscriptions.push(subscription);
    }
    // Every buffer of this language that was opened while we were starting.
    open_pending_docs(&handle, project, server.name, &docs, &buffers, &store);
    store.bump(project);
}

/// Send `didOpen` for every document of this server that has not had one.
///
/// The text is read *here* rather than when the document was registered,
/// because a server that takes ten seconds to initialize is a server the user
/// has been typing at for ten seconds: `didOpen` has to describe the buffer as
/// it is now, and the LSP version it carries is the one every later
/// `didChange` counts on from.
fn open_pending_docs(
    server: &LanguageServer,
    project: ProjectId,
    name: &'static str,
    docs: &Docs,
    buffers: &Arc<Mutex<HashMap<BufferId, BufferState>>>,
    store: &DiagnosticStore,
) {
    let pending: Vec<(BufferId, Uri, PathBuf, &'static str, i32)> = {
        let mut docs = docs.lock().unwrap();
        docs.iter_mut()
            .filter(|(_, doc)| doc.project == project && doc.server == name && !doc.opened)
            .map(|(buffer, doc)| {
                doc.opened = true;
                (
                    *buffer,
                    doc.uri.clone(),
                    doc.path.clone(),
                    doc.language_id,
                    doc.lsp_version,
                )
            })
            .collect()
    };
    for (buffer, uri, path, language_id, version) in pending {
        let Some((text, buffer_version)) = buffers
            .lock()
            .unwrap()
            .get(&buffer)
            .map(|state| (state.buffer.text(), state.version))
        else {
            continue;
        };
        store.note_sent(&path, version, buffer_version);
        server.register_buffer(uri, language_id.to_owned(), version, text);
    }
}

/// What the server said about document sync, defaulted the way the spec says:
/// absent means no sync at all, and a server that says nothing gets told
/// nothing.
fn sync_of(capabilities: &lsp::ServerCapabilities) -> (TextDocumentSyncKind, bool) {
    match &capabilities.text_document_sync {
        Some(TextDocumentSyncCapability::Kind(kind)) => (*kind, false),
        Some(TextDocumentSyncCapability::Options(options)) => (
            options.change.unwrap_or(TextDocumentSyncKind::NONE),
            match &options.save {
                Some(TextDocumentSyncSaveOptions::Supported(supported)) => *supported,
                Some(TextDocumentSyncSaveOptions::SaveOptions(_)) => true,
                None => false,
            },
        ),
        None => (TextDocumentSyncKind::NONE, false),
    }
}

/// Send one request and turn its answer into the JSON the UI reads.
async fn perform(
    server: &LanguageServer,
    kind: RequestKind,
    uri: Uri,
    row: u32,
    col_utf16: u32,
) -> (RequestState, serde_json::Value) {
    let position = TextDocumentPositionParams {
        text_document: TextDocumentIdentifier::new(uri),
        position: Position::new(row, col_utf16),
    };
    match kind {
        RequestKind::Completion => {
            let response = server
                .request::<lsp::request::Completion>(
                    CompletionParams {
                        text_document_position: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                        context: None,
                    },
                    COMPLETION_TIMEOUT,
                )
                .await;
            settle(response, completion_json)
        }
        RequestKind::Hover => {
            let response = server
                .request::<lsp::request::HoverRequest>(
                    HoverParams {
                        text_document_position_params: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                    },
                    HOVER_TIMEOUT,
                )
                .await;
            settle(response, hover_json)
        }
        RequestKind::Definition => {
            let response = server
                .request::<lsp::request::GotoDefinition>(
                    GotoDefinitionParams {
                        text_document_position_params: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                    },
                    DEFINITION_TIMEOUT,
                )
                .await;
            settle(response, definition_json)
        }
    }
}

fn settle<T>(
    response: util::ConnectionResult<T>,
    render: impl FnOnce(T) -> serde_json::Value,
) -> (RequestState, serde_json::Value) {
    match response {
        util::ConnectionResult::Result(Ok(value)) => (RequestState::Done, render(value)),
        util::ConnectionResult::Result(Err(err)) => {
            log::debug!("lsp request failed: {err:#}");
            (RequestState::Unavailable, serde_json::Value::Null)
        }
        util::ConnectionResult::Timeout => (RequestState::Timeout, serde_json::Value::Null),
        // The server died mid-request. Reported as unavailable rather than as
        // a timeout, because retrying will not help until it is restarted.
        util::ConnectionResult::ConnectionReset => {
            (RequestState::Unavailable, serde_json::Value::Null)
        }
    }
}

// ---------------------------------------------------------------------------
// The JSON the UI wave consumes. Frozen after this change.
// ---------------------------------------------------------------------------

#[derive(serde::Serialize)]
struct CompletionPayload {
    /// The list is not the whole truth: re-ask after the next character.
    is_incomplete: bool,
    items: Vec<CompletionItemJson>,
}

#[derive(serde::Serialize)]
struct CompletionItemJson {
    /// What the popup shows.
    label: String,
    /// The signature or type the server puts to the right of the label.
    detail: Option<String>,
    /// LSP's `CompletionItemKind` in snake_case ("function", "struct",
    /// "type_parameter"), or null.
    kind: Option<String>,
    /// The text to put in the buffer. Never null: falls back to `label`, which
    /// is what the spec says.
    insert_text: String,
    /// `insert_text` is a snippet (`${1:name}` placeholders), not literal text.
    is_snippet: bool,
    /// What to match the user's typing against; falls back to `label`.
    filter_text: String,
    /// What to sort by; falls back to `label`.
    sort_text: String,
    /// Documentation, flattened to markdown.
    documentation: Option<String>,
    deprecated: bool,
    preselect: bool,
    /// The range `insert_text` replaces, in the buffer's UTF-16 coordinates,
    /// when the server named one. Null means the UI decides — the word around
    /// the caret, as Zed does.
    edit: Option<RangeJson>,
}

#[derive(serde::Serialize)]
struct RangeJson {
    row: u32,
    col_utf16: u32,
    end_row: u32,
    end_col_utf16: u32,
}

impl From<Range> for RangeJson {
    fn from(range: Range) -> Self {
        Self {
            row: range.start.line,
            col_utf16: range.start.character,
            end_row: range.end.line,
            end_col_utf16: range.end.character,
        }
    }
}

#[derive(serde::Serialize)]
struct HoverPayload {
    /// Everything the server said, as one markdown string. Empty when it had
    /// nothing to say, which is a perfectly ordinary answer.
    contents: String,
    /// What the hover applies to, when the server said.
    range: Option<RangeJson>,
}

#[derive(serde::Serialize)]
struct DefinitionPayload {
    targets: Vec<TargetJson>,
}

#[derive(serde::Serialize)]
struct TargetJson {
    /// Absolute host path — which is also the guest path, because the binds are
    /// identities. Pass it straight to `openFile`.
    path: String,
    /// Where to put the caret.
    row: u32,
    col_utf16: u32,
    /// The whole symbol, for selecting it on arrival.
    end_row: u32,
    end_col_utf16: u32,
}

fn completion_json(response: Option<CompletionResponse>) -> serde_json::Value {
    let (is_incomplete, items) = match response {
        Some(CompletionResponse::Array(items)) => (false, items),
        Some(CompletionResponse::List(list)) => (list.is_incomplete, list.items),
        None => (false, Vec::new()),
    };
    let items = items
        .into_iter()
        .map(|item| {
            let (text_edit, edit) = match item.text_edit {
                Some(CompletionTextEdit::Edit(edit)) => (Some(edit.new_text), Some(edit.range)),
                Some(CompletionTextEdit::InsertAndReplace(edit)) => {
                    // The *replace* range is the one Zed applies, because
                    // accepting a completion over an existing identifier should
                    // replace it rather than double it.
                    (Some(edit.new_text), Some(edit.replace))
                }
                None => (None, None),
            };
            let insert_text = text_edit
                .or(item.insert_text)
                .unwrap_or_else(|| item.label.clone());
            CompletionItemJson {
                kind: item.kind.map(completion_kind_name),
                detail: item.detail,
                is_snippet: item.insert_text_format == Some(lsp::InsertTextFormat::SNIPPET),
                filter_text: item.filter_text.unwrap_or_else(|| item.label.clone()),
                sort_text: item.sort_text.unwrap_or_else(|| item.label.clone()),
                documentation: item.documentation.map(|documentation| match documentation {
                    Documentation::String(text) => text,
                    Documentation::MarkupContent(markup) => markup.value,
                }),
                deprecated: item.deprecated.unwrap_or(false)
                    || item
                        .tags
                        .as_ref()
                        .is_some_and(|tags| tags.contains(&lsp::CompletionItemTag::DEPRECATED)),
                preselect: item.preselect.unwrap_or(false),
                edit: edit.map(RangeJson::from),
                insert_text,
                label: item.label,
            }
        })
        .collect();
    serde_json::to_value(CompletionPayload {
        is_incomplete,
        items,
    })
    .unwrap_or(serde_json::Value::Null)
}

/// LSP's numbered kinds, spelled the way the rest of our JSON spells enums.
fn completion_kind_name(kind: lsp::CompletionItemKind) -> String {
    use lsp::CompletionItemKind as K;
    let name = match kind {
        K::TEXT => "text",
        K::METHOD => "method",
        K::FUNCTION => "function",
        K::CONSTRUCTOR => "constructor",
        K::FIELD => "field",
        K::VARIABLE => "variable",
        K::CLASS => "class",
        K::INTERFACE => "interface",
        K::MODULE => "module",
        K::PROPERTY => "property",
        K::UNIT => "unit",
        K::VALUE => "value",
        K::ENUM => "enum",
        K::KEYWORD => "keyword",
        K::SNIPPET => "snippet",
        K::COLOR => "color",
        K::FILE => "file",
        K::REFERENCE => "reference",
        K::FOLDER => "folder",
        K::ENUM_MEMBER => "enum_member",
        K::CONSTANT => "constant",
        K::STRUCT => "struct",
        K::EVENT => "event",
        K::OPERATOR => "operator",
        K::TYPE_PARAMETER => "type_parameter",
        _ => "text",
    };
    name.to_owned()
}

fn hover_json(response: Option<lsp::Hover>) -> serde_json::Value {
    let Some(hover) = response else {
        return serde_json::to_value(HoverPayload {
            contents: String::new(),
            range: None,
        })
        .unwrap_or(serde_json::Value::Null);
    };
    let contents = match hover.contents {
        HoverContents::Scalar(marked) => marked_string(marked),
        HoverContents::Array(marked) => marked
            .into_iter()
            .map(marked_string)
            .collect::<Vec<_>>()
            .join("\n\n"),
        HoverContents::Markup(markup) => markup.value,
    };
    serde_json::to_value(HoverPayload {
        contents: contents.trim().to_owned(),
        range: hover.range.map(RangeJson::from),
    })
    .unwrap_or(serde_json::Value::Null)
}

/// LSP's pre-markup hover form, rendered as markdown so the UI has one thing
/// to draw rather than three.
fn marked_string(marked: MarkedString) -> String {
    match marked {
        MarkedString::String(text) => text,
        MarkedString::LanguageString(string) => {
            format!("```{}\n{}\n```", string.language, string.value)
        }
    }
}

fn definition_json(response: Option<GotoDefinitionResponse>) -> serde_json::Value {
    let targets = match response {
        Some(GotoDefinitionResponse::Scalar(location)) => {
            vec![target(location.uri, location.range)]
        }
        Some(GotoDefinitionResponse::Array(locations)) => locations
            .into_iter()
            .map(|location| target(location.uri, location.range))
            .collect(),
        Some(GotoDefinitionResponse::Link(links)) => links
            .into_iter()
            .map(|link| target(link.target_uri, link.target_selection_range))
            .collect(),
        None => Vec::new(),
    };
    serde_json::to_value(DefinitionPayload {
        targets: targets.into_iter().flatten().collect(),
    })
    .unwrap_or(serde_json::Value::Null)
}

fn target(uri: Uri, range: Range) -> Option<TargetJson> {
    // A definition in a URI that is not a file — a server's synthetic
    // `zipfile:` or `jdt:` document — is one we cannot open, so it is dropped
    // rather than handed over as a path that does not exist.
    let path = uri.to_file_path().ok()?;
    Some(TargetJson {
        path: path.to_string_lossy().into_owned(),
        row: range.start.line,
        col_utf16: range.start.character,
        end_row: range.end.line,
        end_col_utf16: range.end.character,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;

    // -----------------------------------------------------------------------
    // Starting a server
    // -----------------------------------------------------------------------

    /// An engine with a userland whose files actually exist, because
    /// `Userland::is_installed` looks.
    fn engine_with_userland(dir: &Path) -> Engine {
        let proot = dir.join("libproot_exec.so");
        let rootfs = dir.join("debian");
        std::fs::write(&proot, "").unwrap();
        std::fs::create_dir_all(&rootfs).unwrap();
        std::fs::create_dir_all(dir.join("projects")).unwrap();
        let engine = Engine::new();
        engine.set_userland(&proot, &rootfs, dir, &dir.join("projects"));
        engine
    }

    /// The command line a language server is started with, spelled out.
    ///
    /// This is the whole of route (1): Zed's `LanguageServer::new` execs
    /// `path` with `arguments`, so if proot's flags are not in this list they
    /// are nowhere, and the failure is a server that cannot see the project or
    /// cannot find its own libraries — quiet, on a device, at the far end of a
    /// pipe.
    #[test]
    fn a_server_is_started_as_proot_with_the_server_as_its_tail() {
        let dir = tempfile::tempdir().unwrap();
        let engine = engine_with_userland(dir.path());
        let userland = engine.userland().expect("a userland");
        let root = dir.path().join("projects").join("thing");
        std::fs::create_dir_all(&root).unwrap();

        let (server, language_id) = server_for("rust").expect("rust has a server");
        assert_eq!(language_id, "rust");
        let binary = server_binary(&userland, &server, &root);

        assert_eq!(binary.path, dir.path().join("libproot_exec.so"));
        let args: Vec<String> = binary
            .arguments
            .iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        // proot's own flags, in guest.rs's order...
        assert_eq!(
            args[..6],
            [
                "-0",
                "--kill-on-exit",
                "--link2symlink",
                "-k",
                "6.2.1",
                "-r"
            ]
        );
        // ...the rootfs, the three system binds, the projects bind...
        assert!(args.contains(&"/proc".to_owned()));
        // ...the project as the working directory, and the server argv last,
        // which is the whole contract: everything after `-w <dir>` is the
        // guest's command line.
        let w = args.iter().position(|arg| arg == "-w").expect("a -w");
        assert_eq!(args[w + 1], root.to_string_lossy());
        assert_eq!(args[w + 2..], ["rust-analyzer".to_owned()]);

        // The guest environment travels with it: without a guest PATH, every
        // program inside the fake root is "command not found".
        let env = binary.env.expect("an environment");
        assert_eq!(env.get("HOME").map(String::as_str), Some("/root"));
        assert!(
            env.get("PATH")
                .is_some_and(|path| path.contains("/usr/bin"))
        );
        assert!(env.contains_key("PROOT_TMP_DIR"));
    }

    /// One server per *server*, not per language: clangd is started once for a
    /// project holding both C and C++, and typescript-language-server once for
    /// both TypeScript and TSX.
    #[test]
    fn languages_that_share_a_server_share_its_key() {
        let (c, c_id) = server_for("c").unwrap();
        let (cpp, cpp_id) = server_for("cpp").unwrap();
        assert_eq!(c.name, cpp.name);
        assert_eq!(c.argv, cpp.argv);
        // ...but each keeps its own `languageId`, which is what `didOpen`
        // carries and what a server switches dialect on.
        assert_eq!((c_id, cpp_id), ("c", "cpp"));

        let (ts, ts_id) = server_for("typescript").unwrap();
        let (tsx, tsx_id) = server_for("tsx").unwrap();
        assert_eq!(ts.name, tsx.name);
        assert_eq!((ts_id, tsx_id), ("typescript", "typescriptreact"));

        // A grammar we highlight but Debian packages no server for has none,
        // which is a normal state and not a hole.
        assert!(server_for("markdown").is_none());
        assert!(server_for("yaml").is_none());
    }

    // -----------------------------------------------------------------------
    // The inert paths
    // -----------------------------------------------------------------------

    fn project_with_file(dir: &Path, name: &str, text: &str) -> PathBuf {
        let root = dir.join("projects").join("thing");
        std::fs::create_dir_all(&root).unwrap();
        let file = root.join(name);
        std::fs::write(&file, text).unwrap();
        file
    }

    /// The `play` flavour, and every `full` build before the user installs
    /// Debian: opening a Rust file starts nothing, reports nothing, and — the
    /// part that matters on the keystroke path — leaves the engine's edit path
    /// exactly as it was.
    #[test]
    fn without_a_userland_nothing_starts_and_nothing_complains() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");

        let engine = Engine::new();
        let project = engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();

        // The document *is* registered, so that a userland appearing later can
        // start a server for a file that is already open...
        assert_eq!(engine.lsp.docs.lock().unwrap().len(), 1);
        // ...but nothing is running, so the edit path stays free.
        assert!(!engine.lsp_is_live());
        assert!(engine.lsp.servers.lock().unwrap().is_empty());

        assert_eq!(engine.lsp_version(project), 0);
        assert!(engine.lsp_servers(project).is_empty());
        assert_eq!(engine.buffer_diagnostics_version(buffer), 0);
        assert!(engine.buffer_diagnostics(buffer).rows.is_empty());
        assert_eq!(engine.lsp_diagnostics(project).totals, Counts::default());

        // A request is answered rather than refused: the UI has one code path
        // whether or not there is a server behind it.
        let id = engine.lsp_request_completion(buffer, 0, 3);
        let result = engine.lsp_request_result(id);
        assert_eq!(result.state, RequestState::Unavailable);
        assert_eq!(result.kind, RequestKind::Completion);
        assert_eq!(result.buffer_id, buffer);

        // Editing it is still just an edit.
        engine.edit(buffer, 0, 0, "// ").unwrap();
        assert!(engine.lsp.last_change.lock().unwrap().is_none());

        // And closing takes the registration with it rather than leaking one
        // entry per file the user ever opened.
        assert!(engine.close_buffer(buffer));
        assert!(engine.lsp.docs.lock().unwrap().is_empty());
    }

    /// A file opened before its project — a recent-files entry restored at
    /// launch — has no project to belong to when `open_file` runs, and would
    /// otherwise never get a server no matter how long it stayed open.
    #[test]
    fn a_file_opened_before_its_project_is_adopted() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");

        let engine = Engine::new();
        let buffer = engine.open_file(&file).unwrap();
        assert!(
            engine.lsp.docs.lock().unwrap().is_empty(),
            "nothing to belong to yet"
        );

        let project = engine.open_project(file.parent().unwrap());
        engine.adopt_open_buffers(project);
        let docs = engine.lsp.docs.lock().unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[&buffer].project, project);
        assert_eq!(docs[&buffer].server, "rust-analyzer");
        assert_eq!(docs[&buffer].language_id, "rust");
    }

    /// A language with no server is inert for a different reason, and just as
    /// quietly: nothing is registered at all.
    #[test]
    fn a_language_with_no_server_registers_nothing() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "README.md", "# hi\n");

        let engine = Engine::new();
        engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();
        assert_eq!(engine.buffer_language(buffer), Some("markdown"));
        assert!(engine.lsp.docs.lock().unwrap().is_empty());
    }

    // -----------------------------------------------------------------------
    // didChange: UTF-16, and the old text
    // -----------------------------------------------------------------------

    /// A `didChange` range is measured in UTF-16 code units, on the text as it
    /// was *before* the edit. Both halves are load-bearing and neither is
    /// visible from outside: a client that sends byte columns, or that measures
    /// the new text, desynchronizes the server silently — it goes on answering,
    /// about a document nobody has.
    #[test]
    fn a_change_is_a_utf16_range_in_the_old_text() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        // '€' is three UTF-8 bytes and one UTF-16 unit; '𝄞' is four bytes and
        // *two* UTF-16 units, which is the case a naive "chars" count gets
        // wrong as well.
        let file = project_with_file(dir.path(), "main.rs", "let a = \"€𝄞\"; let b = 1;\n");
        let engine = Engine::new();
        engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();
        // Stand in for a server having started, which is all `lsp_did_change`
        // needs to run far enough to record the change.
        engine.lsp.live.store(true, Ordering::Relaxed);

        // Replace the `1` with `2`. `let a = "€𝄞"; let b = ` is 23 UTF-16 units
        // (9 + 1 + 2 + 11) but 27 bytes.
        let text = engine.text(buffer).unwrap();
        let byte = text.find('1').unwrap();
        assert_eq!(byte, 27, "27 bytes of prefix...");
        engine.edit(buffer, byte, byte + 1, "2").unwrap();

        let change = engine.lsp.last_change.lock().unwrap().take().unwrap();
        assert_eq!(
            change.start,
            PointUtf16::new(0, 23),
            "...but 23 UTF-16 units"
        );
        assert_eq!(change.old_end, PointUtf16::new(0, 24));
        assert_eq!(change.text, "2");
        assert_eq!(change.buffer_version, engine.version(buffer).unwrap());
        // Nobody asked for whole-document sync, so the O(file) copy was not
        // made.
        assert!(change.whole.is_none());

        // ...and that is what goes on the wire.
        let event = content_changes(change, TextDocumentSyncKind::INCREMENTAL).unwrap();
        assert_eq!(
            event[0].range,
            Some(Range::new(Position::new(0, 23), Position::new(0, 24)))
        );
        assert_eq!(event[0].text, "2");
    }

    /// Undo has no edit shape to report, so it reports one range covering the
    /// whole old document — an ordinary incremental change, which is what keeps
    /// this to a single code path.
    #[test]
    fn undo_replaces_the_whole_document() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "main.rs", "fn a() {}\nfn b() {}\n");
        let engine = Engine::new();
        engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();
        engine.lsp.live.store(true, Ordering::Relaxed);

        engine.edit(buffer, 0, 0, "// ").unwrap();
        engine.lsp.last_change.lock().unwrap().take();
        engine.undo(buffer).unwrap();

        let change = engine.lsp.last_change.lock().unwrap().take().unwrap();
        assert_eq!(change.start, PointUtf16::new(0, 0));
        // The end of the document *before* the undo: three characters longer
        // on its first line, and a trailing empty line after the final "\n".
        assert_eq!(change.old_end, PointUtf16::new(2, 0));
        assert_eq!(change.text, "fn a() {}\nfn b() {}\n");
    }

    /// A server that negotiated whole-document sync gets the whole document,
    /// and one that negotiated nothing gets nothing at all.
    #[test]
    fn the_sync_kind_decides_what_a_change_looks_like() {
        let change = || TextChange {
            start: PointUtf16::new(1, 0),
            old_end: PointUtf16::new(1, 4),
            text: "x".to_owned(),
            whole: Some("everything".to_owned()),
            buffer_version: 7,
        };
        let full = content_changes(change(), TextDocumentSyncKind::FULL).unwrap();
        assert_eq!(full[0].range, None);
        assert_eq!(full[0].text, "everything");

        assert!(content_changes(change(), TextDocumentSyncKind::NONE).is_none());

        // Full sync with nothing to send at all is silence rather than a lie.
        let mut without = change();
        without.whole = None;
        assert!(content_changes(without, TextDocumentSyncKind::FULL).is_none());
    }

    // -----------------------------------------------------------------------
    // The diagnostics cache
    // -----------------------------------------------------------------------

    fn diagnostic(line: u32, character: u32, severity: lsp::DiagnosticSeverity) -> lsp::Diagnostic {
        lsp::Diagnostic {
            range: Range::new(
                Position::new(line, character),
                Position::new(line, character + 3),
            ),
            severity: Some(severity),
            source: Some("rustc".to_owned()),
            code: Some(lsp::NumberOrString::String("E0308".to_owned())),
            message: "mismatched types".to_owned(),
            ..Default::default()
        }
    }

    fn publish(
        path: &Path,
        version: Option<i32>,
        diagnostics: Vec<lsp::Diagnostic>,
    ) -> PublishDiagnosticsParams {
        PublishDiagnosticsParams {
            uri: Uri::from_file_path(path).unwrap(),
            diagnostics,
            version,
        }
    }

    /// The generation counter, which is the whole of the UI's contract: it
    /// starts at 0, moves on every publish, and never moves backwards.
    #[test]
    fn diagnostics_publish_behind_a_generation_counter() {
        let store = DiagnosticStore::default();
        let path = PathBuf::from("/p/main.rs");
        assert_eq!(store.version(1), 0);

        store.publish(
            1,
            path.clone(),
            publish(
                &path,
                None,
                vec![diagnostic(3, 4, lsp::DiagnosticSeverity::ERROR)],
            ),
        );
        assert_eq!(store.version(1), 1);
        // Another project's counter is its own.
        assert_eq!(store.version(2), 0);

        store.publish(
            1,
            path.clone(),
            publish(
                &path,
                None,
                vec![
                    diagnostic(3, 4, lsp::DiagnosticSeverity::ERROR),
                    diagnostic(9, 0, lsp::DiagnosticSeverity::WARNING),
                ],
            ),
        );
        assert_eq!(store.version(1), 2);

        let files = store.files.lock().unwrap();
        let file = files.get(&path).expect("an entry");
        assert_eq!(file.version, 2);
        assert_eq!(
            file.counts,
            Counts {
                errors: 1,
                warnings: 1,
                ..Counts::default()
            }
        );
        // Sorted by position: "next diagnostic" is a scan, not a sort.
        assert_eq!(file.rows[0].row, 3);
        assert_eq!(file.rows[1].row, 9);
        assert_eq!(file.rows[0].source.as_deref(), Some("rustc"));
        assert_eq!(file.rows[0].code.as_deref(), Some("E0308"));
        assert_eq!(file.rows[0].severity, Severity::Error);
        // UTF-16 columns, taken as the server gave them — which is what
        // `initialize` negotiated, so there is nothing to convert.
        assert_eq!((file.rows[0].col_utf16, file.rows[0].end_col_utf16), (4, 7));
        drop(files);

        // An empty publish is a retraction, and still a generation.
        store.publish(1, path.clone(), publish(&path, None, Vec::new()));
        assert_eq!(store.version(1), 3);
        assert!(store.files.lock().unwrap().is_empty());
    }

    /// A diagnostic with no severity is a warning: it is the assumption every
    /// editor makes, and the safer of the two available guesses.
    #[test]
    fn a_diagnostic_without_a_severity_is_a_warning() {
        assert_eq!(severity_of(None), Severity::Warning);
        assert_eq!(
            severity_of(Some(lsp::DiagnosticSeverity::HINT)),
            Severity::Hint
        );
        assert_eq!(
            severity_of(Some(lsp::DiagnosticSeverity::INFORMATION)),
            Severity::Info
        );
    }

    /// A publish dated with a document version we never sent describes text
    /// that no longer exists, and the UI has to be able to tell.
    #[test]
    fn a_publish_is_dated_against_what_we_last_sent() {
        let store = DiagnosticStore::default();
        let path = PathBuf::from("/p/main.rs");
        store.note_sent(&path, 5, 40);

        store.publish(
            1,
            path.clone(),
            publish(
                &path,
                Some(5),
                vec![diagnostic(0, 0, lsp::DiagnosticSeverity::ERROR)],
            ),
        );
        assert_eq!(
            store.files.lock().unwrap()[&path].buffer_version,
            Some(40),
            "a publish for the version we sent describes that buffer version"
        );

        store.publish(
            1,
            path.clone(),
            publish(
                &path,
                Some(4),
                vec![diagnostic(0, 0, lsp::DiagnosticSeverity::ERROR)],
            ),
        );
        assert_eq!(
            store.files.lock().unwrap()[&path].buffer_version,
            None,
            "an older document version dates to nothing we can name"
        );
    }

    /// What the editor actually reads: rows for one buffer, and whether they
    /// still describe it.
    #[test]
    fn a_buffer_reads_its_own_diagnostics_and_knows_when_they_are_stale() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");
        let engine = Engine::new();
        let project = engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();
        let path = engine.buffer_path(buffer).unwrap();

        assert_eq!(engine.buffer_diagnostics_version(buffer), 0);
        engine.lsp.store.note_sent(&path, 1, 0);
        engine.lsp.store.publish(
            project,
            path.clone(),
            publish(
                &path,
                Some(1),
                vec![diagnostic(0, 3, lsp::DiagnosticSeverity::ERROR)],
            ),
        );

        assert_eq!(engine.buffer_diagnostics_version(buffer), 1);
        let diagnostics = engine.buffer_diagnostics(buffer);
        assert_eq!(diagnostics.version, 1);
        assert_eq!(diagnostics.rows.len(), 1);
        assert_eq!(diagnostics.buffer_version, Some(0));
        assert!(
            !diagnostics.stale,
            "the buffer has not moved since the server saw it"
        );

        // Type; the rows now describe a document that has changed.
        engine.edit(buffer, 0, 0, "// ").unwrap();
        assert!(engine.buffer_diagnostics(buffer).stale);
        // The counter has *not* moved: nothing new was published, and a UI
        // polling it must not be woken by its own typing.
        assert_eq!(engine.buffer_diagnostics_version(buffer), 1);

        // The project summary sees the same publish, with a project-relative
        // path spelled the way the panels spell paths.
        let summary = engine.lsp_diagnostics(project);
        assert_eq!(summary.totals.errors, 1);
        assert_eq!(summary.files.len(), 1);
        assert_eq!(summary.files[0].path, "main.rs");

        // Closing the buffer does *not* retract them: a workspace-wide
        // analysis is still true about a file nobody has on screen, and a
        // diagnostics panel that emptied itself when a tab closed would be
        // worse than useless. Only an empty publish from the server clears
        // them.
        assert!(engine.close_buffer(buffer));
        assert_eq!(engine.lsp_diagnostics(project).files.len(), 1);
        assert_eq!(engine.lsp_diagnostics(project).totals.errors, 1);

        // Reopening cannot date them against the new buffer, so they read as
        // stale until the server publishes again.
        let reopened = engine.open_file(&file).unwrap();
        let diagnostics = engine.buffer_diagnostics(reopened);
        assert_eq!(diagnostics.rows.len(), 1);
        assert_eq!(diagnostics.buffer_version, None);
        assert!(diagnostics.stale);

        // The project closing is what does clear everything: the paths are
        // meaningless without the project they were relative to.
        assert!(engine.close_project(project));
        assert_eq!(engine.lsp_diagnostics(project).files.len(), 0);
        assert_eq!(engine.lsp_version(project), 0);
    }

    // -----------------------------------------------------------------------
    // Requests
    // -----------------------------------------------------------------------

    /// A newer request of the same kind retires the older one — the rule that
    /// keeps a completion popup re-asking on every keystroke from accumulating
    /// work, and the reason the server is told to stop.
    #[test]
    fn a_newer_request_supersedes_the_older_one_of_its_kind() {
        let engine = Engine::new();
        let first = engine.lsp_request_completion(1, 0, 0);
        let second = engine.lsp_request_completion(1, 0, 1);
        assert_ne!(first, second);

        assert_eq!(engine.lsp_request_version(first), 0, "forgotten");
        assert_eq!(
            engine.lsp_request_result(first).state,
            RequestState::Cancelled
        );
        assert!(engine.lsp_request_version(second) > 0);

        // A different kind has its own slot.
        let hover = engine.lsp_request_hover(1, 0, 1);
        assert!(engine.lsp_request_version(second) > 0);
        assert!(engine.lsp_request_version(hover) > 0);

        assert!(engine.lsp_cancel_request(hover));
        assert!(!engine.lsp_cancel_request(hover));
        assert_eq!(engine.lsp_request_version(hover), 0);
        assert_eq!(
            engine.lsp_request_result(hover).state,
            RequestState::Cancelled
        );
    }

    // -----------------------------------------------------------------------
    // The JSON the UI wave is going to build against
    // -----------------------------------------------------------------------

    #[test]
    fn completion_json_is_this_shape() {
        let response = CompletionResponse::List(lsp::CompletionList {
            is_incomplete: true,
            item_defaults: None,
            items: vec![
                lsp::CompletionItem {
                    label: "push".to_owned(),
                    kind: Some(lsp::CompletionItemKind::METHOD),
                    detail: Some("fn(&mut self, value: T)".to_owned()),
                    documentation: Some(Documentation::MarkupContent(lsp::MarkupContent {
                        kind: lsp::MarkupKind::Markdown,
                        value: "Appends an element.".to_owned(),
                    })),
                    text_edit: Some(CompletionTextEdit::Edit(lsp::TextEdit {
                        range: Range::new(Position::new(4, 8), Position::new(4, 10)),
                        new_text: "push(${1:value})".to_owned(),
                    })),
                    insert_text_format: Some(lsp::InsertTextFormat::SNIPPET),
                    preselect: Some(true),
                    sort_text: Some("0001push".to_owned()),
                    ..Default::default()
                },
                // Nothing but a label, which is legal and common.
                lsp::CompletionItem {
                    label: "pop".to_owned(),
                    ..Default::default()
                },
            ],
        });
        let json = completion_json(Some(response));
        assert_eq!(json["is_incomplete"], true);

        let first = &json["items"][0];
        assert_eq!(first["label"], "push");
        assert_eq!(first["kind"], "method");
        assert_eq!(first["detail"], "fn(&mut self, value: T)");
        assert_eq!(first["insert_text"], "push(${1:value})");
        assert_eq!(first["is_snippet"], true);
        assert_eq!(first["documentation"], "Appends an element.");
        assert_eq!(first["preselect"], true);
        assert_eq!(first["deprecated"], false);
        assert_eq!(first["sort_text"], "0001push");
        // Filter text falls back to the label rather than to the insert text,
        // which for a snippet would be `push(${1:value})` and match nothing.
        assert_eq!(first["filter_text"], "push");
        assert_eq!(first["edit"]["row"], 4);
        assert_eq!(first["edit"]["col_utf16"], 8);
        assert_eq!(first["edit"]["end_row"], 4);
        assert_eq!(first["edit"]["end_col_utf16"], 10);

        let second = &json["items"][1];
        assert_eq!(second["insert_text"], "pop");
        assert_eq!(second["kind"], serde_json::Value::Null);
        assert_eq!(second["edit"], serde_json::Value::Null);
        assert_eq!(second["is_snippet"], false);

        // Nothing at all is an empty list, not a null.
        let empty = completion_json(None);
        assert_eq!(empty["is_incomplete"], false);
        assert_eq!(empty["items"].as_array().map(Vec::len), Some(0));
    }

    /// A completion carrying an insert-and-replace edit applies over the
    /// identifier it replaces, not beside it.
    #[test]
    fn an_insert_and_replace_completion_uses_the_replace_range() {
        let response = CompletionResponse::Array(vec![lsp::CompletionItem {
            label: "collect".to_owned(),
            text_edit: Some(CompletionTextEdit::InsertAndReplace(
                lsp::InsertReplaceEdit {
                    new_text: "collect".to_owned(),
                    insert: Range::new(Position::new(1, 4), Position::new(1, 6)),
                    replace: Range::new(Position::new(1, 4), Position::new(1, 11)),
                },
            )),
            ..Default::default()
        }]);
        let json = completion_json(Some(response));
        assert_eq!(json["items"][0]["edit"]["end_col_utf16"], 11);
    }

    #[test]
    fn hover_json_is_one_markdown_string() {
        let json = hover_json(Some(lsp::Hover {
            contents: HoverContents::Array(vec![
                MarkedString::LanguageString(lsp::LanguageString {
                    language: "rust".to_owned(),
                    value: "fn main()".to_owned(),
                }),
                MarkedString::String("The entry point.".to_owned()),
            ]),
            range: Some(Range::new(Position::new(0, 3), Position::new(0, 7))),
        }));
        assert_eq!(
            json["contents"],
            "```rust\nfn main()\n```\n\nThe entry point."
        );
        assert_eq!(json["range"]["col_utf16"], 3);
        assert_eq!(json["range"]["end_col_utf16"], 7);

        // "nothing to say" is an empty string, never a null: the UI shows no
        // popup either way and should not have to tell them apart.
        let empty = hover_json(None);
        assert_eq!(empty["contents"], "");
        assert_eq!(empty["range"], serde_json::Value::Null);
    }

    #[test]
    fn definition_json_is_paths_and_positions() {
        let uri = Uri::from_file_path("/p/src/lib.rs").unwrap();
        let json = definition_json(Some(GotoDefinitionResponse::Scalar(lsp::Location {
            uri: uri.clone(),
            range: Range::new(Position::new(12, 3), Position::new(12, 9)),
        })));
        assert_eq!(json["targets"][0]["path"], "/p/src/lib.rs");
        assert_eq!(json["targets"][0]["row"], 12);
        assert_eq!(json["targets"][0]["col_utf16"], 3);
        assert_eq!(json["targets"][0]["end_col_utf16"], 9);

        // A link answer uses the *selection* range, which is the name rather
        // than the whole definition — where the caret should land.
        let json = definition_json(Some(GotoDefinitionResponse::Link(vec![
            lsp::LocationLink {
                origin_selection_range: None,
                target_uri: uri,
                target_range: Range::new(Position::new(12, 0), Position::new(20, 1)),
                target_selection_range: Range::new(Position::new(12, 3), Position::new(12, 9)),
            },
        ])));
        assert_eq!(json["targets"][0]["row"], 12);
        assert_eq!(json["targets"][0]["end_row"], 12);

        assert_eq!(
            definition_json(None)["targets"].as_array().map(Vec::len),
            Some(0)
        );
    }

    /// Paths in the summary are project-relative and `/`-separated, matching
    /// `TreeEntry::path`; one outside the project — a header from
    /// `/usr/include` — keeps its absolute name rather than being mangled into
    /// a relative one that points nowhere.
    #[test]
    fn summary_paths_are_project_relative_where_they_can_be() {
        let root = Path::new("/p/thing");
        assert_eq!(
            relative_path(Some(root), Path::new("/p/thing/src/main.rs")),
            "src/main.rs"
        );
        assert_eq!(
            relative_path(Some(root), Path::new("/usr/include/stdio.h")),
            "/usr/include/stdio.h"
        );
        assert_eq!(relative_path(None, Path::new("/p/x.rs")), "/p/x.rs");
    }
}
