//! ACP agents, run inside the Debian userland.
//!
//! There is no protocol code here. `agent-client-protocol = "=2.0.0"` — the
//! exact crate and pin Zed uses (zed Cargo.toml:523) — owns the JSON-RPC
//! framing, the request tables and the schema; what this module owns is
//! everything around it: how the agent process is started and stopped, where
//! its messages land, and how the answers reach a UI that must never block.
//! The per-session state machine those messages drive is `acp_thread.rs`.
//!
//! Five things shape it, four of them inherited straight from how the rest of
//! the engine already works:
//!
//! **The process comes from `guest.rs::spawn`.** An agent is a resident guest
//! program with all three pipes — exactly the seam `spawn` kept alive for
//! (guest.rs's own comment names "an ACP agent" as the expected caller). That
//! buys the identity binds, the guest environment, and above all the
//! SIGQUIT-first shutdown proot requires: cancel and close paths end in
//! `GuestProcess`'s `Drop`, never a bare kill.
//!
//! **The connection loop runs on a dedicated thread with a big stack.** Zed's
//! warning (agent_servers/src/acp.rs:930-944): inbound ACP dispatch wants
//! ~0.5 MiB of stack per message in unoptimized builds, which overflows small
//! worker stacks — and JNI-attached threads have small stacks. One thread per
//! agent, `CONNECTION_STACK_SIZE`, polling the SDK's connection future via
//! `block_on`; the SDK's own event loop runs every handler and spawned task
//! there, so nothing protocol-shaped ever executes on a JNI thread.
//!
//! **The handshake races the child's exit.** Zed selects the initialize
//! response against `child.status()` so a dead agent produces its stderr
//! rather than a timeout (acp_servers/src/acp.rs:957-1021). Same effect here,
//! shaped for threads instead of executors: a watcher thread polls
//! [`crate::guest::GuestProcess::exit_status`], and an exit closes the pipes —
//! which fails the pending initialize through the transport — while the
//! watcher records the exit and the stderr tail that explains it. The watcher
//! also enforces [`INITIALIZE_TIMEOUT`] by taking the process down, so a hung
//! agent becomes an error instead of a forever-"starting" panel.
//!
//! **Nothing the UI calls waits for the agent.** Prompting returns
//! immediately; every read is a cache read behind the per-session revision
//! counter (`acp_thread.rs`), polled exactly like `lspVersion`. The one
//! blocking JNI-visible call is starting a session, and it only blocks for a
//! `Command::spawn`.
//!
//! **The agent spends the same process budget as everything else.** One agent
//! connection at a time, reserved as [`PROCESSES_PER_AGENT`] against
//! [`guest::PROCESS_BUDGET`] via [`guest::RESERVED_FOR_AGENT`], which the
//! language-server cap reads — the revisit the P5-4 decision explicitly
//! scheduled. While an agent runs, fewer language servers do.
//!
//! Security note, because an agent's tool call is an untrusted write: the
//! `fs/read_text_file` and `fs/write_text_file` handlers confine every path
//! to the session's project root through [`resolves_inside`] — the Rust twin
//! of the Kotlin `SafeDelete.resolvesInside` guard, with the same symlink
//! rule, born of the same two data-loss defects.

use std::collections::HashMap;
use std::io::{BufRead, Write};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use agent_client_protocol::schema::ProtocolVersion;
use agent_client_protocol::schema::v1 as acp;
use agent_client_protocol::{Agent, Client, ConnectionTo, Lines, Responder};

use crate::acp_thread::{PermissionDecision, Phase, SessionThread};
use crate::guest::{self, GuestCommand};
use crate::{BufferId, BufferState, ProjectId};

/// How long the whole startup — spawn, initialize — gets before the watcher
/// takes the process down. The same figure, for the same reasons, as
/// `lsp::INITIALIZE_TIMEOUT`: an `npx`-style agent on a cold cache inside
/// proot is slow, but one silent for a minute is not going to answer.
const INITIALIZE_TIMEOUT: Duration = Duration::from_secs(60);

/// How often the watcher looks at the child.
const WATCH_INTERVAL: Duration = Duration::from_millis(100);

/// Stack for the connection thread. Zed measured ~0.5 MiB per inbound message
/// in unoptimized builds (agent_servers/src/acp.rs:934-944); host tests run
/// unoptimized, so give the same headroom `runtime.rs` gives gpui.
const CONNECTION_STACK_SIZE: usize = 8 * 1024 * 1024;

/// What one agent connection costs in processes, reserved against
/// [`guest::PROCESS_BUDGET`] while it runs.
///
/// The resident pair is proot and the agent runtime (node) —
/// [`guest::PROCESSES_PER_RUN`]. On top of that the agent runs its tools:
/// a shell command is a `sh` plus the command itself, and Claude Code will
/// happily hold one of each while it thinks. Four of burst headroom is the
/// same shape of allowance the language servers carry, sized for the deepest
/// ordinary case rather than the worst imaginable one.
pub(crate) const PROCESSES_PER_AGENT: usize = guest::PROCESSES_PER_RUN + 4;

/// How much stderr is kept for error messages. Agents log freely; the last
/// few lines are what explains an exit.
const STDERR_TAIL: usize = 4 * 1024;

/// How many updates for a not-yet-indexed session are buffered. Updates can
/// arrive between the agent answering `session/new` and our task recording
/// the id; more than this is an agent flooding before the session exists.
const MAX_EARLY_UPDATES: usize = 256;

/// What to launch, from the Kotlin side's agent configuration:
/// `{"name": "Claude Code", "argv": ["claude-code-acp"], "env": {"K": "V"}}`.
/// `argv` is the guest command line, program included — the agent must be on
/// the guest PATH (installed with `npm -g`, P6-3).
#[derive(Debug, Clone, serde::Deserialize)]
pub struct AgentSpec {
    pub name: String,
    pub argv: Vec<String>,
    #[serde(default)]
    pub env: HashMap<String, String>,
}

impl AgentSpec {
    /// One string that changes iff the launch would: how a new session knows
    /// it can reuse the running agent.
    fn key(&self) -> String {
        let mut env: Vec<_> = self.env.iter().collect();
        env.sort();
        format!("{}\u{0}{:?}\u{0}{env:?}", self.name, self.argv)
    }
}

// ---------------------------------------------------------------------------
// Engine-level state
// ---------------------------------------------------------------------------

type Sessions = Arc<Mutex<HashMap<u64, Arc<SessionHandle>>>>;
type Index = Arc<Mutex<HashMap<acp::SessionId, u64>>>;
type Buffers = Arc<Mutex<HashMap<BufferId, BufferState>>>;

/// Lock order, wherever two are wanted at once: **the agent slot, then the
/// sessions map, then one session's thread, then permissions / written /
/// stderr / index**. `init` is taken alone, or under the agent slot.
/// Nothing holds any of them across a send on the wire.
#[derive(Default)]
pub(crate) struct AcpState {
    /// The one live agent connection — one, by budget, not by accident.
    /// Starting a session with a different [`AgentSpec`] replaces it.
    agent: Mutex<Option<Arc<AgentShared>>>,
    sessions: Sessions,
    index: Index,
    next_session: AtomicU64,
    written: Arc<WrittenFiles>,
}

/// One session as the engine holds it: the state machine under its lock, and
/// the revision mirror the UI polls without taking it.
pub(crate) struct SessionHandle {
    /// Which agent connection this session belongs to
    /// ([`AgentShared::id`]).
    ///
    /// The sessions map is the engine's, shared by every agent that has ever
    /// run in this process, so "my sessions" is a filter rather than a
    /// container. Without it, an agent being replaced took the *replacement's*
    /// sessions down with it on its way out: the old connection's teardown
    /// fails every session it can see, and it could see all of them.
    owner: u64,
    revision: AtomicU64,
    thread: Mutex<SessionThread>,
    /// Permission requests waiting on the user, by tool-call id. Parked here
    /// because a `Responder` is consumed by answering, and the user answers
    /// on a JNI thread long after the handler returned.
    permissions: Mutex<HashMap<String, Responder<acp::RequestPermissionResponse>>>,
}

impl SessionHandle {
    fn new(owner: u64, thread: SessionThread) -> Self {
        let revision = thread.revision;
        SessionHandle {
            owner,
            revision: AtomicU64::new(revision),
            thread: Mutex::new(thread),
            permissions: Mutex::new(HashMap::new()),
        }
    }

    /// Run `f` under the thread lock and mirror the revision out.
    fn update<T>(&self, f: impl FnOnce(&mut SessionThread) -> T) -> T {
        let mut thread = self.thread.lock().unwrap();
        let result = f(&mut thread);
        self.revision.store(thread.revision, Ordering::Release);
        result
    }

    /// Answer every parked permission request with `cancelled` — the spec's
    /// requirement when a turn is cancelled, and the only honest answer when
    /// the agent is gone — and mark each waiting tool call cancelled.
    fn cancel_permissions(&self) {
        let parked: Vec<_> = {
            let mut permissions = self.permissions.lock().unwrap();
            permissions.drain().collect()
        };
        for (id, responder) in parked {
            self.update(|thread| {
                thread.finish_permission(&id, PermissionDecision::Cancel);
            });
            let _ = responder.respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Cancelled,
            ));
        }
    }
}

/// Files the agent has written through `fs/write_text_file`, so the UI can
/// reload the open buffers among them. Grows within a launch; the list is a
/// few paths, not a log.
#[derive(Default)]
pub(crate) struct WrittenFiles {
    version: AtomicU64,
    paths: Mutex<Vec<String>>,
}

impl WrittenFiles {
    fn record(&self, path: &Path) {
        let mut paths = self.paths.lock().unwrap();
        paths.push(path.to_string_lossy().into_owned());
        self.version.fetch_add(1, Ordering::Release);
    }

    fn json(&self, since: usize) -> serde_json::Value {
        let paths = self.paths.lock().unwrap();
        serde_json::json!({
            "total": paths.len(),
            "paths": paths.get(since.min(paths.len())..).unwrap_or(&[]),
        })
    }
}

/// Where an agent connection is in its life.
enum InitPhase {
    Starting,
    Ready(AgentInfo),
    Failed(String),
}

