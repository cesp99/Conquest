package to.eyed.conquest.code.ui.workspace

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
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
}

/**
 * The command a key event maps to, or null to let it through.
 *
 * Tab selection by index (Ctrl+1…Ctrl+9) is handled separately by
 * [tabIndexFor], since it carries a payload rather than being one command.
 */
fun workspaceCommandFor(event: KeyEvent): WorkspaceCommand? {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return null
    return when (event.key) {
        Key.S -> WorkspaceCommand.Save
        Key.W -> WorkspaceCommand.CloseTab
        Key.B -> WorkspaceCommand.ToggleProjectPanel
        Key.O -> WorkspaceCommand.OpenProjects
        Key.P -> WorkspaceCommand.FindFile
        Key.Tab -> if (event.isShiftPressed) {
            WorkspaceCommand.PreviousTab
        } else {
            WorkspaceCommand.NextTab
        }
        Key.PageDown -> WorkspaceCommand.NextTab
        Key.PageUp -> WorkspaceCommand.PreviousTab
        else -> null
    }
}

/**
 * Zero-based tab index for Ctrl+1…Ctrl+9, or null. Ctrl+9 means "the last
 * tab", as in every browser and editor that has this binding, rather than
 * literally the ninth.
 */
fun tabIndexFor(event: KeyEvent, tabCount: Int): Int? {
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
