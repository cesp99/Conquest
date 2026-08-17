package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.AppSettings
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.core.ProjectEntry
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.core.ProjectSummary
import to.eyed.conquest.code.core.ProjectsRoot
import to.eyed.conquest.code.core.SafTransfer
import java.io.File
import android.content.Context
import to.eyed.conquest.code.terminal.GitClone
import to.eyed.conquest.code.terminal.TerminalSessions
import to.eyed.conquest.code.terminal.Userland
import to.eyed.conquest.code.terminal.UserlandInstaller
import to.eyed.conquest.code.terminal.UserlandState
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.core.ProjectSearchMatch
import to.eyed.conquest.code.ui.search.BufferSearchBar
import to.eyed.conquest.code.ui.search.ProjectSearchPanel
import to.eyed.conquest.code.ui.search.revealProjectSearchMatch
import to.eyed.conquest.code.ui.editor.EditorPane
import to.eyed.conquest.code.ui.editor.EditorState
import to.eyed.conquest.code.ui.terminal.TerminalDock

/**
 * Where the project panel stops being a drawer and becomes Zed's sidebar.
 *
 * 840dp is Material's "expanded" breakpoint, and it was the wrong rule: the
 * Fold's inner display is 674dp at its density, so the device this app is
 * built for was getting the phone layout while unfolded. What actually
 * matters is whether the editor is still usable beside a 240dp panel, and at
 * 600dp it has 360dp — a phone's whole width — so that is the line.
 */
private val WideLayoutMinWidth = 600.dp
// Zed's own default (assets/settings/default.json:816).
private val ProjectPanelWidth = 240.dp

/** What ProjectSearchPanel asks for as a dock — kept in step with it. */
private val ProjectSearchDockWidth = 360.dp

/**
 * Under this the editor is not worth showing beside two docks; the search
 * panel takes the whole work area instead. A phone's own width, which is the
 * least anyone edits code in.
 */
private val MinEditorWidth = 360.dp

/** Terminal dock: initial height, and how small or large a drag may make it. */
private val TerminalDockHeight = 260.dp
private val TerminalDockMinHeight = 96.dp

/** The file opened on a fresh install, relative to the sample project root. */
private const val STARTUP_FILE = "src/main.rs"

/**
 * How often to re-read each open buffer's dirty / on-disk state from the
 * engine. Those are plain JNI getters rather than observable state, so the UI
 * pulls them; a few calls per second per tab is far cheaper than making every
 * keystroke push through the bridge.
 */
private const val STATUS_POLL_MS = 250L

/**
 * The line between two docks.
 *
 * Material's dividers default to `outlineVariant`, which our theme maps to
 * Zed's `border.variant` — and Zed reserves that for the quieter lines inside
 * a panel (a toolbar's underline, a list separator). The edges *between* docks
 * are `border` (crates/workspace/src/dock.rs:1203), and at One Dark's values
 * the two differ enough to read as a different app.
 */
@Composable
private fun DockDivider(vertical: Boolean = false) {
    val color = LocalZedTheme.current.color("border")
    if (vertical) VerticalDivider(color = color) else HorizontalDivider(color = color)
}

/**
 * Root of the IDE UI, in the spirit of Zed's workspace: a project panel, a tab
 * strip, an editor area and a status bar. Wide screens (tablets, unfolded
 * foldables) get a fixed sidebar; compact screens (phones, folded) get a
 * slimmer layout with the panel in a drawer.
 */
