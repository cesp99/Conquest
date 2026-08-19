//! One agent session's state, UI-free.
//!
//! This is our equivalent of Zed's `acp_thread` state machine. Zed's own
//! crate cannot be vendored — `AcpThread` is a gpui entity whose entries hold
//! `Entity<Markdown>` values and whose diffs are `multi_buffer` excerpts
//! (crates/acp_thread/src/acp_thread.rs:860-879) — so the *rules* are
//! reimplemented here over plain data, with the Zed source cited at each rule.
//! What the UI reads is JSON: every entry carries a revision stamp, so a
//! polling client fetches only the entries that moved rather than the whole
//! conversation (the same "results only grow, plus in-place updates" contract
//! `project_search.rs` established).
//!
//! Nothing in this file talks to a process or a wire. `acp.rs` owns the
//! connection and calls in here under the session lock; every function is
//! synchronous and total, which is what makes the state machine testable on a
//! host with no agent anywhere near it.
//!
//! Units, for the record (CONVENTIONS says every contract must state them):
//! entry text is UTF-8 markdown handed to Kotlin as JSON strings; a tool-call
//! location's `line` is passed through as the agent sent it, which Zed treats
//! as a 0-based row (acp_thread.rs:1150-1152); diff hunks are 1-based rows in
//! the shape `git_patch::FileDiff` already speaks, so the diff view renders an
//! agent's edit with the exact code that renders `git diff`.

use std::path::{Path, PathBuf};

use agent_client_protocol::schema::v1 as acp;
use imara_diff::{Algorithm, Diff, InternedInput};

use crate::ProjectId;
use crate::git_patch::{FileDiff, PatchHunk, PatchLine};

/// Context lines around a diff hunk — git's own `-U3`, which is what every
/// other diff in the app shows.
const DIFF_CONTEXT: u32 = 3;

/// Beyond this, the two sides of a tool-call diff are not card material. The
/// same ceiling as `git_diff::MAX_DIFF_BYTES`, for the same reason: a diff of
/// a generated megabyte is seconds of work nobody will scroll.
const MAX_DIFF_BYTES: usize = 8 * 1024 * 1024;

/// Where a session is in its life. Serialized snake_case into the state JSON.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    /// The agent process is being spawned, initialized, or asked for a
    /// session. Nothing can be prompted yet.
    Starting,
    /// A session exists and the agent is waiting for a prompt.
    Ready,
    /// A prompt turn is in flight.
    Running,
    /// The session cannot continue — the agent exited, refused, or wants
    /// authentication. `error` says which.
    Unavailable,
}

/// A tool call's state, Zed's `ToolCallStatus` (acp_thread.rs:1263-1285)
/// minus the gpui channel inside `WaitingForConfirmation` — the parked
/// responder lives in `acp.rs`, keyed by the tool-call id.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ToolStatus {
    Pending,
    WaitingForConfirmation,
    InProgress,
    Completed,
    Failed,
    /// The user rejected it at the permission prompt.
    Rejected,
    /// The turn it belonged to was cancelled while it was still moving.
    Canceled,
}

impl From<acp::ToolCallStatus> for ToolStatus {
    fn from(status: acp::ToolCallStatus) -> Self {
        match status {
            acp::ToolCallStatus::Pending => ToolStatus::Pending,
            acp::ToolCallStatus::InProgress => ToolStatus::InProgress,
            acp::ToolCallStatus::Completed => ToolStatus::Completed,
            acp::ToolCallStatus::Failed => ToolStatus::Failed,
            // The schema enum is non-exhaustive; Zed defaults the unknown to
            // Pending too (acp_thread.rs:1287-1297).
            _ => ToolStatus::Pending,
        }
    }
}

/// What granting permission resumes a tool call *to*: a call that was still
/// `Pending` when it asked starts running the moment it is allowed. Zed's
/// `status_after_permission_grant` (acp_thread.rs:1311-1317).
fn status_after_grant(current: ToolStatus) -> ToolStatus {
    match current {
        ToolStatus::Pending => ToolStatus::InProgress,
        other => other,
    }
}

/// One piece of a tool call's output.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ToolContent {
    /// Ordinary content, rendered as markdown.
    Markdown { markdown: String },
    /// A file edit, pre-diffed into the exact shape `gitPatch` hands the diff
    /// view, so the panel's expandable diff is the same renderer as a git tab.
    Diff { diff: FileDiff },
    /// A command the agent asked *us* to run, through `terminal/create`.
    ///
    /// Only the id travels in the protocol — `acp::Terminal` has no other
    /// field — so everything the card shows (the command line, the output so
    /// far, the exit status) is read from the engine's own terminal registry
    /// by id, through `acpTerminalOutput`. Keeping the live bytes out of the
    /// entry is deliberate: the entry delta is a merge-in-place cache, and a
    /// build log growing inside it would re-send the whole card on every
    /// chunk.
    #[serde(rename_all = "camelCase")]
    Terminal { terminal_id: String },
}

/// A file position the agent says it is working at, passed through verbatim.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct Location {
    pub path: String,
    pub line: Option<u32>,
}

/// One tool call, Zed's `ToolCall` (acp_thread.rs:860-879) reduced to data.
#[derive(Debug, Clone, serde::Serialize)]
pub struct ToolCallEntry {
    pub id: String,
    pub title: String,
    /// The `acp::ToolKind`, snake_case — what the UI picks an icon by.
    ///
    /// Serialized as `tool_kind`, **not** `kind`: [`EntryBody`] is an
    /// internally tagged enum whose tag is `kind`, and serde writes the tag
    /// and then this struct's fields into the same map — so a field called
    /// `kind` here silently overwrites the tag, and every tool-call row
    /// reached the UI claiming to be a `"read"` entry instead of a
    /// `"tool_call"` one. The tag is the contract the whole delta poll
    /// dispatches on, so this is the field that moves.
    #[serde(rename = "tool_kind")]
    pub kind: String,
    pub status: ToolStatus,
    /// Present only while `status` is `waiting_for_confirmation`: the choices
    /// the agent offered, serialized in ACP's own camelCase wire shape
    /// (`optionId`, `name`, `kind` of `allow_once`/`allow_always`/
    /// `reject_once`/`reject_always`).
    pub options: Vec<acp::PermissionOption>,
    pub content: Vec<ToolContent>,
    pub locations: Vec<Location>,
    /// What `status` goes back to if the user allows the call — not serialized,
    /// the UI never needs it.
    #[serde(skip)]
    resume: ToolStatus,
}

/// A chunk of the assistant's output. Thoughts stay separate so the UI can
/// fold them, exactly as Zed keeps `Message` and `Thought` chunks apart
/// (acp_thread.rs:349-358).
#[derive(Debug, Clone, serde::Serialize)]
pub struct AssistantChunk {
    pub thought: bool,
    pub markdown: String,
}

/// What one conversation row is.
#[derive(Debug, Clone, serde::Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum EntryBody {
    User {
        markdown: String,
        /// The chunks as they arrived, kept for the echo check below.
        #[serde(skip)]
        chunks: Vec<String>,
        /// Pushed by us before the prompt went out, so an agent that echoes
        /// the user message back does not double it. Zed's `is_optimistic`
        /// (acp_thread.rs:2560-2584).
        #[serde(skip)]
        optimistic: bool,
    },
    Assistant {
        chunks: Vec<AssistantChunk>,
    },
    ToolCall(ToolCallEntry),
}

/// One row of the conversation, stamped with the revision that last touched
/// it so `entries_since` can hand the UI only what moved.
#[derive(Debug, Clone, serde::Serialize)]
pub struct Entry {
    pub rev: u64,
    #[serde(flatten)]
    pub body: EntryBody,
}

/// Context-window usage, from ACP's `UsageUpdate`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
pub struct Usage {
    pub used: u64,
    pub size: u64,
}

