package to.eyed.conquest.code.ui.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import to.eyed.conquest.code.core.Commit
import to.eyed.conquest.code.core.CommitDetails
import to.eyed.conquest.code.core.CommitPage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.R
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.core.GitChange
import to.eyed.conquest.code.core.GitFileStatus
import to.eyed.conquest.code.core.GitPanelState
import to.eyed.conquest.code.core.GitSession
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.core.RemoteOpResult
import to.eyed.conquest.code.core.ResumedEffect
import to.eyed.conquest.code.core.pollVersion
import to.eyed.conquest.code.terminal.Userland
import to.eyed.conquest.code.ui.theme.BufferFontFamily
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.workspace.ContextMenu
import to.eyed.conquest.code.ui.workspace.ContextMenuItem
import to.eyed.conquest.code.ui.workspace.GitStatusColours
import to.eyed.conquest.code.ui.workspace.onSecondaryClick
import to.eyed.conquest.code.ui.workspace.GitFileStatus as PanelStatus

/**
 * Whether this build can show a git panel at all.
 *
 * Everything the panel does runs the `git` inside the Linux userland, so the
 * `play` edition — which has no userland and never will — is not offered it,
 * greyed or otherwise. That is the same rule the clone action already follows:
 * an editor should not advertise what it cannot ever do.
 */
val isGitPanelSupported: Boolean
    get() = Userland.backend.isSupported

/**
 * Zed's `git_panel.default_width` (assets/settings/default.json:997) — what
 * the workspace budgets for this dock when it decides on a layout.
 */
internal val GitPanelDockWidth = 360.dp

/**
 * Every list surface in the panel — entry rows, section headers, empty-section
 * notes — is `list_item_height()` = `rems(1.75)` = 28px (git_panel.rs:7257-7259).
 */
private val ListItemHeight = 28.dp

/**
 * The bar above the change list is `min_h(Tab::container_height)` = `Base32` =
 * 32px (git_panel.rs:5787; ui/src/components/tab.rs:83-85), and the tab strip
 * at the top of the panel is the same `Tab::container_height` (git_panel.rs:6303).
 */
private val BarHeight = 32.dp

/** Rows are `pl_2p5` / `pr_1` — 10px in, 4px out (git_panel.rs:7690-7691). */
private val RowStartPadding = 10.dp
private val RowEndPadding = 4.dp

/** Inputs are `rounded_md` = 6px (search_bar.rs:78). */
private val FieldRadius = 6.dp

/** The engine debounces git by 400 ms; polling faster only costs JNI calls. */
private const val POLL_MS = 250L

/**
 * The Fetch From picker's extra row when there is more than one remote —
 * `FetchOptions::All.name()` (repository.rs:664-669; git_panel.rs:3653-3655).
 */
private const val FetchAllRemotes = "Fetch all remotes"

/** How far PageUp and PageDown move the selection. */
private const val PAGE_ROWS = 10

/**
 * Zed's commit box is exactly six lines of the commit font and grows no
 * further — `MAX_PANEL_EDITOR_LINES = 6`, pinned as both `min_lines` and
 * `max_lines` (git_panel.rs:1080, 1091-1095) — so the file list keeps the
 * panel.
 */
private const val CommitEditorLines = 6

/**
 * Zed pins the commit editor's type to 12px in its own defaults
 * (`git_commit_buffer_font_size`, assets/settings/default.json:81); the
 * buffer-size fallback in settings.rs:446-451 never applies at defaults.
 */
private const val CommitBufferFontSize = 12f

/**
 * gpui's φ — the `buffer_line_height: "comfortable"` the commit editor is laid
 * out in (theme_settings/src/settings.rs:390).
 */
private const val BufferLineHeight = 1.618034f

/**
 * The git panel — Zed's `crates/git_ui/src/git_panel.rs`, in the shape a phone
 * can hold: the changed files in their sections, a checkbox each for staging, a
 * commit message and a commit button.
 *
 * A dock beside the editor on a wide screen and the whole work area on a
 * compact one, which is the split project search already makes.
 *
 * What it deliberately does not have is Zed's diff view: opening a row opens
 * the *file*, and the gutter beside it is where its hunks are. Side-by-side is
 * wrong on a phone, and a unified diff of a whole repository is a second
 * editor's worth of surface for a wave that is building three other things.
 *
 * Two of the actions here destroy work, so both are guarded. Discard confirms,
 * names the file, and says which of its meanings applies — restore, trash, or a
 * rename undone — and it cannot be reached in one tap from anywhere. A row it
 * cannot state a promise for, a conflict above all, it refuses instead. Commit
 * refuses an empty message rather than making an empty commit.
 */