@Composable
fun WorkspaceScreen(
    settings: AppSettings,
    settingsPath: String?,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Resolving the project root can write to disk (it seeds the sample on a
    // fresh install), so it happens off the main thread and the UI starts with
    // no project — which is also the state P3-4's project picker will use.
    var project by remember { mutableStateOf<ProjectSession?>(null) }
    val files = remember { OpenFilesState() }
    val scope = rememberCoroutineScope()
    // Conflicts the user chose to live with, so the bar doesn't nag.
    val dismissedConflicts = remember { mutableStateOf(setOf<String>()) }

    // Workspace shortcuts are matched in a *preview* pass at the root, so they
    // work wherever focus sits — including while the editor holds it. Editor
    // chords are never matched here, so they still reach EditorPane; terminal
    // chords are arbitrated by focus (see Keybindings.kt).
    val rootFocus = remember { FocusRequester() }

    // The terminal dock. Sessions survive the dock being hidden — a build
    // keeps running while you read code — but not a project switch, since a
    // shell sitting in a directory nobody has open is a trap.
    val terminals = remember(context) { TerminalSessions.of(context) }
    var terminalFocused by remember { mutableStateOf(false) }
    var dockHeight by remember { mutableStateOf(TerminalDockHeight) }
    // Removing the Linux userland throws away ~100 MB and everything installed
    // into it, so it confirms first — same rule as deleting a project.
    var removeUserlandOpen by remember { mutableStateOf(false) }

    // Project picker state. `projects` is re-listed whenever the dialog opens
    // or a transfer finishes, rather than watched — projects change only when
    // the user changes them.
    var pickerOpen by remember { mutableStateOf(false) }
    /** The picker opens straight into the clone form for Ctrl+Shift+G. */
    var pickerStartsInClone by remember { mutableStateOf(false) }
    var finderOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    /** Ctrl+Shift+E asked the panel to show the active file and take the keyboard. */
    var revealInPanel by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var themeSelectorOpen by remember { mutableStateOf(false) }
    var searchBarOpen by remember { mutableStateOf(false) }
    /** Ctrl+Shift+F. The token is bumped to pull focus back to its query. */
    var projectSearchOpen by remember { mutableStateOf(false) }
    var projectSearchFocus by remember { mutableIntStateOf(0) }
    /**
     * Whether the panel is drawn over the whole work area rather than beside
     * the editor. Decided during layout, where the width is known, and read by
     * [openMatch] — which has to hand the area back when it is true, and must
     * not when the editor is right there next to it.
     */
    var searchTakesWorkArea by remember { mutableStateOf(false) }
    var settingsValid by remember { mutableStateOf(true) }
    var projects by remember { mutableStateOf(emptyList<ProjectSummary>()) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var transferError by remember { mutableStateOf<String?>(null) }

    fun refreshProjects() {
        scope.launch {
            projects = withContext(Dispatchers.IO) { ProjectsRoot.list(context) }
        }
    }

    fun openFile(
        project: ProjectSession,
        path: String,
        /** Runs once the tab exists — how a search hit puts the caret on itself. */
        onOpened: (suspend (OpenFile) -> Unit)? = null,
    ) {
        val existing = files.indexOfPath(path)
        if (existing >= 0) {
            files.select(existing)
            val tab = files.tabs[existing]
            if (onOpened != null) scope.launch { onOpened(tab) }
            return
        }
        scope.launch {
            val absolutePath = project.absolutePathOf(path) ?: return@launch
            val session = withContext(Dispatchers.IO) { BufferSession.openFile(absolutePath) }
                ?: return@launch
            val opened = OpenFile(path, EditorState(session))
            files.open(opened)
            onOpened?.invoke(opened)
        }
    }

    /**
     * A path the panel deleted. The engine keys buffers by path, so a tab left
     * open on it would keep a live buffer whose next save recreates the file
     * the user just deleted.
     */
    fun closeTabsUnder(path: String) {
        for (index in files.tabs.indices.reversed()) {
            val tab = files.tabs[index]
            if (tab.path == path || tab.path.startsWith("$path/")) files.close(index)
        }
    }

    /**
     * A path the panel renamed or moved: the tab has to be reopened at the new
     * path, or saving writes back to the old one and the user ends up with
     * both files.
     *
     * **Unsaved edits travel with the file.** Closing the tab is what makes
     * the engine let the buffer go, and `close` is the unconditional kind — it
     * drops whatever was unsaved. Asking here would be too late and asking
     * before the rename would be asking about a file that still had its old
     * name, so the edits are written to the *new* path first. Saving instead
     * would write them back to the old name and recreate the file the user
     * just renamed away.
     */
    fun retitleTabs(from: String, to: String) {
        val open = project ?: return
        val moved = files.tabs.filter { it.path == from || it.path.startsWith("$from/") }
        if (moved.isEmpty()) return
        val wasActive = files.active?.path
        val movedPaths = moved.map { it.path }
        scope.launch {
            for (tab in moved) {
                if (!tab.isDirty) continue
                val destination = open.absolutePathOf(to + tab.path.removePrefix(from))
                val text = withContext(Dispatchers.IO) { CoreBridge.bufferText(tab.session.id) }
                if (destination != null && text != null) {
                    withContext(Dispatchers.IO) { File(destination).writeText(text) }
                }
            }
            for (path in movedPaths) files.indexOfPath(path).takeIf { it >= 0 }?.let(files::close)
            for (path in movedPaths) openFile(open, to + path.removePrefix(from))
            if (wasActive != null && wasActive !in movedPaths) {
                files.indexOfPath(wasActive).takeIf { it >= 0 }?.let(files::select)
            }
        }
    }

    /**
     * Switch the workspace to another project: close every tab and the old
     * worktree first, so the engine isn't left scanning a project nobody is
     * looking at.
     */
    fun openProject(path: String, startupFile: String? = null) {
        scope.launch {
            while (files.tabs.isNotEmpty()) files.close(files.tabs.lastIndex)
            terminals.closeAll()
            files.clearClosedHistory()
            dismissedConflicts.value = emptySet()
            project?.close()
            val opened = ProjectSession(path)
            project = opened
            withContext(Dispatchers.IO) {
                ProjectsRoot.setLastOpened(context, File(path).name)
            }
            if (startupFile != null) openFile(opened, startupFile)
            refreshProjects()
        }
    }

    // The engine runs Debian's git through proot for status, and cannot guess
    // where either lives. Keyed on the installer's state, not on `Unit`: the
    // userland can be installed and removed while the app runs, and told once
    // at startup the engine kept pointing at whatever was true then. Install
    // Debian and git status stayed silently empty until the next launch;
    // remove it and the engine went on running a git that was no longer there.
    LaunchedEffect(UserlandInstaller.state) {
        withContext(Dispatchers.IO) { syncUserlandWithEngine(context) }
    }

    LaunchedEffect(Unit) {
        val root = withContext(Dispatchers.IO) { ProjectsRoot.defaultProject(context) }
        val opened = ProjectSession(root)
        project = opened
        withContext(Dispatchers.IO) { ProjectsRoot.setLastOpened(context, File(root).name) }
        refreshProjects()
        // Only meaningful for the seeded sample; a project the user brought in
        // simply opens with no tabs.
        if (File(root, STARTUP_FILE).isFile) openFile(opened, STARTUP_FILE)
    }
    DisposableEffect(Unit) {
        onDispose { project?.close() }
    }

    // SAF pickers. Import copies a folder in; export copies a project out.
    // Neither can open in place — see ProjectsRoot for why.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferError = null
            transferMessage = "Importing…"
            val result = withContext(Dispatchers.IO) {
                SafTransfer.importAsProject(context, uri) { progress ->
                    transferMessage = "Importing ${progress.files} files… ${progress.currentName}"
                }
            }
            transferMessage = null
            when (result) {
                is SafTransfer.Result.Imported -> {
                    refreshProjects()
                    openProject(result.project.absolutePath)
                    pickerOpen = false
                }
                is SafTransfer.Result.Failed -> transferError = result.message
                else -> Unit
            }
        }
    }

    var exportTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferError = null
            transferMessage = "Exporting…"
            val result = withContext(Dispatchers.IO) {
                SafTransfer.exportProject(context, File(target.path), uri) { progress ->
                    transferMessage = "Exporting ${progress.files} files… ${progress.currentName}"
                }
            }
            transferMessage = null
            when (result) {
                is SafTransfer.Result.Exported ->
                    transferError = "Exported ${result.files} files to the chosen folder"
                is SafTransfer.Result.Failed -> transferError = result.message
                else -> Unit
            }
        }
    }

    // One loop for every tab's status. A buffer whose file changed underneath
    // it while *clean* is reloaded without asking: there are no local edits to
    // lose, and silently showing stale text would be the worse behaviour.
    LaunchedEffect(files) {
        while (true) {
            files.refreshStatuses()
            for (tab in files.tabs) {
                if (tab.hasDiskChange && !tab.isDirty) {
                    withContext(Dispatchers.IO) { tab.session.reload() }
                    tab.refreshStatus()
                    dismissedConflicts.value -= tab.path
                }
            }
            delay(STATUS_POLL_MS)
        }
    }

    fun save(file: OpenFile) {
        scope.launch {
            withContext(Dispatchers.IO) { file.session.save() }
            file.refreshStatus()
            dismissedConflicts.value -= file.path
        }
    }

    fun reload(file: OpenFile) {
        scope.launch {
            withContext(Dispatchers.IO) { file.session.reload() }
            file.refreshStatus()
            dismissedConflicts.value -= file.path
        }
    }

    val onOpenEntry: (ProjectEntry) -> Unit = { entry ->
        project?.let { openFile(it, entry.path) }
    }

    // Wide layouts can hide the panel (Ctrl+B); compact ones use the drawer.
    var panelVisible by remember { mutableStateOf(true) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var isWide by remember { mutableStateOf(false) }


    fun runCommand(command: WorkspaceCommand): Boolean {
        val active = files.active
        when (command) {
            WorkspaceCommand.Save -> active?.let { save(it) } ?: return false
            WorkspaceCommand.CloseTab -> {
                if (files.activeIndex < 0) return false
                // `requestClose`, never `close`: the unconditional one drops
                // the buffer and every edit since the last save, and this
                // command is the most-used route to it.
                files.requestClose(files.activeIndex)
            }
            WorkspaceCommand.CloseOtherTabs -> {
                if (files.activeIndex < 0) return false
                files.requestCloseOthers(files.activeIndex)
            }
            WorkspaceCommand.CloseTabsToTheRight -> {
                if (files.activeIndex < 0) return false
                files.requestCloseToTheRight(files.activeIndex)
            }
            WorkspaceCommand.CloseAllTabs -> files.requestCloseAll()
            WorkspaceCommand.TogglePinTab -> {
                if (files.activeIndex < 0) return false
                files.togglePin(files.activeIndex)
            }
            WorkspaceCommand.ReopenClosedTab -> {
                val opened = project ?: return false
                val path = files.takeReopenPath() ?: return false
                openFile(opened, path)
            }
            WorkspaceCommand.RevealInProjectPanel -> {
                if (files.active == null) return false
                if (isWide) panelVisible = true else scope.launch { drawerState.open() }
                revealInPanel = true
            }
            WorkspaceCommand.NextTab -> files.selectRelative(1)
            WorkspaceCommand.PreviousTab -> files.selectRelative(-1)
            WorkspaceCommand.ToggleProjectPanel -> if (isWide) {
                panelVisible = !panelVisible
            } else {
                scope.launch { if (drawerState.isOpen) drawerState.close() else drawerState.open() }
            }
            WorkspaceCommand.OpenProjects -> {
                refreshProjects()
                transferError = null
                pickerStartsInClone = false
                pickerOpen = true
            }
            WorkspaceCommand.CloneRepository -> {
                if (!GitClone.isSupported) return false
                refreshProjects()
                transferError = null
                pickerStartsInClone = true
                pickerOpen = true
            }
            WorkspaceCommand.FindFile -> {
                if (project == null) return false
                finderOpen = true
            }
            WorkspaceCommand.SelectTheme -> themeSelectorOpen = true
            WorkspaceCommand.FindInFile -> {
                if (files.active == null) return false
                searchBarOpen = true
            }
            WorkspaceCommand.OpenSettings -> {
                scope.launch {
                    settingsValid = withContext(Dispatchers.IO) { CoreBridge.settingsAreValid() }
                    settingsOpen = true
                }
            }
            WorkspaceCommand.ToggleTerminal -> {
                val root = project?.rootPath ?: return false
                if (terminals.isOpen) {
                    terminals.hide()
                    // Give the keyboard back to the workspace, or the next
                    // keystroke would go nowhere.
                    terminalFocused = false
                    rootFocus.requestFocus()
                } else {
                    terminals.open(root)
                }
            }
            WorkspaceCommand.NewTerminal -> {
                val root = project?.rootPath ?: return false
                terminals.newSession(root)
            }
            WorkspaceCommand.CloseTerminal -> {
                if (terminals.activeIndex < 0) return false
                terminals.closeSession(terminals.activeIndex)
                if (!terminals.isOpen) {
                    terminalFocused = false
                    rootFocus.requestFocus()
                }
            }
            WorkspaceCommand.NextTerminal -> terminals.selectRelative(1)
            WorkspaceCommand.PreviousTerminal -> terminals.selectRelative(-1)
        }
        return true
    }

    /** A project-search hit: open its file and put the caret on the match. */
    fun openMatch(path: String, match: ProjectSearchMatch) {
        val open = project ?: return
        openFile(open, path) { file -> file.editor.revealProjectSearchMatch(match) }
        if (searchTakesWorkArea) {
            // A compact screen gave the panel the whole work area, so opening a
            // file has to hand it back — and hand the keyboard back with it, or
            // the keymap dies the way it did when Stop-all closed the dock.
            projectSearchOpen = false
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    fun openProjectSearch(): Boolean {
        if (project == null) return false
        projectSearchOpen = true
        // The panel takes a compact screen away from a focused terminal, and
        // nothing else would tell the key table that the terminal is gone.
        terminalFocused = false
        projectSearchFocus++
        return true
    }

    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    // The dock can close without anyone here asking it to: the foreground
    // service's "Stop all" ends every session from the notification shade, and
    // the composable that held focus — a TerminalView — simply disappears.
    // Compose does not hand that focus anywhere, so the whole keymap goes dead:
    // measured on the Fold, neither Ctrl+` nor Ctrl+P did anything afterwards,
    // and only clicking the status bar's terminal button brought the app back.
    // Whenever the dock is not open, focus belongs to the workspace.
    LaunchedEffect(terminals.isOpen) {
        if (!terminals.isOpen) {
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val focus = if (terminalFocused) Focus.Terminal else Focus.Workspace
                // The palette is not a WorkspaceCommand: it would have to be
                // dispatched by the same `runCommand` it opens, and a command
                // that opens the list of commands is a knot for no gain.
                if (isCommandPalette(event, focus)) {
                    paletteOpen = true
                    return@onPreviewKeyEvent true
                }
                if (isProjectSearch(event)) return@onPreviewKeyEvent openProjectSearch()
                tabIndexFor(event, files.tabs.size, focus)?.let { index ->
                    files.select(index)
                    return@onPreviewKeyEvent true
                }
                workspaceCommandFor(event, focus)?.let { return@onPreviewKeyEvent runCommand(it) }
                false
            }
    ) {
        isWide = maxWidth >= WideLayoutMinWidth
        val windowWidth = maxWidth
        // The status bar spans the whole window, below the panel as well as
        // the editor — it reports on the workspace, not on the editor pane.
        val active = files.active
        val menuGroups = listOf(
            listOf(
                MenuAction("New project…", null) {
                    refreshProjects(); transferError = null; pickerOpen = true
                },
                MenuAction("Open project…", shortcutLabel(WorkspaceCommand.OpenProjects)) {
                    runCommand(WorkspaceCommand.OpenProjects)
                },
                MenuAction("Import folder…", null) { importLauncher.launch(null) },
            ),
            listOf(
                MenuAction("Search all files…", ProjectSearchChord.label, enabled = project != null) {
                    openProjectSearch()
                },
                MenuAction("Find file…", shortcutLabel(WorkspaceCommand.FindFile), enabled = project != null) {
                    runCommand(WorkspaceCommand.FindFile)
                },
                MenuAction("Save", shortcutLabel(WorkspaceCommand.Save), enabled = active != null) {
                    active?.let { save(it) }
                },
                MenuAction("Save all", null, enabled = files.tabs.any { it.isDirty }) {
                    for (tab in files.tabs) if (tab.isDirty) save(tab)
                },
                MenuAction("Close tab", shortcutLabel(WorkspaceCommand.CloseTab), enabled = active != null) {
                    runCommand(WorkspaceCommand.CloseTab)
                },
            ),
            listOf(
                // The palette's own route for anyone without a keyboard —
                // which on a phone is everyone, and it is the only way to
                // reach the commands this table cannot give a chord to.
                MenuAction("Command palette…", CommandPaletteChord.label) {
                    paletteOpen = true
                },
                MenuAction("Reveal in project panel", shortcutLabel(WorkspaceCommand.RevealInProjectPanel), enabled = active != null) {
                    runCommand(WorkspaceCommand.RevealInProjectPanel)
                },
                MenuAction("Toggle project panel", shortcutLabel(WorkspaceCommand.ToggleProjectPanel)) {
                    runCommand(WorkspaceCommand.ToggleProjectPanel)
                },
                MenuAction("Toggle terminal", shortcutLabel(WorkspaceCommand.ToggleTerminal), enabled = project != null) {
                    runCommand(WorkspaceCommand.ToggleTerminal)
                },
                MenuAction("New terminal", shortcutLabel(WorkspaceCommand.NewTerminal), enabled = project != null) {
                    runCommand(WorkspaceCommand.NewTerminal)
                },

                MenuAction("Theme…", shortcutLabel(WorkspaceCommand.SelectTheme)) {
                    runCommand(WorkspaceCommand.SelectTheme)
                },
                MenuAction("Settings…", shortcutLabel(WorkspaceCommand.OpenSettings)) {
                    runCommand(WorkspaceCommand.OpenSettings)
                },
            ) + userlandActions(context) { removeUserlandOpen = true },
        )

        Column(modifier = Modifier.fillMaxSize()) {
            TitleBar(
                projectName = project?.rootName,
                filePath = active?.path,
                isDirty = active?.isDirty == true,
                menuGroups = menuGroups,
            )
            DockDivider()
            // Compact screens have no room to split: the dock takes the whole
            // work area, as the settings screen and the drawer already do.
            // Two things want the whole of a compact work area. Search wins
            // while it is open — opening it is the more recent and more
            // deliberate act — and Escape gives the terminal straight back.
            //
            // "Compact" for search is not the same line as for the panel: a
            // 674dp foldable is wide enough for a sidebar *or* a search dock
            // and not for both, and squeezing the editor between them left it
            // one character wide. So search takes the whole area unless what
            // would be left for the editor is still a usable width.
            val editorWidthWithDock = windowWidth -
                (if (panelVisible) ProjectPanelWidth else 0.dp) - ProjectSearchDockWidth
            val searchIsFullScreen = projectSearchOpen && project != null &&
                (!isWide || editorWidthWithDock < MinEditorWidth)
            searchTakesWorkArea = searchIsFullScreen
            val dockIsFullScreen = !isWide && terminals.isOpen && !searchIsFullScreen
            Box(modifier = Modifier.weight(1f)) {
                if (searchIsFullScreen) {
                    ProjectSearchPanel(
                        project = project!!,
                        isDock = false,
                        focusToken = projectSearchFocus,
                        onOpenMatch = ::openMatch,
                        onDismiss = {
                            projectSearchOpen = false
                            rootFocus.requestFocus()
                        },
                    )
                } else if (dockIsFullScreen) {
                    TerminalDock(
                        state = terminals,
                        cwd = project?.rootPath,
                        fontSizeSp = settings.bufferFontSize,
                        onCommand = { runCommand(it) },
                        onFocusChanged = { focused -> terminalFocused = focused },
                    )
                } else if (isWide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (panelVisible) {
                            Box(
                                modifier = Modifier
                                    .width(ProjectPanelWidth)
                                    .fillMaxHeight()
                            ) {
                                ProjectPanel(
                                project = project,
                                onOpenFile = onOpenEntry,
                                openedPath = files.active?.path,
                                gitignoredFiles = settings.gitignoredFiles,
                                revealRequest = revealInPanel,
                                onRevealHandled = { revealInPanel = false },
                                onEntryRemoved = ::closeTabsUnder,
                                onEntryMoved = ::retitleTabs,
                            )
                            }
                            DockDivider(vertical = true)
                        }
                        EditorArea(
                            files = files,
                            dismissed = dismissedConflicts,
                            onSave = ::save,
                            onReload = ::reload,
                            onReopen = { runCommand(WorkspaceCommand.ReopenClosedTab) },
                            searchOpen = searchBarOpen,
                            onSearchDismissed = {
                                searchBarOpen = false
                                rootFocus.requestFocus()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        // The panel draws its own left edge, which is also the
                        // handle that drags it wider, so no divider here.
                        if (projectSearchOpen && project != null) {
                            ProjectSearchPanel(
                                project = project!!,
                                isDock = true,
                                focusToken = projectSearchFocus,
                                onOpenMatch = ::openMatch,
                                onDismiss = {
                                    projectSearchOpen = false
                                    rootFocus.requestFocus()
                                },
                            )
                        }
                    }
                } else {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet(drawerState) {
                                ProjectPanel(
                                    project = project,
                                    onOpenFile = { entry ->
                                        onOpenEntry(entry)
                                        scope.launch { drawerState.close() }
                                    },
                                    openedPath = files.active?.path,
                                    gitignoredFiles = settings.gitignoredFiles,
                                    revealRequest = revealInPanel,
                                    onRevealHandled = { revealInPanel = false },
                                    onEntryRemoved = ::closeTabsUnder,
                                    onEntryMoved = ::retitleTabs,
                                )
                            }
                        }
                    ) {
                        EditorArea(
                            files = files,
                            dismissed = dismissedConflicts,
                            onSave = ::save,
                            onReload = ::reload,
                            onReopen = { runCommand(WorkspaceCommand.ReopenClosedTab) },
                            searchOpen = searchBarOpen,
                            onSearchDismissed = {
                                searchBarOpen = false
                                rootFocus.requestFocus()
                            },
                        )
                    }
                }
            }
            if (terminals.isOpen && !dockIsFullScreen && !searchIsFullScreen) {
                // Drag handle. Wide screens are where a paired mouse lives, so
                // it gets a resize cursor as well as a touch target.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .pointerHoverIcon(PointerIcon.Crosshair)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, delta ->
                                dockHeight = (dockHeight - delta.toDp())
                                    .coerceAtLeast(TerminalDockMinHeight)
                            }
                        }
                ) {
                    DockDivider()
                }
                TerminalDock(
                    state = terminals,
                    cwd = project?.rootPath,
                    fontSizeSp = settings.bufferFontSize,
                    onCommand = { runCommand(it) },
                    onFocusChanged = { focused -> terminalFocused = focused },
                    modifier = Modifier.height(dockHeight),
                )
            }
            DockDivider()
            StatusBar(
                cursorRow = active?.editor?.cursorRow ?: 0,
                cursorCol = active?.editor?.cursorCol ?: 0,
                language = active?.language,
                hasFile = active != null,
                isPanelVisible = if (isWide) panelVisible else drawerState.isOpen,
                onToggleProjectPanel = { runCommand(WorkspaceCommand.ToggleProjectPanel) },
                onFindFile = if (project != null) {
                    { runCommand(WorkspaceCommand.FindFile) }
                } else {
                    null
                },
                isTerminalOpen = terminals.isOpen,
                onToggleTerminal = if (project != null) {
                    { runCommand(WorkspaceCommand.ToggleTerminal) }
                } else {
                    null
                },
            )
        }
    }

    if (settingsOpen) {
        SettingsScreen(
            settings = settings,
            settingsPath = settingsPath,
            isFileValid = settingsValid,
            onSet = { keyPath, valueJson ->
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AppSettings.set(keyPath, valueJson)
                    }
                    if (updated != null) {
                        onSettingsChanged(updated)
                        settingsValid = true
                    }
                }
            },
            onDismiss = { settingsOpen = false },
        )
    }

    val openedProject = project
    if (finderOpen && openedProject != null) {
        FileFinder(
            project = openedProject,
            onOpen = { match ->
                finderOpen = false
                openFile(openedProject, match.path)
            },
            onDismiss = { finderOpen = false },
        )
    }

    if (themeSelectorOpen) {
        ThemeSelector(
            mode = settings.theme,
            onSetMode = { mode ->
                onSettingsChanged(settings.copy(theme = mode))
                scope.launch(Dispatchers.IO) {
                    CoreBridge.setSetting(AppSettings.KEY_THEME, mode.key)
                }
            },
            onDismiss = {
                themeSelectorOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    if (paletteOpen) {
        CommandPalette(
            workspace = CommandContext(
                hasProject = project != null,
                hasActiveFile = files.active != null,
                tabCount = files.tabs.size,
                terminalCount = terminals.sessions.size,
                canClone = GitClone.isSupported,
            ),
            onRun = { runCommand(it) },
            onDismiss = {
                paletteOpen = false
                // Compose hands focus nowhere when an overlay leaves, and the
                // whole keymap goes with it — the same failure the terminal's
                // Stop-all once caused.
                rootFocus.requestFocus()
            },
            keyboardFocus = if (terminalFocused) Focus.Terminal else Focus.Workspace,
        )
    }

    if (removeUserlandOpen) {
        val name = Userland.backend.displayName
        AlertDialog(
            onDismissRequest = { removeUserlandOpen = false },
            title = { Text("Remove the $name userland?") },
            text = {
                Text(
                    "Everything installed with apt is deleted and the terminal " +
                        "goes back to Android's own shell. Your projects are not " +
                        "part of the userland and are left alone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removeUserlandOpen = false
                    scope.launch {
                        // Sessions are running inside it; they have to go first.
                        terminals.closeAll()
                        withContext(Dispatchers.IO) {
                            Userland.backend.remove(context)
                            // Or the engine keeps pointing git at a rootfs
                            // that is no longer there.
                            CoreBridge.clearUserland()
                        }
                        // And tell the installer, which is what the terminal's
                        // banner and the ☰ entry both read. Without this the
                        // dock goes on believing there is a userland — it only
                        // asks the disk when the pane is first composed — so
                        // the offer to install never came back and the shell
                        // sat at a prompt inside a rootfs that no longer
                        // existed.
                        UserlandInstaller.refresh(context)
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeUserlandOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (pickerOpen) {
        ProjectPicker(
            startInClone = pickerStartsInClone,
            onCloned = { path ->
                pickerOpen = false
                pickerStartsInClone = false
                refreshProjects()
                openProject(path)
            },
            projects = projects,
            currentPath = project?.let { session -> projects.firstOrNull { it.name == session.rootName }?.path },
            busyMessage = transferMessage,
            errorMessage = transferError,
            onOpen = { summary ->
                pickerOpen = false
                openProject(summary.path)
            },
            onCreate = { name ->
                scope.launch {
                    val created = withContext(Dispatchers.IO) { ProjectsRoot.create(context, name) }
                    if (created == null) {
                        transferError = "Could not create that project"
                    } else {
                        pickerOpen = false
                        openProject(created.absolutePath)
                    }
                }
            },
            onImport = { importLauncher.launch(null) },
            onExport = { summary ->
                exportTarget = summary
                exportLauncher.launch(null)
            },
            onDelete = { summary ->
                scope.launch {
                    val wasCurrent = project?.rootName == summary.name
                    withContext(Dispatchers.IO) { ProjectsRoot.delete(context, summary.name) }
                    refreshProjects()
                    if (wasCurrent) {
                        val next = withContext(Dispatchers.IO) { ProjectsRoot.defaultProject(context) }
                        openProject(next)
                    }
                }
            },
            onDismiss = { pickerOpen = false },
            nameError = { name -> ProjectsRoot.nameError(context, name) },
        )
    }
}

@Composable
private fun EditorArea(
    files: OpenFilesState,
    dismissed: androidx.compose.runtime.MutableState<Set<String>>,
    onSave: (OpenFile) -> Unit,
    onReload: (OpenFile) -> Unit,
    onReopen: () -> Unit,
    searchOpen: Boolean,
    onSearchDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = files.active
    Column(modifier = modifier.fillMaxSize()) {
        if (files.tabs.isNotEmpty()) {
            EditorTabs(files, onSave = onSave, onReopen = onReopen)
            DockDivider()
        }
        if (searchOpen && active != null) {
            BufferSearchBar(
                editor = active.editor,
                onDismiss = {
                    active.editor.clearSearchMatches()
                    onSearchDismissed()
                },
            )
            DockDivider()
        }

        // Only a dirty buffer (or a vanished file) needs the user's decision;
        // the clean case is reloaded by the status loop without a prompt.
        if (active != null &&
            (active.hasDiskChange || active.isDeleted) &&
            active.path !in dismissed.value
        ) {
            FileConflictBar(
                file = active,
                onReload = { onReload(active) },
                onSave = { onSave(active) },
                onDismiss = { dismissed.value = dismissed.value + active.path },
            )
            HorizontalDivider()
        }

        if (active == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Open a file from the project panel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            EditorPane(
                state = active.editor,
                modifier = Modifier.weight(1f),
                onSave = { onSave(active) },
            )
        }
    }
}

/**
 * The userland entry, or nothing at all in a build that has no userland —
 * an editor should not advertise a feature it cannot perform.
 *
 * The state comes from [UserlandInstaller] first, because that one is Compose
 * state: asking the backend directly reads the disk and tells the truth, but
 * tells it *once*, so the menu built before an install finished kept saying
 * there was nothing to remove until something unrelated happened to
 * recompose it. Measured on the emulator: install Debian, open the menu, and
 * the entry is missing.
 */
@Composable
private fun userlandActions(
    context: android.content.Context,
    onRemove: () -> Unit,
): List<MenuAction> {
    val installed = UserlandInstaller.state ?: Userland.backend.state(context)
    return if (installed is UserlandState.Ready) {
        listOf(
            MenuAction("Remove ${Userland.backend.displayName} userland…", null) { onRemove() }
        )
    } else {
        emptyList()
    }
}

/**
 * Point the engine at the Linux userland, so it can run git for project-panel
 * status. Blocking; call it off the main thread.
 *
 * Nothing to do in a build without a userland, or before Debian is installed —
 * git status then reads as "clean", which is the right way for a feature that
 * cannot run to look.
 */
internal fun syncUserlandWithEngine(context: Context) {
    if (Userland.backend.state(context) !is UserlandState.Ready) {
        CoreBridge.clearUserland()
        return
    }
    CoreBridge.setUserland(
        File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so").absolutePath,
        File(context.filesDir, "debian").absolutePath,
        context.cacheDir.absolutePath,
        File(context.filesDir, "projects").absolutePath,
    )
}
