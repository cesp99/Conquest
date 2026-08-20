package to.eyed.conquest.code.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.delay
import to.eyed.conquest.code.terminal.TerminalPanelState
import to.eyed.conquest.code.terminal.TerminalSessionHost
import to.eyed.conquest.code.terminal.Userland
import to.eyed.conquest.code.terminal.UserlandInstaller
import to.eyed.conquest.code.terminal.UserlandState
import to.eyed.conquest.code.ui.theme.ZedTheme
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.workspace.ContextMenu
import to.eyed.conquest.code.ui.workspace.ContextMenuItem
import to.eyed.conquest.code.ui.workspace.Focus
import to.eyed.conquest.code.ui.workspace.onSecondaryClick
import to.eyed.conquest.code.ui.workspace.WorkspaceCommand
import to.eyed.conquest.code.ui.workspace.workspaceCommandFor

/** How long the dock stays lit after a bell. Long enough to catch, short enough to ignore. */
private const val BELL_FLASH_MS = 220L

/** Modifier keys held by the extra-key row, since a soft keyboard has none. */
private class StickyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)

    fun clear() {
        ctrl = false
        alt = false
        shift = false
    }

    /** Modifier bits in the form [TerminalView.handleKeyCode] wants. */
    fun keyMod(): Int {
        var mod = 0
        if (ctrl) mod = mod or KEYMOD_CTRL
        if (alt) mod = mod or KEYMOD_ALT
        if (shift) mod = mod or KEYMOD_SHIFT
        return mod
    }
}

/**
 * Everything the dock can do to the session in front of it.
 *
 * One table behind the keyboard, the overflow menu and the right-click menu,
 * so the three can't drift: the shortcut a menu prints is the one the key
 * table matches. Names and bindings follow Zed's `terminal:` actions.
 */
private enum class TerminalAction(val label: String, val shortcut: String?) {
    Copy("Copy", "Ctrl Shift C"),
    Paste("Paste", "Ctrl Shift V"),
    Clear("Clear", "Ctrl Shift L"),
    ScrollToTop("Scroll to top", "Shift Home"),
    ScrollToBottom("Scroll to bottom", "Shift End"),
    Rename("Rename…", "Ctrl Shift N"),
    Restart("Restart", null),
    Close("Close", "Ctrl Shift W"),
}

/** The chord table for the actions above, matched ahead of the shell. */
private fun terminalActionFor(event: AndroidKeyEvent): TerminalAction? {
    if (event.action != AndroidKeyEvent.ACTION_DOWN || !event.isShiftPressed) return null
    val ctrl = event.isCtrlPressed
    return when (event.keyCode) {
        AndroidKeyEvent.KEYCODE_C -> TerminalAction.Copy.takeIf { ctrl }
        AndroidKeyEvent.KEYCODE_V -> TerminalAction.Paste.takeIf { ctrl }
        AndroidKeyEvent.KEYCODE_L -> TerminalAction.Clear.takeIf { ctrl }
        AndroidKeyEvent.KEYCODE_N -> TerminalAction.Rename.takeIf { ctrl }
        AndroidKeyEvent.KEYCODE_MOVE_HOME -> TerminalAction.ScrollToTop.takeIf { !ctrl }
        AndroidKeyEvent.KEYCODE_MOVE_END -> TerminalAction.ScrollToBottom.takeIf { !ctrl }
        else -> null
    }
}

/**
 * Frame around the terminal view that sees hardware keys first.
 *
 * The vendored view drops the text selection at the top of its own `onKeyDown`,
 * *before* the client callback runs, so a `Ctrl+Shift+C` arriving that way
 * would always find nothing selected. Every ancestor `ViewGroup` is on the
 * dispatch path ahead of the focused child, which is early enough.
 */
private class TerminalKeyFrame(context: Context) : FrameLayout(context) {
    val terminal = TerminalView(context, null)

    /** Returns true when the chord belongs to the dock rather than the shell. */
    var onKey: ((AndroidKeyEvent) -> Boolean)? = null