/// What `initialize` answered, held for the state JSON.
#[derive(Clone)]
struct AgentInfo {
    /// The configured display name.
    name: String,
    /// What the agent calls itself, when it says.
    agent_name: Option<String>,
    agent_version: Option<String>,
    /// `auth_methods`, in ACP's own wire shape — the UI renders them as
    /// choices for `acp_authenticate`.
    auth_methods: serde_json::Value,
}

/// Everything the handlers and worker threads share for one agent process.
struct AgentShared {
    /// This connection's identity, stamped onto the sessions it owns. Agents
    /// come and go within one process — a different spec replaces the running
    /// one — and the sessions map outlives all of them.
    id: u64,
    key: String,
    name: String,
    /// The way to talk to the agent, once the transport is up.
    connection: Mutex<Option<ConnectionTo<Agent>>>,
    init: Mutex<InitPhase>,
    sessions: Sessions,
    index: Index,
    written: Arc<WrittenFiles>,
    buffers: Buffers,
    /// Sessions created before `initialize` answered; drained by the
    /// connection's main task. Guarded by `init`'s lock order: taken only
    /// while `init` is held, so a session cannot fall between the phases.
    pending_sessions: Mutex<Vec<u64>>,
    /// Updates for an acp session id we have not indexed yet — see
    /// [`MAX_EARLY_UPDATES`].
    early_updates: Mutex<Vec<(acp::SessionId, acp::SessionUpdate)>>,
    stderr: Mutex<String>,
    /// Ask the watcher to take the process down.
    shutdown: AtomicBool,
    /// The process is gone — observed dead, or killed on request.
    dead: AtomicBool,
}

impl AgentShared {
    fn new(
        spec: &AgentSpec,
        sessions: Sessions,
        index: Index,
        written: Arc<WrittenFiles>,
        buffers: Buffers,
    ) -> Arc<Self> {
        static NEXT_AGENT: AtomicU64 = AtomicU64::new(1);
        Arc::new(AgentShared {
            id: NEXT_AGENT.fetch_add(1, Ordering::Relaxed),
            key: spec.key(),
            name: spec.name.clone(),
            connection: Mutex::new(None),
            init: Mutex::new(InitPhase::Starting),
            sessions,
            index,
            written,
            buffers,
            pending_sessions: Mutex::new(Vec::new()),
            early_updates: Mutex::new(Vec::new()),
            stderr: Mutex::new(String::new()),
            shutdown: AtomicBool::new(false),
            dead: AtomicBool::new(false),
        })
    }

    fn connection(&self) -> Option<ConnectionTo<Agent>> {
        self.connection.lock().unwrap().clone()
    }

    fn initialized(&self) -> bool {
        !matches!(*self.init.lock().unwrap(), InitPhase::Starting)
    }

    /// The one line of stderr worth showing a user, if there is one.
    ///
    /// **Not simply the last line, and the device is why.** proot reports a
    /// missing program in two lines — `proot error: 'claude-code-acp' not
    /// found (root = …)` and then `fatal error: see \`libproot_exec.so
    /// --help\`` — so taking the last one put a pointer to proot's own usage
    /// message in front of the user and, worse, threw away the only words that
    /// say what is wrong. The panel reads these sentences too: it offers to
    /// install Node exactly when one of them says something was not found, so
    /// picking the wrong line also silently removed the way out.
    ///
    /// So: the first line that names a missing thing, and otherwise the last
    /// non-empty one — which for an agent that started and then crashed is the
    /// end of its own traceback, the useful end.
    fn stderr_hint(&self) -> Option<String> {
        let stderr = self.stderr.lock().unwrap();
        let named_missing = stderr.lines().find(|line| {
            let line = line.to_ascii_lowercase();
            line.contains("not found") || line.contains("no such file")
        });
        named_missing
            .or_else(|| {
                stderr
                    .lines()
                    .filter(|line| !line.trim().is_empty())
                    .next_back()
            })
            .map(|line| trim_proot_detail(line.trim()))
            .filter(|line| !line.is_empty())
    }

    /// What the agent said about itself, or `base` when it said nothing.
    ///
    /// The agent's own words win outright rather than being appended: the
    /// caller's `base` is a description of the *transport* giving up — "the
    /// connection closed", with the SDK's multi-line JSON in it — and beside
    /// "'claude-code-acp' not found" it is noise. The lsp module made the same
    /// call for the same reason (lsp.rs's `start_server`, which shows the
    /// captured line and falls back to the error only when there is none).
    fn with_stderr(&self, base: &str) -> String {
        self.stderr_hint().unwrap_or_else(|| base.to_owned())
    }

    /// One of *our* sessions. A session belonging to an agent that has since
    /// been replaced is not ours to touch, however loudly its id is quoted at
    /// us by a message still in flight on the old wire.
    fn session(&self, id: u64) -> Option<Arc<SessionHandle>> {
        self.sessions
            .lock()
            .unwrap()
            .get(&id)
            .filter(|handle| handle.owner == self.id)
            .cloned()
    }

    /// Every session this connection owns, id included.
    fn own_sessions(&self) -> Vec<(u64, Arc<SessionHandle>)> {
        self.sessions
            .lock()
            .unwrap()
            .iter()
            .filter(|(_, handle)| handle.owner == self.id)
            .map(|(id, handle)| (*id, handle.clone()))
            .collect()
    }

    fn session_for_acp_id(&self, id: &acp::SessionId) -> Option<(u64, Arc<SessionHandle>)> {
        let our_id = *self.index.lock().unwrap().get(id)?;
        Some((our_id, self.session(our_id)?))
    }

    /// The agent process is gone, however it went: every session it served is
    /// over, and every parked question is answered.
    ///
    /// Strictly *its* sessions. This runs when the connection loop ends, which
    /// for a replaced agent is after its successor is already serving — and
    /// failing everything in sight would take the successor's sessions down
    /// with it.
    fn agent_gone(&self, message: String) {
        {
            let mut init = self.init.lock().unwrap();
            if matches!(*init, InitPhase::Starting) {
                *init = InitPhase::Failed(message.clone());
            }
        }
        *self.connection.lock().unwrap() = None;
        let mine = self.own_sessions();
        for (_, handle) in &mine {
            handle.cancel_permissions();
            handle.update(|thread| {
                if thread.phase != Phase::Unavailable {
                    thread.fail(message.clone());
                }
            });
        }
        // The acp-id → our-id mapping is this connection's; the next agent
        // issues its own ids and must not inherit these.
        let ours: Vec<acp::SessionId> = mine
            .iter()
            .filter_map(|(_, handle)| handle.thread.lock().unwrap().acp_id.clone())
            .collect();
        let mut index = self.index.lock().unwrap();
        for id in ours {
            index.remove(&id);
        }
    }

    // -- inbound handlers; all run on the connection thread's event loop -----

    fn on_session_update(&self, notification: acp::SessionNotification) {
        match self.session_for_acp_id(&notification.session_id) {
            Some((_, handle)) => handle.update(|thread| thread.apply_update(notification.update)),
            None => {
                // Between the agent answering `session/new` and our task
                // recording the id, updates for it are already legal; hold
                // them and let `session_ready` replay them in order.
                let mut early = self.early_updates.lock().unwrap();
                if early.len() < MAX_EARLY_UPDATES {
                    early.push((notification.session_id, notification.update));
                } else {
                    log::debug!("acp: dropping update for unknown session (buffer full)");
                }
            }
        }
    }

    fn on_permission(
        &self,
        request: acp::RequestPermissionRequest,
        responder: Responder<acp::RequestPermissionResponse>,
    ) {
        let Some((_, handle)) = self.session_for_acp_id(&request.session_id) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown session")),
            );
            return;
        };
        let tool_call_id = request.tool_call.tool_call_id.0.to_string();
        handle.update(|thread| thread.begin_permission(request.tool_call, request.options));
        // Two live requests for one tool call cannot both be answered; the
        // newer one is the agent's current question, so the older is answered
        // `cancelled` on its way out.
        if let Some(previous) = handle
            .permissions
            .lock()
            .unwrap()
            .insert(tool_call_id, responder)
        {
            let _ = previous.respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Cancelled,
            ));
        }
    }

    fn on_write_text_file(
        &self,
        request: acp::WriteTextFileRequest,
        responder: Responder<acp::WriteTextFileResponse>,
    ) {
        let Some((_, handle)) = self.session_for_acp_id(&request.session_id) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown session")),
            );
            return;
        };
        let root = handle.thread.lock().unwrap().root.clone();
        // The untrusted-write case the symlink guard exists for: an agent
        // handed a path must not be able to reach outside the project, not
        // even through a symlink a previous tool call created.
        if !resolves_inside(&root, &request.path) {
            log::warn!(
                "acp: refused write outside the project: {}",
                request.path.display()
            );
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("path is outside the project")),
            );
            return;
        }
        let result = (|| -> std::io::Result<()> {
            if let Some(parent) = request.path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            crate::file::write_atomically_io(&request.path, &request.content)
        })();
        match result {
            Ok(()) => {
                // Flag any open buffer the usual way, and put the path where
                // the UI's poll will find it — the UI reloads through
                // `reloadBuffer`, which keeps highlighting, LSP sync and the
                // undo history correct, exactly as an external edit does.
                //
                // Canonical, because that is the spelling buffers hold
                // (`Engine::open_file`): an agent writing through an
                // in-project symlink alias would otherwise miss the very
                // buffer it just changed.
                let path = std::fs::canonicalize(&request.path).unwrap_or(request.path.clone());
                crate::file::note_disk_changes(&self.buffers, &[path.clone()]);
                self.written.record(&path);
                let _ = responder.respond(acp::WriteTextFileResponse::new());
            }
            Err(err) => {
                let _ = responder
                    .respond_with_error(acp::Error::internal_error().data(err.to_string()));
            }
        }
    }

    fn on_read_text_file(
        &self,
        request: acp::ReadTextFileRequest,
        responder: Responder<acp::ReadTextFileResponse>,
    ) {
        let Some((_, handle)) = self.session_for_acp_id(&request.session_id) else {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("unknown session")),
            );
            return;
        };
        let root = handle.thread.lock().unwrap().root.clone();
        if !resolves_inside(&root, &request.path) {
            let _ = responder.respond_with_error(
                acp::Error::invalid_params().data(serde_json::json!("path is outside the project")),
            );
            return;
        }
        // An open buffer's text, not the disk's: what the agent must see is
        // what the user sees, unsaved edits included — Zed's
        // `handle_read_text_file` reads the buffer for the same reason
        // (agent_servers/src/acp.rs:4787-4812).
        let canonical = std::fs::canonicalize(&request.path).unwrap_or(request.path.clone());
        let buffer_text = self
            .buffers
            .lock()
            .unwrap()
            .values()
            .find(|state| state.file_path() == Some(&canonical))
            .map(|state| state.buffer.text());
        let text = match buffer_text {
            Some(text) => Ok(text),
            None => std::fs::read_to_string(&request.path),
        };
        match text {
            Ok(text) => {
                let text = clip_lines(&text, request.line, request.limit);
                let _ = responder.respond(acp::ReadTextFileResponse::new(text));
            }
            Err(err) => {
                let _ = responder
                    .respond_with_error(acp::Error::internal_error().data(err.to_string()));
            }
        }
    }
}

