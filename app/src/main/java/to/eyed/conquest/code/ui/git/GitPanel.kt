package to.eyed.conquest.code.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.GitChange
import to.eyed.conquest.code.core.GitFileStatus
import to.eyed.conquest.code.core.GitPanelState
import to.eyed.conquest.code.core.GitSession
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.terminal.Userland
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

/** Zed's `git_panel.default_width` (assets/settings/default.json:997). */
/** What the workspace budgets for this dock when it decides on a layout. */
internal val GitPanelDockWidth = 360.dp
private val DockMinWidth = 260.dp

/** The project search panel's bar, because this is its twin. */
private val BarHeight = 36.dp
private val FieldRadius = 6.dp

/**
 * Zed's row is its label's line box — 3.6mm on a phone. Everything clickable
 * here is at least this tall, and the visual sits inside it: the panel is used
 * with a thumb as often as with a mouse, and a checkbox you miss twice is worse
 * than one that looks slightly large.
 */
private val RowMinHeight = 40.dp

/** The dock's grip, the same 6dp the terminal dock's is. */
private val HandleWidth = 6.dp

/** The engine debounces git by 400 ms; polling faster only costs JNI calls. */
private const val POLL_MS = 250L

/** How far PageUp and PageDown move the selection. */
private const val PAGE_ROWS = 10

/**
 * Zed's own default: the commit box is three lines and grows no further, so the
 * file list keeps the panel.
 */
private val CommitBoxHeight = 76.dp

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
    /** Wide screens dock it beside the editor and let a mouse drag it wider. */
    isDock: Boolean,
    /**
     * Bumped by the workspace whenever the panel's chord is pressed, so pressing
     * it again puts the keyboard back on the file list rather than doing nothing.
     */
    focusToken: Int,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    val session = remember(project) { GitSession(project) }

    var state by remember(project) { mutableStateOf(GitPanelState()) }
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
    var dockWidth by remember { mutableStateOf(GitPanelDockWidth) }
    var messageFocused by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val listFocus = remember { FocusRequester() }
    // Map reads, so once per theme rather than once per row per frame.
    val colours = remember(theme) {
        GitStatusColours.from(theme, theme.color("text"), theme.color("text.muted"))
    }
    LaunchedEffect(focusToken) { listFocus.requestFocus() }

    // One counter, polled; the parse happens only when it moves. Reading the
    // counter is itself a JNI call that schedules a `git status`, so it is off
    // the main thread too — cheap, but it takes the engine's locks.
    LaunchedEffect(session) {
        var seen = -1L
        while (true) {
            val version = withContext(Dispatchers.Default) { session.version }
            if (version != seen) {
                seen = version
                state = withContext(Dispatchers.Default) { session.state() }
            }
            delay(POLL_MS)
        }
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
    fun perform(action: suspend () -> String?, onSuccess: () -> Unit = {}) {
        // One at a time, and *said* rather than swallowed: a `git add` inside
        // proot is easily a second, and a Ctrl+Enter that vanished into it
        // looks exactly like a keybinding that does not work.
        if (busy) {
            error = "Still running the last git command…"
            return
        }
        busy = true
        error = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) { action() }
            error = failure
            if (failure == null) onSuccess()
            busy = false
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

    fun commit() {
        val text = message.text
        // Refused here as well as in the engine, so the button can say why
        // before it is pressed rather than after.
        if (text.isBlank()) {
            error = "Write a commit message first"
            return
        }
        // The message is cleared only on success: one the user would have to
        // retype because git refused the commit is the wrong thing to lose.
        perform({ session.commit(text) }) {
            message = TextFieldValue()
            CommitDrafts.clear(project.id)
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
            .then(if (isDock) Modifier.width(dockWidth).fillMaxHeight() else Modifier.fillMaxSize())
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
                    // Zed's `ctrl-enter` for commit, and it works from the
                    // message box as well — that is where it is wanted.
                    val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (isEnter) {
                        commit()
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (event.key == Key.Escape) {
                    onDismiss()
                    return@onPreviewKeyEvent true
                }
                if (messageFocused) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionUp -> { move(-1); true }
                    Key.PageDown -> { move(PAGE_ROWS); true }
                    Key.PageUp -> { move(-PAGE_ROWS); true }
                    Key.Enter, Key.NumPadEnter -> {
                        val change = selectedChange
                        if (change == null) move(1) else onOpenFile(change.path)
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
        if (isDock) {
            ResizeHandle { delta -> dockWidth = (dockWidth - delta).coerceAtLeast(DockMinWidth) }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.color("panel.background"))
        ) {
            HeaderBar(state = state, busy = busy, onClose = onDismiss)
            HorizontalDivider(color = theme.color("border.variant"))

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
                                onOpen = {
                                    selected = index
                                    onOpenFile(row.change.path)
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
                onCommit = ::commit,
            )
        }
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
}

/**
 * The dock's left edge: the border between it and the editor, and the grip that
 * drags it wider. The project search panel's handle, unchanged.
 */
@Composable
private fun ResizeHandle(onDrag: (Dp) -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .width(HandleWidth)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, delta -> onDrag(delta.toDp()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(color = theme.color("border"))
    }
}

/** Branch, drift, and the close button. */
@Composable
private fun HeaderBar(state: GitPanelState, busy: Boolean, onClose: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BarHeight)
            .background(theme.color("toolbar.background"))
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = branchLabel(state),
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            Text(
                text = "…",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted"),
            )
        }
        Glyph("✕", "Close the git panel", onClose)
    }
}