    /**
     * What the last `update` pass pushed into the view, so a recomposition
     * that changed none of it — a bell, a title, a latched modifier — does
     * not call through again: `setTextSize` allocates a whole new renderer
     * per call, and `applyPalette` invalidates the screen. The palette is
     * written into the emulator, so a fresh emulator — session switch,
     * restart, first layout — needs it applied again even under the same
     * theme; hence the emulator is tracked next to the theme. Neither
     * reference catches a program resetting the colours *in place* (RIS,
     * OSC 104), so the guard also probes the emulator's actual colour state
     * via [paletteSentinelsMatch] before trusting these.
     */
    var lastTextSizePx = 0
    var lastTheme: ZedTheme? = null
    var lastEmulator: TerminalEmulator? = null

    init {
        addView(
            terminal,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (event.action == AndroidKeyEvent.ACTION_DOWN && onKey?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}

/**
 * The terminal dock — Zed's bottom panel, with the shell running in the
 * project directory.
 *
 * The screen itself is Termux's `TerminalView` (a classic Android `View`)
 * embedded with `AndroidView`; everything around it is ours. The parts worth
 * knowing:
 *
 * - **Keyboard.** The shell gets every plain `Ctrl+<letter>`, Escape, `Alt`
 *   chords and the function keys. Only the short reserved list in
 *   [workspaceCommandFor] and the dock's own chords in [terminalActionFor] are
 *   intercepted, and both are intercepted *here* rather than in the workspace's
 *   preview pass, because a focused Android view receives key events before
 *   Compose's focus system sees them.
 * - **Touch.** Tap focuses and raises the keyboard; long-press selects, with
 *   the vendored handles and copy/paste toolbar; pinch resizes the font. The
 *   extra-key row exists because GBoard has no Esc, Tab, Ctrl, arrows or page
 *   keys — without it the terminal cannot be driven by touch at all, and the
 *   scrollback could not be reached.
 * - **Mouse.** The vendored view already handles wheel scrolling and mouse
 *   reporting; right-click opens the same action menu the `⋮` button does, and
 *   the chrome takes hover cursors like the rest of the app.
 */
@Composable
fun TerminalDock(
    state: TerminalPanelState,
    cwd: String?,
    fontSizeSp: Float,
    onCommand: (WorkspaceCommand) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = state.active ?: return
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val density = LocalDensity.current

    // Pinch-to-zoom is terminal-local: the setting drives the editor, and a
    // pinch here should not rewrite the user's settings file.
    var fontScale by remember { mutableFloatStateOf(1f) }
    val textSizePx = remember(fontSizeSp, fontScale, density) {
        with(density) { (fontSizeSp * fontScale).sp.toPx() }.toInt().coerceIn(8, 96)
    }

    val sticky = remember { StickyModifiers() }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var renaming by remember { mutableStateOf<TerminalSessionHost?>(null) }
    /** Where the right-click landed, and therefore whether the menu is up. */
    var surfaceMenuAt by remember { mutableStateOf<DpOffset?>(null) }

    fun runAction(action: TerminalAction) {
        val terminal = view
        // Read the active session *now*, never the `host` this composition
        // captured. `AndroidView`'s factory runs once and keeps the first
        // `onKey` it is given, so a closure over `host` is pinned to whichever
        // session existed when the dock's view was created: open a second
        // shell, press Ctrl+Shift+V, and the clipboard goes into the first
        // one's pty — invisibly, and executed if it ends in a newline.
        val active = state.active
        when (action) {
            TerminalAction.Copy -> terminal?.let { active?.copy(terminalSelection(it)) }
            TerminalAction.Paste -> active?.paste()
            TerminalAction.Clear -> terminal?.let(::clearTerminal)
            TerminalAction.ScrollToTop -> terminal?.let(::scrollTerminalToTop)
            TerminalAction.ScrollToBottom -> terminal?.let(::scrollTerminalToBottom)
            TerminalAction.Rename -> renaming = active
            TerminalAction.Restart -> active?.restart()
            TerminalAction.Close -> state.closeSession(state.activeIndex)
        }
    }

    val client = remember {
        ConquestTerminalViewClient(
            context = context,
            sticky = sticky,
            currentHost = { state.active },
            currentView = { view },
            onCommand = onCommand,
            onFontScale = { scale -> fontScale = (fontScale * scale).coerceIn(0.5f, 3f) },
            onEmulatorReady = { view?.let { applyPalette(it, theme) } },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("terminal.background", theme.color("editor.background")))
    ) {
        TerminalHeader(
            state = state,
            onNew = { cwd?.let { state.newSession(it) } },
            onSelect = state::select,
            onClose = state::closeSession,
            onRename = { session -> renaming = session },
            onAction = ::runAction,
            onHide = { onCommand(WorkspaceCommand.ToggleTerminal) },
        )
        HorizontalDivider()

        // The userland offer. Absent in builds without one, and once Debian is
        // installed there is nothing to say. The work itself belongs to
        // UserlandInstaller rather than to this composable — hiding the dock
        // must not cancel a 30 MB download.
        LaunchedEffect(Unit) { UserlandInstaller.refresh(context) }
        val userland = UserlandInstaller.state
        if (userland != null &&
            userland !is UserlandState.Ready &&
            userland !is UserlandState.Unsupported
        ) {
            UserlandBanner(
                state = userland,
                onInstall = {
                    // Re-enter the shell on success so this session lands in
                    // Debian rather than the fallback it started in.
                    UserlandInstaller.install(context) { host.restart() }
                },
                onCancel = { UserlandInstaller.cancel() },
            )
            HorizontalDivider()
        }

        // The renderer draws from x=0, so the padding has to come from here or
        // the first column sits against the window edge.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .onSecondaryClick { position -> surfaceMenuAt = position }
        ) {
            AndroidView(
                factory = { ctx ->
                    TerminalKeyFrame(ctx).apply {
                        terminal.setTerminalViewClient(client)
                        terminal.isFocusableInTouchMode = true
                        // Order matters: setTextSize builds the renderer that
                        // setTypeface then reads its size from.
                        terminal.setTextSize(textSizePx)
                        lastTextSizePx = textSizePx
                        terminal.setTypeface(Typeface.MONOSPACE)
                        terminal.setOnFocusChangeListener { _, hasFocus ->
                            onFocusChanged(hasFocus)
                        }
                        onKey = { event ->
                            val action = terminalActionFor(event)
                            if (action != null) runAction(action)
                            action != null
                        }
                        view = terminal
                    }
                },
                update = { frame ->
                    // Runs on every recomposition — a bell, a title, a sticky
                    // modifier — so the expensive calls only go through when
                    // what they would push has actually changed.
                    if (frame.lastTextSizePx != textSizePx) {
                        frame.terminal.setTextSize(textSizePx)
                        frame.lastTextSizePx = textSizePx
                    }
                    if (frame.terminal.currentSession !== host.session) {
                        host.attach(frame.terminal)
                    }
                    // The emulator only exists once the view has a size, so
                    // keep retrying until the first application lands. The
                    // sentinel probe is there because identity alone lies: a
                    // program can reset the palette in place (see
                    // [paletteSentinelsMatch]) without either tracked
                    // reference changing.
                    val emulator = frame.terminal.mEmulator
                    if (emulator != null &&
                        (frame.lastTheme !== theme ||
                            frame.lastEmulator !== emulator ||
                            !paletteSentinelsMatch(emulator, theme))
                    ) {
                        applyPalette(frame.terminal, theme)
                        frame.lastTheme = theme
                        frame.lastEmulator = emulator
                    }
                },
                onRelease = { frame -> host.detach(frame.terminal) },
                modifier = Modifier.fillMaxSize(),
            )

            BellFlash(host)

            val menuAt = surfaceMenuAt
            if (menuAt != null) {
                ContextMenu(
                    expanded = true,
                    onDismiss = { surfaceMenuAt = null },
                    items = menuItems(surfaceActions, ::runAction),
                    offset = menuAt,
                )
            }
        }

        // A session that exits stays where it is and says so. Zed does the
        // same: the scrollback is usually the reason you are looking, and a
        // pane that vanishes takes the error message with it.
        if (host.exitStatus != null) {
            HorizontalDivider()
            SessionExitedBar(
                host = host,
                onRestart = { runAction(TerminalAction.Restart) },
                onClose = { runAction(TerminalAction.Close) },
            )
        }

        HorizontalDivider()
        ExtraKeysRow(
            sticky = sticky,
            onKey = { keyCode ->
                view?.handleKeyCode(keyCode, sticky.keyMod())
                sticky.clear()
            },
            onPage = { up -> view?.let { scrollTerminalPage(it, up) } },
            onToggleKeyboard = { view?.let { toggleSoftKeyboard(context, it) } },
        )
    }

    val session = renaming
    if (session != null) {
        RenameSessionDialog(
            host = session,
            onRename = { name ->
                session.rename(name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    // Attach on entry, and re-attach whenever the visible session changes, so
    // the session that is on screen is the one receiving screen updates.
    val attached = view
    if (attached != null) {
        DisposableEffect(host, attached) {
            host.attach(attached)
            attached.requestFocus()
            applyPalette(attached, theme)
            onDispose { host.detach(attached) }
        }
    }
}

/** What the right-click menu offers: the actions that act on the screen itself. */
private val surfaceActions = listOf(
    TerminalAction.Copy,
    TerminalAction.Paste,
    TerminalAction.Clear,
    TerminalAction.ScrollToTop,
    TerminalAction.ScrollToBottom,
)

/** Everything, for the `⋮` button — the touch user's way to all of it. */
private val overflowActions = TerminalAction.entries

/**
 * The bell, made visible.
 *
 * A short wash of the terminal's foreground colour over the screen, and
 * nothing else: a sound or a vibration needs a setting to turn it off, and
 * `settings.json` has no terminal section yet. The session chip keeps a dot
 * until you type, so a bell you missed is still there when you look.
 */
@Composable
private fun BoxScope.BellFlash(host: TerminalSessionHost) {
    val theme = LocalZedTheme.current
    var lit by remember(host) { mutableStateOf(false) }
    LaunchedEffect(host, host.bells) {
        if (host.bells == 0) {
            lit = false
            return@LaunchedEffect
        }
        lit = true
        delay(BELL_FLASH_MS)
        lit = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (lit) 0.16f else 0f,
        animationSpec = tween(durationMillis = BELL_FLASH_MS.toInt()),
        label = "bell",
    )
    if (alpha > 0f) {
        // No pointer modifiers, so it is not a hit target: the terminal keeps
        // every tap while the flash is on screen.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    theme
                        .color("terminal.foreground", theme.color("editor.foreground"))
                        .copy(alpha = alpha)
                )
        )
    }
}

@Composable
private fun TerminalHeader(
    state: TerminalPanelState,
    onNew: () -> Unit,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onRename: (TerminalSessionHost) -> Unit,
    onAction: (TerminalAction) -> Unit,
    onHide: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(theme.color("tab_bar.background"))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.sessions.forEachIndexed { index, session ->
                SessionChip(
                    session = session,
                    selected = index == state.activeIndex,
                    onSelect = { onSelect(index) },
                    onRename = { onRename(session) },
                    onRestart = { session.restart() },
                    onClose = { onClose(index) },
                )
            }
        }

        Box {
            HeaderAction(label = "⋮", onClick = { overflowOpen = true })
            ContextMenu(
                expanded = overflowOpen,
                onDismiss = { overflowOpen = false },
                items = menuItems(overflowActions, onAction),
            )
        }
        HeaderAction(label = "+", onClick = onNew)
        HeaderAction(label = "⌄", onClick = onHide)
    }
}

/**
 * One session in the header strip.
 *
 * The label is whatever the session is calling itself — the name you gave it,
 * else the title the running program set with an OSC sequence, else "shell 2".
 * A program's title can be a whole path, so it is clipped rather than allowed
 * to push the other sessions off the strip.
 */
@Composable
private fun SessionChip(
    session: TerminalSessionHost,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (selected) {
                        theme.color("tab.active_background", Color.Transparent)
                    } else {
                        Color.Transparent
                    }
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .onSecondaryClick { menuOpen = true }
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = { menuOpen = true },
                    onDoubleClick = onRename,
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (session.bells > 0) {
                // Left over from a bell nobody was watching for.
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(theme.color("warning", MaterialTheme.colorScheme.primary))
                )
            }
            Text(
                text = session.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    session.exitStatus != null -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onClose),
            )
        }
        ContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            items = listOf(
                ContextMenuItem(TerminalAction.Rename.label, TerminalAction.Rename.shortcut) {
                    onRename()
                },
                ContextMenuItem(TerminalAction.Restart.label) { onRestart() },
                ContextMenuItem(TerminalAction.Close.label, TerminalAction.Close.shortcut) {
                    onClose()
                },
            ),
        )
    }
}