/// Drop the machine detail proot puts after its own message.
///
/// `proot error: 'claude-code-acp' not found (root = /data/user/0/…, cwd =
/// /data/user/0/…, $PATH=/usr/local/sbin:…)` — the sentence is the first
/// clause and the parenthetical is three absolute paths, which on a phone is
/// six wrapped lines of panel pushing the way out of trouble off the screen.
/// It is in logcat either way. Only proot's own shape is trimmed, and only
/// when the sentence survives it: an agent's message is never touched.
fn trim_proot_detail(line: &str) -> String {
    let Some(head) = line.split(" (root = ").next() else {
        return line.to_owned();
    };
    if head.len() < line.len() && !head.trim().is_empty() {
        return head.trim().to_owned();
    }
    line.to_owned()
}

/// `line` (1-based) and `limit` applied the way the protocol describes them.
///
/// Sliced rather than split-and-rejoined: `lines()` throws away whether each
/// line ended `\n` or `\r\n` and whether the last one ended at all, and an
/// agent that reads a window of a CRLF file and writes it back would silently
/// rewrite the file's line endings.
fn clip_lines(text: &str, line: Option<u32>, limit: Option<u32>) -> String {
    if line.is_none() && limit.is_none() {
        return text.to_owned();
    }
    let skip = line.map(|l| l.saturating_sub(1) as usize).unwrap_or(0);
    let take = limit.map(|l| l as usize).unwrap_or(usize::MAX);

    // Byte offset just past the nth newline, or the end of the text.
    let after_newlines = |count: usize| -> usize {
        if count == 0 {
            return 0;
        }
        let mut seen = 0;
        for (offset, byte) in text.bytes().enumerate() {
            if byte == b'\n' {
                seen += 1;
                if seen == count {
                    return offset + 1;
                }
            }
        }
        text.len()
    };

    let start = after_newlines(skip);
    if start >= text.len() {
        return String::new();
    }
    let end = match take.checked_add(skip) {
        Some(through) if take != usize::MAX => after_newlines(through),
        _ => text.len(),
    };
    text[start..end.max(start)].to_owned()
}

// ---------------------------------------------------------------------------
// The symlink guard, engine-side
// ---------------------------------------------------------------------------

/// Whether `path`, once every existing component and symlink is resolved,
/// still lies inside `root` (which must itself be canonical).
///
/// The Rust twin of Kotlin's `SafeDelete.resolvesInside`, guarding the same
/// attack: a symlink inside the project pointing out of it makes an innocent
/// looking path land somewhere unrelated, and `File.isDirectory` /
/// `create_dir_all` follow symlinks without asking. For a path that does not
/// fully exist yet — an agent creating a new file in a new directory — the
/// deepest existing ancestor is what gets resolved, and the not-yet-existing
/// remainder must be plain names.
pub(crate) fn resolves_inside(root: &Path, path: &Path) -> bool {
    use std::path::Component;
    if !path.is_absolute() {
        return false;
    }
    // `..` and `.` in the un-created remainder cannot be resolved against
    // anything real, so they are refused outright rather than reasoned about.
    if path
        .components()
        .any(|c| matches!(c, Component::ParentDir | Component::CurDir))
    {
        return false;
    }
    let mut existing = path.to_path_buf();
    while !existing.exists() {
        match existing.parent() {
            Some(parent) => existing = parent.to_path_buf(),
            None => return false,
        }
    }
    match existing.canonicalize() {
        Ok(resolved) => resolved.starts_with(root),
        Err(_) => false,
    }
}

// ---------------------------------------------------------------------------
// Process + transport plumbing
// ---------------------------------------------------------------------------

/// Spawn the agent in the guest and wire its three pipes to a connection
/// thread. Returns false when the guest could not even be spawned.
fn start_agent(
    shared: Arc<AgentShared>,
    userland: Arc<guest::Userland>,
    spec: &AgentSpec,
    root: &Path,
) -> bool {
    let argv = spec.argv.iter().map(std::ffi::OsString::from).collect();
    let mut command = GuestCommand::new(format!("acp:{}", spec.name), argv).workdir(root);
    for (key, value) in &spec.env {
        command = command.env(key, value);
    }
    let Some(mut process) = guest::spawn(&userland, &command) else {
        return false;
    };
    // Paired with the subtraction in the watcher's tail below, which is the
    // one place this connection's processes stop being spent.
    guest::RESERVED_FOR_AGENT.fetch_add(PROCESSES_PER_AGENT, Ordering::Relaxed);

    let stdin = process.take_stdin().expect("spawn pipes stdin");
    let stdout = process.take_stdout().expect("spawn pipes stdout");
    let stderr = process.take_stderr().expect("spawn pipes stderr");

    // Writer: outgoing lines, newline-delimited JSON-RPC. Its channel closing
    // (the connection ended) closes the agent's stdin, which is the polite
    // half of shutdown.
    let (out_tx, out_rx) = std::sync::mpsc::channel::<String>();
    spawn_named("acp-write", move || {
        let mut stdin = stdin;
        for line in out_rx {
            log::debug!("acp → {line}");
            if stdin.write_all(line.as_bytes()).is_err()
                || stdin.write_all(b"\n").is_err()
                || stdin.flush().is_err()
            {
                return;
            }
        }
    });

    // Reader: incoming lines into the transport's stream. EOF ends the
    // stream, which ends the connection future, which ends its thread.
    let (in_tx, in_rx) = futures::channel::mpsc::unbounded::<std::io::Result<String>>();
    spawn_named("acp-read", move || {
        let mut reader = std::io::BufReader::new(stdout);
        let mut line = String::new();
        loop {
            line.clear();
            match reader.read_line(&mut line) {
                Ok(0) => return,
                Ok(_) => {
                    let trimmed = line.trim_end_matches(['\n', '\r']);
                    log::debug!("acp ← {trimmed}");
                    if in_tx.unbounded_send(Ok(trimmed.to_owned())).is_err() {
                        return;
                    }
                }
                Err(err) => {
                    let _ = in_tx.unbounded_send(Err(err));
                    return;
                }
            }
        }
    });

    // Stderr: the tail is what explains a failed launch; the log gets it all,
    // because a panic in the agent is otherwise invisible.
    {
        let shared = shared.clone();
        spawn_named("acp-stderr", move || {
            let mut reader = std::io::BufReader::new(stderr);
            let mut line = String::new();
            loop {
                line.clear();
                match reader.read_line(&mut line) {
                    Ok(0) | Err(_) => return,
                    Ok(_) => {
                        let trimmed = line.trim_end_matches(['\n', '\r']);
                        log::warn!("acp agent stderr: {trimmed}");
                        let mut tail = shared.stderr.lock().unwrap();
                        tail.push_str(trimmed);
                        tail.push('\n');
                        if tail.len() > STDERR_TAIL {
                            let cut = tail.len() - STDERR_TAIL;
                            let cut = tail
                                .char_indices()
                                .find(|(i, _)| *i >= cut)
                                .map(|(i, _)| i)
                                .unwrap_or(0);
                            tail.drain(..cut);
                        }
                    }
                }
            }
        });
    }

    // The watcher owns the process: it is the one place a `GuestProcess` is
    // dropped, and its Drop is the SIGQUIT-first shutdown. See the module doc
    // on racing the handshake against exit.
    {
        let shared = shared.clone();
        let started = Instant::now();
        spawn_named("acp-watch", move || {
            loop {
                if shared.shutdown.load(Ordering::Acquire) {
                    log::info!("acp: stopping agent \"{}\"", shared.name);
                    break;
                }
                if let Some(status) = process.exit_status() {
                    shared.agent_gone(shared.with_stderr(&format!("agent exited ({status})")));
                    break;
                }
                if !shared.initialized() && started.elapsed() > INITIALIZE_TIMEOUT {
                    shared.agent_gone("the agent did not answer initialize in time".to_owned());
                    break;
                }
                thread::sleep(WATCH_INTERVAL);
            }
            // Dropping is the shutdown: SIGQUIT first, SIGKILL as the last
            // resort, tracees included (guest.rs::terminate). Never a bare
            // kill — proot ignores SIGTERM and orphans its tracees otherwise.
            drop(process);
            // Ours only — a replacement agent may already be holding its own.
            guest::RESERVED_FOR_AGENT.fetch_sub(PROCESSES_PER_AGENT, Ordering::Relaxed);
            shared.dead.store(true, Ordering::Release);
        });
    }

    // The connection loop, on the one thread sized for it.
    {
        let shared = shared.clone();
        let sink = futures::sink::unfold(out_tx, |tx, line: String| async move {
            tx.send(line).map_err(|_| {
                std::io::Error::new(std::io::ErrorKind::BrokenPipe, "agent stdin closed")
            })?;
            Ok::<_, std::io::Error>(tx)
        });
        let transport = Lines::new(sink, in_rx);
        thread::Builder::new()
            .name("acp-connection".to_owned())
            .stack_size(CONNECTION_STACK_SIZE)
            .spawn(move || {
                futures::executor::block_on(run_connection(shared.clone(), transport));
                // However the loop ended — clean EOF, error, shutdown — the
                // sessions must not be left waiting on a wire nobody holds.
                shared.agent_gone(shared.with_stderr("the agent connection closed"));
                shared.shutdown.store(true, Ordering::Release);
            })
            .expect("failed to spawn the acp connection thread");
    }
    true
}