/// What granting or refusing a permission means for the tool call.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PermissionDecision {
    Allow,
    Reject,
    /// The turn was cancelled before the user answered. The spec requires the
    /// client to answer *every* outstanding permission request with
    /// `cancelled` when it sends `session/cancel`.
    Cancel,
}

/// One prompt as the UI sends it: the text, plus the project-relative paths
/// the user @-mentioned. Mentions ride the queue too, so a follow-up typed
/// mid-turn keeps its context — Zed's message editor sends the same shape,
/// text plus resource blocks (agent_ui/src/message_editor.rs:2140-2180).
#[derive(Clone, Debug, PartialEq)]
pub struct PromptInput {
    pub text: String,
    pub mentions: Vec<String>,
}

impl PromptInput {
    pub fn text_only(text: impl Into<String>) -> Self {
        PromptInput {
            text: text.into(),
            mentions: Vec::new(),
        }
    }
}

/// The whole of one session's state. Lives behind a mutex in `acp.rs`; every
/// method here runs under it and none of them blocks.
pub struct SessionThread {
    pub project: ProjectId,
    /// Canonical project root: the session's cwd, and the boundary the fs
    /// handlers confine the agent to.
    pub root: PathBuf,
    pub phase: Phase,
    /// Why the session is `Unavailable`, when it is.
    pub error: Option<String>,
    /// The agent's id for this session, once `session/new` has answered.
    pub acp_id: Option<acp::SessionId>,
    pub title: Option<String>,
    pub entries: Vec<Entry>,
    /// The agent's plan, replaced wholesale on every update — the protocol's
    /// own rule (plan.rs: "the client replaces the entire plan").
    pub plan: Vec<acp::PlanEntry>,
    pub usage: Option<Usage>,
    /// Session modes (Claude Code's default / acceptEdits / plan …), when the
    /// agent has them.
    pub modes: Option<acp::SessionModeState>,
    /// Slash commands the agent advertised. Carried in the state JSON;
    /// serialized in ACP's own camelCase shape.
    pub commands: Vec<acp::AvailableCommand>,
    /// The agent's session configuration options — model, effort, whatever it
    /// advertises (selects and booleans). Replaced wholesale on every
    /// `ConfigOptionUpdate`, which is the protocol's own rule: the update
    /// carries "the full set".
    pub config_options: Vec<acp::SessionConfigOption>,
    /// How the last turn ended, snake_case (`end_turn`, `cancelled`, …).
    pub stop_reason: Option<String>,
    /// A prompt sent while a turn was running: the running turn is cancelled
    /// and this goes out when it settles — Zed's follow-up interrupt
    /// (acp_thread.rs:3742-3752, where `run_turn` awaits `cancel_inner`).
    pub queued_prompt: Option<PromptInput>,
    /// Bumped by every mutation; entry stamps come from it.
    ///
    /// **Starts at 1, not 0.** A live session must never report the version an
    /// engine that has forgotten the id reports, and it must *move* on its
    /// first real change. Starting at 0 and clamping the read to 1 gave the
    /// first bump the value the poller had already seen, so the
    /// starting → ready transition — the first thing that ever happens —
    /// looked like no change at all and the panel stayed on "starting".
    pub revision: u64,
    /// The session wants `authenticate` before `session/new` will work.
    pub needs_auth: bool,
    /// The running turn has been cancelled and its answer is still on its
    /// way. While this is set, a `session/request_permission` that arrives is
    /// **answered `cancelled` immediately** rather than parked: the user has
    /// already said stop, and parking it deadlocks the turn — the agent waits
    /// for the answer while the engine waits for the agent's `PromptResponse`,
    /// and a ghost permission dialog appears after the Stop was pressed.
    /// Cleared when a new turn starts and when the cancelled one settles.
    pub turn_cancelled: bool,
}

impl SessionThread {
    pub fn new(project: ProjectId, root: PathBuf) -> Self {
        SessionThread {
            project,
            root,
            phase: Phase::Starting,
            error: None,
            acp_id: None,
            title: None,
            entries: Vec::new(),
            plan: Vec::new(),
            usage: None,
            modes: None,
            commands: Vec::new(),
            config_options: Vec::new(),
            stop_reason: None,
            queued_prompt: None,
            revision: 1,
            needs_auth: false,
            turn_cancelled: false,
        }
    }

    /// Record that something changed, so the next poll re-reads. Every
    /// mutation goes through this, including the ones made from `acp.rs` under
    /// the session lock — a change the counter does not move is a change the
    /// UI never sees.
    pub(crate) fn bump(&mut self) -> u64 {
        self.revision += 1;
        self.revision
    }

    fn touch(&mut self, index: usize) {
        let rev = self.bump();
        if let Some(entry) = self.entries.get_mut(index) {
            entry.rev = rev;
        }
    }

    fn push_entry(&mut self, body: EntryBody) {
        let rev = self.bump();
        self.entries.push(Entry { rev, body });
    }

    /// The user's message, pushed before the prompt request goes out so the
    /// panel shows it immediately — Zed does the same and calls it optimistic
    /// (acp_thread.rs:3670-3686).
    pub fn push_user_message(&mut self, text: &str) {
        self.stop_reason = None;
        self.error = None;
        self.phase = Phase::Running;
        self.turn_cancelled = false;
        self.push_entry(EntryBody::User {
            markdown: text.to_owned(),
            chunks: vec![text.to_owned()],
            optimistic: true,
        });
    }

    /// A prompt typed while the session was still starting, or while a turn
    /// was running: shown now, sent later.
    ///
    /// The entry is pushed here rather than when the prompt finally goes out,
    /// because otherwise the send is *invisible* — the user types, presses
    /// enter, and nothing at all happens until the running turn settles.
    /// Deliberately does not touch `phase`: a session still starting is still
    /// starting, and a running turn is still the running turn's.
    pub fn queue_prompt(&mut self, prompt: &PromptInput) {
        self.queued_prompt = Some(prompt.clone());
        self.push_entry(EntryBody::User {
            markdown: prompt.text.clone(),
            chunks: vec![prompt.text.clone()],
            optimistic: true,
        });
    }

    /// Take the queued prompt, ready to send it, making sure the entry showing
    /// it is on screen.
    ///
    /// It normally is — [`Self::queue_prompt`] pushed it — but a refusal
    /// truncates the transcript back past the last user message, which can
    /// take this one with it. Re-pushing is cheap and keeps the invariant the
    /// panel relies on: a prompt that is about to be sent is a prompt the user
    /// can see.
    pub(crate) fn take_queued_prompt(&mut self) -> Option<PromptInput> {
        let prompt = self.queued_prompt.take()?;
        let shown = matches!(
            self.entries.last(),
            Some(Entry {
                body: EntryBody::User {
                    optimistic: true,
                    markdown,
                    ..
                },
                ..
            }) if *markdown == prompt.text
        );
        if !shown {
            self.push_entry(EntryBody::User {
                markdown: prompt.text.clone(),
                chunks: vec![prompt.text.clone()],
                optimistic: true,
            });
        }
        self.phase = Phase::Running;
        self.stop_reason = None;
        self.error = None;
        self.turn_cancelled = false;
        self.bump();
        Some(prompt)
    }

    /// Throw away a queued prompt that will now never be sent, and the entry
    /// that was showing it — otherwise the transcript keeps a message the
    /// agent never received, which is the one thing a transcript must not do.
    pub fn discard_queued_prompt(&mut self) {
        let Some(prompt) = self.queued_prompt.take() else {
            return;
        };
        let trailing_is_ours = matches!(
            self.entries.last(),
            Some(Entry {
                body: EntryBody::User {
                    optimistic: true,
                    markdown,
                    ..
                },
                ..
            }) if *markdown == prompt.text
        );
        if trailing_is_ours {
            self.entries.pop();
        }
        self.bump();
    }

