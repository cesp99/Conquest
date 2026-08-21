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
    Save("workspace::Save", isAvailable = { it.hasActiveBuffer }),

    /** Close the active tab. */
    CloseTab("pane::CloseActiveItem", isAvailable = { it.hasActiveFile }),

    NextTab("pane::ActivateNextItem", isAvailable = { it.tabCount > 1 }),
    PreviousTab("pane::ActivatePreviousItem", isAvailable = { it.tabCount > 1 }),

    /**
     * Back along the navigation history — the tab and place you were before.
     * The tab bar's `←` is the same command with a mouse.
     */
    GoBack("pane::GoBack", isAvailable = { it.canGoBack }),

    /** Forward again, replaying what GoBack stepped out of. */
    GoForward("pane::GoForward", isAvailable = { it.canGoForward }),

    /**
     * Create a file in the project and open it — Zed's `workspace::NewFile`,
     * which the tab bar's `+` leads with (pane.rs:4272). The file lands at
     * the project root unless the name typed is a path.
     */
    NewFile("workspace::NewFile", isAvailable = { it.hasProject }),

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
    ToggleProjectPanel(
        "project_panel::Toggle",
        isAvailable = { "project_panel" !in it.hiddenPanels },
    ),

    /** Open the project picker (switch, create, import, export). */
    OpenProjects("projects::Open"),

    /** Open the fuzzy file finder. */
    FindFile("file_finder::Toggle", isAvailable = { it.hasProject }),

    /** Find within the open file — Zed's buffer search. */
    FindInFile("buffer_search::Deploy", isAvailable = { it.hasActiveBuffer }),

    /**
     * Show or hide the preview of the open file.
     *
     * One command for both previews, as Zed has one button for both: which of
     * them appears is a property of the file, not a choice the user makes.
     * Unavailable — greyed, not hidden — on a file with neither.
     */
    TogglePreview(
        "conquest::TogglePreview",
        isAvailable = { it.canPreview && "preview" !in it.hiddenPanels },
    ),

    /** Open the settings screen. */
    OpenSettings("conquest::OpenSettings"),

    /**
     * Open settings.json itself as an editor tab — Zed's
     * `zed::OpenSettingsFile` (zed/src/zed.rs:261). Not a convenience: the
     * file lives in app-private storage no other editor on the device can
     * reach, and it is the only place `agent_servers` and anything else
     * without a settings-screen row can be written at all. Needs a project
     * only because the tab strip does.
     */
    OpenSettingsFile("zed::OpenSettingsFile", isAvailable = { it.hasProject }),

    /** Pick a theme, previewing each as the selection moves — Zed's own. */
    SelectTheme("theme_selector::Toggle"),

    /**
     * Clone a git repository into a new project.
     *
     * In the table like everything else, and refused at the point of use where
     * there is no Linux userland to run git in — the same way FindFile is
     * refused with no project open. No chord: `Ctrl+Shift+G` is the git panel
     * in Zed and is the git panel here, and cloning is a thing one does once
     * per repository — the palette and the picker's footer are enough.
     */
    CloneRepository("git::Clone", isOffered = { it.canClone }),

    /**
     * Install a language server from apt — Zed asks before installing the
     * extension for a language (extension_suggest.rs:176) and so do we;
     * nothing here ever downloads on its own.
     *
     * No chord, for CloneRepository's reason: it is done once per language,
     * and the two ways in are the palette and the status bar saying a server
     * is missing. Absent, not greyed, where there is no userland to run apt.
     */
    InstallLanguageServer(
        "conquest::InstallLanguageServer",
        isOffered = { it.canInstallLanguageServer },
    ),

    /**
     * Wrap long lines, or stop — Zed's `editor::ToggleSoftWrap`, which it
     * binds to `ctrl-k ctrl-z`, a two-key sequence this table cannot express
     * yet. It writes the setting, so it survives a restart the way Zed's does.
     */
    ToggleSoftWrap("editor::ToggleSoftWrap"),

    /**
     * The UI font size, which is Zed's rem: `window.rem_size = ui_font_size`
     * (theme_settings/src/settings.rs:619), so these grow and shrink the whole
     * chrome — rows, bars, gaps and icons — not only the text. Zed's own
     * chords (default-linux.json:1402-1405).
     */
    IncreaseUiFontSize("zed::IncreaseUiFontSize"),
    DecreaseUiFontSize("zed::DecreaseUiFontSize"),
    ResetUiFontSize("zed::ResetUiFontSize"),

    /**
     * The commit graph — Zed's `git::OpenGraph`, which it opens as a pane item
     * and so does this: it is a view of the repository, read and scrolled.
     */
    OpenGitGraph("git::OpenGraph", isAvailable = { it.hasProject }),

    /**
     * The branch picker — Zed's `git::Switch`, which the git panel's branch
     * button and the title bar's branch chip both dispatch, on the chord Zed
     * gives its `branches::OpenRecent` alias (default-linux.json:644).
     */
    SwitchBranch("git::Switch", isAvailable = { it.hasProject }),

    /**
     * The remote family — Zed's `git::Fetch`, `git::Pull`, `git::PullRebase`,
     * `git::Push` and `git::ForcePush`, registered on the workspace there
     * (git_ui.rs:193-241) and handed to the git panel here, which owns the
     * session, the single-flight busy flag and the strip that says what git
     * answered. Their chords are the panel's ctrl-g leader sequences, scoped
     * to the panel exactly as Zed scopes them (default-linux.json:1060-1066)
     * — two keystrokes, which this table cannot express, so no chord prints
     * beside them and the panel's split button menu carries the labels
     * instead.
     *
     * Offered only where there is a userland to run git in, like cloning;
     * greyed while the git panel is switched off, like its toggle — the
     * commands run *in* the panel, so a hidden panel means them too.
     */
    GitFetch(
        "git::Fetch",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitPull(
        "git::Pull",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitPullRebase(
        "git::PullRebase",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitPush(
        "git::Push",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitForcePush(
        "git::ForcePush",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),

    /**
     * The bulk stages — Zed's `git::StageAll` / `git::UnstageAll`, whose
     * chords (`ctrl-space` / `ctrl-shift-space`, default-linux.json:
     * 1070-1071) live in the panel's own key handler because they are
     * panel-scoped there too: in the editor `ctrl-space` is completions.
     */
    GitStageAll(
        "git::StageAll",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitUnstageAll(
        "git::UnstageAll",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),

    /**
     * The whole project's diff as a tab — Zed's `git::Diff`, the panel's own
     * "View Diff" button and the `ctrl-g d` chord (default-linux.json:1067).
     * Routed through the panel like the remote family, so the one dispatcher
     * serves every way in.
     */
    GitDiff(
        "git::Diff",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),

    /**
     * Show or hide the git panel — Zed's `git_panel::ToggleFocus`, on the
     * chord Zed gives it (default-linux.json:700).
     */
    ToggleGitPanel(
        "git_panel::ToggleFocus",
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),

    /**
     * Show or hide the agent panel — Zed's `agent::ToggleFocus`, on the chord
     * Zed gives it (default-linux.json: `ctrl-?`, which a phone keyboard
     * cannot reach, so this takes Zed's *other* agent chord `ctrl-alt-a`).
     *
     * Offered only where an agent could run at all: the `play` edition has no
     * userland, so it has no agent panel and is not shown one greyed out.
     */
    ToggleAgentPanel(
        "agent::ToggleFocus",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasProject && "agent_panel" !in it.hiddenPanels },
    ),

    /**
     * Show or hide the left and right docks — Zed's `workspace::ToggleLeftDock`
     * on `ctrl-b` and `ToggleRightDock` on `ctrl-alt-b`
     * (default-linux.json:668-669).
     *
     * A dock with nothing in it opens the panel that lives on that side, which
     * is what "show the sidebar" means to the person pressing it.
     */
    ToggleLeftDock("workspace::ToggleLeftDock"),
    ToggleRightDock("workspace::ToggleRightDock"),

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
    /**
     * Whether Alt must be held. Zed spends it sparingly here — `ctrl-alt-b`
     * for the right dock, `ctrl-alt-minus` for the navigation history — and
     * every other chord in this table requires it *not* to be, so an Alt
     * chord bound by the IME or by a desktop shell cannot be swallowed by
     * accident.
     */
    val alt: Boolean = false,
) {
    /** What the menus, the palette and docs/SHORTCUTS.md all print. */
    val label: String = buildString {
        if (ctrl) append("Ctrl ")
        if (alt) append("Alt ")
        if (shift == true) append("Shift ")
        append(keyName)
    }

    internal fun matches(event: AndroidKeyEvent): Boolean =
        event.keyCode == keyCode &&
            event.isCtrlPressed == ctrl &&
            event.isAltPressed == alt &&
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
    // Zed's `ctrl-b` and `ctrl-alt-b` (default-linux.json:668-669). The
    // project panel keeps the plain chord because on this app's default layout
    // it *is* the left dock, and because it is the one people press.
    Binding(
        WorkspaceCommand.ToggleLeftDock,
        Chord(AndroidKeyEvent.KEYCODE_B, "B", shift = null),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.ToggleRightDock,
        Chord(AndroidKeyEvent.KEYCODE_B, "B", shift = null, alt = true),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.OpenProjects,
        // `shift = false`, not "either": the shifted twin is the outline
        // picker (Zed's `outline::Toggle`), and two commands on one physical
        // chord must be disjoint here rather than settled by handler order.
        Chord(AndroidKeyEvent.KEYCODE_O, "O", shift = false),
        InWorkspace,
    ),
    Binding(WorkspaceCommand.FindFile, Chord(AndroidKeyEvent.KEYCODE_P, "P"), InWorkspace),
    // Zed's ctrl-= / ctrl-+ / ctrl-- / ctrl-0 (default-linux.json:1402-1405).
    // `shift = null` on the grow chord because `ctrl-+` *is* the shifted `=`
    // on most layouts, and Zed binds both to the same action.
    Binding(
        WorkspaceCommand.IncreaseUiFontSize,
        Chord(AndroidKeyEvent.KEYCODE_EQUALS, "=", shift = null),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.DecreaseUiFontSize,
        // Not the shifted twin: `Ctrl+Shift+-` and `Ctrl+Alt+-` are the
        // navigation history's, and a chord that grew the font instead of
        // going back would be maddening.
        Chord(AndroidKeyEvent.KEYCODE_MINUS, "-", shift = false),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.ResetUiFontSize,
        Chord(AndroidKeyEvent.KEYCODE_0, "0", shift = false),
        InWorkspace,
    ),
    // Ctrl+F is free in the editor — it moves nothing there — and this is
    // where every editor puts find. In a terminal it is readline's "forward
    // one character", so the bar is not offered while a shell has the keys.
    //
    // The one command whose shifted twin is *not* itself: Ctrl+Shift+F opens
    // project search ([ProjectSearchChord]), which is why this one is pinned
    // to unshifted rather than matching either way like its neighbours.
    Binding(
        WorkspaceCommand.FindInFile,
        Chord(AndroidKeyEvent.KEYCODE_F, "F", shift = false),
        InWorkspace,
    ),
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
    // Zed's Linux chords for the navigation history, exactly as the keymap
    // writes them: `ctrl-alt--` is GoBack and `ctrl-alt-_` — the same key
    // with Shift — is GoForward (default-linux.json:512-514). Alt chords on
    // the minus key survive AltGr (see the note in `workspaceCommandFor`)
    // because Zed ships these on Linux layouts unchanged. The keymap's other
    // spellings, the mouse thumb buttons `back`/`forward`, have no Android
    // key event to match. Workspace-only: `Alt+anything` belongs to the
    // shell while a terminal has the keyboard.
    Binding(
        WorkspaceCommand.GoBack,
        Chord(AndroidKeyEvent.KEYCODE_MINUS, "-", shift = false, alt = true),
        InWorkspace,
    ),
    Binding(
        WorkspaceCommand.GoForward,
        Chord(AndroidKeyEvent.KEYCODE_MINUS, "-", shift = true, alt = true),
        InWorkspace,
    ),
    // Zed's `ctrl-n` (default-linux.json:654). Shift is ignored like Save's,
    // until something claims the twin (Zed spends it on the new *window*,
    // which an Android app does not have). Workspace-only: in a shell
    // `Ctrl+N` is readline's next-history.
    Binding(
        WorkspaceCommand.NewFile,
        // `shift = false`, not "either": `Ctrl+Shift+N` is the project
        // panel's new-folder chord, and a workspace binding that matched it
        // too would kill it from above (the preview pass runs first).
        Chord(AndroidKeyEvent.KEYCODE_N, "N", shift = false),
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

    // Zed's own `ctrl-shift-g`. Cloning had it while there was no git panel
    // to give it to; it keeps the palette, the ☰ menu and the project
    // picker's own footer, which is where somebody looking to clone a
    // repository actually goes.
    Binding(
        WorkspaceCommand.ToggleGitPanel,
        Chord(AndroidKeyEvent.KEYCODE_G, "G", shift = true),
        Everywhere,
    ),
    // Zed's `alt-ctrl-shift-b` for the branch picker (default-linux.json:644,
    // via the `branches::OpenRecent` alias of `git::Branch`). All three
    // modifiers, so it cannot collide with `ctrl-b`'s dock family above.
    // `InWorkspace`, like every other Alt chord.
    Binding(
        WorkspaceCommand.SwitchBranch,
        Chord(AndroidKeyEvent.KEYCODE_B, "B", shift = true, alt = true),
        InWorkspace,
    ),
    // `ctrl-alt-a`, which is what Zed binds `agent::ToggleFocus` to besides
    // `ctrl-?` — the question mark needs a shift on every layout a phone
    // keyboard has, and the chord table cannot express that.
    //
    // `InWorkspace`, like the dock toggles it sits beside: an Alt chord
    // belongs to the pty while the terminal has focus, and the workspace keeps
    // only its ``Ctrl+` `` and the Ctrl+Shift twins there. Taking this one
    // everywhere would break Alt+A in vi to save a keystroke.
    Binding(
        WorkspaceCommand.ToggleAgentPanel,
        Chord(AndroidKeyEvent.KEYCODE_A, "A", alt = true),
        InWorkspace,
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

/**
 * The chord that opens project search — Zed's own `ctrl-shift-f`
 * (assets/keymaps/default-linux.json:682).
 *
 * Not a [WorkspaceCommand], for the reason the palette is not one either: what
 * it opens is a *surface* rather than an action. The panel takes the keyboard,
 * keeps the caret in its own query field, answers Escape itself and holds a
 * live search that has to be cancelled when it goes; the workspace's whole job
 * is to put it on screen.
 *
 * Matched in the workspace's preview pass, so it fires wherever focus sits —
 * except inside a focused terminal, which receives key events before Compose
 * does and dispatches only [workspaceCommandFor]. The ☰ menu carries the panel
 * for everyone else: a finger, and a shell that has the keyboard.
 */
val ProjectSearchChord = Chord(AndroidKeyEvent.KEYCODE_F, "F", shift = true)

/** True when this event should open project search. */
fun isProjectSearch(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.nativeKeyEvent.isAltPressed) return false
    return ProjectSearchChord.matches(event.nativeKeyEvent)
}

/**
 * Go to line — Zed's `go_to_line::Toggle` on `ctrl-g`
 * (assets/keymaps/default-linux.json:622), and the reason cloning had to take
 * the shifted twin.
 *
 * A surface rather than a [WorkspaceCommand], for the same reason project
 * search is one: what it puts on screen takes the keyboard, moves the caret as
 * you type, answers Escape itself and has to put the caret back if you cancel.
 * The workspace's whole job is to show it.
 *
 * Never matched while a shell has the keyboard: `Ctrl+G` is BEL on a terminal
 * and readline's abort, and no editor command is worth taking those. The ☰
 * menu carries it for a finger and for a focused terminal.
 */
val GoToLineChord = Chord(AndroidKeyEvent.KEYCODE_G, "G", shift = false)

/** True when this event should open go-to-line. */
fun isGoToLine(event: KeyEvent, focus: Focus = Focus.Workspace): Boolean {
    if (focus != Focus.Workspace) return false
    if (event.type != KeyEventType.KeyDown) return false
    if (event.nativeKeyEvent.isAltPressed) return false
    return GoToLineChord.matches(event.nativeKeyEvent)
}

/**
 * The outline picker — Zed's `outline::Toggle` on `ctrl-shift-o`
 * (default-linux.json:621). A surface like go-to-line, and for the same
 * reason: it previews as you browse and must restore the caret on Escape.
 * The breadcrumbs are its touch route, as they are Zed's own button into it.
 */
val OutlineChord = Chord(AndroidKeyEvent.KEYCODE_O, "O", shift = true)

/** True when this event should open the outline picker. */
fun isOutline(event: KeyEvent, focus: Focus = Focus.Workspace): Boolean {
    if (focus != Focus.Workspace) return false
    if (event.type != KeyEventType.KeyDown) return false
    if (event.nativeKeyEvent.isAltPressed) return false
    return OutlineChord.matches(event.nativeKeyEvent)
}

/**
 * Show or hide the preview of the open file.
 *
 * Zed puts this on `ctrl-shift-v` (default-linux.json:607) and this table may
 * not: the convention above is that editor-local clipboard chords never appear
 * here, and `Ctrl+Shift+V` is paste twice over. It is the editor's own paste —
 * `handleEditorKey` pastes on `Ctrl+V` with or without Shift — and it is the
 * project panel's, which matches `Ctrl+V` without looking at Shift at all, so
 * a chord here would have silently eaten a file paste and offered no feedback
 * that it had. Zed's other binding, `ctrl-k v`, is a two-key sequence this
 * table cannot express yet.
 *
 * So `Ctrl+Shift+M`, which nothing in this app or in a shell claims. Which side
 * the preview lands on is decided by the width of the screen rather than by a
 * second chord, and *which* preview by the file that is open — Markdown or
 * SVG, the same two Zed's own eye button offers. Workspace-only: in a terminal
 * `Ctrl+Shift+M` is free but a preview needs a file open, not a shell.
 */
val PreviewChord = Chord(AndroidKeyEvent.KEYCODE_M, "M", shift = true)

/** True when this event should show or hide the preview. */
fun isPreview(event: KeyEvent, focus: Focus = Focus.Workspace): Boolean {
    if (focus != Focus.Workspace) return false
    if (event.type != KeyEventType.KeyDown) return false
    if (event.nativeKeyEvent.isAltPressed) return false
    return PreviewChord.matches(event.nativeKeyEvent)
}

/** The command a key event maps to, or null to let it through. */
fun workspaceCommandFor(event: KeyEvent, focus: Focus = Focus.Workspace): WorkspaceCommand? {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return null
    return workspaceCommandFor(event.nativeKeyEvent, focus)
}

/** As above, for callers holding an Android key event (the terminal view). */
fun workspaceCommandFor(event: AndroidKeyEvent, focus: Focus): WorkspaceCommand? {
    if (event.action != AndroidKeyEvent.ACTION_DOWN || !event.isCtrlPressed) return null
    // Alt is matched by the chord rather than refused outright, and nearly
    // every chord requires it *not* to be held: on European layouts AltGr
    // arrives as Ctrl+Alt, and a chord meant to type `@` or `[` must not run a
    // command. The exceptions are `Ctrl+Alt+B` for the right dock and the
    // navigation history's `Ctrl+Alt+Minus` pair — Zed's own Linux bindings,
    // on keys no common Latin layout hangs a character on.
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