@Composable
fun GitPanel(
    project: ProjectSession,
    /**
     * Bumped by the workspace whenever the panel's chord is pressed, so pressing
     * it again puts the keyboard back on the file list rather than doing nothing.
     */
    focusToken: Int,
    onOpenFile: (String) -> Unit,
    /** Open a diff view — one file's, or the whole project's for null. */
    onOpenDiff: (String?) -> Unit,
    /** Open the commit graph, which is a view of the whole repository. */
    onOpenGraph: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    val session = remember(project) { GitSession(project) }

    var state by remember(project) { mutableStateOf(GitPanelState()) }
    /**
     * The commit HEAD names, from the engine's cached status run. It is the
     * history tab's staleness key: `git log` needs re-running when *this*
     * moves, not when the status snapshot is replaced — which happens on
     * every save while the panel is open.
     */
    var head by remember(project) { mutableStateOf<String?>(null) }
    // Seeded from, and written back to, the draft the panel was closed with:
    // Escape and — on a phone — opening a file both take the panel out of the
    // composition, and a commit message is the one thing here nobody wants to
    // type twice. Cleared on a commit that succeeded, and nowhere else.
    var message by remember(project) {
        val draft = CommitDrafts.of(project.id)
        // Caret at the end of what was already typed, which is where the user
        // left it and where they expect to carry on.
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    var selected by remember(project) { mutableIntStateOf(-1) }
    var busy by remember(project) { mutableStateOf(false) }
    var error by remember(project) { mutableStateOf<String?>(null) }
    var confirming by remember(project) { mutableStateOf<GitChange?>(null) }
    /**
     * The identity form, shown when git refuses to commit without one. Not a
     * setting and not a dialog: it is the answer to the error immediately
     * above it, and it goes away as soon as it is answered.
     */
    /** Zed's two tabs: what has changed, and what has been committed. */
    var tab by remember(project) { mutableStateOf(GitPanelTab.Changes) }
    var history by remember(project) { mutableStateOf<CommitPage?>(null) }
    /** The commit whose detail is expanded, by sha. */
    var openCommit by remember(project) { mutableStateOf<CommitDetails?>(null) }
    var identityWanted by remember(project) { mutableStateOf(false) }
    var identityName by remember(project) { mutableStateOf(TextFieldValue()) }
    var identityEmail by remember(project) { mutableStateOf(TextFieldValue()) }
    var messageFocused by remember { mutableStateOf(false) }
    // The split button's three toggles, seeded from the objects that outlive
    // the composition — the panel is removed by Escape, and losing a pending
    // amend that way would quietly turn the next Ctrl+Enter into a plain
    // commit. Every write goes back through the object.
    var amendPending by remember(project) { mutableStateOf(AmendDrafts.pending(project.id)) }
    var signoffEnabled by remember(project) { mutableStateOf(CommitToggles.signoff) }
    var skipHooks by remember(project) { mutableStateOf(CommitToggles.skipHooks) }
    /**
     * Zed's pre-flight warnings are blocking prompts with a single OK —
     * `window.prompt(PromptLevel::Warning, …, ["OK"])` (git_panel.rs:3072-3079,
     * 3109-3112) — not toasts, so ours are a dialog and not the error strip.
     */
    var warning by remember(project) { mutableStateOf<String?>(null) }
    /**
     * What the last remote command said when it *worked* — Zed's success
     * `StatusToast` (git_panel.rs:5278-5334), worded by [formatRemoteOutput].
     * Shown in the strip the errors use, and cleared the moment any next
     * command starts, as a toast would have timed out.
     */
    var remoteNotice by remember(project) { mutableStateOf<String?>(null) }
    /**
     * The remote command in flight — Zed's `pending_remote_operation`
     * (git_panel.rs:442-447). What turns the split button's spinner; [busy]
     * alone cannot, because it is also every stage and commit.
     */
    var pendingRemote by remember(project) { mutableStateOf(false) }
    /** A "which remote?" question waiting on the user — see [RemotePickerRequest]. */
    var remotePicker by remember(project) { mutableStateOf<RemotePickerRequest?>(null) }

    val listState = rememberLazyListState()
    // History's own scroll survives a round trip through the Changes tab.
    val historyListState = rememberLazyListState()
    val listFocus = remember { FocusRequester() }
    // Map reads, so once per theme rather than once per row per frame.
    val colours = remember(theme) {
        GitStatusColours.from(theme, theme.color("text"), theme.color("text.muted"))
    }
    LaunchedEffect(focusToken) { listFocus.requestFocus() }

    // One counter, polled; the parse happens only when it moves. Reading the
    // counter is itself a JNI call that schedules a `git status`, so it is off
    // the main thread too — cheap, but it takes the engine's locks. Gated on
    // the lifecycle for the same reason: a backgrounded app must not keep
    // scheduling `git status` runs under proot.
    ResumedEffect(session) {
        pollVersion(
            intervalMs = POLL_MS,
            version = { session.version },
            read = { session.state() to CoreBridge.gitHead(project.id) },
            apply = { (newState, newHead) ->
                state = newState
                head = newHead
            },
        )
    }

    // Loaded when the History tab is opened, and again whenever the commit
    // graph could have moved — a commit made in this panel changes HEAD, so
    // it appears at the top without asking the user to come back, while a
    // save only replaces the status snapshot and reloads nothing. The branch
    // is keyed whole — name, ahead/behind, upstream — because a fetch or push
    // moves upstream refs without touching HEAD, and the ref chips beside the
    // subjects would otherwise go stale. A pure `git tag` moves neither and
    // still does not reload, which is the one residual this key accepts. When
    // the engine cannot name HEAD, the snapshot itself is the key, which is
    // the old trigger: eager, but never stale.
    LaunchedEffect(session, tab, state.branch, head ?: state) {
        if (tab != GitPanelTab.History) return@LaunchedEffect
        history = withContext(Dispatchers.IO) { session.log() }
    }

    val rows = remember(state) { gitPanelRows(state) }
    // A selection is an index into a list that grows and shrinks under it, so
    // it is clamped here rather than trusted.
    val selection = selected.takeIf { it in rows.indices } ?: -1
    val selectedChange = (rows.getOrNull(selection) as? GitPanelRow.FileRow)?.change

    /**
     * Every command: off the main thread, one at a time, and whatever git said
     * about it shown rather than logged.
     *
     * [onSuccess] runs back on the main thread, so a command that clears a
     * field does it here and not from an IO dispatcher.
     */
    fun perform(
        action: suspend () -> String?,
        onFailure: (String) -> Unit = {},
        onSuccess: () -> Unit = {},
    ) {
        // One at a time, and *said* rather than swallowed: a `git add` inside
        // proot is easily a second, and a Ctrl+Enter that vanished into it
        // looks exactly like a keybinding that does not work.
        if (busy) {
            error = "Still running the last git command…"
            return
        }
        busy = true
        error = null
        remoteNotice = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) { action() }
            error = failure
            // Cleared *before* the callbacks: one of them runs the next
            // command — saving an identity commits straight afterwards — and
            // with the flag still set that command refused itself with "still
            // running the last git command", which is this function's own
            // guard talking about a command that had finished.
            busy = false
            if (failure == null) onSuccess() else onFailure(failure)
            // The list still shows what git said *before* the command: the
            // engine invalidates its cache and re-runs `git status` behind a
            // debounce, so the row moves a fraction of a second later, when the
            // poll above sees the counter change. Asking here is what schedules
            // that run rather than waiting for the next poll to.
            state = withContext(Dispatchers.Default) { session.state() }
        }
    }

    fun toggleStaged(change: GitChange) {
        if (change.conflicted) return
        // A file that is staged *and* modified again stages the rest of it,
        // which is what its checkbox showing "partly staged" invites. Only a
        // wholly staged file unstages.
        if (change.staged != null && change.unstaged == null) {
            perform({ session.unstage(listOf(change.path)) })
        } else {
            perform({ session.stage(listOf(change.path)) })
        }
    }

    /**
     * Leave amend mode, or enter it — Zed's `set_amend_pending`
     * (git_panel.rs:8029-8049). Entering saves whatever is typed as the
     * original message and replaces it with HEAD's full message
     * (`load_last_commit_message`, git_panel.rs:2971-2991); leaving — by the
     * Cancel button, by unticking the menu entry, or by the amend commit
     * landing — puts the saved draft back.
     */
    fun setAmendPending(on: Boolean) {
        if (on == amendPending) return
        if (on) {
            val sha = head ?: return
            AmendDrafts.enter(project.id, message.text)
            amendPending = true
            scope.launch {
                val details = withContext(Dispatchers.IO) { session.commitDetails(sha) }
                val last = details?.message?.trimEnd('\n')
                // Only while the amend is still pending: the HEAD message
                // arriving after a quick Cancel must not stamp on the restored
                // draft.
                if (last != null && AmendDrafts.pending(project.id)) {
                    message = TextFieldValue(last, TextRange(last.length))
                    CommitDrafts.put(project.id, last)
                }
            }
        } else {
            val original = AmendDrafts.original(project.id)
            AmendDrafts.clear(project.id)
            amendPending = false
            message = TextFieldValue(original, TextRange(original.length))
            CommitDrafts.put(project.id, original)
        }
    }

    /**
     * Zed's `commit_changes` (git_panel.rs:3055-3148), which is what both the
     * button and Ctrl+Enter run: commit the index when anything is staged;
     * otherwise stage every *tracked* change first — never the untracked ones —
     * and commit that, which is what the "Commit Tracked" label promises.
     */
    fun commit() {
        // Zed's guard and its words (git_panel.rs:3072-3079). Ours has no
        // staged half of a conflict — staging the resolution clears the
        // conflict — so any conflict at all is an unstaged one.
        if (state.conflicts.isNotEmpty()) {
            warning = "There are still conflicts. You must stage these before committing"
            return
        }
        val text = message.text
        // Refused here as well as in the engine, so the button can say why
        // before it is pressed rather than after.
        if (text.isBlank()) {
            error = "Write a commit message first"
            return
        }
        val amend = amendPending
        val hasStaged = state.staged.isNotEmpty()
        val tracked = if (hasStaged) emptyList() else trackedCommitPaths(state.entries)
        // Zed's words (git_panel.rs:3109-3112) — and amend is excused, because
        // folding a better message into HEAD changes nothing on disk.
        if (!hasStaged && tracked.isEmpty() && !amend) {
            warning = "No changes to commit"
            return
        }
        // The message is cleared only on success: one the user would have to
        // retype because git refused the commit is the wrong thing to lose.
        perform(
            action = {
                if (tracked.isNotEmpty()) {
                    // Stage-then-commit, as Zed's stage_entries-before-commit
                    // (git_panel.rs:3114-3122); a stage that failed is the
                    // whole answer, and the commit is not attempted after it.
                    val failure = session.stage(tracked)
                    if (failure != null) return@perform failure
                }
                session.commit(
                    text,
                    amend = amend,
                    signoff = signoffEnabled,
                    noVerify = skipHooks,
                )
            },
            onFailure = { failure ->
                // A fresh Debian has no git identity, guesses one from the
                // hostname and refuses to use it. Every commit in a new
                // userland hits this, and the error alone leaves the user to
                // work out that the fix is two `git config` commands in a
                // shell. Offer the form instead — and prefill it, in case git
                // has half of it already.
                if (needsIdentity(failure)) {
                    identityWanted = true
                    scope.launch {
                        val known = withContext(Dispatchers.IO) { session.identity() }
                        if (known != null) {
                            if (identityName.text.isEmpty() && known.name.isNotBlank()) {
                                identityName = TextFieldValue(known.name)
                            }
                            if (identityEmail.text.isEmpty() && known.email.isNotBlank()) {
                                identityEmail = TextFieldValue(known.email)
                            }
                        }
                    }
                }
            },
        ) {
            // Skip Hooks is spent by the commit it was armed for
            // (git_panel.rs:3131); Signoff, deliberately, is not.
            skipHooks = false
            CommitToggles.skipHooks = false
            if (amend) {
                // Leaving amend mode is what restores the pre-amend draft:
                // Zed does not clear the editor in the amend branch
                // (git_panel.rs:3132-3133).
                setAmendPending(false)
            } else {
                message = TextFieldValue()
                CommitDrafts.clear(project.id)
            }
        }
    }

    /**
     * The `git::Amend` action, two-phase as in Zed (git_panel.rs:2944-2963):
     * the first Ctrl+Shift+Enter only *enters* amend mode — the button relabels
     * and HEAD's message fills the editor for editing — and the second performs
     * the commit. Nothing to amend in a repository with no commits.
     */
    fun amend() {
        if (head == null) return
        if (amendPending) commit() else setAmendPending(true)
    }

    fun toggleSignoff() {
        signoffEnabled = !signoffEnabled
        CommitToggles.signoff = signoffEnabled
    }

    fun toggleSkipHooks() {
        skipHooks = !skipHooks
        CommitToggles.skipHooks = skipHooks
    }

    /**
     * A remote command through [perform]: one at a time, the split button's
     * spinner while it runs, and the outcome *said* — Zed's toast sentence on
     * success ([formatRemoteOutput]), git's own refusal in the error strip on
     * failure, where Zed shows a "git {fetch|pull|push} failed" toast with a
     * log view (notifications.rs:36-73).
     *
     * There is no credential helper inside the guest, so an HTTPS remote will
     * ask for a password nobody can type and git will fail — with its own
     * words, which is what the strip below shows. SSH with a key in the
     * userland's `~/.ssh` works.
     */
    fun runRemote(action: RemoteAction, command: () -> RemoteOpResult) {
        var toast: RemoteToast? = null
        perform(
            action = {
                // Set here, past [perform]'s busy guard, so a refused second
                // command never claims the spinner.
                pendingRemote = true
                val result = command()
                if (result.ok) toast = formatRemoteOutput(action, result)
                result.error
            },
            onFailure = { pendingRemote = false },
            onSuccess = {
                pendingRemote = false
                remoteNotice = toast?.message
            },
        )
    }

    /**
     * Zed's `get_remote` (git_panel.rs:4130-4175): the branch's configured
     * remote for that direction first — skipped when [alwaysSelect], which is
     * what makes Push To always ask — then the whole `git remote -v` list,
     * where none at all is [onNone]'s problem, exactly one picks itself with
     * no modal, and several go to the picker (picker_prompt.rs:27-31).
     */
    fun resolveRemote(
        branch: String,
        forPush: Boolean,
        alwaysSelect: Boolean,
        onNone: () -> Unit,
        onRemote: (String) -> Unit,
    ) {
        scope.launch {
            val configured = if (alwaysSelect) {
                null
            } else {
                withContext(Dispatchers.IO) { session.branchRemote(branch, forPush) }
            }
            if (configured != null) {
                onRemote(configured)
                return@launch
            }
            val listing = withContext(Dispatchers.IO) { session.remotes() }
            if (listing.error != null) {
                error = listing.error
                return@launch
            }
            val names = listing.remotes.map { it.name }
            when {
                names.isEmpty() -> onNone()
                names.size == 1 -> onRemote(names.first())
                else -> remotePicker = RemotePickerRequest(
                    // Zed's prompt — pulls included: the same helper serves
                    // both directions (git_panel.rs:4160-4166).
                    prompt = "Pick which remote to push to",
                    options = names,
                    onPick = onRemote,
                )
            }
        }
    }

    /**
     * Zed's `git::Fetch` and `git::FetchFrom` (git_panel.rs:3637-3732):
     * [fetchAll] is the plain Fetch — `git fetch --all` — while Fetch From
     * lists the remotes, appends a "Fetch all remotes" row when there are
     * several (git_panel.rs:3653-3655), and fetches the one picked. No
     * remotes at all is silently nothing, as in Zed (git_panel.rs:3705-3707).
     */
    fun fetch(fetchAll: Boolean) {
        if (fetchAll) {
            runRemote(RemoteAction.Fetch(null)) { session.fetch(null) }
            return
        }
        scope.launch {
            val listing = withContext(Dispatchers.IO) { session.remotes() }
            if (listing.error != null) {
                error = listing.error
                return@launch
            }
            val names = listing.remotes.map { it.name }
            when {
                names.isEmpty() -> {}
                names.size == 1 -> runRemote(RemoteAction.Fetch(names.first())) {
                    session.fetch(names.first())
                }
                else -> remotePicker = RemotePickerRequest(
                    // Zed's prompt (git_panel.rs:3660); the extra row's label
                    // is `FetchOptions::All.name()` (repository.rs:664-669).
                    prompt = "Pick which remote to fetch",
                    options = names + FetchAllRemotes,
                    onPick = { choice ->
                        val remote = choice.takeUnless { it == FetchAllRemotes }
                        runRemote(RemoteAction.Fetch(remote)) { session.fetch(remote) }
                    },
                )
            }
        }
    }

    /**
     * Zed's `git::Pull` / `git::PullRebase` (git_panel.rs:3830-3892). The
     * branch name joins the argv only when the branch has no upstream, and
     * the engine is what knows that.
     */
    fun pull(rebase: Boolean) {
        // No branch, no pull — the handler's own early-return (git_panel.rs:3837).
        val branch = state.branch?.name ?: return
        resolveRemote(
            branch = branch,
            forPush = false,
            alwaysSelect = false,
            // Pull with no remotes is silently nothing (git_panel.rs:3850-3854).
            onNone = {},
        ) { remote ->
            runRemote(RemoteAction.Pull(remote)) { session.pull(branch, remote, rebase) }
        }
    }

    /**
     * Push, or publish a branch that has no upstream yet — and Force Push and
     * Push To, which are the same command with a flag or a question in front
     * (git_panel.rs:3894-3986).
     *
     * Zed's own button says "Publish" for the no-upstream case and shows the
     * push for the first; the difference is `-u`, and it is the difference
     * between "send these commits" and "make this branch exist on the remote".
     * [force] is `--force-with-lease`, never plain `--force`, and the lease is
     * the only safety Zed puts in front of it.
     */
    fun push(force: Boolean = false, selectRemote: Boolean = false) {
        val branch = state.branch ?: return
        // The handler's early-return on a detached HEAD (git_panel.rs:3908).
        val name = branch.name ?: return
        resolveRemote(
            branch = name,
            forPush = true,
            alwaysSelect = selectRemote,
            onNone = {
                // Zed git_panel.rs:3941
                error = "No remote available to push to. Add a remote to be able to publish changes."
            },
        ) { remote ->
            runRemote(RemoteAction.Push(name, remote)) {
                session.push(
                    name,
                    remote,
                    // Publish and Republish both: no upstream, or an upstream
                    // whose remote branch is gone (git_panel.rs:3920-3929).
                    // Force wins over it in the argv, exactly as in Zed's
                    // options (repository.rs:2717-2727).
                    setUpstream = !branch.hasUpstream || branch.upstreamGone,
                    force = force,
                )
            }
        }
    }

    /** Save the identity, then commit — which is what the user asked for. */
    fun saveIdentity() {
        perform({ session.setIdentity(identityName.text, identityEmail.text) }) {
            identityWanted = false
            commit()
        }
    }

    /**
     * Discard, from every route to it — the menu, the row, and Delete.
     *
     * A conflict never reaches the dialog. `git restore --source=HEAD` on an
     * unmerged path does not refuse: it keeps "ours", stages it, and leaves the
     * merge half-done with nothing on screen to say so. The engine refuses it
     * too; this is the half that can explain *why* without a round trip.
     */
    fun requestDiscard(change: GitChange) {
        val refusal = discardRefusal(change)
        if (refusal != null) {
            error = refusal
            return
        }
        confirming = change
    }

    /** Walk the file rows, stepping over the section headers between them. */
    fun move(delta: Int) {
        val stops = rows.indices.filter { rows[it] is GitPanelRow.FileRow }
        if (stops.isEmpty()) return
        val at = stops.indexOf(selection)
        val next = when {
            at < 0 -> if (delta > 0) 0 else stops.lastIndex
            else -> (at + delta).coerceIn(0, stops.lastIndex)
        }
        selected = stops[next]
        scope.launch { listState.animateScrollToItem(stops[next]) }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            // The panel itself is the focus target the arrows talk to. The
            // commit box takes focus away from it while it is being typed in,
            // which is exactly what `messageFocused` below is watching for.
            .focusRequester(listFocus)
            .focusable()
            // The list's keys are taken before the list sees them, and the
            // commit box's are left alone while it has the caret — Space and
            // Backspace mean "stage" and "discard" in a list and something else
            // entirely in a text field.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.isCtrlPressed) {
                    // Zed's `ctrl-enter` for commit and `ctrl-shift-enter` for
                    // amend (default-linux.json:1054-1055), and both work from
                    // the message box as well — that is where they are wanted.
                    val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (isEnter) {
                        if (event.isShiftPressed) amend() else commit()
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (event.key == Key.Escape) {
                    onDismiss()
                    return@onPreviewKeyEvent true
                }
                if (messageFocused) return@onPreviewKeyEvent false
                // The keys below act on the Changes list. On the History tab
                // that list is not on screen, and Space or Delete would stage
                // or discard a file the user cannot see — silent git mutation
                // from the keyboard.
                if (tab != GitPanelTab.Changes) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionUp -> { move(-1); true }
                    Key.PageDown -> { move(PAGE_ROWS); true }
                    Key.PageUp -> { move(-PAGE_ROWS); true }
                    Key.Enter, Key.NumPadEnter -> {
                        val change = selectedChange
                        if (change == null) move(1) else onOpenDiff(change.path)
                        true
                    }
                    // Zed's `space: git::ToggleStaged`.
                    Key.Spacebar -> {
                        selectedChange?.let(::toggleStaged)
                        true
                    }
                    // Zed's `delete` / `backspace: git::RestoreFile`, which it
                    // also binds with `skip_prompt: false`. Ours has no version
                    // that skips the prompt.
                    Key.Delete, Key.Backspace -> {
                        selectedChange?.let(::requestDiscard)
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.color("panel.background"))
        ) {
            // The branch on the left, the remote split button on the right —
            // Zed's `PanelRepoFooter` row (git_panel.rs:8711-8746), worn as
            // this panel's header: the button at the top right, opposite the
            // branch, which is where the phone keeps it on every tab.
            RepoHeader(
                state = state,
                head = head,
                busy = busy,
                pendingRemote = pendingRemote,
                onFetch = { fetch(fetchAll = true) },
                onFetchFrom = { fetch(fetchAll = false) },
                onPull = { pull(rebase = false) },
                onPullRebase = { pull(rebase = true) },
                onPush = { push() },
                onPushTo = { push(selectRemote = true) },
                onForcePush = { push(force = true) },
            )
            TabBar(
                tab = tab,
                changeCount = state.entries.size,
                onTab = {
                    // Re-selecting the open tab is a no-op: it must not throw
                    // away the expanded commit the user is reading.
                    if (it != tab) {
                        tab = it
                        openCommit = null
                    }
                },
            )

            if (tab == GitPanelTab.Changes) {
                ActionBar(
                    state = state,
                    onViewDiff = { onOpenDiff(null) },
                )
            }

            if (tab == GitPanelTab.History) {
                // Zed's History tab is the bare list; the Graph view is ours,
                // so its way in wears the changes header's clothes — the same
                // 32px row, a ghost button at its end (git_panel.rs:5786-5796).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = BarHeight)
                        .padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Ctrl+Enter commits from this tab too; the repo header
                    // above carries the busy mark for every tab now.
                    Spacer(modifier = Modifier.weight(1f))
                    GhostButton(label = "Graph", enabled = true, onClick = onOpenGraph)
                }
                HistoryList(
                    page = history,
                    open = openCommit,
                    listState = historyListState,
                    onOpen = { commit ->
                        if (openCommit?.commit?.sha == commit.sha) {
                            openCommit = null
                        } else {
                            scope.launch {
                                openCommit = withContext(Dispatchers.IO) {
                                    session.commitDetails(commit.sha)
                                }
                            }
                        }
                    },
                    onOpenFile = onOpenFile,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (rows.isEmpty()) {
                    EmptyMessage(state)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        when (row) {
                            is GitPanelRow.SectionRow -> SectionHeader(
                                row = row,
                                enabled = !busy,
                                onStageAll = {
                                    val paths = row.paths
                                    if (paths.isNotEmpty()) {
                                        perform({
                                            if (row.section == GitSection.Staged) {
                                                session.unstage(paths)
                                            } else {
                                                session.stage(paths)
                                            }
                                        })
                                    }
                                },
                            )
                            is GitPanelRow.FileRow -> ChangeRow(
                                change = row.change,
                                section = row.section,
                                colours = colours,
                                isSelected = index == selection,
                                enabled = !busy,
                                onSelect = { selected = index },
                                // Zed opens the *diff* when a change is
                                // clicked, not the file: the question a
                                // changed row asks is "what changed".
                                onOpen = {
                                    selected = index
                                    onOpenDiff(row.change.path)
                                },
                                onToggleStaged = {
                                    selected = index
                                    toggleStaged(row.change)
                                },
                                onDiscard = {
                                    selected = index
                                    requestDiscard(row.change)
                                },
                            )
                        }
                    }
                }
            }

            error?.let { text ->
                HorizontalDivider(color = theme.color("border.variant"))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("error"),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // What a remote command said when it *worked* — the strip the
            // errors use, in quieter clothes: the panel's stand-in for Zed's
            // success StatusToast (git_panel.rs:5278-5334).
            remoteNotice?.let { text ->
                HorizontalDivider(color = theme.color("border.variant"))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted"),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            if (identityWanted) {
                HorizontalDivider(color = theme.color("border.variant"))
                IdentityForm(
                    name = identityName,
                    email = identityEmail,
                    onName = { identityName = it },
                    onEmail = { identityEmail = it },
                    busy = busy,
                    onSave = ::saveIdentity,
                    onDismiss = { identityWanted = false },
                )
            }

            if (tab == GitPanelTab.Changes) {
            // The commit editor's own `border_t_1` in `border`
            // (git_panel.rs:5991-5996).
            HorizontalDivider(color = theme.color("border"))
            CommitBox(
                message = message,
                onMessage = {
                    message = it
                    CommitDrafts.put(project.id, it.text)
                },
                onFocusChanged = { messageFocused = it },
                stagedCount = state.staged.size,
                busy = busy,
                commitLabel = commitButtonLabel(
                    amendPending = amendPending,
                    hasStaged = state.staged.isNotEmpty(),
                    hasTracked = hasTrackedChanges(state.entries),
                ),
                // The menu's Amend entry exists only where a commit does
                // (`has_previous_commit`, git_panel.rs:5563, 5574).
                hasHeadCommit = head != null,
                amendPending = amendPending,
                signoffEnabled = signoffEnabled,
                skipHooks = skipHooks,
                onCommit = ::commit,
                onToggleAmend = { setAmendPending(!amendPending) },
                onToggleSignoff = ::toggleSignoff,
                onToggleSkipHooks = ::toggleSkipHooks,
            )
            // While an amend is pending a banner says what the button will now
            // do, with the way out beside it (git_panel.rs:6125-6150) — Zed
            // hangs it under the commit footer too (git_panel.rs:8356-8361).
            if (amendPending) {
                HorizontalDivider(color = theme.color("border.variant"))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.color("editor.background"))
                        // `py_1p5 px_2 gap_1p5 justify_between` (git_panel.rs:6131-6136).
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        // Zed's banner label (git_panel.rs:6139-6141).
                        text = "This will update your most recent commit.",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("text.muted"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        // Its "Cancel" (git_panel.rs:6143-6148).
                        label = "Cancel",
                        enabled = !busy,
                        onClick = { setAmendPending(false) },
                    )
                }
            }
            }
        }
    }

    // Fetch From, Push To, and any pull or push whose branch names no remote
    // of its own: the "which remote?" modal (picker_prompt.rs:27-42).
    remotePicker?.let { request ->
        RemotePickerDialog(request = request, onDismiss = { remotePicker = null })
    }

    val pending = confirming
    if (pending != null) {
        DiscardDialog(
            change = pending,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                perform({ session.discard(listOf(pending.path)) })
            },
        )
    }

    // A commit turned back before it ran — Zed's blocking warning prompt with
    // its single "OK" (git_panel.rs:3072-3079, 3109-3112).
    warning?.let { text ->
        AlertDialog(
            onDismissRequest = { warning = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { warning = null }) { Text("OK") } },
        )
    }
}