/** "main ↑2 ↓1", or what there is of it. */
private fun branchLabel(state: GitPanelState): String {
    if (!state.hasRepo) return "No repository"
    val branch = state.branch ?: return "git"
    val name = branch.name ?: "detached HEAD"
    return buildString {
        append(name)
        if (branch.unborn) append(" · no commits yet")
        if (branch.ahead > 0) append(" ↑${branch.ahead}")
        if (branch.behind > 0) append(" ↓${branch.behind}")
    }
}

@Composable
private fun EmptyMessage(state: GitPanelState) {
    val theme = LocalZedTheme.current
    val text = when {
        !state.hasRepo -> "This project is not in a git repository"
        !state.scanned -> "Asking git…"
        else -> "Nothing has changed since the last commit"
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.padding(24.dp),
        )
    }
}

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
            .heightIn(min = RowMinHeight)
            .background(theme.color("elevated_surface.background", Color.Transparent))
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${row.section.title} (${row.paths.size})",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.weight(1f),
        )
        if (row.section != GitSection.Conflicts) {
            TextAction(
                label = if (row.section == GitSection.Staged) "Unstage all" else "Stage all",
                enabled = enabled && row.paths.isNotEmpty(),
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
    var menuAt by remember { mutableStateOf<DpOffset?>(null) }

    val status = if (section == GitSection.Staged) change.staged else change.unstaged
    val background = when {
        isSelected -> theme.color("ghost_element.selected")
        hovered -> theme.color("ghost_element.hover", Color.Transparent)
        else -> Color.Transparent
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
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
                .padding(end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageBox(
                staged = change.staged != null,
                partial = change.staged != null && change.unstaged != null,
                enabled = enabled && !change.conflicted,
                path = change.path,
                onClick = onToggleStaged,
            )
            Text(
                text = change.name,
                style = MaterialTheme.typography.bodyMedium,
                color = colours.colorFor(status.forColours(), dimIgnored = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = change.directory,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            Text(
                text = statusLetter(change, section),
                style = MaterialTheme.typography.labelMedium,
                color = colours.colorFor(status.forColours(), dimIgnored = false),
            )
            Glyph("⋯", "Actions for ${change.name}") { menuAt = DpOffset.Zero }
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
 * The stage checkbox. Zed draws a 16dp box; this one is a 16dp box inside a
 * 40dp target, because it is the control the panel is used through and a thumb
 * cannot hit 16dp reliably.
 */
@Composable
private fun StageBox(
    staged: Boolean,
    partial: Boolean,
    enabled: Boolean,
    path: String,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .size(RowMinHeight)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            onClickLabel = if (staged) "Unstage $path" else "Stage $path",
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
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (staged) theme.color("element.selected") else Color.Transparent
                )
                .border(1.dp, theme.color("border"), RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (staged) {
                Text(
                    text = if (partial) "–" else "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text"),
                )
            }
        }
    }
}

@Composable
private fun CommitBox(
    message: TextFieldValue,
    onMessage: (TextFieldValue) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    stagedCount: Int,
    busy: Boolean,
    onCommit: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("panel.background"))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CommitBoxHeight)
                .clip(RoundedCornerShape(FieldRadius))
                .background(theme.color("editor.background"))
                .border(1.dp, theme.color("border"), RoundedCornerShape(FieldRadius))
                .pointerHoverIcon(PointerIcon.Text)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            BasicTextField(
                value = message,
                onValueChange = onMessage,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
                cursorBrush = SolidColor(theme.cursor),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { onFocusChanged(it.isFocused) },
            )
            if (message.text.isEmpty()) {
                Text(
                    // Zed's own placeholder (git_panel.rs).
                    text = "Enter commit message",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.placeholder"),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            TextAction(
                label = "Commit",
                // Enabled with nothing staged on purpose: git's refusal is the
                // honest explanation, and a button that greys out for reasons
                // the user cannot see is worse than one that answers.
                enabled = !busy && message.text.isNotBlank(),
                shortcut = "Ctrl Enter",
                onClick = onCommit,
            )
        }
    }
}

/** A word-shaped button, sized for a thumb. */
@Composable
private fun TextAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    shortcut: String? = null,
) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .heightIn(min = RowMinHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (enabled) theme.color("element.background", Color.Transparent) else Color.Transparent
            )
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClickLabel = shortcut?.let { "$label ($it)" }, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color(if (enabled) "text" else "text.disabled"),
        )
    }
}

@Composable
private fun Glyph(glyph: String, description: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .size(RowMinHeight)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("icon"),
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

/** Which section a row belongs to. */
internal enum class GitSection(val title: String) {
    Conflicts("Conflicts"),
    Staged("Staged"),
    Changes("Changes"),
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