    /// Feed one `session/update` through the same rules as Zed's
    /// `handle_session_update` (acp_thread.rs:2549-2655).
    pub fn apply_update(&mut self, update: acp::SessionUpdate) {
        match update {
            acp::SessionUpdate::UserMessageChunk(chunk) => {
                let text = block_markdown(&chunk.content);
                // Skip chunks that are just our optimistic message coming
                // back — some agents echo the prompt (acp_thread.rs:2560-2584).
                let echoed = matches!(
                    self.entries.last(),
                    Some(Entry {
                        body: EntryBody::User {
                            optimistic: true,
                            chunks,
                            ..
                        },
                        ..
                    }) if chunks.contains(&text)
                );
                if echoed {
                    return;
                }
                let index = self.entries.len().wrapping_sub(1);
                if let Some(Entry {
                    body:
                        EntryBody::User {
                            markdown, chunks, ..
                        },
                    ..
                }) = self.entries.last_mut()
                {
                    markdown.push_str(&text);
                    chunks.push(text);
                    self.touch(index);
                } else {
                    self.push_entry(EntryBody::User {
                        markdown: text.clone(),
                        chunks: vec![text],
                        optimistic: false,
                    });
                }
            }
            acp::SessionUpdate::AgentMessageChunk(chunk) => {
                self.push_assistant_chunk(block_markdown(&chunk.content), false);
            }
            acp::SessionUpdate::AgentThoughtChunk(chunk) => {
                self.push_assistant_chunk(block_markdown(&chunk.content), true);
            }
            acp::SessionUpdate::ToolCall(tool_call) => {
                let status = ToolStatus::from(tool_call.status);
                self.upsert_tool_call(tool_call.into(), status);
            }
            acp::SessionUpdate::ToolCallUpdate(update) => {
                let status = update.fields.status.map(ToolStatus::from);
                self.update_tool_call(update, status);
            }
            acp::SessionUpdate::Plan(plan) => {
                self.plan = plan.entries;
                self.bump();
            }
            acp::SessionUpdate::AvailableCommandsUpdate(update) => {
                self.commands = update.available_commands;
                self.bump();
            }
            acp::SessionUpdate::CurrentModeUpdate(update) => {
                if let Some(modes) = &mut self.modes {
                    modes.current_mode_id = update.current_mode_id;
                    self.bump();
                }
            }
            acp::SessionUpdate::ConfigOptionUpdate(update) => {
                // "The full set of configuration options and their current
                // values" — replacement, never a merge.
                self.config_options = update.config_options;
                // And it *must* bump: the panel polls the revision and only
                // re-reads the state when it moves, so a config change that
                // arrives while the session is idle — an agent dropping the
                // model after a rate limit, or confirming its own `/model`
                // — would otherwise leave the selector chips showing the old
                // value for ever. Worse than cosmetic: the boolean toggle
                // computes what to send from the snapshot it can see.
                self.bump();
            }
            acp::SessionUpdate::SessionInfoUpdate(update) => {
                // `MaybeUndefined`: absent means "unchanged", null means
                // "cleared" — only a real value changes the title.
                if let agent_client_protocol::schema::MaybeUndefined::Value(title) = update.title {
                    self.title = Some(title);
                    self.bump();
                }
            }
            acp::SessionUpdate::UsageUpdate(update) => {
                self.usage = Some(Usage {
                    used: update.used,
                    size: update.size,
                });
                self.bump();
            }
            // The enum is non-exhaustive and new update kinds must be ignored
            // rather than crash the session — Zed's `_ => {}` (:2652).
            _ => {}
        }
    }

    /// Append to the last assistant entry, to its last chunk when the
    /// thought-ness matches — Zed's merge rule (acp_thread.rs:2795-2863).
    fn push_assistant_chunk(&mut self, text: String, thought: bool) {
        let index = self.entries.len().wrapping_sub(1);
        if let Some(Entry {
            body: EntryBody::Assistant { chunks },
            ..
        }) = self.entries.last_mut()
        {
            match chunks.last_mut() {
                Some(last) if last.thought == thought => last.markdown.push_str(&text),
                _ => chunks.push(AssistantChunk {
                    thought,
                    markdown: text,
                }),
            }
            self.touch(index);
        } else {
            self.push_entry(EntryBody::Assistant {
                chunks: vec![AssistantChunk {
                    thought,
                    markdown: text,
                }],
            });
        }
    }

    /// The index of a tool call, searched from the end because the one being
    /// updated is almost always the last — Zed's own access pattern and its
    /// reasoning (acp_thread.rs:3276-3292).
    fn tool_call_index(&self, id: &acp::ToolCallId) -> Option<usize> {
        self.entries.iter().enumerate().rev().find_map(|(i, e)| {
            matches!(&e.body, EntryBody::ToolCall(call) if call.id == id.0.as_ref()).then_some(i)
        })
    }

    /// Insert or update a tool call — Zed's `upsert_tool_call_inner`
    /// (acp_thread.rs:3200-3258). `status` is the full new status; an update
    /// that carried none passes `None` through [`Self::update_tool_call`].
    fn upsert_tool_call(&mut self, update: acp::ToolCallUpdate, status: ToolStatus) {
        self.update_tool_call(update, Some(status));
    }

    fn update_tool_call(&mut self, update: acp::ToolCallUpdate, status: Option<ToolStatus>) {
        let root = self.root.clone();
        if let Some(index) = self.tool_call_index(&update.tool_call_id) {
            if let EntryBody::ToolCall(call) = &mut self.entries[index].body {
                apply_tool_fields(call, update.fields, &root);
                if let Some(status) = status {
                    apply_tool_status(call, status);
                }
            }
            self.touch(index);
        } else {
            // An update for a call we have never seen becomes the call, when
            // it carries enough to be one — the protocol's own fallback
            // (`TryFrom<ToolCallUpdate> for ToolCall`, which Zed also relies
            // on at acp_thread.rs:3244-3253). One that does not is dropped
            // with a log line rather than an error: a broken agent must not
            // poison the session.
            let Ok(tool_call) = acp::ToolCall::try_from(update) else {
                log::debug!("acp: tool-call update for an unknown call with no title; dropped");
                return;
            };
            let mut call = ToolCallEntry {
                id: tool_call.tool_call_id.0.to_string(),
                title: tool_call.title.clone(),
                kind: kind_name(tool_call.kind).to_owned(),
                status: ToolStatus::Pending,
                options: Vec::new(),
                content: Vec::new(),
                locations: Vec::new(),
                resume: ToolStatus::Pending,
            };
            apply_tool_fields(
                &mut call,
                acp::ToolCallUpdate::from(tool_call).fields,
                &root,
            );
            if let Some(status) = status {
                apply_tool_status(&mut call, status);
            }
            self.push_entry(EntryBody::ToolCall(call));
        }
    }

    /// The agent asked permission for a tool call: surface it as
    /// `waiting_for_confirmation` with the offered options. The parked
    /// responder is `acp.rs`'s to keep — this only records what the UI shows.
    /// Zed: `request_tool_call_authorization` (acp_thread.rs:3383-3418).
    pub fn begin_permission(
        &mut self,
        tool_call: acp::ToolCallUpdate,
        options: Vec<acp::PermissionOption>,
    ) {
        let status = tool_call.fields.status.map(ToolStatus::from);
        self.update_tool_call(tool_call.clone(), status);
        if let Some(index) = self.tool_call_index(&tool_call.tool_call_id) {
            if let EntryBody::ToolCall(call) = &mut self.entries[index].body {
                call.resume = status_after_grant(call.status);
                call.status = ToolStatus::WaitingForConfirmation;
                call.options = options;
            }
            self.touch(index);
        }
    }