/**
 * Zed's `PanelRepoFooter` — the git-branch icon and the branch on the left,
 * the remote split button on the right, an `px_2` / `py_1p5` row,
 * `justify_between` with a `gap_1` (git_panel.rs:8711-8746). Zed hangs it
 * above the commit editor; this panel wears it as its header. The branch name
 * is a `LabelSize::Small` button label there (git_panel.rs:8687-8692).
 *
 * No branch to speak for — detached HEAD, nothing committed — and there is no
 * remote button at all (git_panel.rs:5851 via [remoteButtonSpec]).
 */
@Composable
private fun RepoHeader(
    state: GitPanelState,
    head: String?,
    busy: Boolean,
    pendingRemote: Boolean,
    onFetch: () -> Unit,
    onFetchFrom: () -> Unit,
    onPull: () -> Unit,
    onPullRebase: () -> Unit,
    onPush: () -> Unit,
    onPushTo: () -> Unit,
    onForcePush: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The `GitBranch` icon leading the row — `IconSize::Small` = 14px,
        // Disabled with a single repository, which is all this app opens
        // (git_panel.rs:8721-8727).
        Image(
            painter = painterResource(R.drawable.ic_ui_git_branch),
            contentDescription = null,
            colorFilter = ColorFilter.tint(theme.color("text.disabled")),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = branchLabel(state, head),
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // The non-remote commands — stages, commits — have no spinner of
        // their own, so their busy mark stays; a running remote command is
        // the split button's own disabled-and-turning state (git_ui.rs:1110-1123).
        if (busy && !pendingRemote) {
            Text(
                text = "…",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
            )
        }
        val spec = remoteButtonSpec(state.branch)
        if (spec != null) {
            RemoteSplitButton(
                spec = spec,
                enabled = !busy,
                remotePending = pendingRemote,
                onFetch = onFetch,
                onFetchFrom = onFetchFrom,
                onPull = onPull,
                onPullRebase = onPullRebase,
                onPush = onPush,
                onPushTo = onPushTo,
                onForcePush = onForcePush,
            )
        }
    }
}

