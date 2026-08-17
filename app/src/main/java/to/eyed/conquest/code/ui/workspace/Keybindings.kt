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
 * The bindings are *data* ([BINDINGS]), not a `when`, because two readers need
 * them: the matcher below, and the command palette, which prints the chord
 * beside each command. A palette that carried its own copy of the table would
 * be wrong the first time somebody rebound anything.
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
enum class WorkspaceCommand(
    /**
     * Zed's own action name for this command, and its stable identity: the
     * palette humanises it into what the user reads and searches, so
     * `terminal_panel::Toggle` shows as "terminal panel: toggle".
     *
     * **Adding a command is these two lines and nothing else**: a case here
     * with an id, and a branch in `WorkspaceScreen.runCommand`. The palette
     * picks it up from [entries] on its own — there is no second registry to
     * forget. Give it a chord in [BINDINGS] if it deserves one.
     */
    val id: String,
    /**
     * Whether this build offers the command at all. Default yes; the one
     * exception is cloning, which needs a Linux userland — an editor should
     * not advertise, even greyed out, something it cannot ever do.
     */
    val isOffered: (CommandContext) -> Boolean = { true },
    /**
     * Whether it can run *right now*. False greys it in the palette rather
     * than hiding it, as Zed does: a command that needs a project should tell
     * you it exists and why it is unavailable, not vanish.
     */
    val isAvailable: (CommandContext) -> Boolean = { true },
) {
    /** Write the active file to disk. */
    Save("workspace::Save", isAvailable = { it.hasActiveFile }),

    /** Close the active tab. */
    CloseTab("pane::CloseActiveItem", isAvailable = { it.hasActiveFile }),

    NextTab("pane::ActivateNextItem", isAvailable = { it.tabCount > 1 }),
    PreviousTab("pane::ActivatePreviousItem", isAvailable = { it.tabCount > 1 }),

    /** Close every other tab. Pinned ones survive, as in Zed. */
    CloseOtherTabs("pane::CloseOtherItems", isAvailable = { it.tabCount > 1 }),

    /** Close the tabs to the right of the active one. */
    CloseTabsToTheRight("pane::CloseItemsToTheRight", isAvailable = { it.tabCount > 1 }),

    /** Close every tab. Pinned ones survive. */
    CloseAllTabs("pane::CloseAllItems", isAvailable = { it.tabCount > 0 }),

    /** Pin the active tab, or unpin it. Pinned tabs sit left. */
    TogglePinTab("pane::TogglePinTab", isAvailable = { it.hasActiveFile }),

    /** Reopen the tab closed most recently. */
    ReopenClosedTab("pane::ReopenClosedItem", isAvailable = { it.hasProject }),

    /** Show the active file in the project panel, and give the panel focus. */
    RevealInProjectPanel("pane::RevealInProjectPanel", isAvailable = { it.hasActiveFile }),

    /** Show or hide the project panel. */
    ToggleProjectPanel("project_panel::Toggle"),

    /** Open the project picker (switch, create, import, export). */
    OpenProjects("projects::Open"),

    /** Open the fuzzy file finder. */
    FindFile("file_finder::Toggle", isAvailable = { it.hasProject }),

    /** Open the settings screen. */
    OpenSettings("conquest::OpenSettings"),

    /** Pick a theme, previewing each as the selection moves — Zed's own. */
    SelectTheme("theme_selector::Toggle"),

    /**
     * Clone a git repository into a new project.
     *
     * In the table like everything else, and refused at the point of use where
     * there is no Linux userland to run git in — the same way FindFile is
     * refused with no project open. Shift is required: a bare `Ctrl+G` is "go
     * to line" in every editor that has one, and this table must not spend it
     * on a dialog.
     */
    CloneRepository("git::Clone", isOffered = { it.canClone }),

    /** Show or hide the terminal dock. */
    ToggleTerminal("terminal_panel::Toggle", isAvailable = { it.hasProject }),

    /** Start another shell in the project directory. */
    NewTerminal("workspace::NewTerminal", isAvailable = { it.hasProject }),

    /** Kill the shell showing in the dock. */
    CloseTerminal("terminal::Close", isAvailable = { it.terminalCount > 0 }),

    NextTerminal("terminal::ActivateNextItem", isAvailable = { it.terminalCount > 1 }),
    PreviousTerminal("terminal::ActivatePreviousItem", isAvailable = { it.terminalCount > 1 }),
}

/** Which surface the keyboard is talking to. */
enum class Focus {
    /** The editor, project panel, dialogs — everything that is not a shell. */
    Workspace,