fn spawn_named(name: &str, f: impl FnOnce() + Send + 'static) {
    thread::Builder::new()
        .name(name.to_owned())
        .spawn(f)
        .unwrap_or_else(|err| panic!("failed to spawn {name}: {err}"));
}

/// The SDK connection: Zed's handler set minus what we do not advertise
/// (terminals, elicitations), with the same newline-delimited transport
/// (agent_servers/src/acp.rs:678-765).
async fn run_connection(
    shared: Arc<AgentShared>,
    transport: impl agent_client_protocol::ConnectTo<Client> + 'static,
) {
    let permission_shared = shared.clone();
    let write_shared = shared.clone();
    let read_shared = shared.clone();
    let update_shared = shared.clone();
    let main_shared = shared.clone();

    let result = Client
        .builder()
        .name("conquest-code")
        .on_receive_request(
            async move |request: acp::RequestPermissionRequest,
                        responder: Responder<acp::RequestPermissionResponse>,
                        _cx| {
                permission_shared.on_permission(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::WriteTextFileRequest,
                        responder: Responder<acp::WriteTextFileResponse>,
                        _cx| {
                write_shared.on_write_text_file(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |request: acp::ReadTextFileRequest,
                        responder: Responder<acp::ReadTextFileResponse>,
                        _cx| {
                read_shared.on_read_text_file(request, responder);
                Ok(())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_notification(
            async move |notification: acp::SessionNotification, _cx| {
                update_shared.on_session_update(notification);
                Ok(())
            },
            agent_client_protocol::on_receive_notification!(),
        )
        .connect_with(transport, async move |cx| agent_main(main_shared, cx).await)
        .await;
    if let Err(err) = result {
        log::info!("acp: connection ended: {err}");
    }
}

/// The connection's own startup: initialize, then serve session requests
/// until the transport closes.
async fn agent_main(shared: Arc<AgentShared>, cx: ConnectionTo<Agent>) -> Result<(), acp::Error> {
    *shared.connection.lock().unwrap() = Some(cx.clone());

    // The capabilities Zed advertises, minus terminals — without the
    // capability an agent runs commands inside its own process, which is the
    // right shape while the terminal dock knows nothing about agents. Minus
    // elicitations too, which are unstable; an agent must not offer what we
    // cannot render.
    let capabilities = acp::ClientCapabilities::new().fs(acp::FileSystemCapabilities::new()
        .read_text_file(true)
        .write_text_file(true));
    let initialize = cx
        .send_request(
            acp::InitializeRequest::new(ProtocolVersion::V1)
                .client_capabilities(capabilities)
                .client_info(acp::Implementation::new(
                    "conquest-code",
                    crate::ENGINE_VERSION,
                )),
        )
        .block_task()
        .await;

    let response = match initialize {
        Ok(response) => response,
        Err(err) => {
            let message = shared.with_stderr(&format!("the agent failed to initialize: {err}"));
            log::info!("acp: {message}");
            fail_startup(&shared, message);
            // Returning ends the connection; the watcher then takes the
            // process down.
            shared.shutdown.store(true, Ordering::Release);
            return Ok(());
        }
    };
    if response.protocol_version < ProtocolVersion::V1 {
        fail_startup(
            &shared,
            format!(
                "the agent speaks ACP protocol version {:?}, which is too old",
                response.protocol_version
            ),
        );
        shared.shutdown.store(true, Ordering::Release);
        return Ok(());
    }

    let info = AgentInfo {
        name: shared.name.clone(),
        agent_name: response.agent_info.as_ref().map(|info| info.name.clone()),
        agent_version: response
            .agent_info
            .as_ref()
            .map(|info| info.version.clone())
            .filter(|version| !version.is_empty()),
        auth_methods: serde_json::to_value(&response.auth_methods)
            .unwrap_or(serde_json::Value::Null),
    };
    log::info!(
        "acp: \"{}\" initialized (agent {:?} {:?})",
        shared.name,
        info.agent_name,
        info.agent_version
    );
    // Ready first, then drain: a session arriving between the two lands in
    // neither limbo — `acp_start_session` checks the phase under this lock.
    let pending: Vec<u64> = {
        let mut init = shared.init.lock().unwrap();
        *init = InitPhase::Ready(info);
        shared.pending_sessions.lock().unwrap().drain(..).collect()
    };
    for id in pending {
        let task_shared = shared.clone();
        let task_cx = cx.clone();
        let _ = cx.spawn(async move {
            create_session(task_shared, task_cx, id).await;
            Ok(())
        });
    }

    // Serve until the transport closes; handlers and spawned tasks do the
    // rest. (Zed pends the same way, agent_servers/src/acp.rs:757-763.)
    futures::future::pending::<()>().await;
    Ok(())
}

fn fail_startup(shared: &AgentShared, message: String) {
    {
        let mut init = shared.init.lock().unwrap();
        *init = InitPhase::Failed(message.clone());
    }
    shared.agent_gone(message);
}

/// Ask the agent for a session and wire the answer up. Runs as a task on the
/// connection's event loop.
async fn create_session(shared: Arc<AgentShared>, cx: ConnectionTo<Agent>, our_id: u64) {
    let Some(handle) = shared.session(our_id) else {
        return;
    };
    let root = handle.thread.lock().unwrap().root.clone();
    let result = cx
        .send_request(acp::NewSessionRequest::new(root))
        .block_task()
        .await;
    // The session may have been closed while `session/new` was in flight;
    // indexing it then would leave a mapping nothing can ever reach.
    if shared.session(our_id).is_none() {
        return;
    }
    match result {
        Ok(response) => {
            let acp_id = response.session_id.clone();
            shared.index.lock().unwrap().insert(acp_id.clone(), our_id);
            handle.update(|thread| thread.ready(acp_id.clone(), response.modes));
            // Updates that raced the response are still in arrival order.
            let early: Vec<acp::SessionUpdate> = {
                let mut buffered = shared.early_updates.lock().unwrap();
                let (ours, rest): (Vec<_>, Vec<_>) =
                    buffered.drain(..).partition(|(id, _)| *id == acp_id);
                *buffered = rest;
                ours.into_iter().map(|(_, update)| update).collect()
            };
            if !early.is_empty() {
                handle.update(|thread| {
                    for update in early {
                        thread.apply_update(update);
                    }
                });
            }
            // A prompt typed while the session was starting goes out now. Its
            // entry is already on screen, so nothing is pushed for it here.
            let queued = handle.update(|thread| thread.take_queued_prompt());
            if let Some(text) = queued {
                start_prompt(&shared, &cx, our_id, text, false);
            }
        }
        Err(err) if err.code == acp::ErrorCode::AuthRequired => {
            handle.update(|thread| {
                thread.auth_required("the agent wants you to sign in first".to_owned())
            });
        }
        Err(err) => {
            handle.update(|thread| thread.fail(shared.with_stderr(&format!("{err}"))));
        }
    }
}

/// Kick off one prompt turn as a task on the connection loop. `push` says
/// whether the optimistic user entry still needs pushing (a queued follow-up
/// does; a prompt from the UI was already pushed on the caller's thread).
fn start_prompt(
    shared: &Arc<AgentShared>,
    cx: &ConnectionTo<Agent>,
    our_id: u64,
    text: String,
    push: bool,
) {
    let task_shared = shared.clone();
    let task_cx = cx.clone();
    let _ = cx.spawn(async move {
        run_prompt(task_shared, task_cx, our_id, text, push).await;
        // Never propagate an error: a task error tears the whole connection
        // down (SDK contract), and a failed turn is a session-level fact.
        Ok(())
    });
}

async fn run_prompt(
    shared: Arc<AgentShared>,
    cx: ConnectionTo<Agent>,
    our_id: u64,
    text: String,
    push: bool,
) {
    let mut text = text;
    let mut push = push;
    loop {
        let Some(handle) = shared.session(our_id) else {
            return;
        };
        let Some(acp_id) = handle.thread.lock().unwrap().acp_id.clone() else {
            return;
        };
        if push {
            handle.update(|thread| thread.push_user_message(&text));
        }
        let result = cx
            .send_request(acp::PromptRequest::new(
                acp_id,
                vec![acp::ContentBlock::from(text.clone())],
            ))
            .block_task()
            .await;

        // However the turn ended, no permission question may outlive it: the
        // spec requires cancelled answers on cancellation, and a settled turn
        // has no open questions.
        handle.cancel_permissions();
        let next = handle.update(|thread| match result {
            Ok(response) => thread.end_turn(response.stop_reason),
            Err(err) if err.code == acp::ErrorCode::AuthRequired => {
                thread.auth_required("the agent wants you to sign in first".to_owned());
                None
            }
            Err(err) => {
                thread.fail_turn(shared.with_stderr(&format!("{err}")));
                None
            }
        });
        match next {
            Some(follow_up) => {
                text = follow_up;
                // `take_queued_prompt` has already made sure the entry showing
                // it is on screen; pushing again would double it.
                push = false;
            }
            None => return,
        }
    }
}

// ---------------------------------------------------------------------------
// Engine surface — what the JNI layer calls
// ---------------------------------------------------------------------------

impl crate::Engine {
    /// Start (or join) the configured agent and open a session for `project`.
    /// Returns a session id to poll; the session reports its own progress —
    /// `starting` until the agent answers, `unavailable` with a sentence if
    /// it never does. Blocks only for a process spawn.
    ///
    /// `spec_json` is an [`AgentSpec`]. A spec different from the running
    /// agent's replaces it — one agent process at a time, by budget.
    ///
    /// **`Err` means the caller is wrong, not that the agent is missing.** A
    /// spec that is not JSON or a project that does not exist is a bug on the
    /// Kotlin side and has nothing to show a user; everything the *user* can
    /// act on — no userland, an agent that will not spawn — comes back as a
    /// session id whose state carries the sentence, so the panel has one code
    /// path for "here is what went wrong" and never a second kind of failure
    /// to render.
    pub fn acp_start_session(&self, project: ProjectId, spec_json: &str) -> Result<u64, String> {
        let spec: AgentSpec =
            serde_json::from_str(spec_json).map_err(|err| format!("bad agent spec: {err}"))?;
        if spec.argv.is_empty() {
            return Err("the agent has no command".to_owned());
        }
        let Some(root) = self.project_root(project) else {
            return Err("unknown project".to_owned());
        };
        let root = std::fs::canonicalize(&root).unwrap_or(root);

        let id = self.acp.next_session.fetch_add(1, Ordering::Relaxed) + 1;

        // No guest to run an agent in: a `full` build before Debian is
        // installed, or the `play` flavour, which has no agent panel at all.
        // Owner 0, because no agent owns it and none ever will — agent ids
        // start at 1.
        let Some(userland) = self.userland().filter(|userland| userland.is_installed()) else {
            let handle = Arc::new(SessionHandle::new(0, SessionThread::new(project, root)));
            handle.update(|thread| thread.fail("the Linux userland is not installed".to_owned()));
            self.acp.sessions.lock().unwrap().insert(id, handle);
            return Ok(id);
        };

        // One agent at a time: reuse it when the spec matches, replace it
        // when it does not. A dying agent is not reusable — `shutdown` is set
        // the moment anything decides it is over, while `dead` lags it by the
        // watcher's poll and proot's grace, and a session handed to an agent
        // in between would simply never be answered by anyone.
        let key = spec.key();
        let mut slot = self.acp.agent.lock().unwrap();
        let reusable = slot
            .as_ref()
            .filter(|shared| {
                shared.key == key
                    && !shared.dead.load(Ordering::Acquire)
                    && !shared.shutdown.load(Ordering::Acquire)
            })
            .cloned();
        let shared = match reusable {
            Some(shared) => shared,
            None => {
                if let Some(old) = slot.take() {
                    shutdown_agent(&old);
                }
                let shared = AgentShared::new(
                    &spec,
                    self.acp.sessions.clone(),
                    self.acp.index.clone(),
                    self.acp.written.clone(),
                    self.buffers.clone(),
                );
                if !start_agent(shared.clone(), userland, &spec, &root) {
                    drop(slot);
                    let handle = Arc::new(SessionHandle::new(0, SessionThread::new(project, root)));
                    handle
                        .update(|thread| thread.fail("the agent could not be started".to_owned()));
                    self.acp.sessions.lock().unwrap().insert(id, handle);
                    return Ok(id);
                }
                *slot = Some(shared.clone());
                shared
            }
        };
        // Stamped with its owner before anybody can see it, so a teardown
        // running concurrently on the *previous* agent cannot claim it.
        let handle = Arc::new(SessionHandle::new(
            shared.id,
            SessionThread::new(project, root.clone()),
        ));
        self.acp.sessions.lock().unwrap().insert(id, handle.clone());
        drop(slot);

        // Hand the session to the connection: directly when it is up, via the
        // pending list when initialize is still in flight. Checked under the
        // init lock so it cannot fall between the two.
        let init = shared.init.lock().unwrap();
        match &*init {
            InitPhase::Starting => shared.pending_sessions.lock().unwrap().push(id),
            InitPhase::Ready(_) => match shared.connection() {
                Some(cx) => {
                    let task_shared = shared.clone();
                    let task_cx = cx.clone();
                    let _ = cx.spawn(async move {
                        create_session(task_shared, task_cx, id).await;
                        Ok(())
                    });
                }
                // Initialized, but the wire has gone since — the agent died
                // after answering. Say so rather than leaving the session
                // `starting` for ever with nothing on its way to it.
                None => {
                    drop(init);
                    handle.update(|thread| {
                        thread.fail(shared.with_stderr("the agent connection closed"))
                    });
                    return Ok(id);
                }
            },
            InitPhase::Failed(message) => {
                let message = message.clone();
                drop(init);
                handle.update(|thread| thread.fail(message));
                return Ok(id);
            }
        }
        Ok(id)
    }

    /// The session's revision counter — poll it exactly like `lspVersion`.
    /// 0 for an id the engine has forgotten.
    ///
    /// A live session starts at 1 and every change moves it, so a poller that
    /// stores what it read and compares never misses the first transition —
    /// see the note on `SessionThread::revision` for the bug that is.
    pub fn acp_session_version(&self, session: u64) -> u64 {
        self.acp
            .sessions
            .lock()
            .unwrap()
            .get(&session)
            .map(|handle| handle.revision.load(Ordering::Acquire))
            .unwrap_or(0)
    }

    /// Everything but the entries, as JSON — see `SessionThread::state_json`.
    /// `"null"` for a forgotten id.
    pub fn acp_session_state(&self, session: u64) -> String {
        let Some(handle) = self.session_handle(session) else {
            return "null".to_owned();
        };
        let agent = {
            let slot = self.acp.agent.lock().unwrap();
            match slot.as_ref().map(|shared| shared.init.lock().unwrap()) {
                Some(init) => match &*init {
                    InitPhase::Ready(info) => serde_json::json!({
                        "name": info.name,
                        "agent_name": info.agent_name,
                        "agent_version": info.agent_version,
                        "auth_methods": info.auth_methods,
                    }),
                    InitPhase::Starting => serde_json::json!({"starting": true}),
                    InitPhase::Failed(message) => serde_json::json!({"error": message}),
                },
                None => serde_json::Value::Null,
            }
        };
        let thread = handle.thread.lock().unwrap();
        thread.state_json(agent).to_string()
    }

    /// The entries whose revision is newer than `since`, as JSON — see
    /// `SessionThread::entries_json` for the delta contract.
    pub fn acp_entries_since(&self, session: u64, since: u64) -> String {
        let Some(handle) = self.session_handle(session) else {
            return "null".to_owned();
        };
        let thread = handle.thread.lock().unwrap();
        thread.entries_json(since).to_string()
    }

    /// Send a prompt. Returns immediately; the turn streams in behind the
    /// version counter. A prompt while a turn is running interrupts it — the
    /// running turn is cancelled and this prompt follows it, which is Zed's
    /// follow-up behaviour. False for a forgotten id or a dead session.
    pub fn acp_prompt(&self, session: u64, text: &str) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let Some(shared) = self.acp.agent.lock().unwrap().clone() else {
            return false;
        };
        enum Route {
            Refused,
            Queue,
            Interrupt(acp::SessionId),
            Send,
        }
        let route = handle.update(|thread| match thread.phase {
            Phase::Unavailable => Route::Refused,
            // Queued, and *shown* — `queue_prompt` pushes the entry now, so a
            // send while the agent is still starting or still answering looks
            // like a send rather than like a key that did nothing.
            Phase::Starting => {
                thread.queue_prompt(text);
                Route::Queue
            }
            Phase::Running => {
                thread.queue_prompt(text);
                match &thread.acp_id {
                    Some(id) => Route::Interrupt(id.clone()),
                    None => Route::Queue,
                }
            }
            Phase::Ready => {
                thread.push_user_message(text);
                Route::Send
            }
        });
        match route {
            Route::Refused => false,
            Route::Queue => true,
            Route::Interrupt(acp_id) => {
                // The running turn ends with `cancelled`; `run_prompt` then
                // takes the queued text and sends it.
                if let Some(cx) = shared.connection() {
                    let _ = cx.send_notification(acp::CancelNotification::new(acp_id));
                }
                true
            }
            Route::Send => {
                let Some(cx) = shared.connection() else {
                    handle.update(|thread| {
                        thread.fail_turn("the agent connection is gone".to_owned())
                    });
                    return false;
                };
                start_prompt(&shared, &cx, session, text.to_owned(), false);
                true
            }
        }
    }

    /// Stop the running turn. The agent answers the prompt with `cancelled`,
    /// which is what settles the session — this only asks, marks what is
    /// already known to be over, and answers every open permission question
    /// with `cancelled`, as the spec requires.
    pub fn acp_cancel(&self, session: u64) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let acp_id = handle.update(|thread| {
            // A queued follow-up is cancelled too, and its entry goes with it:
            // a transcript must not show a message the agent never received.
            thread.discard_queued_prompt();
            thread.cancel_pending_tool_calls();
            thread.acp_id.clone()
        });
        handle.cancel_permissions();
        if let (Some(acp_id), Some(shared)) = (acp_id, self.acp.agent.lock().unwrap().clone()) {
            if let Some(cx) = shared.connection() {
                let _ = cx.send_notification(acp::CancelNotification::new(acp_id));
            }
        }
        true
    }

    /// The user answered a permission prompt. `option_id` is one of the ids
    /// the entry offered. False if nothing was waiting under that tool call.
    pub fn acp_respond_permission(&self, session: u64, tool_call: &str, option_id: &str) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let Some(responder) = handle.permissions.lock().unwrap().remove(tool_call) else {
            return false;
        };
        let decision = handle.update(|thread| {
            let kind = thread.entries.iter().rev().find_map(|entry| {
                if let crate::acp_thread::EntryBody::ToolCall(call) = &entry.body {
                    if call.id == tool_call {
                        return call
                            .options
                            .iter()
                            .find(|option| option.option_id.0.as_ref() == option_id)
                            .map(|option| option.kind);
                    }
                }
                None
            });
            let decision = match kind {
                Some(
                    acp::PermissionOptionKind::AllowOnce | acp::PermissionOptionKind::AllowAlways,
                ) => PermissionDecision::Allow,
                Some(
                    acp::PermissionOptionKind::RejectOnce | acp::PermissionOptionKind::RejectAlways,
                ) => PermissionDecision::Reject,
                // An id we never offered, or a kind the schema grew since:
                // treat unknown as allow only if the agent recognises the id;
                // safer to reject nothing and cancel nothing — refuse below.
                _ => return None,
            };
            thread.finish_permission(tool_call, decision);
            Some(decision)
        });
        match decision {
            Some(_) => responder
                .respond(acp::RequestPermissionResponse::new(
                    acp::RequestPermissionOutcome::Selected(acp::SelectedPermissionOutcome::new(
                        acp::PermissionOptionId::new(option_id.to_owned()),
                    )),
                ))
                .is_ok(),
            None => {
                // Unknown option: put the question back rather than answer
                // with something the user did not choose.
                handle
                    .permissions
                    .lock()
                    .unwrap()
                    .insert(tool_call.to_owned(), responder);
                false
            }
        }
    }

    /// Switch the session's mode (Claude Code's "default" / "acceptEdits" /
    /// "plan"…). The change lands when the agent confirms it.
    pub fn acp_set_mode(&self, session: u64, mode_id: &str) -> bool {
        let Some(handle) = self.session_handle(session) else {
            return false;
        };
        let Some(acp_id) = handle.thread.lock().unwrap().acp_id.clone() else {
            return false;
        };
        let Some(shared) = self.acp.agent.lock().unwrap().clone() else {
            return false;
        };
        let Some(cx) = shared.connection() else {
            return false;
        };
        let mode = acp::SessionModeId::new(mode_id.to_owned());
        let session_id = session;
        let task_shared = shared.clone();
        let request = acp::SetSessionModeRequest::new(acp_id, mode.clone());
        let task_cx = cx.clone();
        cx.spawn(async move {
            if task_cx.send_request(request).block_task().await.is_ok() {
                if let Some(handle) = task_shared.session(session_id) {
                    handle.update(|thread| {
                        if let Some(modes) = &mut thread.modes {
                            modes.current_mode_id = mode;
                        }
                        // Without this the confirmed mode change is invisible
                        // until something else happens to move the counter.
                        thread.bump();
                    });
                }
            }
            Ok(())
        })
        .is_ok()
    }

    /// Run one of the agent's advertised auth methods, then retry
    /// `session/new` for every session that was waiting on it.
    pub fn acp_authenticate(&self, session: u64, method_id: &str) -> bool {
        let Some(shared) = self.acp.agent.lock().unwrap().clone() else {
            return false;
        };
        let Some(cx) = shared.connection() else {
            return false;
        };
        let _ = session;
        let request = acp::AuthenticateRequest::new(acp::AuthMethodId::new(method_id.to_owned()));
        let task_shared = shared.clone();
        let task_cx = cx.clone();
        cx.spawn(async move {
            match task_cx.send_request(request).block_task().await {
                Ok(_) => {
                    let waiting: Vec<u64> = task_shared
                        .own_sessions()
                        .into_iter()
                        .filter(|(_, handle)| handle.thread.lock().unwrap().needs_auth)
                        .map(|(id, _)| id)
                        .collect();
                    for id in waiting {
                        create_session(task_shared.clone(), task_cx.clone(), id).await;
                    }
                }
                Err(err) => {
                    let message = format!("authentication failed: {err}");
                    for (_, handle) in task_shared.own_sessions() {
                        handle.update(|thread| {
                            if thread.needs_auth {
                                thread.error = Some(message.clone());
                                // A failed sign-in is the whole of what
                                // happened; a session parked on `needs_auth`
                                // has nothing else coming to move the counter
                                // for it, so without this the button does
                                // nothing visible at all.
                                thread.bump();
                            }
                        });
                    }
                }
            }
            Ok(())
        })
        .is_ok()
    }

    /// Close a session and forget it. Closing the last session stops the
    /// agent — through the SIGQUIT-first path, never a bare kill.
    pub fn acp_close_session(&self, session: u64) -> bool {
        let Some(handle) = self.acp.sessions.lock().unwrap().remove(&session) else {
            return false;
        };
        handle.cancel_permissions();
        let acp_id = handle.thread.lock().unwrap().acp_id.clone();
        if let Some(acp_id) = &acp_id {
            self.acp.index.lock().unwrap().remove(acp_id);
        }
        let mut slot = self.acp.agent.lock().unwrap();
        if let Some(shared) = slot.as_ref() {
            // Tell the agent the turn is over, so it stops spending tokens.
            if let (Some(acp_id), Some(cx)) = (acp_id, shared.connection()) {
                let _ = cx.send_notification(acp::CancelNotification::new(acp_id));
            }
            // Only *its* sessions: the map may still hold sessions of an agent
            // being replaced, and those are not a reason to keep this one
            // alive — nor is this one's emptiness a reason to stop that one.
            if shared.own_sessions().is_empty() {
                shutdown_agent(shared);
                *slot = None;
            }
        }
        true
    }

    /// Files the agent has written, from `since` onwards, as
    /// `{"total": n, "paths": [...]}` — absolute paths. The UI reloads the
    /// open buffers among them (through `reloadBuffer`, so the edit is
    /// undoable and every layer hears about it) and passes the new total back
    /// next time.
    pub fn acp_written_files(&self, since: u64) -> String {
        self.acp.written.json(since as usize).to_string()
    }

    fn session_handle(&self, session: u64) -> Option<Arc<SessionHandle>> {
        self.acp.sessions.lock().unwrap().get(&session).cloned()
    }
}

/// Stop an agent: flag the watcher, which drops the process, which is the
/// SIGQUIT-first shutdown. The connection future ends when the pipes close.
fn shutdown_agent(shared: &Arc<AgentShared>) {
    shared.shutdown.store(true, Ordering::Release);
    *shared.connection.lock().unwrap() = None;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::acp_thread::{EntryBody, ToolStatus};
    use agent_client_protocol::ConnectTo;
    use std::path::PathBuf;

    // -------------------------------------------------------------------
    // The symlink guard
    // -------------------------------------------------------------------

    #[test]
    fn resolves_inside_confines_real_and_future_paths() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::fs::create_dir(root.join("src")).unwrap();
        std::fs::write(root.join("src/main.rs"), "x").unwrap();

        // Existing file, new file, and a new file in a new directory.
        assert!(resolves_inside(&root, &root.join("src/main.rs")));
        assert!(resolves_inside(&root, &root.join("src/new.rs")));
        assert!(resolves_inside(&root, &root.join("brand/new/dir/file.rs")));

        // Relative and dot-riddled paths are refused outright.
        assert!(!resolves_inside(&root, Path::new("src/main.rs")));
        assert!(!resolves_inside(&root, &root.join("src/../../etc/passwd")));

        // A path simply outside.
        assert!(!resolves_inside(&root, Path::new("/etc/passwd")));
    }

    #[cfg(unix)]
    #[test]
    fn resolves_inside_refuses_a_symlink_escape() {
        let dir = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        std::os::unix::fs::symlink(outside.path(), root.join("vendor")).unwrap();

        // The attack SafeDelete documents: `vendor` points out of the
        // project, so `vendor/anything` is not project ground.
        assert!(!resolves_inside(&root, &root.join("vendor/pwned.txt")));
        assert!(!resolves_inside(
            &root,
            &root.join("vendor/sub/dir/pwned.txt")
        ));

        // A symlink that stays inside is fine.
        std::fs::create_dir(root.join("real")).unwrap();
        std::os::unix::fs::symlink(root.join("real"), root.join("alias")).unwrap();
        assert!(resolves_inside(&root, &root.join("alias/file.txt")));
    }

    #[test]
    fn clip_lines_is_one_based_and_clamped() {
        let text = "a\nb\nc\nd";
        assert_eq!(clip_lines(text, None, None), text);
        assert_eq!(clip_lines(text, Some(2), None), "b\nc\nd");
        // Line 3 ends with a newline in the source, so the window does too:
        // it is a slice of the file, not a re-rendering of it.
        assert_eq!(clip_lines(text, Some(2), Some(2)), "b\nc\n");
        assert_eq!(clip_lines(text, Some(9), None), "");
        assert_eq!(clip_lines(text, None, Some(1)), "a\n");
        // The last line has no newline after it, and does not acquire one.
        assert_eq!(clip_lines(text, Some(4), Some(1)), "d");
    }

    /// The window is a slice, so the file's own line endings survive it.
    ///
    /// `lines()` + `join("\n")` would hand a CRLF file back as LF, and an
    /// agent that reads a window and writes it back would silently rewrite
    /// every ending in the file it touched.
    #[test]
    fn clip_lines_keeps_the_files_own_line_endings() {
        let crlf = "one\r\ntwo\r\nthree\r\n";
        assert_eq!(clip_lines(crlf, Some(2), Some(1)), "two\r\n");
        assert_eq!(clip_lines(crlf, Some(2), None), "two\r\nthree\r\n");
        // A trailing newline is not a fifth line to be skipped past.
        assert_eq!(clip_lines("a\n", Some(1), Some(1)), "a\n");
        assert_eq!(clip_lines("a\n", Some(2), Some(1)), "");
    }

    // -------------------------------------------------------------------
    // Two agents in one process: the sessions map outlives both.
    // -------------------------------------------------------------------

    fn shared_for_test(sessions: &Sessions, index: &Index) -> Arc<AgentShared> {
        AgentShared::new(
            &AgentSpec {
                name: "test".to_owned(),
                argv: vec!["test".to_owned()],
                env: HashMap::new(),
            },
            sessions.clone(),
            index.clone(),
            Arc::new(WrittenFiles::default()),
            Arc::default(),
        )
    }

    /// Replacing an agent must not take the *replacement's* sessions with it.
    ///
    /// The old connection's loop ends — and runs its teardown — after the new
    /// agent is already serving, and the sessions map it can see is the
    /// engine's, holding both. Without ownership it failed everything in
    /// sight, so configuring a different agent killed the session you had just
    /// opened with it.
    #[test]
    fn a_departing_agent_leaves_the_new_agents_sessions_alone() {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let old = shared_for_test(&sessions, &index);
        let new = shared_for_test(&sessions, &index);
        assert_ne!(old.id, new.id, "each connection is its own owner");

        let root = PathBuf::from("/proj");
        let old_session = Arc::new(SessionHandle::new(
            old.id,
            SessionThread::new(1, root.clone()),
        ));
        let new_session = Arc::new(SessionHandle::new(new.id, SessionThread::new(1, root)));
        old_session.update(|thread| thread.ready(acp::SessionId::new("old-1"), None));
        new_session.update(|thread| thread.ready(acp::SessionId::new("new-1"), None));
        sessions.lock().unwrap().insert(1, old_session.clone());
        sessions.lock().unwrap().insert(2, new_session.clone());
        index
            .lock()
            .unwrap()
            .insert(acp::SessionId::new("old-1"), 1);
        index
            .lock()
            .unwrap()
            .insert(acp::SessionId::new("new-1"), 2);

        old.agent_gone("the old agent exited".to_owned());

        assert_eq!(
            old_session.thread.lock().unwrap().phase,
            Phase::Unavailable,
            "its own session is over"
        );
        assert_eq!(
            new_session.thread.lock().unwrap().phase,
            Phase::Ready,
            "the replacement's session is untouched"
        );
        // And the acp-id mapping it leaves behind is its own only: the next
        // agent issues its own ids and must not inherit these.
        let index = index.lock().unwrap();
        assert!(!index.contains_key(&acp::SessionId::new("old-1")));
        assert!(index.contains_key(&acp::SessionId::new("new-1")));
    }

    /// The sentence a user is shown when an agent will not start.
    ///
    /// Found on the emulator, not in a test: proot says a missing program in
    /// two lines and the *second* is a pointer to its own usage message, so
    /// taking the last line showed "fatal error: see `libproot_exec.so
    /// --help`" — which tells the user nothing and, because the panel decides
    /// whether to offer the Node install by looking for "not found" in this
    /// very sentence, also took away the way out.
    #[test]
    fn the_sentence_for_a_missing_agent_is_the_one_that_names_it() {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let shared = shared_for_test(&sessions, &index);

        // Verbatim from the device (logcat, 2026-08-18).
        *shared.stderr.lock().unwrap() = concat!(
            "proot error: 'claude-code-acp' not found (root = /data/.../debian, ",
            "cwd = /data/.../projects/Spoon-Knife, $PATH=/usr/local/sbin:/usr/bin)\n",
            "fatal error: see `libproot_exec.so --help`.\n",
        )
        .to_owned();

        let sentence = shared.with_stderr("the agent failed to initialize: transport closed");
        assert!(
            sentence.contains("'claude-code-acp' not found"),
            "the sentence must name what is missing: {sentence}"
        );
        assert!(
            !sentence.contains("--help"),
            "and must not be proot's own usage pointer: {sentence}"
        );
        // The panel keys the "install Node" offer off exactly this.
        assert!(sentence.to_ascii_lowercase().contains("not found"));
        // proot's parenthetical — three absolute paths — is six wrapped lines
        // of panel on a phone and says nothing the user can act on.
        assert_eq!(sentence, "proot error: 'claude-code-acp' not found");
    }

    /// The trim knows exactly one shape — proot's — and leaves everything else
    /// alone, an agent's own parentheses included.
    #[test]
    fn only_proots_own_detail_is_trimmed() {
        assert_eq!(
            trim_proot_detail("proot error: 'x' not found (root = /a, cwd = /b, $PATH=/c)"),
            "proot error: 'x' not found"
        );
        let agents_own = "Error: config invalid (expected an object) at line 3";
        assert_eq!(trim_proot_detail(agents_own), agents_own);
        assert_eq!(trim_proot_detail(""), "");
    }

    /// An agent that started and then died says something useful at the *end*
    /// of its own output, so that is what a hint falls back to.
    #[test]
    fn a_crashing_agent_is_quoted_from_the_end_of_its_traceback() {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let shared = shared_for_test(&sessions, &index);
        *shared.stderr.lock().unwrap() =
            "Traceback:\n  at thing (index.js:4)\nError: no API key configured\n".to_owned();
        assert_eq!(
            shared.with_stderr("the agent exited"),
            "Error: no API key configured"
        );

        // And with nothing on stderr at all, the caller's own words stand.
        *shared.stderr.lock().unwrap() = "  \n\n".to_owned();
        assert_eq!(
            shared.with_stderr("the agent exited (1)"),
            "the agent exited (1)"
        );
    }

    /// A message still in flight on the old wire names a session id the new
    /// agent now owns. It must not be able to touch it.
    #[test]
    fn a_late_message_from_the_old_agent_cannot_reach_the_new_agents_session() {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let old = shared_for_test(&sessions, &index);
        let new = shared_for_test(&sessions, &index);

        let handle = Arc::new(SessionHandle::new(
            new.id,
            SessionThread::new(1, PathBuf::from("/proj")),
        ));
        handle.update(|thread| thread.ready(acp::SessionId::new("s1"), None));
        sessions.lock().unwrap().insert(7, handle.clone());
        index.lock().unwrap().insert(acp::SessionId::new("s1"), 7);

        assert!(old.session(7).is_none(), "not the old agent's to see");
        assert!(old.session_for_acp_id(&acp::SessionId::new("s1")).is_none());
        assert!(new.session(7).is_some(), "the owner still sees it");

        // A session update arriving late on the old connection is buffered as
        // "unknown session" rather than applied to somebody else's.
        old.on_session_update(acp::SessionNotification::new(
            acp::SessionId::new("s1"),
            acp::SessionUpdate::AgentMessageChunk(acp::ContentChunk::new(acp::ContentBlock::from(
                "from the dead agent".to_owned(),
            ))),
        ));
        assert!(handle.thread.lock().unwrap().entries.is_empty());
    }

    // -------------------------------------------------------------------
    // A full conversation over a real wire, with a fake agent on the other
    // end built from the same SDK. No process anywhere: the transport is a
    // pair of in-memory line channels, which is exactly what the pipes carry.
    // -------------------------------------------------------------------

    type LineTx = futures::channel::mpsc::UnboundedSender<std::io::Result<String>>;
    type LineRx = futures::channel::mpsc::UnboundedReceiver<std::io::Result<String>>;

    fn line_sink(
        tx: LineTx,
    ) -> impl futures::Sink<String, Error = std::io::Error> + Send + 'static {
        futures::sink::unfold(tx, |tx, line: String| async move {
            tx.unbounded_send(Ok(line))
                .map_err(|_| std::io::Error::new(std::io::ErrorKind::BrokenPipe, "peer gone"))?;
            Ok::<_, std::io::Error>(tx)
        })
    }

    fn transport_pair() -> (
        Lines<impl futures::Sink<String, Error = std::io::Error> + Send, LineRx>,
        Lines<impl futures::Sink<String, Error = std::io::Error> + Send, LineRx>,
    ) {
        let (c2a_tx, c2a_rx) = futures::channel::mpsc::unbounded();
        let (a2c_tx, a2c_rx) = futures::channel::mpsc::unbounded();
        (
            Lines::new(line_sink(c2a_tx), a2c_rx),
            Lines::new(line_sink(a2c_tx), c2a_rx),
        )
    }

    /// A minimal but honest ACP agent: answers initialize and session/new,
    /// and on prompt streams a message, runs a tool call through a permission
    /// request, writes a file through fs/write_text_file when allowed, and
    /// ends the turn.
    async fn fake_agent(
        transport: impl ConnectTo<Agent> + 'static,
        file_to_write: PathBuf,
    ) -> Result<(), acp::Error> {
        Agent
            .builder()
            .name("fake-agent")
            .on_receive_request(
                async move |_request: acp::InitializeRequest,
                            responder: Responder<acp::InitializeResponse>,
                            _cx| {
                    responder.respond(acp::InitializeResponse::new(ProtocolVersion::V1))?;
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                async move |_request: acp::NewSessionRequest,
                            responder: Responder<acp::NewSessionResponse>,
                            _cx| {
                    responder.respond(acp::NewSessionResponse::new(acp::SessionId::new("s1")))?;
                    Ok(())
                },
                agent_client_protocol::on_receive_request!(),
            )
            .on_receive_request(
                {
                    let file_to_write = file_to_write.clone();
                    async move |request: acp::PromptRequest,
                                responder: Responder<acp::PromptResponse>,
                                cx: ConnectionTo<Client>| {
                        let session = request.session_id.clone();
                        let file_to_write = file_to_write.clone();
                        let task_cx = cx.clone();
                        cx.spawn(async move {
                            let update = |u| acp::SessionNotification::new(session.clone(), u);
                            task_cx.send_notification(update(
                                acp::SessionUpdate::AgentMessageChunk(acp::ContentChunk::new(
                                    acp::ContentBlock::from("editing now".to_owned()),
                                )),
                            ))?;
                            let call = acp::ToolCall::new(acp::ToolCallId::new("t1"), "Write file")
                                .kind(acp::ToolKind::Edit)
                                .status(acp::ToolCallStatus::Pending);
                            task_cx.send_notification(update(acp::SessionUpdate::ToolCall(
                                call.clone(),
                            )))?;
                            let outcome = task_cx
                                .send_request(acp::RequestPermissionRequest::new(
                                    session.clone(),
                                    acp::ToolCallUpdate::from(call),
                                    vec![
                                        acp::PermissionOption::new(
                                            acp::PermissionOptionId::new("yes"),
                                            "Allow",
                                            acp::PermissionOptionKind::AllowOnce,
                                        ),
                                        acp::PermissionOption::new(
                                            acp::PermissionOptionId::new("no"),
                                            "Deny",
                                            acp::PermissionOptionKind::RejectOnce,
                                        ),
                                    ],
                                ))
                                .block_task()
                                .await?;
                            let allowed = matches!(
                                outcome.outcome,
                                acp::RequestPermissionOutcome::Selected(selected)
                                    if selected.option_id.0.as_ref() == "yes"
                            );
                            if allowed {
                                task_cx
                                    .send_request(acp::WriteTextFileRequest::new(
                                        session.clone(),
                                        file_to_write.clone(),
                                        "written by the agent\n",
                                    ))
                                    .block_task()
                                    .await?;
                                task_cx.send_notification(update(
                                    acp::SessionUpdate::ToolCallUpdate(acp::ToolCallUpdate::new(
                                        acp::ToolCallId::new("t1"),
                                        acp::ToolCallUpdateFields::new()
                                            .status(acp::ToolCallStatus::Completed)
                                            .content(vec![acp::ToolCallContent::Diff(
                                                acp::Diff::new(
                                                    file_to_write.clone(),
                                                    "written by the agent\n",
                                                )
                                                .old_text("old\n".to_owned()),
                                            )]),
                                    )),
                                ))?;
                            }
                            responder
                                .respond(acp::PromptResponse::new(acp::StopReason::EndTurn))?;
                            Ok(())
                        })?;
                        Ok(())
                    }
                },
                agent_client_protocol::on_receive_request!(),
            )
            .connect_to(transport)
            .await
    }

    /// Spin until `ready` answers, or fail with `what` after five seconds.
    #[track_caller]
    fn wait_for(what: &str, mut ready: impl FnMut() -> bool) {
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline {
            if ready() {
                return;
            }
            thread::sleep(Duration::from_millis(5));
        }
        panic!("timed out waiting for {what}");
    }

    struct Rig {
        shared: Arc<AgentShared>,
        handle: Arc<SessionHandle>,
    }

    /// The production connection stack over an in-memory wire, with one
    /// session pending — everything `start_agent` builds except the process.
    fn rig(root: &Path, agent_file: &Path) -> Rig {
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let written = Arc::new(WrittenFiles::default());
        let buffers: Buffers = Arc::default();
        let spec = AgentSpec {
            name: "fake".to_owned(),
            argv: vec!["fake".to_owned()],
            env: HashMap::new(),
        };
        let shared = AgentShared::new(&spec, sessions.clone(), index, written, buffers);
        let handle = Arc::new(SessionHandle::new(
            shared.id,
            SessionThread::new(1, root.to_path_buf()),
        ));
        sessions.lock().unwrap().insert(7, handle.clone());
        shared.pending_sessions.lock().unwrap().push(7);

        let (client_transport, agent_transport) = transport_pair();
        let file = agent_file.to_path_buf();
        thread::Builder::new()
            .name("test-fake-agent".to_owned())
            .stack_size(CONNECTION_STACK_SIZE)
            .spawn(move || {
                let _ = futures::executor::block_on(fake_agent(agent_transport, file));
            })
            .unwrap();
        {
            let shared = shared.clone();
            thread::Builder::new()
                .name("test-acp-connection".to_owned())
                .stack_size(CONNECTION_STACK_SIZE)
                .spawn(move || {
                    futures::executor::block_on(run_connection(shared, client_transport));
                })
                .unwrap();
        }
        Rig { shared, handle }
    }

    #[test]
    fn a_whole_conversation_with_permission_gating_and_a_guarded_write() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        let file = root.join("notes.txt");
        std::fs::write(&file, "old\n").unwrap();

        let rig = rig(&root, &file);
        let Rig { shared, handle } = &rig;

        // The handshake runs and the pending session comes up on its own.
        wait_for("the session to be ready", || {
            handle.thread.lock().unwrap().phase == Phase::Ready
        });

        // Prompt. The fake agent streams a message, opens a tool call and
        // asks permission.
        let cx = shared.connection().expect("connection is up");
        handle.update(|thread| thread.push_user_message("edit the file"));
        start_prompt(shared, &cx, 7, "edit the file".to_owned(), false);
        wait_for("the permission prompt", || {
            handle.permissions.lock().unwrap().contains_key("t1")
        });
        {
            let thread = handle.thread.lock().unwrap();
            let waiting = thread.entries.iter().any(|entry| {
                matches!(&entry.body, EntryBody::ToolCall(call)
                    if call.status == ToolStatus::WaitingForConfirmation && call.options.len() == 2)
            });
            assert!(waiting, "the tool call shows its options");
        }

        // Allow it, the production way: decide by option kind, respond, and
        // let the agent write through fs/write_text_file.
        let responder = handle.permissions.lock().unwrap().remove("t1").unwrap();
        handle.update(|thread| {
            thread.finish_permission("t1", PermissionDecision::Allow);
        });
        responder
            .respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Selected(acp::SelectedPermissionOutcome::new(
                    acp::PermissionOptionId::new("yes"),
                )),
            ))
            .unwrap();

        wait_for("the turn to end", || {
            handle.thread.lock().unwrap().phase == Phase::Ready
                && handle.thread.lock().unwrap().stop_reason.is_some()
        });

        // The write landed on disk, inside the project, and was recorded for
        // the UI's reload poll.
        assert_eq!(
            std::fs::read_to_string(&file).unwrap(),
            "written by the agent\n"
        );
        assert_eq!(shared.written.json(0)["total"], 1);

        // The transcript holds the whole exchange: user, assistant, and a
        // completed tool call carrying diff rows.
        let thread = handle.thread.lock().unwrap();
        assert_eq!(thread.stop_reason.as_deref(), Some("end_turn"));
        let mut kinds = Vec::new();
        for entry in &thread.entries {
            match &entry.body {
                EntryBody::User { .. } => kinds.push("user"),
                EntryBody::Assistant { .. } => kinds.push("assistant"),
                EntryBody::ToolCall(call) => {
                    kinds.push("tool_call");
                    assert_eq!(call.status, ToolStatus::Completed);
                    assert!(call.content.iter().any(|content| matches!(
                        content,
                        crate::acp_thread::ToolContent::Diff { diff }
                            if diff.path == "notes.txt" && !diff.hunks.is_empty()
                    )));
                }
            }
        }
        assert_eq!(kinds, vec!["user", "assistant", "tool_call"]);
    }

    #[test]
    fn a_write_outside_the_project_is_refused_at_the_wire() {
        let dir = tempfile::tempdir().unwrap();
        let root = std::fs::canonicalize(dir.path()).unwrap();
        let outside = tempfile::tempdir().unwrap();
        let target = outside.path().join("escape.txt");

        let rig = rig(&root, &target);
        let Rig { shared, handle } = &rig;
        wait_for("the session to be ready", || {
            handle.thread.lock().unwrap().phase == Phase::Ready
        });
        let cx = shared.connection().expect("connection is up");
        handle.update(|thread| thread.push_user_message("try to escape"));
        start_prompt(shared, &cx, 7, "try to escape".to_owned(), false);
        wait_for("the permission prompt", || {
            handle.permissions.lock().unwrap().contains_key("t1")
        });
        let responder = handle.permissions.lock().unwrap().remove("t1").unwrap();
        responder
            .respond(acp::RequestPermissionResponse::new(
                acp::RequestPermissionOutcome::Selected(acp::SelectedPermissionOutcome::new(
                    acp::PermissionOptionId::new("yes"),
                )),
            ))
            .unwrap();

        wait_for("the turn to settle", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase != Phase::Running || thread.error.is_some()
        });
        // The wire refused it: nothing outside the project was written.
        assert!(!target.exists());
        assert_eq!(shared.written.json(0)["total"], 0);
    }

    // -------------------------------------------------------------------
    // The process path: real guest spawns. What matters is that a death is
    // noticed and explained with stderr, and that the budget reservation is
    // kept honestly across agents coming and going.
    // -------------------------------------------------------------------

    /// [`guest::RESERVED_FOR_AGENT`] is process-wide, and the suite runs its
    /// tests on threads of one process — so the two tests that assert on it
    /// take turns. Nothing else in the suite spawns an agent.
    #[cfg(unix)]
    static BUDGET_TESTS: Mutex<()> = Mutex::new(());

    #[cfg(unix)]
    fn budget_guard() -> std::sync::MutexGuard<'static, ()> {
        // A test that panicked while holding it poisoned it; the next one
        // still wants to run.
        BUDGET_TESTS.lock().unwrap_or_else(|err| err.into_inner())
    }

    #[cfg(unix)]
    #[test]
    fn a_dead_agent_reports_its_stderr_and_frees_the_budget() {
        let _guard = budget_guard();
        let dir = tempfile::tempdir().unwrap();
        let userland = Arc::new(guest::testing::fake_userland(dir.path()));
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        let spec = AgentSpec {
            name: "doomed".to_owned(),
            argv: vec![
                "/bin/sh".to_owned(),
                "-c".to_owned(),
                "echo command not found >&2; exit 127".to_owned(),
            ],
            env: HashMap::new(),
        };
        let shared = AgentShared::new(
            &spec,
            sessions.clone(),
            index,
            Arc::new(WrittenFiles::default()),
            Arc::default(),
        );
        let handle = Arc::new(SessionHandle::new(
            shared.id,
            SessionThread::new(1, dir.path().to_path_buf()),
        ));
        sessions.lock().unwrap().insert(1, handle.clone());
        shared.pending_sessions.lock().unwrap().push(1);

        assert!(start_agent(shared.clone(), userland, &spec, dir.path()));
        assert_eq!(
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed),
            PROCESSES_PER_AGENT
        );

        wait_for("the session to fail with the agent's stderr", || {
            let thread = handle.thread.lock().unwrap();
            thread.phase == Phase::Unavailable
                && thread
                    .error
                    .as_deref()
                    .is_some_and(|error| error.contains("command not found"))
        });
        wait_for("the budget to be released", || {
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed) == 0
        });
    }

    /// Replacing an agent overlaps the two — the new one starts while the old
    /// one's watcher is still inside proot's SIGQUIT grace — and the
    /// departing watcher must give back only *its* share.
    ///
    /// It used to `store(0)`, so the moment an agent was replaced the
    /// language-server cap silently went back from 2 to 4: the exact
    /// over-subscription the reservation exists to prevent, and invisible
    /// until something got killed.
    #[cfg(unix)]
    #[test]
    fn a_departing_agent_gives_back_only_its_own_share_of_the_budget() {
        let _guard = budget_guard();
        let dir = tempfile::tempdir().unwrap();
        let userland = Arc::new(guest::testing::fake_userland(dir.path()));
        let sessions: Sessions = Arc::default();
        let index: Index = Arc::default();
        // Long-lived: it says nothing and stays up until it is stopped, which
        // is all this test needs of an "agent".
        let spec = AgentSpec {
            name: "quiet".to_owned(),
            argv: vec!["/bin/sh".to_owned(), "-c".to_owned(), "sleep 30".to_owned()],
            env: HashMap::new(),
        };

        let old = shared_for_test(&sessions, &index);
        assert!(start_agent(
            old.clone(),
            userland.clone(),
            &spec,
            dir.path()
        ));
        assert_eq!(
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed),
            PROCESSES_PER_AGENT
        );

        // The replacement arrives before the old one is gone, which is the
        // real sequence: `acp_start_session` asks the old one to stop and
        // starts the new one without waiting for the death.
        shutdown_agent(&old);
        let new = shared_for_test(&sessions, &index);
        assert!(start_agent(new.clone(), userland, &spec, dir.path()));
        assert_eq!(
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed),
            2 * PROCESSES_PER_AGENT,
            "both are spending processes while they overlap",
        );

        wait_for("the old agent to finish leaving", || {
            old.dead.load(Ordering::Acquire)
        });
        assert_eq!(
            guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed),
            PROCESSES_PER_AGENT,
            "the survivor's reservation is still held",
        );

        shutdown_agent(&new);
        wait_for("the new agent to stop", || new.dead.load(Ordering::Acquire));
        assert_eq!(guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed), 0);
    }
}