/** The branch's name, or what stands in for one (git_panel.rs:8640-8654). */
private fun branchLabel(state: GitPanelState, head: String?): String {
    if (!state.hasRepo) return "No repository"
    val branch = state.branch ?: return "git"
    val name = branch.name
    return when {
        // The drift arrows are the split button's counts now, as in Zed —
        // the name is only the name.
        name != null && branch.unborn -> "$name · no commits yet"
        name != null -> name
        // A detached HEAD wears the first 8 characters of its sha —
        // `MAX_SHORT_SHA_LEN` — and a repository with no commit at all Zed's
        // "(no branch)" (git_panel.rs:8640-8654).
        head != null -> head.take(8)
        else -> "(no branch)"
    }
}

@Composable
private fun EmptyMessage(state: GitPanelState) {
    val theme = LocalZedTheme.current
    val text = when {
        !state.hasRepo -> "This project is not in a git repository"
        !state.scanned -> "Asking git…"
        // An empty list is what "clean" looks like *and* what "git never ran"
        // looks like. Claiming the first when it is the second told a user
        // with no git in their Debian that their tree was clean.
        !state.ran -> "Could not run git here — ${Userland.backend.displayName} needs git " +
            "installed before the panel can show anything"
        // Zed's words for a clean tree (git_panel.rs:6940). The other cases
        // are ours: Zed never has to explain a missing userland.
        else -> "No changes to commit"
    }
    // Zed centres a muted default-size label in the empty panel
    // (git_panel.rs:6926-6940).
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.padding(24.dp),
        )
    }
}

/**
 * A section header, in Zed's `render_list_header` shape: the same 28px row as
 * an entry, `pl_2p5`/`pr_1`, the title a `LabelSize::Small` in `text.muted`,
 * and the stage-all control a checkbox at the row's end rather than a word
 * (git_panel.rs:7288-7318, 7322-7345). No count — Zed's headers carry none;
 * the tab already says how many. No chevron either: Zed's collapses the
 * section (git_panel.rs:7307-7315) and ours does not, and a disclosure that
 * discloses nothing would be a lie.
 */
