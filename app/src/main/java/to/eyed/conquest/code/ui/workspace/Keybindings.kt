package to.eyed.conquest.code.ui.workspace

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Workspace-level keyboard commands.
 *
 * These are *global*: they fire wherever focus happens to be, which is why
 * they are matched in a preview pass at the workspace root rather than in the
 * editor's own key handler. Editor-local chords (Ctrl+A/C/X/V/Z, arrows,
 * Backspace) deliberately stay in `EditorPane` and are never matched here, so
 * this pass can't swallow them.
 *
 * **The terminal is the exception, and the reason [Focus] exists.** Every
 * plain `Ctrl+<letter>` means something to a shell — C interrupts, D ends
 * input, A and E jump to the ends of the line, U and K and W kill, R searches
 * history, P and N walk it, L clears, Z suspends — so while the terminal has
 * focus this table must get out of the way almost entirely. What it keeps are
 * chords no terminal uses: `Ctrl+\``, `Ctrl+Tab`, `Ctrl+PageUp/Down`, and
 * `Ctrl+Shift+<letter>` twins of the ordinary commands. Escape, `Alt+key` and
 * the function keys go to the shell untouched, because vi and htop need them.
 *
 * Matching is written against Android's `KeyEvent` rather than Compose's so
 * that both callers share one table: Compose's key events wrap a native one,
 * and the vendored `TerminalView` only ever sees the native form.
 *
 * Conquest Code targets foldables, tablets and DeX, where a keyboard and mouse
 * are ordinary rather than exotic. Anything reachable by touch should be
 * reachable from the keyboard too — see the convention in
 * agent-docs/CONVENTIONS.md and the user-facing list in docs/SHORTCUTS.md.
 * Keep those two in sync with this table.
 */
enum class WorkspaceCommand {
    /** Write the active file to disk. */
    Save,

    /** Close the active tab. */
    CloseTab,

    NextTab,
    PreviousTab,

    /** Show or hide the project panel. */
    ToggleProjectPanel,

    /** Open the project picker (switch, create, import, export). */
    OpenProjects,

    /** Open the fuzzy file finder. */
    FindFile,

    /** Open the settings screen. */
    OpenSettings,

    /** Show or hide the terminal dock. */
    ToggleTerminal,

    /** Start another shell in the project directory. */
    NewTerminal,

    /** Kill the shell showing in the dock. */
    CloseTerminal,

    NextTerminal,
    PreviousTerminal,
}

/** Which surface the keyboard is talking to. */
enum class Focus {
    /** The editor, project panel, dialogs — everything that is not a shell. */
    Workspace,

    /** A terminal session: the shell claims the keyboard. */
    Terminal,
}

/** The command a key event maps to, or null to let it through. */
fun workspaceCommandFor(event: KeyEvent, focus: Focus = Focus.Workspace): WorkspaceCommand? {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return null
    return workspaceCommandFor(event.nativeKeyEvent, focus)
}

/** As above, for callers holding an Android key event (the terminal view). */
fun workspaceCommandFor(event: AndroidKeyEvent, focus: Focus): WorkspaceCommand? {
    if (event.action != AndroidKeyEvent.ACTION_DOWN || !event.isCtrlPressed) return null
    val shift = event.isShiftPressed
    return if (focus == Focus.Terminal) terminalReserved(event, shift) else workspace(event, shift)
}

private fun workspace(event: AndroidKeyEvent, shift: Boolean): WorkspaceCommand? =
    when (event.keyCode) {
        AndroidKeyEvent.KEYCODE_S -> WorkspaceCommand.Save
        AndroidKeyEvent.KEYCODE_W -> WorkspaceCommand.CloseTab
        AndroidKeyEvent.KEYCODE_B -> WorkspaceCommand.ToggleProjectPanel
        AndroidKeyEvent.KEYCODE_O -> WorkspaceCommand.OpenProjects
        AndroidKeyEvent.KEYCODE_P -> WorkspaceCommand.FindFile
        AndroidKeyEvent.KEYCODE_COMMA -> WorkspaceCommand.OpenSettings
        AndroidKeyEvent.KEYCODE_GRAVE ->
            if (shift) WorkspaceCommand.NewTerminal else WorkspaceCommand.ToggleTerminal
        AndroidKeyEvent.KEYCODE_TAB ->
            if (shift) WorkspaceCommand.PreviousTab else WorkspaceCommand.NextTab
        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> WorkspaceCommand.NextTab
        AndroidKeyEvent.KEYCODE_PAGE_UP -> WorkspaceCommand.PreviousTab
        else -> null
    }