    /** A terminal session: the shell claims the keyboard. */
    Terminal,
}

/**
 * One chord: what it matches, and how it prints.
 *
 * [shift] is three-valued because the table needs all three: `Ctrl+Shift+\``
 * is a different command from `Ctrl+\``, while `Ctrl+PageDown` doesn't care
 * either way.
 */
data class Chord(
    val keyCode: Int,
    /** The key on its own, as the UI writes it: `S`, `Tab`, `` ` ``. */
    val keyName: String,
    val ctrl: Boolean = true,
    /** True: Shift must be held. False: it must not be. Null: either. */
    val shift: Boolean? = false,
) {
    /** What the menus, the palette and docs/SHORTCUTS.md all print. */
    val label: String = buildString {
        if (ctrl) append("Ctrl ")
        if (shift == true) append("Shift ")
        append(keyName)
    }

    internal fun matches(event: AndroidKeyEvent): Boolean =
        event.keyCode == keyCode &&
            event.isCtrlPressed == ctrl &&
            (shift == null || shift == event.isShiftPressed)
}

/** A chord, the command it runs, and where it is listened for. */
private data class Binding(
    val command: WorkspaceCommand,
    val chord: Chord,
    val scope: Set<Focus>,
)

private val Everywhere = setOf(Focus.Workspace, Focus.Terminal)
private val InWorkspace = setOf(Focus.Workspace)
private val InTerminal = setOf(Focus.Terminal)

/**
 * The keymap.
 *
 * The terminal half is the short list the workspace keeps while a shell has
 * the keyboard. Note what is *not* in it: every plain `Ctrl+<letter>`.
 * `Ctrl+Tab` cycles terminal sessions rather than editor tabs, because that is
 * the pane you are looking at; `Ctrl+PageUp/Down` still cycles editor tabs
 * from anywhere.
 */
private val BINDINGS: List<Binding> = listOf(
    // Shift is ignored on the workspace half, so the twins a user learned in
    // the terminal keep working in the editor. `P` is the exception: its twin
    // is the command palette.
    Binding(
        WorkspaceCommand.Save,
        Chord(AndroidKeyEvent.KEYCODE_S, "S", shift = null),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.CloseTab,
        Chord(AndroidKeyEvent.KEYCODE_W, "W", shift = null),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.ToggleProjectPanel,
        Chord(AndroidKeyEvent.KEYCODE_B, "B", shift = null),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.OpenProjects,
        Chord(AndroidKeyEvent.KEYCODE_O, "O", shift = null),
        InWorkspace,
    ),
    Binding(WorkspaceCommand.FindFile, Chord(AndroidKeyEvent.KEYCODE_P, "P"), InWorkspace),
    Binding(
        WorkspaceCommand.OpenSettings,
        Chord(AndroidKeyEvent.KEYCODE_COMMA, ",", shift = null),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.NextTab,
        Chord(AndroidKeyEvent.KEYCODE_TAB, "Tab"),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.PreviousTab,
        Chord(AndroidKeyEvent.KEYCODE_TAB, "Tab", shift = true),
        InWorkspace,
    ),
    // Zed's own `ctrl-shift-t`. The rest of the tab commands (close others,
    // close to the right, close all, pin) are Zed's `ctrl-k` chords, which
    // this table cannot express — two-key sequences wait for P7-3's keymap
    // JSON — so they live in the tab's context menu and the palette rather
    // than being given bindings a Zed user would have to unlearn.
    Binding(
        WorkspaceCommand.ReopenClosedTab,
        Chord(AndroidKeyEvent.KEYCODE_T, "T", shift = true),
        InWorkspace,
    ),
    // Zed's `ctrl-shift-e`.
    Binding(
        WorkspaceCommand.RevealInProjectPanel,
        Chord(AndroidKeyEvent.KEYCODE_E, "E", shift = true),
        InWorkspace,
    ),

    Binding(
        WorkspaceCommand.CloneRepository,
        Chord(AndroidKeyEvent.KEYCODE_G, "G", shift = true),
        Everywhere,
    ),
    Binding(
        WorkspaceCommand.ToggleTerminal,
        Chord(AndroidKeyEvent.KEYCODE_GRAVE, "`"),
        Everywhere,
    ),
    Binding(
        WorkspaceCommand.NewTerminal,
        Chord(AndroidKeyEvent.KEYCODE_GRAVE, "`", shift = true),
        Everywhere,
    ),
    Binding(
        WorkspaceCommand.NextTab,
        Chord(AndroidKeyEvent.KEYCODE_PAGE_DOWN, "PageDown", shift = null),
        Everywhere,
    ),
    Binding(
        WorkspaceCommand.PreviousTab,
        Chord(AndroidKeyEvent.KEYCODE_PAGE_UP, "PageUp", shift = null),
        Everywhere,
    ),

    Binding(
        WorkspaceCommand.NextTerminal,
        Chord(AndroidKeyEvent.KEYCODE_TAB, "Tab"),
        InTerminal,
    ),
    Binding(
        WorkspaceCommand.PreviousTerminal,
        Chord(AndroidKeyEvent.KEYCODE_TAB, "Tab", shift = true),
        InTerminal,
    ),
    Binding(
        WorkspaceCommand.Save,
        Chord(AndroidKeyEvent.KEYCODE_S, "S", shift = true),
        InTerminal,
    ),
    Binding(
        WorkspaceCommand.CloseTerminal,
        Chord(AndroidKeyEvent.KEYCODE_W, "W", shift = true),
        InTerminal,
    ),
    Binding(
        WorkspaceCommand.ToggleProjectPanel,
        Chord(AndroidKeyEvent.KEYCODE_B, "B", shift = true),
        InTerminal,
    ),
    Binding(
        WorkspaceCommand.OpenProjects,
        Chord(AndroidKeyEvent.KEYCODE_O, "O", shift = true),
        InTerminal,
    ),
    Binding(
        WorkspaceCommand.OpenSettings,
        Chord(AndroidKeyEvent.KEYCODE_COMMA, ",", shift = true),
        InTerminal,
    ),
)