@Composable
private fun SectionHeader(
    row: GitPanelRow.SectionRow,
    enabled: Boolean,
    onStageAll: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ListItemHeight)
            .padding(start = RowStartPadding, end = RowEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.section.title,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.weight(1f),
        )
        if (row.section != GitSection.Conflicts) {
            ZedCheckbox(
                checked = row.section == GitSection.Staged,
                partial = false,
                enabled = enabled && row.paths.isNotEmpty(),
                label = if (row.section == GitSection.Staged) "Unstage all" else "Stage all",
                onClick = onStageAll,
            )
        }
    }
}

@Composable
private fun ChangeRow(
    change: GitChange,
    section: GitSection,
    colours: GitStatusColours,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onToggleStaged: () -> Unit,
    onDiscard: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var menuAt by remember { mutableStateOf<DpOffset?>(null) }

    val status = if (section == GitSection.Staged) change.staged else change.unstaged
    val deleted = status == GitFileStatus.Deleted
    // Zed's entry ramp: a selected row is `status.info` at 0.08 alpha, not a
    // `ghost_element` fill — and hover/press on a selected row brighten the
    // same wash to 0.12/0.16 rather than swapping to the ghost pair
    // (git_panel.rs:7616-7640).
    val background = when {
        isSelected && pressed -> theme.color("info").copy(alpha = 0.16f)
        isSelected && hovered -> theme.color("info").copy(alpha = 0.12f)
        isSelected -> theme.color("info").copy(alpha = 0.08f)
        pressed -> theme.color("ghost_element.active", Color.Transparent)
        hovered -> theme.color("ghost_element.hover", Color.Transparent)
        else -> Color.Transparent
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // `list_item_height()` exactly — no minimum, no padding
                // (git_panel.rs:7688-7689).
                .height(ListItemHeight)
                .background(background)
                .pointerHoverIcon(PointerIcon.Hand)
                // The list is the one focus target; rows taking it in turn
                // would fight the arrows for the selection.
                .focusProperties { canFocus = false }
                .onSecondaryClick { at -> onSelect(); menuAt = at }
                .combinedClickable(
                    interactionSource = interaction,
                    // Zed swaps a row's colour instantly and has no ripple.
                    indication = null,
                    onLongClick = { onSelect(); menuAt = DpOffset.Zero },
                    onClick = onOpen,
                )
                // `pl_2p5` / `pr_1` (git_panel.rs:7690-7691).
                .padding(start = RowStartPadding, end = RowEndPadding),
            verticalAlignment = Alignment.CenterVertically,
            // `gap_1p5` between the name row and the checkbox
            // (git_panel.rs:7692).
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Zed leads with the status mark, tinted by the
            // `version_control.*` colour for that status (git_ui.rs:1185-1207);
            // ours is git's letter where Zed draws an icon, in a fixed slot so
            // the filenames line up.
            Text(
                text = statusLetter(change, section),
                style = MaterialTheme.typography.labelMedium,
                color = colours.colorFor(status.forColours(), dimIgnored = false),
                maxLines = 1,
                modifier = Modifier.width(14.dp),
            )
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // With the mark carrying the status, the filename is plain
                // `text` and the directory `text.muted` — a deleted file goes
                // `text.disabled` and struck through instead of shouting in
                // red (git_panel.rs:7571-7592, 7965-8003).
                Text(
                    text = change.name,
                    style = if (deleted) {
                        MaterialTheme.typography.bodyMedium
                            .copy(textDecoration = TextDecoration.LineThrough)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = theme.color(if (deleted) "text.disabled" else "text"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = change.directory,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color(if (deleted) "text.disabled" else "text.muted"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Zed separates name from path with a literal space
                    // (git_panel.rs:7978).
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            // Zed's staging checkbox sits at the *end* of the row
            // (git_panel.rs:7712-7724).
            ZedCheckbox(
                checked = change.staged != null,
                partial = change.staged != null && change.unstaged != null,
                enabled = enabled && !change.conflicted,
                label = if (change.staged != null) "Unstage ${change.path}" else "Stage ${change.path}",
                onClick = onToggleStaged,
            )
        }

        ContextMenu(
            expanded = menuAt != null,
            onDismiss = { menuAt = null },
            offset = menuAt ?: DpOffset.Zero,
            items = listOfNotNull(
                ContextMenuItem("Open", onClick = onOpen),
                if (change.conflicted) {
                    null
                } else if (change.staged != null && change.unstaged == null) {
                    ContextMenuItem("Unstage", shortcut = "Space", enabled = enabled, onClick = onToggleStaged)
                } else {
                    ContextMenuItem("Stage", shortcut = "Space", enabled = enabled, onClick = onToggleStaged)
                },
                // Named for what it will actually do to *this* file, and it
                // opens the confirmation rather than doing it. A conflicted row
                // keeps the item and gets the reason it cannot: an item that
                // silently vanishes teaches nothing.
                ContextMenuItem(
                    label = discardLabel(change),
                    shortcut = "Delete",
                    enabled = enabled,
                    onClick = onDiscard,
                ),
            ),
        )
    }
}

/**
 * Zed's checkbox, as the git panel builds it: `Checkbox::new(..).fill()
 * .elevation(ElevationIndex::Surface)` (git_panel.rs:7718-7720). The container
 * is a 20px square (toggle.rs:180-182); the box inside it is `size_4` = 16px,
 * `rounded_xs` 2px, bordered 1px in `border` — `border.variant` when disabled
 * — and filled `editor.background`, which is what `darker_bg` resolves to at
 * Surface elevation (toggle.rs:169-178, 226-236; elevation.rs:108-111). The
 * mark is a check or a dash in `Color::Selected` → `text.accent`
 * (toggle.rs:186-208; color.rs:108). Hovering fades the border to 0.7 alpha
 * (toggle.rs:215).
 *
 * 20dp is under the 40dp thumb rule; per the 2026-08-17 density decision that
 * is accepted, and staging keeps its other routes — Space on the selected row
 * and the long-press menu's Stage/Unstage item.
 */
@Composable
private fun ZedCheckbox(
    checked: Boolean,
    partial: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border = when {
        !enabled -> theme.color("border.variant")
        hovered -> theme.color("border").copy(alpha = 0.7f)
        else -> theme.color("border")
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            // Instant, rippleless, as every toggle in Zed.
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (enabled) {
                        theme.color("editor.background")
                    } else {
                        theme.color("element.disabled").copy(alpha = 0.6f)
                    }
                )
                .border(1.dp, border, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked || partial) {
                Text(
                    text = if (partial) "–" else "✓",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color(if (enabled) "text.accent" else "text.disabled"),
                )
            }
        }
    }
}

/**
 * The commit editor and its footer, in Zed's anatomy: not a rounded input but
 * a bare region of `editor.background` under a 1px top border (drawn by the
 * caller), the message set in the *buffer* font at
 * `git_commit_buffer_font_size` — pinned to **12** in Zed's own defaults
 * (default.json:81), so the buffer_font_size fallback in
 * theme_settings/src/settings.rs:446-451 never applies at defaults —
 * exactly [CommitEditorLines] lines tall, with `pt_2`/`px_2` around the text
 * (git_panel.rs:6002-6006). Below it, a `p_1p5` footer row with the commit
 * button at its end (git_panel.rs:6021-6045).
 */
@Composable
private fun CommitBox(
    message: TextFieldValue,
    onMessage: (TextFieldValue) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    stagedCount: Int,
    busy: Boolean,
    /** What the split button's left half reads — see [commitButtonLabel]. */
    commitLabel: String,
    /** Whether HEAD names a commit at all, which is what Amend needs. */
    hasHeadCommit: Boolean,
    amendPending: Boolean,
    signoffEnabled: Boolean,
    skipHooks: Boolean,
    onCommit: () -> Unit,
    onToggleAmend: () -> Unit,
    onToggleSignoff: () -> Unit,
    onToggleSkipHooks: () -> Unit,
) {
    val theme = LocalZedTheme.current
    // sp treated as dp, which at font scale 1 is what Zed's px-per-line rule
    // means; a user's font scale then grows the text but not the box, which
    // scrolls — the list keeping the panel matters more than the sixth line.
    val fontSize = CommitBufferFontSize
    val lineHeight = fontSize * BufferLineHeight
    val editorStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = BufferFontFamily,
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        color = theme.color("text"),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((lineHeight * CommitEditorLines).dp + 8.dp)
                .background(theme.color("editor.background"))
                .pointerHoverIcon(PointerIcon.Text)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
        ) {
            BasicTextField(
                value = message,
                onValueChange = onMessage,
                textStyle = editorStyle,
                cursorBrush = SolidColor(theme.cursor),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { onFocusChanged(it.isFocused) },
            )
            if (message.text.isEmpty()) {
                Text(
                    // Zed's own placeholder (git_panel.rs:1109).
                    text = "Enter commit message",
                    style = editorStyle,
                    color = theme.color("text.placeholder"),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.color("editor.background"))
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Zed's footer keeps an AI message button here; ours counts what
            // will be committed, which is the more honest use of the corner.
            Text(
                text = if (stagedCount == 0) {
                    "Nothing staged"
                } else if (stagedCount == 1) {
                    "1 file staged"
                } else {
                    "$stagedCount files staged"
                },
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                modifier = Modifier.weight(1f),
            )
            CommitSplitButton(
                label = commitLabel,
                // Enabled with nothing staged on purpose: git's refusal is the
                // honest explanation, and a button that greys out for reasons
                // the user cannot see is worse than one that answers.
                enabled = !busy && message.text.isNotBlank(),
                hasHeadCommit = hasHeadCommit,
                amendPending = amendPending,
                signoffEnabled = signoffEnabled,
                skipHooks = skipHooks,
                onCommit = onCommit,
                onToggleAmend = onToggleAmend,
                onToggleSignoff = onToggleSignoff,
                onToggleSkipHooks = onToggleSkipHooks,
            )
        }
    }
}