    /// The user (or a cancellation) answered a permission request. Returns
    /// true when the call was actually waiting — the caller only responds to
    /// the agent then. Zed: `authorize_tool_call` (acp_thread.rs:3433-3478).
    pub fn finish_permission(&mut self, id: &str, decision: PermissionDecision) -> bool {
        let Some(index) = self.entries.iter().enumerate().rev().find_map(|(i, e)| {
            matches!(&e.body, EntryBody::ToolCall(call) if call.id == id).then_some(i)
        }) else {
            return false;
        };
        let EntryBody::ToolCall(call) = &mut self.entries[index].body else {
            return false;
        };
        if call.status != ToolStatus::WaitingForConfirmation {
            return false;
        }
        call.status = match decision {
            PermissionDecision::Allow => call.resume,
            PermissionDecision::Reject => ToolStatus::Rejected,
            PermissionDecision::Cancel => ToolStatus::Canceled,
        };
        call.options.clear();
        self.touch(index);
        true
    }

    /// Every tool call still moving is over: the turn was cancelled. Zed's
    /// `mark_pending_entries_as_canceled` (acp_thread.rs:3929-3966); the
    /// parked responders are drained by the caller.
    pub fn cancel_pending_tool_calls(&mut self) {
        let doomed: Vec<usize> = self
            .entries
            .iter()
            .enumerate()
            .filter_map(|(i, e)| match &e.body {
                EntryBody::ToolCall(call)
                    if matches!(
                        call.status,
                        ToolStatus::Pending
                            | ToolStatus::InProgress
                            | ToolStatus::WaitingForConfirmation
                    ) =>
                {
                    Some(i)
                }
                _ => None,
            })
            .collect();
        for index in doomed {
            if let EntryBody::ToolCall(call) = &mut self.entries[index].body {
                call.status = ToolStatus::Canceled;
                call.options.clear();
            }
            self.touch(index);
        }
    }

    /// A prompt turn settled. Returns a queued follow-up prompt, if the user
    /// typed one while the turn ran — the caller sends it next.
    ///
    /// Zed's turn-end handling (acp_thread.rs:3790-3895): a cancelled turn
    /// cancels what was pending, and a refusal removes the refused prompt from
    /// the transcript because the agent has removed it from its context.
    pub fn end_turn(&mut self, stop_reason: acp::StopReason) -> Option<PromptInput> {
        self.phase = Phase::Ready;
        self.stop_reason = Some(stop_reason_name(stop_reason).to_owned());
        self.turn_cancelled = false;
        self.bump();
        match stop_reason {
            acp::StopReason::Cancelled => self.cancel_pending_tool_calls(),
            acp::StopReason::Refusal => {
                // Zed truncates back to before the refused user message
                // (acp_thread.rs:3852-3860); everything after it is gone from
                // the agent's context, so keeping it would lie.
                if let Some(index) = self
                    .entries
                    .iter()
                    .rposition(|entry| matches!(entry.body, EntryBody::User { .. }))
                {
                    self.entries.truncate(index);
                    self.bump();
                }
            }
            _ => {}
        }
        self.take_queued_prompt()
    }

    /// The turn failed outright — transport error, agent bug. The session
    /// stays usable: the next prompt may well work.
    pub fn fail_turn(&mut self, message: String) {
        self.phase = Phase::Ready;
        self.error = Some(message);
        self.turn_cancelled = false;
        self.discard_queued_prompt();
        self.cancel_pending_tool_calls();
        self.bump();
    }

    /// The session is over — the agent process exited, or refused to start.
    pub fn fail(&mut self, message: String) {
        self.phase = Phase::Unavailable;
        if self.error.is_none() {
            self.error = Some(message);
        }
        self.discard_queued_prompt();
        self.cancel_pending_tool_calls();
        self.bump();
    }

    /// `session/new` answered: the session is live.
    pub fn ready(
        &mut self,
        id: acp::SessionId,
        modes: Option<acp::SessionModeState>,
        config_options: Vec<acp::SessionConfigOption>,
    ) {
        self.acp_id = Some(id);
        self.modes = modes;
        self.config_options = config_options;
        self.phase = Phase::Ready;
        self.needs_auth = false;
        self.error = None;
        self.bump();
    }

    /// The agent wants `authenticate` first.
    pub fn auth_required(&mut self, message: String) {
        self.phase = Phase::Unavailable;
        self.needs_auth = true;
        self.error = Some(message);
        self.bump();
    }

    /// Everything but the entries, for the UI's cheap state read.
    /// `agent` is filled in by the caller, which knows the connection.
    pub fn state_json(&self, agent: serde_json::Value) -> serde_json::Value {
        serde_json::json!({
            "version": self.revision,
            "project": self.project,
            "phase": self.phase,
            "error": self.error,
            "needs_auth": self.needs_auth,
            "title": self.title,
            "stop_reason": self.stop_reason,
            "entry_count": self.entries.len(),
            "plan": self.plan,
            "usage": self.usage,
            "modes": self.modes,
            "commands": self.commands,
            "configOptions": self.config_options,
            "agent": agent,
        })
    }

    /// The entries whose revision is newer than `since`, with their indices,
    /// so a poller merges in place. `total` lets it notice truncation (a
    /// refusal removing entries), in which case it re-reads from zero.
    pub fn entries_json(&self, since: u64) -> serde_json::Value {
        let entries: Vec<serde_json::Value> = self
            .entries
            .iter()
            .enumerate()
            .filter(|(_, entry)| entry.rev > since)
            .map(|(index, entry)| {
                let mut value = serde_json::to_value(entry).unwrap_or(serde_json::Value::Null);
                if let Some(object) = value.as_object_mut() {
                    object.insert("index".to_owned(), index.into());
                }
                value
            })
            .collect();
        serde_json::json!({
            "revision": self.revision,
            "total": self.entries.len(),
            "entries": entries,
        })
    }
}

/// Apply an update's fields onto a call — Zed's `ToolCall::update_fields`
/// (acp_thread.rs:952-1067). Collections are replaced, not extended, which is
/// the protocol's rule for updates.
fn apply_tool_fields(call: &mut ToolCallEntry, fields: acp::ToolCallUpdateFields, root: &Path) {
    if let Some(kind) = fields.kind {
        call.kind = kind_name(kind).to_owned();
    }
    if let Some(title) = fields.title {
        call.title = title;
    }
    if let Some(content) = fields.content {
        call.content = content
            .into_iter()
            .filter_map(|item| tool_content(item, root))
            .collect();
    }
    if let Some(locations) = fields.locations {
        call.locations = locations
            .into_iter()
            .map(|location| Location {
                path: display_path(root, &location.path),
                line: location.line,
            })
            .collect();
    }
    // raw_input / raw_output are deliberately dropped: Zed renders raw_output
    // only when a call has no content at all (acp_thread.rs:1055-1065), and
    // for a phone the content the agent chose to send is the card.
}

fn apply_tool_status(call: &mut ToolCallEntry, status: ToolStatus) {
    // A status update racing a permission prompt must not clear the prompt:
    // Zed keeps the waiting state and remembers the underlying status
    // (`update_acp_status`, acp_thread.rs:1081-1092).
    if call.status == ToolStatus::WaitingForConfirmation
        && matches!(status, ToolStatus::Pending | ToolStatus::InProgress)
    {
        call.resume = status_after_grant(status);
    } else {
        call.status = status;
        call.options.clear();
    }
}

fn tool_content(content: acp::ToolCallContent, root: &Path) -> Option<ToolContent> {
    match content {
        acp::ToolCallContent::Content(content) => Some(ToolContent::Markdown {
            markdown: block_markdown(&content.content),
        }),
        acp::ToolCallContent::Diff(diff) => Some(ToolContent::Diff {
            diff: tool_diff(root, &diff),
        }),
        acp::ToolCallContent::Terminal(terminal) => Some(ToolContent::Terminal {
            terminal_id: terminal.terminal_id.0.to_string(),
        }),
        _ => None,
    }
}