/**
 * The chords that open the command palette — Zed's `Ctrl+Shift+P`, and the
 * `F1` it also binds.
 *
 * The palette is not a [WorkspaceCommand] because it isn't dispatched like
 * one: it is a modal the workspace shows, and what it dispatches afterwards is
 * the rest of this table.
 *
 * `Ctrl+Shift+P` is matched **wherever focus is**, terminal included: a chord
 * that meant two different things depending on which pane you last touched
 * would be the kind of thing that makes a keymap feel unreliable, and the
 * palette is how a shell user reaches the commands the shell's own
 * `Ctrl+<letter>`s cost them. It is why find file has no shifted twin here —
 * from a terminal, it is `Ctrl+Shift+P` and then "file". `F1` stays
 * workspace-only, because function keys belong to vi and htop.
 */
val CommandPaletteChord = Chord(AndroidKeyEvent.KEYCODE_P, "P", shift = true)
private val CommandPaletteFunctionKey =
    Chord(AndroidKeyEvent.KEYCODE_F1, "F1", ctrl = false)

/** True when this event should open the command palette. */
fun isCommandPalette(event: KeyEvent, focus: Focus = Focus.Workspace): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.nativeKeyEvent.isAltPressed) return false
    if (CommandPaletteChord.matches(event.nativeKeyEvent)) return true
    return focus == Focus.Workspace && CommandPaletteFunctionKey.matches(event.nativeKeyEvent)
}

/** The command a key event maps to, or null to let it through. */
fun workspaceCommandFor(event: KeyEvent, focus: Focus = Focus.Workspace): WorkspaceCommand? {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return null
    return workspaceCommandFor(event.nativeKeyEvent, focus)
}

/** As above, for callers holding an Android key event (the terminal view). */
fun workspaceCommandFor(event: AndroidKeyEvent, focus: Focus): WorkspaceCommand? {
    if (event.action != AndroidKeyEvent.ACTION_DOWN || !event.isCtrlPressed) return null
    // Nothing here uses Alt, and on European layouts AltGr can arrive as
    // Ctrl+Alt — a chord meant to type a character must not run a command.
    if (event.isAltPressed) return null
    return BINDINGS
        .firstOrNull { focus in it.scope && it.chord.matches(event) }
        ?.command
}

/**
 * Every chord that runs [command] while [focus] has the keyboard, in table
 * order — so the first is the one to show and the rest are the alternatives.
 */
fun shortcutLabels(
    command: WorkspaceCommand,
    focus: Focus = Focus.Workspace,
): List<String> = BINDINGS
    .filter { it.command == command && focus in it.scope }
    .map { it.chord.label }

/** The chord to print beside [command], or null when it has none. */
fun shortcutLabel(command: WorkspaceCommand, focus: Focus = Focus.Workspace): String? =
    shortcutLabels(command, focus).firstOrNull()

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