/**
 * Zed's ghost button: `ButtonSize::Default` = 22px tall, `rounded_sm`, `px`
 * Base04 = 4px, and the Subtle ramp — transparent at rest,
 * `ghost_element.hover` under the pointer, `ghost_element.active` pressed
 * (button_like.rs:469, 796-803; 245-330). The label is `LabelSize::Small` in
 * `text.muted`, as the changes header's own buttons wear it
 * (git_panel.rs:5805-5809).
 */
@Composable
private fun GhostButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // As with [FilledButton]: the 22dp ghost box is the visual, the tap
    // target is the taller invisible wrapper (density decision, DECISIONS.md).
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        !enabled -> Color.Transparent
                        pressed -> theme.color("ghost_element.active", Color.Transparent)
                        hovered -> theme.color("ghost_element.hover", Color.Transparent)
                        else -> Color.Transparent
                    }
                )
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color(if (enabled) "text.muted" else "text.disabled"),
            )
        }
    }
}

/**
 * Zed's filled small button — the commit and remote buttons are `ButtonLike`s
 * at `ButtonSize::Compact` = 18px on the ModalSurface layer, whose fill is the
 * `background` token (git_panel.rs:6072-6075; button_like.rs:470, 200-207),
 * with the 1px `border`-at-0.8 ring their `SplitButton` wrapper paints
 * (split_button.rs:71-73, 88-95) and a `LabelSize::Small` label. Hover fades
 * the fill to half (button_like.rs:263-272); press is `element.active`
 * (button_like.rs:317-321).
 */
@Composable
private fun FilledButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    shortcut: String? = null,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = theme.color("background")
    // The 18dp pill is the *visual*; the tap target is this outer box, held
    // open to 30dp with a little horizontal slack — the density decision's
    // remedy for a small control with no keyboard twin: expand the hit area
    // invisibly, never the drawing.
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = shortcut?.let { "$label ($it)" } ?: label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        pressed && enabled -> theme.color("element.active")
                        hovered && enabled -> fill.copy(alpha = fill.alpha * 0.5f)
                        else -> fill
                    }
                )
                .border(
                    1.dp,
                    theme.color("border").copy(alpha = 0.8f),
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color(if (enabled) "text" else "text.disabled"),
            )
        }
    }
}

/**
 * Zed's `SplitButton` around the commit action (git_panel.rs:6071-6122): the
 * left half is the commit button in [FilledButton]'s clothes, rounded only on
 * its left; the right half a 20px chevron that deploys the picker menu — down
 * while closed, up while it is open (git_ui.rs:1150-1167). One ring of
 * `border` at 0.8 wraps both halves, with a matching divider between them
 * (split_button.rs:71-95). The menu anchors below with Zed's 2px drop
 * (git_panel.rs:5613-5617).
 */
@Composable
private fun CommitSplitButton(
    label: String,
    enabled: Boolean,
    hasHeadCommit: Boolean,
    amendPending: Boolean,
    signoffEnabled: Boolean,
    skipHooks: Boolean,
    onCommit: () -> Unit,
    onToggleAmend: () -> Unit,
    onToggleSignoff: () -> Unit,
    onToggleSkipHooks: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }
    val leftInteraction = remember { MutableInteractionSource() }
    val leftHovered by leftInteraction.collectIsHoveredAsState()
    val leftPressed by leftInteraction.collectIsPressedAsState()
    val rightInteraction = remember { MutableInteractionSource() }
    val rightHovered by rightInteraction.collectIsHoveredAsState()
    val rightPressed by rightInteraction.collectIsPressedAsState()
    val fill = theme.color("background")
    val ring = theme.color("border").copy(alpha = 0.8f)
    val shape = RoundedCornerShape(4.dp)
    // As [FilledButton]: the 18dp pill is the visual, the tap target is the
    // taller invisible wrapper (density decision, DECISIONS.md).
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(18.dp)
                .clip(shape)
                .border(1.dp, ring, shape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(
                        if (enabled) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = leftInteraction,
                                    indication = null,
                                    onClickLabel = "$label (Ctrl Enter)",
                                    onClick = onCommit,
                                )
                        } else {
                            Modifier
                        }
                    )
                    .background(
                        when {
                            leftPressed && enabled -> theme.color("element.active")
                            leftHovered && enabled -> fill.copy(alpha = fill.alpha * 0.5f)
                            else -> fill
                        }
                    )
                    // The label wears `mr_0p5` inside its half (git_panel.rs:6077-6080).
                    .padding(start = 4.dp, end = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color(if (enabled) "text" else "text.disabled"),
                )
            }
            // `border_l` between the halves (split_button.rs:88-95).
            Box(Modifier.width(1.dp).fillMaxHeight().background(ring))
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .fillMaxHeight()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = rightInteraction,
                        indication = null,
                        onClickLabel = "Commit options",
                    ) { menuOpen = !menuOpen }
                    .background(
                        when {
                            rightPressed || menuOpen -> theme.color("element.active")
                            rightHovered -> fill.copy(alpha = fill.alpha * 0.5f)
                            else -> fill
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(
                        if (menuOpen) R.drawable.ic_ui_chevron_up else R.drawable.ic_ui_chevron_down
                    ),
                    contentDescription = if (menuOpen) "Close commit options" else "Commit options",
                    colorFilter = ColorFilter.tint(theme.color("text")),
                    // `IconSize::XSmall` = 12px (git_ui.rs:1160).
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        ContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            offset = DpOffset(0.dp, 2.dp),
            items = listOfNotNull(
                // Only where a commit exists to amend (git_panel.rs:5563, 5574);
                // ticking it is `toggle_amend_pending` (git_panel.rs:5575-5590).
                if (hasHeadCommit) {
                    ContextMenuItem(
                        label = "Amend",
                        shortcut = "Ctrl Shift Enter",
                        checked = amendPending,
                        onClick = onToggleAmend,
                    )
                } else {
                    null
                },
                // No default binding, so no chord (git_panel.rs:5592-5598).
                ContextMenuItem(
                    label = "Signoff",
                    checked = signoffEnabled,
                    onClick = onToggleSignoff,
                ),
                // Aside and all: the literal flag it arms (git_panel.rs:5599-5608).
                ContextMenuItem(
                    label = "Skip Hooks",
                    checked = skipHooks,
                    aside = "git commit --no-verify",
                    onClick = onToggleSkipHooks,
                ),
            ),
        )
    }
}

/**
 * The confirmation. It names the file, and it says which of discard's three
 * meanings this one is — restored from the last commit, moved to the trash, or
 * a rename undone, which is both at once. They are not the same promise, and
 * only the first is reversible with a `git` command.
 *
 * A conflicted row never gets here: [discardRefusal] turns it back at the door,
 * because there is no wording that would make "keep ours and say nothing" the
 * thing the user meant.
 */