/// A content block as markdown, the way Zed's `ContentBlock::append` renders
/// each variant (acp_thread.rs:1365 onwards): text verbatim, links as links,
/// media as a placeholder the panel can at least name.
fn block_markdown(block: &acp::ContentBlock) -> String {
    match block {
        acp::ContentBlock::Text(text) => text.text.clone(),
        acp::ContentBlock::ResourceLink(link) => format!("[{}]({})", link.name, link.uri),
        acp::ContentBlock::Image(_) => "*[image]*".to_owned(),
        acp::ContentBlock::Audio(_) => "*[audio]*".to_owned(),
        acp::ContentBlock::Resource(resource) => match &resource.resource {
            acp::EmbeddedResourceResource::TextResourceContents(text) => text.text.clone(),
            _ => "*[resource]*".to_owned(),
        },
        _ => String::new(),
    }
}

fn kind_name(kind: acp::ToolKind) -> &'static str {
    match kind {
        acp::ToolKind::Read => "read",
        acp::ToolKind::Edit => "edit",
        acp::ToolKind::Delete => "delete",
        acp::ToolKind::Move => "move",
        acp::ToolKind::Search => "search",
        acp::ToolKind::Execute => "execute",
        acp::ToolKind::Think => "think",
        acp::ToolKind::Fetch => "fetch",
        acp::ToolKind::SwitchMode => "switch_mode",
        _ => "other",
    }
}

fn stop_reason_name(reason: acp::StopReason) -> &'static str {
    match reason {
        acp::StopReason::EndTurn => "end_turn",
        acp::StopReason::MaxTokens => "max_tokens",
        acp::StopReason::MaxTurnRequests => "max_turn_requests",
        acp::StopReason::Refusal => "refusal",
        acp::StopReason::Cancelled => "cancelled",
        _ => "end_turn",
    }
}

/// A path as the panel shows it: project-relative and `/`-separated when it
/// is inside the project, absolute otherwise — the same spelling every other
/// surface uses (`lsp::relative_path` made the same call).
fn display_path(root: &Path, path: &Path) -> String {
    match path.strip_prefix(root) {
        Ok(relative) => relative
            .components()
            .map(|c| c.as_os_str().to_string_lossy())
            .collect::<Vec<_>>()
            .join("/"),
        Err(_) => path.to_string_lossy().into_owned(),
    }
}

/// An ACP diff (`{path, old_text, new_text}` — whole texts, not hunks) as the
/// `FileDiff` the diff view draws. Diffed here, once, when the update
/// arrives; the UI only ever reads rows.
pub(crate) fn tool_diff(root: &Path, diff: &acp::Diff) -> FileDiff {
    let path = display_path(root, &diff.path);
    let old = diff.old_text.as_deref().unwrap_or("");
    let new = diff.new_text.as_str();
    if old.len() + new.len() > MAX_DIFF_BYTES
        || old.bytes().take(8000).any(|b| b == 0)
        || new.bytes().take(8000).any(|b| b == 0)
    {
        // Too big to be a card, or not text. The card still names the file;
        // it just has no rows — the same shape a binary `git diff` has.
        return FileDiff {
            path,
            original: None,
            is_binary: true,
            hunks: Vec::new(),
        };
    }
    FileDiff {
        path,
        original: None,
        is_binary: false,
        hunks: unified_hunks(old, new),
    }
}

/// Split like git counts lines: a trailing newline does not open a final
/// empty line, and `\r` stays with its line's content (the CRLF rule
/// `git_patch::parse_patch` already follows).
fn diff_lines(text: &str) -> Vec<&str> {
    let mut lines: Vec<&str> = text.split('\n').collect();
    if lines.last() == Some(&"") {
        lines.pop();
    }
    lines
}

/// Unified hunks with git's `-U3` context, from two whole texts.
///
/// imara-diff (Histogram — the same algorithm and crate as `git_diff.rs`, and
/// as Zed's `language::text_diff`) yields zero-context hunks; this merges the
/// ones whose 3-line contexts would touch and lays the rows out with the
/// 1-based numbering `PatchLine` documents. The 0 conventions match git's:
/// an insertion with no old rows in the hunk reports `old_start` as the line
/// it comes *after* (0 at the top of a file), and symmetrically for a pure
/// deletion.
fn unified_hunks(old_text: &str, new_text: &str) -> Vec<PatchHunk> {
    let old = diff_lines(old_text);
    let new = diff_lines(new_text);
    let mut input = InternedInput::default();
    input.update_before(old.iter().copied());
    input.update_after(new.iter().copied());
    let diff = Diff::compute(Algorithm::Histogram, &input);

    let raw: Vec<(std::ops::Range<u32>, std::ops::Range<u32>)> = diff
        .hunks()
        .map(|hunk| (hunk.before.clone(), hunk.after.clone()))
        .collect();
    if raw.is_empty() {
        return Vec::new();
    }

    // Group hunks whose expanded contexts would overlap or touch.
    let mut groups: Vec<Vec<&(std::ops::Range<u32>, std::ops::Range<u32>)>> = Vec::new();
    for hunk in &raw {
        match groups.last_mut() {
            Some(group)
                if hunk.0.start.saturating_sub(group.last().unwrap().0.end) <= 2 * DIFF_CONTEXT =>
            {
                group.push(hunk);
            }
            _ => groups.push(vec![hunk]),
        }
    }

    let mut hunks = Vec::new();
    for group in groups {
        let first = group.first().unwrap();
        let last = group.last().unwrap();
        let lead = first.0.start.saturating_sub(DIFF_CONTEXT).max(0);
        // Before the first change the two sides are equal, so the new side's
        // corresponding line is a fixed offset away.
        let lead_new = first.1.start - (first.0.start - lead);

        let mut lines = Vec::new();
        let mut old_line = lead + 1;
        let mut new_line = lead_new + 1;
        let context = |from: u32,
                       to: u32,
                       lines: &mut Vec<PatchLine>,
                       old_line: &mut u32,
                       new_line: &mut u32| {
            for row in from..to {
                lines.push(PatchLine {
                    kind: ' ',
                    text: old.get(row as usize).copied().unwrap_or("").to_owned(),
                    old_line: *old_line,
                    new_line: *new_line,
                });
                *old_line += 1;
                *new_line += 1;
            }
        };

        context(
            lead,
            first.0.start,
            &mut lines,
            &mut old_line,
            &mut new_line,
        );
        let mut previous_end = first.0.start;
        for (before, after) in group.iter().copied() {
            context(
                previous_end,
                before.start,
                &mut lines,
                &mut old_line,
                &mut new_line,
            );
            for row in before.clone() {
                lines.push(PatchLine {
                    kind: '-',
                    text: old.get(row as usize).copied().unwrap_or("").to_owned(),
                    old_line,
                    new_line: 0,
                });
                old_line += 1;
            }
            for row in after.clone() {
                lines.push(PatchLine {
                    kind: '+',
                    text: new.get(row as usize).copied().unwrap_or("").to_owned(),
                    old_line: 0,
                    new_line,
                });
                new_line += 1;
            }
            previous_end = before.end;
        }
        let trail_end = (last.0.end + DIFF_CONTEXT).min(old.len() as u32);
        context(
            previous_end,
            trail_end,
            &mut lines,
            &mut old_line,
            &mut new_line,
        );

        // git's 0 convention for one-sided hunks: a hunk with no old rows
        // starts "after line N" on the old side, and vice versa.
        let old_rows = lines.iter().filter(|line| line.kind != '+').count();
        let new_rows = lines.iter().filter(|line| line.kind != '-').count();
        hunks.push(PatchHunk {
            old_start: if old_rows == 0 { lead } else { lead + 1 },
            new_start: if new_rows == 0 {
                lead_new
            } else {
                lead_new + 1
            },
            heading: String::new(),
            lines,
        });
    }
    hunks
}

#[cfg(test)]
mod tests {
    use super::*;

    fn thread() -> SessionThread {
        SessionThread::new(1, PathBuf::from("/proj"))
    }

    fn text_update(text: &str) -> acp::SessionUpdate {
        acp::SessionUpdate::AgentMessageChunk(acp::ContentChunk::new(acp::ContentBlock::from(
            text.to_owned(),
        )))
    }