/** The dock's actions as menu rows, so the menus and the key table agree. */
private fun menuItems(
    actions: List<TerminalAction>,
    onPick: (TerminalAction) -> Unit,
): List<ContextMenuItem> =
    actions.map { action -> ContextMenuItem(action.label, action.shortcut) { onPick(action) } }

/** Says what happened to the shell, and offers the two things worth doing next. */
@Composable
private fun SessionExitedBar(
    host: TerminalSessionHost,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background"))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "${host.title} ${host.exitDescription}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HeaderAction(label = "↻ restart", onClick = onRestart)
        HeaderAction(label = "close", onClick = onClose)
    }
}

/** Ask for a session's name. Enter accepts, Esc and the scrim cancel. */
@Composable
private fun RenameSessionDialog(
    host: TerminalSessionHost,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(host) { mutableStateOf(host.customTitle ?: host.title) }
    val field = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${host.label}") },
        text = {
            // Inside the slot, not beside the dialog: the field only exists
            // once the dialog's own composition has run.
            LaunchedEffect(host) { field.requestFocus() }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(name) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .pointerHoverIcon(PointerIcon.Text),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Offers the Linux userland, and reports on it while it installs.
 *
 * Deliberately not a modal: the terminal below is a working shell already, and
 * a 30 MB download is not worth blocking on.
 */
@Composable
private fun UserlandBanner(
    state: UserlandState,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val backend = Userland.backend
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background"))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val message = when (state) {
                is UserlandState.NotInstalled ->
                    "Install ${backend.displayName} for apt and a full Linux toolchain " +
                        "(${backend.downloadDescription})"
                is UserlandState.Installing -> state.step + "…"
                is UserlandState.Failed -> "Install failed: ${state.message}"
                else -> ""
            }
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state is UserlandState.Installing) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    text = if (state is UserlandState.Failed) "Retry" else "Install",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onInstall)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (state is UserlandState.Installing) {
            val fraction = state.fraction
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * The keys a soft keyboard doesn't have. Ctrl and Alt latch: press Ctrl, then
 * `c`, and the shell sees `^C` — the same one-shot behaviour Termux uses,
 * cleared as soon as a key is consumed. `pgup` and `pgdn` walk the scrollback
 * rather than the shell's history, which is what a touch user has instead of
 * `Shift+PageUp`.
 */
@Composable
private fun ExtraKeysRow(
    sticky: StickyModifiers,
    onKey: (Int) -> Unit,
    onPage: (Boolean) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(theme.color("status_bar.background"))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ExtraKey("esc") { onKey(AndroidKeyEvent.KEYCODE_ESCAPE) }
        ExtraKey("tab") { onKey(AndroidKeyEvent.KEYCODE_TAB) }
        ExtraKey("ctrl", latched = sticky.ctrl) { sticky.ctrl = !sticky.ctrl }
        ExtraKey("alt", latched = sticky.alt) { sticky.alt = !sticky.alt }
        ExtraKey("←") { onKey(AndroidKeyEvent.KEYCODE_DPAD_LEFT) }
        ExtraKey("↓") { onKey(AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        ExtraKey("↑") { onKey(AndroidKeyEvent.KEYCODE_DPAD_UP) }
        ExtraKey("→") { onKey(AndroidKeyEvent.KEYCODE_DPAD_RIGHT) }
        ExtraKey("home") { onKey(AndroidKeyEvent.KEYCODE_MOVE_HOME) }
        ExtraKey("end") { onKey(AndroidKeyEvent.KEYCODE_MOVE_END) }
        ExtraKey("pgup") { onPage(true) }
        ExtraKey("pgdn") { onPage(false) }
        ExtraKey("⌨", onClick = onToggleKeyboard)
    }
}

@Composable
private fun ExtraKey(label: String, latched: Boolean = false, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (latched) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (latched) {
                    theme.color("element.selected", Color.Transparent)
                } else {
                    Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** The theme's terminal foreground, exactly as [applyPalette] writes it. */
private fun terminalForeground(theme: ZedTheme): Color =
    theme.color("terminal.foreground", theme.color("editor.foreground"))

/** The theme's terminal background, exactly as [applyPalette] writes it. */
private fun terminalBackground(theme: ZedTheme): Color =
    theme.color("terminal.background", theme.color("editor.background"))

/**
 * Whether the emulator still holds the palette [applyPalette] wrote for this
 * theme, probed through the foreground and background slots — the two entries
 * the theme alone determines (the sixteen ANSI lookups fall back to whatever
 * the slot already holds, so their target values cannot be predicted without
 * doing the work this probe exists to skip).
 *
 * The probe exists because a running program can restore the vendored Termux
 * defaults *in place* — `reset`/`tput reset` (RIS, `ESC c`) and a bare OSC 104
 * both call `TerminalColors.reset()` on the same emulator instance — which no
 * identity comparison in the update pass can see. When the slots diverge the
 * whole palette is rewritten, stomping deliberate OSC 4/10/11 recolouring just
 * as the old apply-every-recomposition code did; only ANSI-slot-only tweaks
 * outlive this probe, and merely until the next full reapply.
 *
 * Both sides are ARGB ints: the vendored parser packs `0xFF << 24 | r g b`
 * and [toArgb] packs the same, so plain equality is exact.
 */
private fun paletteSentinelsMatch(emulator: TerminalEmulator, theme: ZedTheme): Boolean {
    val colors = emulator.mColors.mCurrentColors
    return colors[TextStyle.COLOR_INDEX_FOREGROUND] == terminalForeground(theme).toArgb() &&
        colors[TextStyle.COLOR_INDEX_BACKGROUND] == terminalBackground(theme).toArgb()
}

/**
 * Paint the emulator with the Zed theme's terminal palette, which the theme
 * JSON already carries: 16 ANSI colours plus foreground, background and
 * cursor. Without this the terminal would be the only surface in the app not
 * following the theme.
 *
 * The foreground and background written here double as the sentinels
 * [paletteSentinelsMatch] probes, which is why their derivation lives in
 * [terminalForeground] and [terminalBackground] rather than inline.
 */
private fun applyPalette(view: TerminalView, theme: ZedTheme) {
    val emulator = view.mEmulator ?: return
    val colors = emulator.mColors.mCurrentColors
    val names = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")
    for ((index, name) in names.withIndex()) {
        colors[index] = theme.color("terminal.ansi.$name", Color(colors[index])).toArgb()
        colors[index + 8] =
            theme.color("terminal.ansi.bright_$name", Color(colors[index + 8])).toArgb()
    }
    val background = terminalBackground(theme)
    colors[TextStyle.COLOR_INDEX_FOREGROUND] = terminalForeground(theme).toArgb()
    colors[TextStyle.COLOR_INDEX_BACKGROUND] = background.toArgb()
    colors[TextStyle.COLOR_INDEX_CURSOR] = theme.cursor.toArgb()
    view.setBackgroundColor(background.toArgb())
    view.invalidate()
}

private fun toggleSoftKeyboard(context: Context, view: TerminalView) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    view.requestFocus()
    imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
}

private fun showSoftKeyboard(context: Context, view: TerminalView) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.showSoftInput(view, 0)
}

/**
 * The view's side of the contract: what it may ask us, and the one place
 * workspace chords are stolen from the shell.
 */
private class ConquestTerminalViewClient(
    private val context: Context,
    private val sticky: StickyModifiers,
    private val currentHost: () -> TerminalSessionHost?,
    private val currentView: () -> TerminalView?,
    private val onCommand: (WorkspaceCommand) -> Unit,
    private val onFontScale: (Float) -> Unit,
    private val onEmulatorReady: () -> Unit,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        // Called with the accumulated factor; apply it and reset, so the next
        // pinch starts from the new size rather than compounding.
        if (scale < 0.9f || scale > 1.1f) onFontScale(scale)
        return 1f
    }

    /** The view has already taken focus by the time this runs; raise the IME. */
    override fun onSingleTapUp(e: MotionEvent) {
        currentHost()?.clearBell()
        currentView()?.let { showSoftKeyboard(context, it) }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /**
     * Samsung's keyboard does not reset its state on `TYPE_NULL`, which is the
     * bug Termux works around with character-based input — and the owner's
     * device is a Samsung.
     */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: AndroidKeyEvent, session: TerminalSession): Boolean {
        // Typing is how you say you heard the bell.
        currentHost()?.clearBell()
        val command = workspaceCommandFor(e, Focus.Terminal)
        if (command != null) {
            onCommand(command)
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: AndroidKeyEvent): Boolean {
        // The latched modifiers are one-shot: the view read them while
        // handling the key-down, so this is the first safe moment to clear.
        sticky.clear()
        return false
    }

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = sticky.ctrl

    override fun readAltKey(): Boolean = sticky.alt

    override fun readShiftKey(): Boolean = sticky.shift

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        // The view has already folded the latched modifiers into ctrlDown, so
        // clearing here makes them one-shot for soft-keyboard input too.
        sticky.clear()
        currentHost()?.clearBell()
        return false
    }

    override fun onEmulatorSet() {
        onEmulatorReady()
    }

    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