@Composable
private fun DiscardDialog(change: GitChange, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val renamedFrom = change.original
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    renamedFrom != null -> "Undo the rename of ${change.name}?"
                    change.inHead -> "Discard changes to ${change.name}?"
                    else -> "Move ${change.name} to the trash?"
                }
            )
        },
        text = {
            Text(
                buildString {
                    append(change.path)
                    append("\n\n")
                    when {
                        // Both halves, named, because the destructive half is
                        // the one the old name does not cover: the last commit
                        // has never held this file under its new name, so what
                        // has been typed into it since is not in git anywhere.
                        renamedFrom != null -> append(
                            "$renamedFrom comes back as the last commit holds it, and " +
                                "${change.name} goes to the app's trash — the commit has " +
                                "never seen it under that name, so git has no copy of " +
                                "anything you have written in it."
                        )
                        change.inHead -> append(
                            "The file goes back to what the last commit holds. " +
                                "Everything you have changed in it since then is gone, " +
                                "and git has no copy of it."
                        )
                        change.isDirectory -> append(
                            "The last commit has never seen this folder, so there is " +
                                "nothing to restore it from. It goes to the app's trash " +
                                "with everything in it, rather than being deleted."
                        )
                        else -> append(
                            "The last commit has never seen this file, so there is " +
                                "nothing to restore it from. It goes to the app's " +
                                "trash rather than being deleted."
                        )
                    }
                    append(
                        "\n\nA copy open in the editor keeps whatever you have not " +
                            "saved; the tab will say the file changed underneath it."
                    )
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    when {
                        renamedFrom != null -> "Undo the rename"
                        change.inHead -> "Discard"
                        else -> "Move to the trash"
                    }
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Why discarding this row cannot be offered, or null when it can.
 *
 * One conflict, one sentence, every route to discard. `git restore
 * --source=HEAD` on an unmerged path is not refused by git: it keeps "ours",
 * marks the path resolved and staged, exits 0 and leaves `MERGE_HEAD` set — so
 * the panel would go quiet, the section would empty, and the next commit would
 * drop the incoming side of the merge with nothing ever said about it.
 */
internal fun discardRefusal(change: GitChange): String? = when {
    change.conflicted ->
        "${change.name} has a merge conflict. Resolve it in the editor and stage the " +
            "result — discarding it would keep one side of the merge and say nothing."
    else -> null
}

/** What the discard item says it will do to *this* row. */
internal fun discardLabel(change: GitChange): String = when {
    // A rename is both halves at once, and "discard changes" describes neither.
    change.original != null -> "Undo the rename…"
    change.inHead -> "Discard changes…"
    change.isDirectory -> "Move the folder to the trash…"
    else -> "Move to the trash…"
}

/**
 * Commit messages typed but not yet committed, one per project.
 *
 * The panel is a composable that gets *removed*: Escape closes it, and on a
 * compact screen opening a file takes the work area away from it. So its own
 * composition cannot be where a half-written commit message lives — that is the
 * one thing here nobody will retype. Kept beside the panel rather than hoisted
 * into the workspace because nothing else reads it, and touched only from the
 * main thread, like the composition that owns it.
 */
internal object CommitDrafts {
    private val drafts = mutableMapOf<Long, String>()

    fun of(project: Long): String = drafts[project] ?: ""

    fun put(project: Long, message: String) {
        if (message.isEmpty()) drafts.remove(project) else drafts[project] = message
    }

    /** After a commit that landed: that message has done its job. */
    fun clear(project: Long) {
        drafts.remove(project)
    }
}

/**
 * A pending amend, one per project, outliving the composition as
 * [CommitDrafts] does — Zed keeps `amend_pending` and the pre-amend
 * `original_message` per work directory and restores both on load
 * (`SerializedCommitMessage`, git_panel.rs:496-504, 1541-1558). Presence in
 * the map *is* the pending flag; the value is the draft the amend displaced,
 * put back when the amend is cancelled or lands. Main thread only, like the
 * composition that reads it.
 */
internal object AmendDrafts {
    private val originals = mutableMapOf<Long, String>()

    fun pending(project: Long): Boolean = project in originals

    fun original(project: Long): String = originals[project] ?: ""

    fun enter(project: Long, original: String) {
        originals[project] = original
    }

    fun clear(project: Long) {
        originals.remove(project)
    }
}

/**
 * The split button's other two toggles. Signoff keeps its setting the way a
 * draft keeps its words — Zed serializes `signoff_enabled` and never resets it
 * after a commit (git_panel.rs:489-494, 1466-1495). Skip Hooks is deliberately
 * weaker: never persisted, and *spent* — reset to false — by every commit that
 * lands (git_panel.rs:3131, 8059-8064), because `--no-verify` is a decision
 * about one commit, not a policy.
 */
internal object CommitToggles {
    var signoff: Boolean = false
    var skipHooks: Boolean = false
}

/**
 * The split button's title — Zed's `commit_button_title()`
 * (git_panel.rs:5642-5656). Exactly four labels: staging anything makes it a
 * plain "Commit"/"Amend" of the index; with nothing staged the button promises
 * to stage every tracked change first — except that an amend with nothing
 * tracked either is still just "Amend", since amending needs no changes at
 * all. "Commit Tracked" shows even over a clean tree; whether it is *enabled*
 * is a different function's answer, there as here.
 */
internal fun commitButtonLabel(
    amendPending: Boolean,
    hasStaged: Boolean,
    hasTracked: Boolean,
): String = when {
    !amendPending -> if (hasStaged) "Commit" else "Commit Tracked"
    hasStaged || !hasTracked -> "Amend"
    else -> "Amend Tracked"
}

/**
 * Zed's `FileStatus::is_created` (crates/git/src/status.rs:183-192): untracked,
 * or Added on either half of the pair. A conflict is its own category and never
 * "created" — Zed's Unmerged variant falls through the same match arm.
 */
internal fun isCreatedChange(change: GitChange): Boolean {
    if (change.conflicted) return false
    return change.staged == GitFileStatus.Added || change.staged == GitFileStatus.Untracked ||
        change.unstaged == GitFileStatus.Added || change.unstaged == GitFileStatus.Untracked
}

/**
 * What "Commit Tracked" stages before it commits — Zed's
 * `change_entries_by_path()` filtered to `!status.is_created()`
 * (git_panel.rs:3103-3107): every changed path *except* the untracked and
 * newly added ones. A conflicted path passes the filter there as here; the
 * conflicts guard has already turned the commit back before this list is
 * asked for.
 */
internal fun trackedCommitPaths(entries: List<GitChange>): List<String> =
    entries.filterNot(::isCreatedChange).map { it.path }

/**
 * Zed's `has_tracked_changes()` = `tracked_count > 0` (git_panel.rs:5162-5164),
 * whose count buckets conflicted and created entries elsewhere
 * (git_panel.rs:5129-5139) — so for the *label*, a conflict is not a tracked
 * change, even though the commit filter above would carry it.
 */
internal fun hasTrackedChanges(entries: List<GitChange>): Boolean =
    entries.any { !it.conflicted && !isCreatedChange(it) }

/**
 * Which section a row belongs to. The titles are Zed's own for grouping by
 * staging: "Conflicts", "Staged", "Unstaged" (git_panel.rs:641-645).
 */
internal enum class GitSection(val title: String) {
    Conflicts("Conflicts"),
    Staged("Staged"),
    Changes("Unstaged"),
}

/** The flat list the panel draws: section headers and file rows, in order. */
internal sealed interface GitPanelRow {
    val key: String

    data class SectionRow(
        val section: GitSection,
        /** Every path in it, for the section's own stage-all action. */
        val paths: List<String>,
    ) : GitPanelRow {
        override val key: String get() = "section:${section.name}"
    }

    data class FileRow(val section: GitSection, val change: GitChange) : GitPanelRow {
        // Keyed by section as well as path: a file that is staged *and*
        // modified again appears in two sections, and two rows sharing a key
        // is a crash in LazyColumn rather than a cosmetic problem.
        override val key: String get() = "${section.name}:${change.path}"
    }
}

/**
 * Group the changes the way Zed's panel does: conflicts first, because they
 * block everything else; then what is staged, next to the commit box that will
 * use it; then everything else.
 *
 * A file can appear twice, in Staged and in Changes. That is not a bug to fix
 * on this side — it is what `MM` means, and hiding half of it would be hiding
 * that staging captured a version of the file that is no longer the one on disk.
 */
internal fun gitPanelRows(state: GitPanelState): List<GitPanelRow> {
    val rows = ArrayList<GitPanelRow>()
    for (section in GitSection.entries) {
        val changes = when (section) {
            GitSection.Conflicts -> state.conflicts
            GitSection.Staged -> state.staged
            GitSection.Changes -> state.unstaged
        }
        if (changes.isEmpty()) continue
        rows += GitPanelRow.SectionRow(section, changes.map { it.path })
        changes.forEach { rows += GitPanelRow.FileRow(section, it) }
    }
    return rows
}

/** The letter git itself uses for that half of the pair. */
private fun statusLetter(change: GitChange, section: GitSection): String {
    if (change.conflicted) return "U"
    val status = if (section == GitSection.Staged) change.staged else change.unstaged
    return when (status) {
        GitFileStatus.Modified -> "M"
        GitFileStatus.Added -> "A"
        GitFileStatus.Deleted -> "D"
        GitFileStatus.Renamed -> "R"
        GitFileStatus.Untracked -> "?"
        GitFileStatus.Conflicted -> "U"
        GitFileStatus.Ignored -> "!"
        null -> ""
    }
}

/**
 * The engine's status, in the vocabulary the theme's colours are keyed by.
 *
 * Two enums of the same name exist because they answer different questions —
 * one is what git said, the other is what a row is painted — and this is the
 * one place they meet.
 */
private fun GitFileStatus?.forColours(): PanelStatus = when (this) {
    GitFileStatus.Modified -> PanelStatus.Modified
    GitFileStatus.Added -> PanelStatus.Added
    GitFileStatus.Deleted -> PanelStatus.Deleted
    GitFileStatus.Renamed -> PanelStatus.Renamed
    GitFileStatus.Conflicted -> PanelStatus.Conflicted
    GitFileStatus.Untracked -> PanelStatus.Untracked
    GitFileStatus.Ignored -> PanelStatus.Ignored
    null -> PanelStatus.None
}

/**
 * git's own complaint, recognised.
 *
 * Matched on the phrases rather than on an exit code because git says this
 * three different ways depending on version and on whether it found half an
 * identity: "unable to auto-detect email address", "Please tell me who you
 * are", and "empty ident name". All three mean the same thing and have the
 * same fix.
 */
internal fun needsIdentity(failure: String): Boolean {
    val text = failure.lowercase()
    return "unable to auto-detect email address" in text ||
        "please tell me who you are" in text ||
        "empty ident name" in text ||
        "no name was given" in text
}

/**
 * Who commits are by, asked at the moment git refuses to guess.
 *
 * It writes `user.name` and `user.email` into the *guest's* global config, so
 * it is answered once per userland rather than once per clone — and then it
 * runs the commit that was refused, because that is what the user pressed.
 */
@Composable
private fun IdentityForm(
    name: TextFieldValue,
    email: TextFieldValue,
    onName: (TextFieldValue) -> Unit,
    onEmail: (TextFieldValue) -> Unit,
    busy: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "git records who made each commit, and has nobody to record.",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
        )
        IdentityField(value = name, onValue = onName, placeholder = "Your name")
        IdentityField(
            value = email,
            onValue = onEmail,
            placeholder = "you@example.com",
            keyboard = KeyboardType.Email,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton("Not now", enabled = !busy, onClick = onDismiss)
            Spacer(modifier = Modifier.width(8.dp))
            FilledButton(
                "Save and commit",
                enabled = !busy && name.text.isNotBlank() && email.text.isNotBlank(),
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun IdentityField(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val theme = LocalZedTheme.current
    // Zed's input: `min_h_8` 32px, `rounded_md` 6px, 1px `border`, `pl_2` /
    // `pr_1`, the text on `py_1` (search_bar.rs:69-79; buffer_search.rs:233).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("editor.background"))
            .border(1.dp, theme.color("border"), RoundedCornerShape(FieldRadius))
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.placeholder", theme.color("text.muted")),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.color("editor.foreground")),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Zed's two tabs (`git_panel.rs:507`). */
enum class GitPanelTab { Changes, History }

/**
 * The tab strip, stroke for stroke from Zed's `render_tab_bar`
 * (git_panel.rs:6257-6325): `Tab::container_height` = 32px (tab.rs:83-85),
 * each half `flex_1` and centred with a `gap_1`. The *inactive* tab is set
 * back — `editor.background` at 0.6 alpha under a 1px bottom border in
 * `border` at 0.6 — while the active tab has neither, so it opens into the
 * panel below (git_panel.rs:6276-6282; gpui's unset border colour is
 * transparent, style.rs:746). Both swap to `element.hover` under the pointer
 * (git_panel.rs:6277), instantly, and a `BorderFaded` divider stands between
 * them (git_panel.rs:6313-6317; divider.rs:30).
 */
@Composable
private fun TabBar(tab: GitPanelTab, changeCount: Int, onTab: (GitPanelTab) -> Unit) {
    val theme = LocalZedTheme.current
    val fadedBorder = theme.color("border").copy(alpha = 0.6f)
    Row(modifier = Modifier.fillMaxWidth().height(BarHeight)) {
        for (candidate in GitPanelTab.entries) {
            val active = candidate == tab
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        when {
                            hovered -> theme.color("element.hover")
                            !active -> theme.color("editor.background").copy(alpha = 0.6f)
                            else -> Color.Transparent
                        }
                    )
                    .drawBehind {
                        if (!active) {
                            drawRect(
                                color = fadedBorder,
                                topLeft = Offset(0f, size.height - 1.dp.toPx()),
                                size = Size(size.width, 1.dp.toPx()),
                            )
                        }
                    }
                    .clickable(
                        interactionSource = interaction,
                        // Instant swap, no ripple — Zed's tabs never animate.
                        indication = null,
                        onClickLabel = "Show ${candidate.name}",
                    ) { onTab(candidate) }
                    .pointerHoverIcon(PointerIcon.Hand),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) theme.color("text") else theme.color("text.muted"),
                )
                // The count rides the Changes tab as a Small muted "(n)"
                // (git_panel.rs:6284-6290).
                if (candidate == GitPanelTab.Changes && changeCount > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "($changeCount)",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("text.muted"),
                    )
                }
            }
            if (candidate == GitPanelTab.Changes) {
                VerticalDivider(thickness = 1.dp, color = fadedBorder)
            }
        }
    }
}

/**
 * What has been committed — Zed's History tab.
 *
 * A row is the subject, then who and when and which commit, which is what
 * Zed's own rows carry. Tapping one expands what it touched underneath it
 * rather than opening a view of its own: a phone has one work area, and the
 * question "what was in that commit" is usually answered by a glance at the
 * file list.
 */
@Composable
private fun HistoryList(
    page: CommitPage?,
    open: CommitDetails?,
    onOpen: (Commit) -> Unit,
    onOpenFile: (String) -> Unit,
    /** Hoisted by the panel so switching tabs keeps the scroll position. */
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val now = remember(page) { System.currentTimeMillis() / 1000L }
    when {
        page == null -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Reading history…",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted"),
            )
        }
        page.error != null -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = page.error,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("error"),
                modifier = Modifier.padding(24.dp),
            )
        }
        page.commits.isEmpty() -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Nothing has been committed yet",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(24.dp),
            )
        }
        else -> LazyColumn(state = listState, modifier = modifier) {
            items(page.commits, key = { it.sha }) { commit ->
                CommitRow(
                    commit = commit,
                    now = now,
                    isOpen = open?.commit?.sha == commit.sha,
                    onClick = { onOpen(commit) },
                )
                if (open?.commit?.sha == commit.sha) {
                    CommitDetail(details = open, onOpenFile = onOpenFile)
                }
                // No divider: Zed's history rows meet edge to edge and are
                // told apart by hover alone (git_panel.rs:6718-6734).
            }
        }
    }
}