    fn thought_update(text: &str) -> acp::SessionUpdate {
        acp::SessionUpdate::AgentThoughtChunk(acp::ContentChunk::new(acp::ContentBlock::from(
            text.to_owned(),
        )))
    }

    fn tool_call(id: &str, title: &str) -> acp::ToolCall {
        acp::ToolCall::new(acp::ToolCallId::new(id.to_owned()), title)
    }

    /// Every mutating update must move the revision, because the panel polls
    /// it and only re-reads when it moves. `ConfigOptionUpdate` did not, so a
    /// model the agent changed on its own — a rate-limit downgrade, its own
    /// `/model` — left the selector chip showing the old value for ever, and
    /// the boolean toggle then computed what to send from that stale value.
    #[test]
    fn a_config_option_update_moves_the_revision() {
        let mut thread = thread();
        let before = thread.revision;
        thread.apply_update(acp::SessionUpdate::ConfigOptionUpdate(
            serde_json::from_value(serde_json::json!({
                "configOptions": [{
                    "id": "model",
                    "name": "Model",
                    "type": "select",
                    "currentValue": "haiku",
                    "options": [{"value": "haiku", "name": "Haiku"}],
                }],
            }))
            .unwrap(),
        ));
        assert_eq!(thread.config_options.len(), 1);
        assert!(
            thread.revision > before,
            "the panel never re-reads a revision that did not move",
        );
    }

    /// `turn_cancelled` gates `session/request_permission` answers in
    /// `acp::on_permission`; a flag that outlives its turn would swallow the
    /// *next* turn's real permission question, so every way a turn starts or
    /// settles must drop it.
    #[test]
    fn the_cancel_flag_dies_with_its_turn() {
        let mut thread = thread();

        thread.turn_cancelled = true;
        thread.push_user_message("a new turn");
        assert!(!thread.turn_cancelled, "a new prompt starts uncancelled");

        thread.turn_cancelled = true;
        thread.end_turn(acp::StopReason::Cancelled);
        assert!(!thread.turn_cancelled, "a settled turn clears it");

        thread.push_user_message("again");
        thread.turn_cancelled = true;
        thread.fail_turn("the wire broke".to_owned());
        assert!(!thread.turn_cancelled, "a failed turn clears it");

        thread.queue_prompt(&PromptInput::text_only("queued while running"));
        thread.turn_cancelled = true;
        assert!(thread.take_queued_prompt().is_some());
        assert!(
            !thread.turn_cancelled,
            "a queued follow-up is its own turn, not the cancelled one"
        );
    }

    #[test]
    fn assistant_chunks_merge_and_thoughts_stay_separate() {
        let mut thread = thread();
        thread.apply_update(text_update("Hello"));
        thread.apply_update(text_update(", world"));
        thread.apply_update(thought_update("hmm"));
        thread.apply_update(text_update("Done"));

        assert_eq!(thread.entries.len(), 1);
        let EntryBody::Assistant { chunks } = &thread.entries[0].body else {
            panic!("expected an assistant entry");
        };
        assert_eq!(chunks.len(), 3);
        assert_eq!(
            (chunks[0].thought, chunks[0].markdown.as_str()),
            (false, "Hello, world")
        );
        assert_eq!(
            (chunks[1].thought, chunks[1].markdown.as_str()),
            (true, "hmm")
        );
        assert_eq!(
            (chunks[2].thought, chunks[2].markdown.as_str()),
            (false, "Done")
        );
    }

    #[test]
    fn an_echoed_user_chunk_is_not_doubled() {
        let mut thread = thread();
        thread.push_user_message("do the thing");
        // The agent echoes the prompt back, as some do.
        thread.apply_update(acp::SessionUpdate::UserMessageChunk(
            acp::ContentChunk::new(acp::ContentBlock::from("do the thing".to_owned())),
        ));
        assert_eq!(thread.entries.len(), 1);
        let EntryBody::User { markdown, .. } = &thread.entries[0].body else {
            panic!("expected a user entry");
        };
        assert_eq!(markdown, "do the thing");

        // A genuinely different user chunk (an agent-injected context note)
        // does land.
        thread.apply_update(acp::SessionUpdate::UserMessageChunk(
            acp::ContentChunk::new(acp::ContentBlock::from(" plus context".to_owned())),
        ));
        let EntryBody::User { markdown, .. } = &thread.entries[0].body else {
            panic!("expected a user entry");
        };
        assert_eq!(markdown, "do the thing plus context");
    }

    #[test]
    fn tool_calls_upsert_by_id_and_updates_replace_fields() {
        let mut thread = thread();
        thread.apply_update(acp::SessionUpdate::ToolCall(
            tool_call("t1", "Read main.rs").kind(acp::ToolKind::Read),
        ));
        thread.apply_update(text_update("looking…"));
        thread.apply_update(acp::SessionUpdate::ToolCallUpdate(
            acp::ToolCallUpdate::new(
                acp::ToolCallId::new("t1".to_owned()),
                acp::ToolCallUpdateFields::new()
                    .status(acp::ToolCallStatus::Completed)
                    .title("Read src/main.rs".to_owned()),
            ),
        ));

        assert_eq!(thread.entries.len(), 2);
        let EntryBody::ToolCall(call) = &thread.entries[0].body else {
            panic!("expected a tool call");
        };
        assert_eq!(call.title, "Read src/main.rs");
        assert_eq!(call.status, ToolStatus::Completed);
        assert_eq!(call.kind, "read");
    }

    #[test]
    fn an_update_for_an_unknown_call_becomes_the_call_when_it_can() {
        let mut thread = thread();
        thread.apply_update(acp::SessionUpdate::ToolCallUpdate(
            acp::ToolCallUpdate::new(
                acp::ToolCallId::new("t9".to_owned()),
                acp::ToolCallUpdateFields::new()
                    .title("Late call".to_owned())
                    .status(acp::ToolCallStatus::InProgress),
            ),
        ));
        assert_eq!(thread.entries.len(), 1);

        // One with no title cannot become a call and is dropped, not a panic.
        thread.apply_update(acp::SessionUpdate::ToolCallUpdate(
            acp::ToolCallUpdate::new(
                acp::ToolCallId::new("t10".to_owned()),
                acp::ToolCallUpdateFields::new().status(acp::ToolCallStatus::Completed),
            ),
        ));
        assert_eq!(thread.entries.len(), 1);
    }

    fn options() -> Vec<acp::PermissionOption> {
        vec![
            acp::PermissionOption::new(
                acp::PermissionOptionId::new("allow"),
                "Allow",
                acp::PermissionOptionKind::AllowOnce,
            ),
            acp::PermissionOption::new(
                acp::PermissionOptionId::new("deny"),
                "Deny",
                acp::PermissionOptionKind::RejectOnce,
            ),
        ]
    }

    #[test]
    fn a_permission_request_wraps_the_status_and_an_allow_resumes_it() {
        let mut thread = thread();
        thread.begin_permission(
            acp::ToolCallUpdate::from(tool_call("t1", "Edit file").kind(acp::ToolKind::Edit)),
            options(),
        );
        let EntryBody::ToolCall(call) = &thread.entries[0].body else {
            panic!("expected a tool call");
        };
        assert_eq!(call.status, ToolStatus::WaitingForConfirmation);
        assert_eq!(call.options.len(), 2);

        // A status update arriving while the prompt is up must not clear it —
        // it only moves what the call resumes to (Zed acp_thread.rs:1081-1092).
        thread.apply_update(acp::SessionUpdate::ToolCallUpdate(
            acp::ToolCallUpdate::new(
                acp::ToolCallId::new("t1".to_owned()),
                acp::ToolCallUpdateFields::new().status(acp::ToolCallStatus::InProgress),
            ),
        ));
        let EntryBody::ToolCall(call) = &thread.entries[0].body else {
            panic!("expected a tool call");
        };
        assert_eq!(call.status, ToolStatus::WaitingForConfirmation);

        assert!(thread.finish_permission("t1", PermissionDecision::Allow));
        let EntryBody::ToolCall(call) = &thread.entries[0].body else {
            panic!("expected a tool call");
        };
        assert_eq!(call.status, ToolStatus::InProgress);
        assert!(call.options.is_empty());
        // Answering twice does nothing.
        assert!(!thread.finish_permission("t1", PermissionDecision::Reject));
    }