/**
 * The short list the workspace keeps while a shell has the keyboard.
 *
 * Note what is *not* here: every plain `Ctrl+<letter>`. `Ctrl+Tab` cycles
 * terminal sessions rather than editor tabs, because that is the pane you are
 * looking at; `Ctrl+PageUp/Down` still cycles editor tabs from anywhere.
 */
private fun terminalReserved(event: AndroidKeyEvent, shift: Boolean): WorkspaceCommand? =
    when (event.keyCode) {
        AndroidKeyEvent.KEYCODE_GRAVE ->
            if (shift) WorkspaceCommand.NewTerminal else WorkspaceCommand.ToggleTerminal
        AndroidKeyEvent.KEYCODE_TAB ->
            if (shift) WorkspaceCommand.PreviousTerminal else WorkspaceCommand.NextTerminal
        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> WorkspaceCommand.NextTab
        AndroidKeyEvent.KEYCODE_PAGE_UP -> WorkspaceCommand.PreviousTab
        else -> if (!shift) null else when (event.keyCode) {
            AndroidKeyEvent.KEYCODE_S -> WorkspaceCommand.Save
            AndroidKeyEvent.KEYCODE_W -> WorkspaceCommand.CloseTerminal
            AndroidKeyEvent.KEYCODE_B -> WorkspaceCommand.ToggleProjectPanel
            AndroidKeyEvent.KEYCODE_O -> WorkspaceCommand.OpenProjects
            AndroidKeyEvent.KEYCODE_P -> WorkspaceCommand.FindFile
            AndroidKeyEvent.KEYCODE_COMMA -> WorkspaceCommand.OpenSettings
            else -> null
        }
    }

/**
 * `Ctrl+Shift+G` — clone a git repository into a new project.
 *
 * Matched here, with the rest of the table, but deliberately *not* a
 * [WorkspaceCommand]: cloning only exists in a build that has a Linux userland
 * to run git in, so the workspace asks about this chord separately and does
 * nothing with it where `GitClone.isSupported` is false. Fold it into the enum
 * when every edition can clone.
 *
 * Shift is required. A bare `Ctrl+G` is "go to line" in every editor that has
 * one, and this table must not spend it on a dialog. Being a
 * `Ctrl+Shift+<letter>` chord, it is also safe while a shell has the keyboard,
 * which is why [Focus] does not change the answer.
 */
fun isCloneRepositoryChord(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown &&
        event.isCtrlPressed &&
        isCloneRepositoryChord(event.nativeKeyEvent)

/** As above, for callers holding an Android key event (the terminal view). */
fun isCloneRepositoryChord(event: AndroidKeyEvent): Boolean =
    event.action == AndroidKeyEvent.ACTION_DOWN &&
        event.isCtrlPressed &&
        event.isShiftPressed &&
        event.keyCode == AndroidKeyEvent.KEYCODE_G

/**
 * Zero-based tab index for Ctrl+1…Ctrl+9, or null. Ctrl+9 means "the last
 * tab", as in every browser and editor that has this binding, rather than
 * literally the ninth.
 *
 * Not matched while a shell has focus: `Ctrl+<digit>` is a readline argument
 * prefix, and in vi it is a repeat count.
 */
fun tabIndexFor(event: KeyEvent, tabCount: Int, focus: Focus = Focus.Workspace): Int? {
    if (focus == Focus.Terminal) return null
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return null
    val requested = when (event.key) {
        Key.One -> 1
        Key.Two -> 2
        Key.Three -> 3
        Key.Four -> 4
        Key.Five -> 5
        Key.Six -> 6
        Key.Seven -> 7
        Key.Eight -> 8
        Key.Nine -> return if (tabCount > 0) tabCount - 1 else null
        else -> return null
    }
    return (requested - 1).takeIf { it < tabCount }
}