/**
 * One commit, in Zed's history-row anatomy (git_panel.rs:6718-6835): a
 * `py_1` / `px_2` column with `gap_0p5`, the subject a single truncated
 * default-size line beside its tag chips, and the meta underneath — author,
 * relative time, short sha — as Small muted labels between half-faded "•"
 * separators. Hover, like the open row, is `element.hover`.
 */
@Composable
private fun CommitRow(commit: Commit, now: Long, isOpen: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isOpen || hovered) theme.color("element.hover") else Color.Transparent
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Show what this commit changed",
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = commit.subject.ifBlank { "(no message)" },
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Branch and tag chips beside the subject, in Zed's Chip clothes:
            // `px_1`, 1px `border`, `rounded_sm`, `element.background`
            // (git_panel.rs:6746-6764; chip.rs:106-115).
            for (name in commit.refs.take(3)) {
                Text(
                    text = name.removePrefix("HEAD -> "),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text"),
                    maxLines = 1,
                    modifier = Modifier
                        .background(
                            theme.color("element.background", theme.color("border.variant")),
                            RoundedCornerShape(4.dp),
                        )
                        .border(1.dp, theme.color("border"), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                )
            }
        }
        // Meta gap is `gap_1p5` (git_panel.rs:6839-6843); the dots sit in it
        // rather than carrying padding of their own.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = commit.author.ifBlank { "Unknown" },
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Dot(theme)
            Text(
                text = relativeTime(commit.authorTime, now),
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                maxLines = 1,
            )
            Dot(theme)
            Text(
                text = commit.shortSha,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                maxLines = 1,
            )
            if (commit.isMerge) {
                Dot(theme)
                Text(
                    text = "merge",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted"),
                )
            }
        }
    }
}

/** Zed's separator: "•" at Small size, muted, half faded (git_panel.rs:6711-6716). */
@Composable
private fun Dot(theme: to.eyed.conquest.code.ui.theme.ZedTheme) {
    Text(
        text = "•",
        style = MaterialTheme.typography.labelMedium,
        color = theme.color("text.muted").copy(alpha = 0.5f),
    )
}

/** What one commit said and touched. */
@Composable
private fun CommitDetail(details: CommitDetails, onOpenFile: (String) -> Unit) {
    val theme = LocalZedTheme.current
    val colours = remember(theme) {
        GitStatusColours.from(theme, theme.color("text"), theme.color("text.muted"))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("editor.background"))
            // The history row's own `px_2` grid (git_panel.rs:6724).
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val body = details.message.substringAfter('\n', "").trim()
        if (body.isNotEmpty()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted"),
            )
        }
        Text(
            text = "${details.commit.authorEmail} · ${details.files.size} " +
                if (details.files.size == 1) "file" else "files",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
        )
        for (file in details.files) {
            // A file line is its label's line box — Zed's dense-list rule
            // (list_item.rs:365-368) — and the whole width is the tap target.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open ${file.path}") { onOpenFile(file.path) }
                    .pointerHoverIcon(PointerIcon.Hand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = file.status.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colours.colorFor(statusOf(file.status)),
                    modifier = Modifier.width(16.dp),
                )
                Text(
                    text = file.original?.let { "${it} → ${file.path}" } ?: file.path,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** git's letter, in the vocabulary the panel paints with. */
private fun statusOf(letter: Char): to.eyed.conquest.code.ui.workspace.GitFileStatus = when (letter) {
    'A' -> to.eyed.conquest.code.ui.workspace.GitFileStatus.Added
    'D' -> to.eyed.conquest.code.ui.workspace.GitFileStatus.Deleted
    'R', 'C' -> to.eyed.conquest.code.ui.workspace.GitFileStatus.Renamed
    else -> to.eyed.conquest.code.ui.workspace.GitFileStatus.Modified
}

/**
 * Zed's `render_changes_header` — the `min_h(Tab::container_height)` row above
 * the change list, `pl_1` / `pr_2`, with "View Diff" as a ghost button of
 * Small muted text at its start (git_panel.rs:5786-5809). Its right-hand
 * menus (view options, more-actions) have no counterpart here; pushing lives
 * in the repo footer, where Zed keeps its remote button too.
 */
@Composable
private fun ActionBar(
    state: GitPanelState,
    onViewDiff: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BarHeight)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostButton(
            label = "View Diff",
            enabled = !state.isClean,
            onClick = onViewDiff,
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}