    #[test]
    fn a_rejection_rejects_and_a_cancel_cancels() {
        let mut thread = thread();
        thread.begin_permission(
            acp::ToolCallUpdate::from(tool_call("t1", "rm -rf")),
            options(),
        );
        assert!(thread.finish_permission("t1", PermissionDecision::Reject));
        let EntryBody::ToolCall(call) = &thread.entries[0].body else {
            panic!()
        };
        assert_eq!(call.status, ToolStatus::Rejected);

        thread.begin_permission(
            acp::ToolCallUpdate::from(tool_call("t2", "again")),
            options(),
        );
        assert!(thread.finish_permission("t2", PermissionDecision::Cancel));
        let EntryBody::ToolCall(call) = &thread.entries[1].body else {
            panic!()
        };
        assert_eq!(call.status, ToolStatus::Canceled);
    }

    #[test]
    fn a_cancelled_turn_cancels_whatever_was_still_moving() {
        let mut thread = thread();
        thread.push_user_message("go");
        thread.apply_update(acp::SessionUpdate::ToolCall(
            tool_call("t1", "Slow thing").status(acp::ToolCallStatus::InProgress),
        ));
        thread.apply_update(acp::SessionUpdate::ToolCall(
            tool_call("t2", "Done thing").status(acp::ToolCallStatus::Completed),
        ));

        let queued = thread.end_turn(acp::StopReason::Cancelled);
        assert_eq!(queued, None);
        assert_eq!(thread.phase, Phase::Ready);
        assert_eq!(thread.stop_reason.as_deref(), Some("cancelled"));
        let statuses: Vec<ToolStatus> = thread
            .entries
            .iter()
            .filter_map(|e| match &e.body {
                EntryBody::ToolCall(call) => Some(call.status),
                _ => None,
            })
            .collect();
        assert_eq!(statuses, vec![ToolStatus::Canceled, ToolStatus::Completed]);
    }

    #[test]
    fn a_refusal_removes_the_refused_prompt_from_the_transcript() {
        let mut thread = thread();
        thread.push_user_message("first");
        thread.apply_update(text_update("sure"));
        thread.end_turn(acp::StopReason::EndTurn);
        thread.push_user_message("do something the agent refuses");
        thread.apply_update(text_update("no"));

        thread.end_turn(acp::StopReason::Refusal);
        assert_eq!(thread.entries.len(), 2, "the refused exchange is gone");
        assert_eq!(thread.stop_reason.as_deref(), Some("refusal"));
    }

    #[test]
    fn a_queued_prompt_survives_the_turn_it_interrupted() {
        let mut thread = thread();
        thread.push_user_message("first");
        thread.queued_prompt = Some(PromptInput::text_only("follow-up"));
        assert_eq!(
            thread.end_turn(acp::StopReason::Cancelled),
            Some(PromptInput::text_only("follow-up"))
        );
    }

    #[test]
    fn the_plan_is_replaced_wholesale() {
        let mut thread = thread();
        let entry = |content: &str| {
            acp::PlanEntry::new(
                content,
                acp::PlanEntryPriority::Medium,
                acp::PlanEntryStatus::Pending,
            )
        };
        thread.apply_update(acp::SessionUpdate::Plan(acp::Plan::new(vec![
            entry("step one"),
            entry("step two"),
        ])));
        assert_eq!(thread.plan.len(), 2);
        thread.apply_update(acp::SessionUpdate::Plan(acp::Plan::new(vec![entry(
            "only step",
        )])));
        assert_eq!(thread.plan.len(), 1);
    }

    /// The entry tag is what the UI dispatches on, and a tool call has a
    /// `kind` of its own for its icon. Serde writes an internally-tagged
    /// enum's tag and its inner struct's fields into *one* map, so the two
    /// collided and every tool-call row arrived claiming to be a `"read"`
    /// entry. Both must be present, under different names.
    #[test]
    fn a_tool_call_entry_keeps_both_its_entry_tag_and_its_tool_kind() {
        let mut thread = thread();
        thread.push_user_message("go");
        thread.apply_update(text_update("thinking"));
        thread.apply_update(acp::SessionUpdate::ToolCall(
            tool_call("t1", "Read main.rs").kind(acp::ToolKind::Read),
        ));

        let entries = thread.entries_json(0);
        let rows = entries["entries"].as_array().unwrap();
        assert_eq!(rows[0]["kind"], "user");
        assert_eq!(rows[1]["kind"], "assistant");
        assert_eq!(rows[2]["kind"], "tool_call", "the entry tag survives");
        assert_eq!(rows[2]["tool_kind"], "read", "and so does the icon kind");
        assert_eq!(rows[2]["id"], "t1");
    }

    /// The counter must move on the *first* change as well as every later
    /// one. A live session reporting the same number before and after
    /// `session/new` answered left the panel showing "starting" for ever,
    /// because a poller compares what it read with what it reads next.
    #[test]
    fn the_version_moves_on_the_first_change() {
        let mut thread = thread();
        let fresh = thread.revision;
        assert!(
            fresh > 0,
            "a live session is never version 0 — that means forgotten"
        );

        thread.ready(acp::SessionId::new("s1"), None, Vec::new());
        assert!(
            thread.revision > fresh,
            "starting → ready must be visible to a poller: {fresh} → {}",
            thread.revision
        );
    }

    /// A prompt typed while the agent is busy is shown at once and sent
    /// later — not held invisibly until the running turn happens to finish.
    #[test]
    fn a_queued_prompt_is_visible_the_moment_it_is_typed() {
        let mut thread = thread();
        thread.push_user_message("first");
        let before = thread.revision;

        thread.queue_prompt(&PromptInput::text_only("second"));
        assert!(thread.revision > before, "the queue is news");
        assert_eq!(thread.entries.len(), 2);
        let EntryBody::User { markdown, .. } = &thread.entries[1].body else {
            panic!("the queued prompt is on screen");
        };
        assert_eq!(markdown, "second");
        // Queuing does not start the turn; the running one is still running.
        assert_eq!(thread.phase, Phase::Running);

        // When the running turn ends, the queued one is handed over — and is
        // not pushed a second time.
        assert_eq!(
            thread.end_turn(acp::StopReason::EndTurn),
            Some(PromptInput::text_only("second"))
        );
        assert_eq!(thread.entries.len(), 2);
        assert_eq!(
            thread.phase,
            Phase::Running,
            "the queued turn is now running"
        );
    }

    /// Cancelling takes the queued prompt *and* the entry showing it: a
    /// transcript must never keep a message the agent never received.
    #[test]
    fn cancelling_takes_the_queued_prompt_off_the_screen_too() {
        let mut thread = thread();
        thread.push_user_message("first");
        thread.queue_prompt(&PromptInput::text_only("second"));
        assert_eq!(thread.entries.len(), 2);

        thread.discard_queued_prompt();
        assert_eq!(thread.entries.len(), 1);
        assert!(thread.queued_prompt.is_none());
        assert_eq!(thread.end_turn(acp::StopReason::Cancelled), None);
    }

    /// A refusal truncates back past the last user message, which can take a
    /// queued prompt's entry with it — the prompt is still sent, so the entry
    /// has to come back rather than the send going silent.
    #[test]
    fn a_queued_prompt_survives_a_refusal_truncating_its_entry() {
        let mut thread = thread();
        thread.push_user_message("something refused");
        thread.queue_prompt(&PromptInput::text_only("the follow-up"));

        let queued = thread.end_turn(acp::StopReason::Refusal);
        assert_eq!(queued, Some(PromptInput::text_only("the follow-up")));
        let last = thread.entries.last().expect("the follow-up is on screen");
        let EntryBody::User { markdown, .. } = &last.body else {
            panic!("expected the queued user message");
        };
        assert_eq!(markdown, "the follow-up");
    }

