package to.eyed.conquest.code.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import to.eyed.conquest.code.terminal.TerminalPanelState
import to.eyed.conquest.code.terminal.TerminalSessionHost
import to.eyed.conquest.code.terminal.Userland
import to.eyed.conquest.code.terminal.UserlandState
import to.eyed.conquest.code.ui.theme.ZedTheme
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.workspace.Focus
import to.eyed.conquest.code.ui.workspace.WorkspaceCommand
import to.eyed.conquest.code.ui.workspace.workspaceCommandFor

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

    private companion object {
        // com.termux.terminal.KeyHandler's constants, which are package-private.
        const val KEYMOD_ALT = -0x80000000
        const val KEYMOD_CTRL = 0x40000000
        const val KEYMOD_SHIFT = 0x20000000
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
 *   [workspaceCommandFor] is intercepted, and it is intercepted *here*, in the
 *   view's own key callback, because a focused Android view receives key
 *   events before Compose's focus system sees them.
 * - **Touch.** Tap focuses and raises the keyboard; long-press selects, with
 *   the vendored handles and toolbar; pinch resizes the font. The extra-key
 *   row exists because GBoard has no Esc, Tab, Ctrl or arrows — without it the
 *   terminal cannot be driven by touch at all.
 * - **Mouse.** The vendored view already handles wheel scrolling and mouse
 *   reporting; the chrome around it takes hover cursors like the rest of the
 *   app.
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
            onNew = { cwd?.let { state.newSession(context, it) } },
            onSelect = state::select,
            onClose = state::closeSession,
            onRestart = { host.restart() },
            onHide = { onCommand(WorkspaceCommand.ToggleTerminal) },
        )
        HorizontalDivider()

        // The userland offer. Absent in builds without one, and once Debian is
        // installed there is nothing to say.
        val scope = rememberCoroutineScope()
        var userland by remember { mutableStateOf(Userland.backend.state(context)) }
        if (userland !is UserlandState.Ready && userland !is UserlandState.Unsupported) {
            UserlandBanner(
                state = userland,
                onInstall = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            Userland.backend.install(context) { step, fraction ->
                                userland = UserlandState.Installing(step, fraction)
                            }
                        }
                        userland = result.fold(
                            onSuccess = { UserlandState.Ready },
                            onFailure = { UserlandState.Failed(it.message ?: "install failed") },
                        )
                        // Re-enter the shell so this session lands in Debian
                        // rather than the fallback it started in.
                        if (result.isSuccess) host.restart()
                    }
                },
            )
            HorizontalDivider()
        }

        // The renderer draws from x=0, so the padding has to come from here or
        // the first column sits against the window edge.
        Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTerminalViewClient(client)
                        isFocusableInTouchMode = true
                        // Order matters: setTextSize builds the renderer that
                        // setTypeface then reads its size from.
                        setTextSize(textSizePx)
                        setTypeface(Typeface.MONOSPACE)
                        setOnFocusChangeListener { _, hasFocus -> onFocusChanged(hasFocus) }
                        view = this
                    }
                },
                update = { terminalView ->
                    terminalView.setTextSize(textSizePx)
                    if (terminalView.currentSession !== host.session) {
                        host.attach(terminalView)
                    }
                    applyPalette(terminalView, theme)
                },
                onRelease = { terminalView -> host.detach(terminalView) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        HorizontalDivider()
        ExtraKeysRow(
            sticky = sticky,
            onKey = { keyCode ->
                view?.handleKeyCode(keyCode, sticky.keyMod())
                sticky.clear()
            },
            onToggleKeyboard = { view?.let { toggleSoftKeyboard(context, it) } },
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

@Composable
private fun TerminalHeader(
    state: TerminalPanelState,
    onNew: () -> Unit,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onRestart: () -> Unit,
    onHide: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val active = state.active
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
                val selected = index == state.activeIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
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
                        .clickable { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = session.shellTitle ?: session.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = " ✕",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onClose(index) },
                    )
                }
            }
        }

        if (active?.exitStatus != null) {
            HeaderAction(label = "↻ restart", onClick = onRestart)
        }
        HeaderAction(label = "+", onClick = onNew)
        HeaderAction(label = "⌄", onClick = onHide)
    }
}

/**
 * Offers the Linux userland, and reports on it while it installs.
 *
 * Deliberately not a modal: the terminal below is a working shell already, and
 * a 30 MB download is not worth blocking on.
 */
@Composable
private fun UserlandBanner(state: UserlandState, onInstall: () -> Unit) {
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
            if (state !is UserlandState.Installing) {
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
 * cleared as soon as a key is consumed.
 */
@Composable
private fun ExtraKeysRow(
    sticky: StickyModifiers,
    onKey: (Int) -> Unit,
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

/**
 * Paint the emulator with the Zed theme's terminal palette, which the theme
 * JSON already carries: 16 ANSI colours plus foreground, background and
 * cursor. Without this the terminal would be the only surface in the app not
 * following the theme.
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
    val background = theme.color("terminal.background", theme.color("editor.background"))
    colors[TextStyle.COLOR_INDEX_FOREGROUND] =
        theme.color("terminal.foreground", theme.color("editor.foreground")).toArgb()
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
        val command = workspaceCommandFor(e, Focus.Terminal)
        if (command != null) {
            onCommand(command)
            return true
        }
        // Ctrl+C is SIGINT in a terminal, so paste takes the shifted twin —
        // the convention every desktop terminal uses.
        if (e.isCtrlPressed && e.isShiftPressed && keyCode == AndroidKeyEvent.KEYCODE_V) {
            currentHost()?.onPasteTextFromClipboard(session)
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