    #[test]
    fn entries_since_hands_back_only_what_moved() {
        let mut thread = thread();
        thread.push_user_message("go");
        thread.apply_update(text_update("working"));
        let first = thread.revision;

        let all = thread.entries_json(0);
        assert_eq!(all["entries"].as_array().unwrap().len(), 2);
        assert_eq!(all["total"], 2);

        // Nothing moved: nothing to hand over.
        assert!(
            thread.entries_json(first)["entries"]
                .as_array()
                .unwrap()
                .is_empty()
        );

        // One entry moved: only it comes back, with its index.
        thread.apply_update(text_update(" harder"));
        let delta = thread.entries_json(first);
        let entries = delta["entries"].as_array().unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0]["index"], 1);
        assert_eq!(entries[0]["kind"], "assistant");
    }

    #[test]
    fn tool_diffs_arrive_as_patch_rows() {
        let mut thread = thread();
        let diff = acp::Diff::new("/proj/src/main.rs", "fn main() {\n    new();\n}\n")
            .old_text("fn main() {\n    old();\n}\n".to_owned());
        thread.apply_update(acp::SessionUpdate::ToolCall(
            tool_call("t1", "Edit main.rs")
                .kind(acp::ToolKind::Edit)
                .content(vec![acp::ToolCallContent::Diff(diff)]),
        ));

        let EntryBody::ToolCall(call) = &thread.entries[0].body else {
            panic!()
        };
        let ToolContent::Diff { diff } = &call.content[0] else {
            panic!("expected a diff card");
        };
        assert_eq!(diff.path, "src/main.rs", "project-relative, like git paths");
        assert_eq!(diff.hunks.len(), 1);
        let kinds: String = diff.hunks[0].lines.iter().map(|line| line.kind).collect();
        assert_eq!(kinds, " -+ ");
    }

    // ------------------------------------------------------------------
    // The unified-diff builder, pinned against what git would say.
    // ------------------------------------------------------------------

    fn rows(old: &str, new: &str) -> Vec<PatchHunk> {
        unified_hunks(old, new)
    }

    #[test]
    fn a_single_change_gets_three_lines_of_context() {
        let old = "a\nb\nc\nd\ne\nf\ng\nh\ni\n";
        let new = "a\nb\nc\nd\nE\nf\ng\nh\ni\n";
        let hunks = rows(old, new);
        assert_eq!(hunks.len(), 1);
        let hunk = &hunks[0];
        // git says: @@ -2,7 +2,7 @@
        assert_eq!((hunk.old_start, hunk.new_start), (2, 2));
        let kinds: String = hunk.lines.iter().map(|line| line.kind).collect();
        assert_eq!(kinds, "   -+   ");
        assert_eq!(hunk.lines[3].text, "e");
        assert_eq!(hunk.lines[3].old_line, 5);
        assert_eq!(hunk.lines[4].text, "E");
        assert_eq!(hunk.lines[4].new_line, 5);
    }

    #[test]
    fn nearby_changes_merge_into_one_hunk_and_distant_ones_do_not() {
        // Changes at rows 5 and 10 of 20: gap of 4 < 6, one hunk.
        let old: String = (1..=20).map(|n| format!("l{n}\n")).collect();
        let near = old.replace("l5\n", "x5\n").replace("l10\n", "x10\n");
        assert_eq!(rows(&old, &near).len(), 1);
        // Changes at rows 3 and 17: gap of 13 > 6, two hunks.
        let far = old.replace("l3\n", "x3\n").replace("l17\n", "x17\n");
        assert_eq!(rows(&old, &far).len(), 2);
    }

    #[test]
    fn a_new_file_counts_from_zero_like_git_does() {
        let hunks = rows("", "one\ntwo\n");
        assert_eq!(hunks.len(), 1);
        // git says: @@ -0,0 +1,2 @@
        assert_eq!((hunks[0].old_start, hunks[0].new_start), (0, 1));
        assert!(hunks[0].lines.iter().all(|line| line.kind == '+'));
        assert_eq!(hunks[0].lines[0].new_line, 1);
        assert_eq!(hunks[0].lines[1].new_line, 2);
    }

    #[test]
    fn emptying_a_file_counts_from_zero_on_the_new_side() {
        let hunks = rows("one\ntwo\n", "");
        assert_eq!(hunks.len(), 1);
        // git says: @@ -1,2 +0,0 @@
        assert_eq!((hunks[0].old_start, hunks[0].new_start), (1, 0));
        assert!(hunks[0].lines.iter().all(|line| line.kind == '-'));
    }

    #[test]
    fn identical_texts_have_no_hunks() {
        assert!(rows("same\n", "same\n").is_empty());
        assert!(rows("", "").is_empty());
    }

    #[test]
    fn context_is_clipped_at_the_ends_of_the_file() {
        let hunks = rows("a\nb\n", "A\nb\n");
        assert_eq!(hunks.len(), 1);
        assert_eq!((hunks[0].old_start, hunks[0].new_start), (1, 1));
        let kinds: String = hunks[0].lines.iter().map(|line| line.kind).collect();
        assert_eq!(kinds, "-+ ");
    }

    #[test]
    fn an_oversized_or_binary_diff_becomes_a_named_card_with_no_rows() {
        let root = Path::new("/proj");
        let nul = acp::Diff::new("/proj/blob.bin", "a\0b".to_owned());
        assert!(tool_diff(root, &nul).is_binary);

        let big = acp::Diff::new("/proj/big.txt", "x".repeat(MAX_DIFF_BYTES + 1));
        let diff = tool_diff(root, &big);
        assert!(diff.is_binary);
        assert_eq!(diff.path, "big.txt");
    }

    #[test]
    fn locations_and_paths_are_project_relative_when_inside() {
        let mut thread = thread();
        thread.apply_update(acp::SessionUpdate::ToolCall(
            tool_call("t1", "look").locations(vec![
                acp::ToolCallLocation::new("/proj/src/lib.rs").line(4),
                acp::ToolCallLocation::new("/elsewhere/thing.txt"),
            ]),
        ));
        let EntryBody::ToolCall(call) = &thread.entries[0].body else {
            panic!()
        };
        assert_eq!(call.locations[0].path, "src/lib.rs");
        assert_eq!(call.locations[0].line, Some(4));
        assert_eq!(call.locations[1].path, "/elsewhere/thing.txt");
    }

    #[test]
    fn state_json_carries_the_panel_chrome() {
        let mut thread = thread();
        thread.ready(
            acp::SessionId::new("s1"),
            Some(acp::SessionModeState::new(
                acp::SessionModeId::new("default"),
                vec![acp::SessionMode::new(
                    acp::SessionModeId::new("default"),
                    "Always Ask",
                )],
            )),
            vec![acp::SessionConfigOption::new(
                acp::SessionConfigId::new("model"),
                "Model",
                acp::SessionConfigKind::Select(acp::SessionConfigSelect::new(
                    acp::SessionConfigValueId::new("fast"),
                    acp::SessionConfigSelectOptions::Ungrouped(vec![
                        acp::SessionConfigSelectOption::new(
                            acp::SessionConfigValueId::new("fast"),
                            "Fast",
                        ),
                    ]),
                )),
            )],
        );
        thread.apply_update(acp::SessionUpdate::UsageUpdate(
            serde_json::from_value(serde_json::json!({"used": 10, "size": 100})).unwrap(),
        ));
        let state = thread.state_json(serde_json::json!({"name": "claude"}));
        assert_eq!(state["phase"], "ready");
        assert_eq!(state["usage"]["used"], 10);
        assert_eq!(state["modes"]["currentModeId"], "default");
        assert_eq!(state["agent"]["name"], "claude");
        assert_eq!(state["entry_count"], 0);
    }
}
