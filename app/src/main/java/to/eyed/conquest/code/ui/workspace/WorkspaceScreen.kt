package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.AppSettings
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.core.LanguageServerInstaller
import to.eyed.conquest.code.core.DockSide
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
import to.eyed.conquest.code.ui.theme.ThemeStore
import to.eyed.conquest.code.core.ProjectSearchMatch
import to.eyed.conquest.code.ui.search.BufferSearchBar
import to.eyed.conquest.code.ui.search.ProjectSearchPanel
import to.eyed.conquest.code.ui.search.revealProjectSearchMatch
import to.eyed.conquest.code.ui.editor.EditorPane
import to.eyed.conquest.code.ui.editor.DefinitionTarget
import to.eyed.conquest.code.ui.editor.EditorState
import to.eyed.conquest.code.ui.editor.revealDefinitionTarget
import to.eyed.conquest.code.ui.editor.SoftWrapMode
import to.eyed.conquest.code.ui.editor.LspServer
import to.eyed.conquest.code.ui.editor.rememberLspState
import to.eyed.conquest.code.ui.media.MediaKind
import to.eyed.conquest.code.ui.git.DiffPane
import to.eyed.conquest.code.ui.git.DiffTarget
import to.eyed.conquest.code.ui.git.GitGraphPane
import to.eyed.conquest.code.ui.git.GitPanel
import to.eyed.conquest.code.ui.git.GitPanelDockWidth
import to.eyed.conquest.code.ui.git.rememberGitBranch
import to.eyed.conquest.code.ui.media.MediaPane
import to.eyed.conquest.code.ui.preview.MarkdownPreview
import to.eyed.conquest.code.ui.preview.PreviewDockWidth
import to.eyed.conquest.code.ui.preview.PreviewKind
import to.eyed.conquest.code.ui.preview.SvgPreview
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
/**
 * The narrowest a dock can be dragged. Narrower than this and the panel's own
 * rows stop making sense — a file name in 120dp is an ellipsis.
 */
private val DockMinWidth = 200.dp

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
 * How long the caret rests before the breadcrumbs ask the engine for the
 * symbol path. Arrow-key travel and typing move the caret in bursts; a
 * per-move JNI query would be noise, and the answer only matters once the
 * eye has somewhere to settle.
 */
private const val BREADCRUMB_SETTLE_MS = 80L

/**
 * The engine's outline path — a JSON array of strings, outermost first.
 * Parsed defensively: a null (unknown buffer) or garbage answer is an empty
 * trail, never a crash. `getString`, not `optString`: Android's `org.json`
 * renders a JSON null as the string "null" through `optString`.
 */
private fun parseOutlinePath(json: String?): List<String> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val array = org.json.JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val label = array.getString(i)
                if (label.isNotBlank()) add(label)
            }
        }
    } catch (_: org.json.JSONException) {
        emptyList()
    }
}

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
    /** The tab bar's `+`, Ctrl+N, and the palette's `workspace: new file`. */
    var newFileOpen by remember { mutableStateOf(false) }
    /** Whether the project panel holds the keyboard — see [WorkspaceCommand.NewFile]. */
    var projectPanelFocused by remember { mutableStateOf(false) }
    /**
     * Whether the language-server prompt is on screen, and for which grammar
     * (null = show the list). The install itself lives in
     * [LanguageServerInstaller], so closing this does not stop apt.
     */
    var serverPromptOpen by remember { mutableStateOf(false) }
    var serverPromptGrammar by remember { mutableStateOf<String?>(null) }
    /** Why the last new-file create failed, if it did. */
    var newFileError by remember { mutableStateOf<String?>(null) }
    /** The picker opens straight into the clone form for Ctrl+Shift+G. */
    var pickerStartsInClone by remember { mutableStateOf(false) }
    var finderOpen by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(false) }
    /** Ctrl+Shift+E asked the panel to show the active file and take the keyboard. */
    var revealInPanel by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var themeSelectorOpen by remember { mutableStateOf(false) }
    var searchBarOpen by remember { mutableStateOf(false) }
    /**
     * What the right-hand dock is showing, if anything.
     *
     * One at a time, which is a dock's rule in Zed as well: several panels may
     * live in one, exactly one is active. It is also what stops three of them
     * sharing a 600dp screen and leaving the editor a character wide.
     *
     * What the preview previews is decided by whichever file is active, so
     * switching tabs switches it with them rather than leaving a rendered
     * README beside a Rust file.
     */
    val docks = remember { DockLayout() }
    /** Ctrl+G. A surface rather than a command: it answers for itself. */
    var goToLineOpen by remember { mutableStateOf(false) }
    var outlineOpen by remember { mutableStateOf(false) }
    /** Ctrl+Shift+F. The token is bumped to pull focus back to its query. */
    var projectSearchFocus by remember { mutableIntStateOf(0) }
    /** Ctrl+Shift+G, the same way: press it again to put focus back on the list. */
    var gitPanelFocus by remember { mutableIntStateOf(0) }
    /**
     * Whether the dock is drawn over the whole work area rather than beside
     * the editor. Decided during layout, where the width is known, and read by
     * whatever opens a file *from* a dock — which has to hand the area back
     * when it is true, and must not when the editor is right there next to it.
     */
    var dockTookWorkArea by remember { mutableStateOf<DockSide?>(null) }
    /**
     * Which docks the last layout actually drew.
     *
     * Open and drawn are not the same thing — a screen that cannot hold both
     * docks draws one and leaves the other waiting — and the buttons have to
     * know the difference, or the one for a waiting panel closes it invisibly.
     */
    var drawnDocks by remember { mutableStateOf(emptySet<DockSide>()) }
    var settingsValid by remember { mutableStateOf(true) }
    /** What the engine refused, if it refused the last write. */
    var settingsRefusal by remember { mutableStateOf<String?>(null) }
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
        /** Runs instead when the file could not be opened at all. */
        onFailed: (() -> Unit)? = null,
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
            val absolutePath = project.absolutePathOf(path) ?: run {
                onFailed?.invoke()
                return@launch
            }
            // A picture never reaches the engine: opening one as text would
            // put a megabyte of mojibake in a CRDT and set tree-sitter on it.
            val media = MediaKind.of(path.substringAfterLast('/'))
            if (media != null) {
                val opened = OpenFile(path, editor = null, media = media, absolutePath = absolutePath)
                files.open(opened)
                onOpened?.invoke(opened)
                return@launch
            }
            val session = withContext(Dispatchers.IO) { BufferSession.openFile(absolutePath) }
                ?: run {
                    onFailed?.invoke()
                    return@launch
                }
            val opened = OpenFile(path, EditorState(session), absolutePath = absolutePath)
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
            // A diff of a file that has just been deleted is a diff of
            // nothing; its key names the same path.
            val diffed = tab.diff?.path
            val matches = { candidate: String -> candidate == path || candidate.startsWith("$path/") }
            if (matches(tab.path) || (diffed != null && matches(diffed))) files.close(index)
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
        // A diff tab is about the old name; it is closed rather than moved,
        // since reopening it is one tap and a stale patch is a lie.
        for (index in files.tabs.indices.reversed()) {
            val diffed = files.tabs[index].diff?.path ?: continue
            if (diffed == from || diffed.startsWith("$from/")) files.close(index)
        }
        val moved = files.tabs.filter {
            it.editor != null && (it.path == from || it.path.startsWith("$from/"))
        }
        if (moved.isEmpty()) return
        val wasActive = files.active?.path
        val movedPaths = moved.map { it.path }
        scope.launch {
            for (tab in moved) {
                if (!tab.isDirty) continue
                val destination = open.absolutePathOf(to + tab.path.removePrefix(from))
                val id = tab.session?.id ?: continue
                val text = withContext(Dispatchers.IO) { CoreBridge.bufferText(id) }
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

    // The panel moves the caret of the buffer it was opened on; if that tab
    // closes underneath it there is nothing left for it to move.
    LaunchedEffect(files.active) {
        if (files.active?.editor == null) {
            goToLineOpen = false
            outlineOpen = false
        }
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
                    withContext(Dispatchers.IO) { tab.session?.reload() }
                    tab.refreshStatus()
                    dismissedConflicts.value -= tab.path
                }
            }
            delay(STATUS_POLL_MS)
        }
    }

    fun save(file: OpenFile) {
        val open = file.session ?: return
        scope.launch {
            withContext(Dispatchers.IO) { open.save() }
            file.refreshStatus()
            dismissedConflicts.value -= file.path
        }
    }

    fun reload(file: OpenFile) {
        val open = file.session ?: return
        scope.launch {
            withContext(Dispatchers.IO) { open.reload() }
            file.refreshStatus()
            dismissedConflicts.value -= file.path
        }
    }

    var isWide by remember { mutableStateOf(false) }
    // A wide screen opens with its sidebar up, which is what it did before
    // docks existed and what Zed does. A compact one does not: there, a dock
    // *is* the work area, and starting on the tree would hide the editor
    // behind it. Seeded once, so closing it stays closed.
    var docksSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(isWide, project) {
        if (!docksSeeded && isWide && project != null) {
            docks.open(WorkspacePanel.Project, settings)
            docksSeeded = true
        }
    }



    /** Whether the open tab is text the preview can draw. */
    fun canPreviewActiveFile(): Boolean {
        val open = files.active ?: return false
        return open.editor != null && PreviewKind.of(open.path) != null
    }

    /**
     * Show or hide a panel, and put the keyboard somewhere the key table can
     * see it.
     *
     * The focus half is not incidental: panels do not all take focus when they
     * appear — the preview deliberately does not, since it follows a file being
     * typed in — and a compact screen can take a terminal off the screen while
     * `terminalFocused` still says the shell has the keys. Returns whether the
     * panel is now open.
     */
    /** The commit graph, as a tab of its own — Zed opens it as a pane item. */
    fun openGraph() {
        if (project == null) return
        val key = "git-graph:"
        val existing = files.indexOfPath(key)
        if (existing >= 0) {
            files.select(existing)
        } else {
            files.open(OpenFile(path = key, editor = null, graph = true))
        }
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /** Whether [panel] is not just open but on screen. See [drawnDocks]. */
    fun panelIsDrawn(panel: WorkspacePanel): Boolean =
        docks.isOpen(panel, settings) && panel.sideIn(settings) in drawnDocks

    fun togglePanel(panel: WorkspacePanel): Boolean {
        // A panel that is open but *not drawn* — the loser when two docks will
        // not both fit — is raised rather than toggled. Its button says open
        // and the screen says otherwise; the press has to resolve that in
        // favour of showing it, not of closing something invisible.
        if (docks.isOpen(panel, settings) && !panelIsDrawn(panel)) {
            docks.raise(panel, settings)
            terminalFocused = false
            rootFocus.requestFocus()
            return true
        }
        val opened = docks.toggle(panel, settings)
        terminalFocused = false
        rootFocus.requestFocus()
        return opened
    }

    /** Zed's `workspace::ToggleLeftDock` / `ToggleRightDock`. */
    fun toggleDock(side: DockSide): Boolean {
        val showing = docks.active(side)
        if (showing != null) {
            docks.closeDock(side)
            rootFocus.requestFocus()
            return true
        }
        // Nothing in it: open the panel that lives on this side, preferring
        // the tree, which is what a person means by "show the sidebar".
        val panel = WorkspacePanel.entries.firstOrNull { it.sideIn(settings) == side }
            ?: return false
        // A panel with nothing to show would open onto its empty state and
        // take the screen with it.
        // A tree with no project is an empty panel over the work area with no
        // button to dismiss it.
        val usable = when (panel) {
            WorkspacePanel.Preview -> canPreviewActiveFile()
            else -> project != null
        }
        if (!usable) return false
        return togglePanel(panel)
    }


    /**
     * Zed's pane::GoBack / GoForward: [OpenFilesState.goBack] pops the entry
     * and activates its tab when it is still open; a closed file is reopened
     * through the same [openFile] the tabs' reopen uses. Either way the entry
     * restores its caret and scroll once the editor exists.
     */
    fun navigateHistory(back: Boolean): Boolean {
        val entry = (if (back) files.goBack() else files.goForward()) ?: return false
        val index = files.indexOfPath(entry.path)
        if (index >= 0) {
            // goBack already made the tab active, outside the history's ears.
            val tab = files.tabs[index]
            scope.launch { entry.restoreIn(tab) }
        } else {
            val open = project
            if (open == null) {
                files.navigationFailed(entry, wasBack = back)
                return false
            }
            // A file that will not open is not a move: put the entry back
            // rather than lighting the opposite arrow for travel that never
            // happened, and disarm the landing bracket.
            openFile(
                open,
                entry.path,
                onFailed = { files.navigationFailed(entry, wasBack = back) },
            ) { tab -> entry.restoreIn(tab) }
        }
        return true
    }

    fun runCommand(command: WorkspaceCommand): Boolean {
        val active = files.active
        when (command) {
            // A picture has no buffer, so there is nothing to save and the
            // chord is refused rather than silently doing nothing.
            WorkspaceCommand.Save -> {
                if (active?.session == null) return false
                save(active)
            }
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
                docks.open(WorkspacePanel.Project, settings)
                revealInPanel = true
            }
            WorkspaceCommand.NextTab -> files.selectRelative(1)
            WorkspaceCommand.PreviousTab -> files.selectRelative(-1)
            WorkspaceCommand.GoBack -> if (!navigateHistory(back = true)) return false
            WorkspaceCommand.GoForward -> if (!navigateHistory(back = false)) return false
            WorkspaceCommand.NewFile -> {
                if (project == null) return false
                // Zed binds ctrl-n to both `workspace::NewFile` and
                // `project_panel::NewFile` and lets the panel's more specific
                // context win while it has focus (default-linux.json:654,
                // 965). Refusing here does the same: the preview pass falls
                // through and the panel's own handler sees the chord.
                if (projectPanelFocused) return false
                newFileOpen = true
            }
            WorkspaceCommand.ToggleProjectPanel -> togglePanel(WorkspacePanel.Project)
            WorkspaceCommand.ToggleLeftDock -> if (!toggleDock(DockSide.Left)) return false
            WorkspaceCommand.ToggleRightDock -> if (!toggleDock(DockSide.Right)) return false
            WorkspaceCommand.OpenProjects -> {
                refreshProjects()
                transferError = null
                pickerStartsInClone = false
                pickerOpen = true
            }
            WorkspaceCommand.InstallLanguageServer -> {
                if (!LanguageServerInstaller.isSupported) return false
                // The open file names the language; with nothing open the
                // prompt shows the list rather than guessing one.
                serverPromptGrammar = files.active?.language
                serverPromptOpen = true
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
            WorkspaceCommand.TogglePreview -> {
                if (!canPreviewActiveFile()) return false
                togglePanel(WorkspacePanel.Preview)
            }
            // Zed's rem is `ui_font_size`, so these three resize the whole
            // chrome. They persist, unlike Zed's own (`persist: false`),
            // because a phone has no window to remember the size for: the
            // next launch is the same window.
            WorkspaceCommand.IncreaseUiFontSize ->
                ThemeStore.adjustUiFontSize(context, ThemeStore.FONT_SIZE_STEP)

            WorkspaceCommand.DecreaseUiFontSize ->
                ThemeStore.adjustUiFontSize(context, -ThemeStore.FONT_SIZE_STEP)

            WorkspaceCommand.ResetUiFontSize -> ThemeStore.resetUiFontSize(context)

            WorkspaceCommand.ToggleSoftWrap -> {
                val next = if (settings.softWrap.wraps) SoftWrapMode.None else SoftWrapMode.EditorWidth
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AppSettings.set(AppSettings.KEY_SOFT_WRAP, "\"" + next.key + "\"")
                    }
                    if (updated != null) onSettingsChanged(updated)
                }
            }
            WorkspaceCommand.OpenGitGraph -> {
                if (project == null) return false
                openGraph()
            }
            WorkspaceCommand.ToggleGitPanel -> {
                if (project == null) return false
                if (togglePanel(WorkspacePanel.Git)) gitPanelFocus++
            }
            WorkspaceCommand.FindInFile -> {
                if (files.active?.editor == null) return false
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
        openFile(open, path) { file -> file.editor?.revealProjectSearchMatch(match) }
        if (dockTookWorkArea != null) {
            // A compact screen gave the panel the whole work area, so opening a
            // file has to hand it back — and hand the keyboard back with it, or
            // the keymap dies the way it did when Stop-all closed the dock.
            dockTookWorkArea?.let(docks::closeDock)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * A path a dock asked for — a changed file in the git panel, a relative
     * link in the preview.
     *
     * On a compact screen the dock *is* the work area: the tab strip and the
     * editor are not composed at all, so opening a file behind it looks like
     * nothing happening. Hand the screen back, exactly as a search hit does.
     */
    fun openFromDock(path: String) {
        val open = project ?: return
        openFile(open, path)
        // The dock that *has* the work area, which is not always the one
        // opened most recently — a panel can be left holding the screen after
        // the newer one is dismissed.
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    // A tap in the tree is a dock opening a file, and on a compact screen the
    // dock *is* the work area — so it hands the area back, exactly as a search
    // hit does. Without this the file opened behind the tree and nothing
    // looked like it had happened.
    val onOpenEntry: (ProjectEntry) -> Unit = { entry -> openFromDock(entry.path) }

    /**
     * Show a diff — one file's, or the whole project's.
     *
     * A tab rather than a dock: a diff is a *document*, it is read left to
     * right and scrolled, and it belongs beside the file it is about. Keyed by
     * a path of its own so a diff and its file can be open at once.
     */
    fun openDiff(path: String?) {
        val open = project ?: return
        val target = DiffTarget(path)
        val key = "git-diff:${path ?: ""}"
        val existing = files.indexOfPath(key)
        if (existing >= 0) {
            files.select(existing)
        } else {
            files.open(OpenFile(path = key, editor = null, diff = target))
        }
        // The panel that asked may have been holding the whole screen.
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    fun openProjectSearch(): Boolean {
        if (project == null) return false
        docks.open(WorkspacePanel.Search, settings)
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
                if (isPreview(event, focus)) {
                    return@onPreviewKeyEvent runCommand(WorkspaceCommand.TogglePreview)
                }
                if (isGoToLine(event, focus)) {
                    if (files.active?.editor == null) return@onPreviewKeyEvent false
                    goToLineOpen = true
                    return@onPreviewKeyEvent true
                }
                if (isOutline(event, focus)) {
                    if (files.active?.editor == null) return@onPreviewKeyEvent false
                    outlineOpen = true
                    return@onPreviewKeyEvent true
                }
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
                MenuAction(
                    if (settings.softWrap.wraps) "Stop wrapping lines" else "Wrap long lines",
                    shortcutLabel(WorkspaceCommand.ToggleSoftWrap),
                ) {
                    runCommand(WorkspaceCommand.ToggleSoftWrap)
                },
                MenuAction("Git graph", null, enabled = project != null) {
                    runCommand(WorkspaceCommand.OpenGitGraph)
                },
                MenuAction(
                    "Git panel",
                    shortcutLabel(WorkspaceCommand.ToggleGitPanel),
                    enabled = project != null,
                ) {
                    runCommand(WorkspaceCommand.ToggleGitPanel)
                },
                MenuAction("Go to line…", GoToLineChord.label, enabled = active?.editor != null) {
                    goToLineOpen = true
                },
                MenuAction("Outline…", OutlineChord.label, enabled = active?.editor != null) {
                    outlineOpen = true
                },
                MenuAction(
                    "Toggle preview",
                    PreviewChord.label,
                    enabled = canPreviewActiveFile(),
                ) {
                    runCommand(WorkspaceCommand.TogglePreview)
                },
                MenuAction("Save", shortcutLabel(WorkspaceCommand.Save), enabled = active?.session != null) {
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
                MenuAction("Toggle left dock", shortcutLabel(WorkspaceCommand.ToggleLeftDock)) {
                    runCommand(WorkspaceCommand.ToggleLeftDock)
                },
                MenuAction("Toggle right dock", shortcutLabel(WorkspaceCommand.ToggleRightDock)) {
                    runCommand(WorkspaceCommand.ToggleRightDock)
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
                // A tab with no buffer — a diff, the graph, a picture — has a
                // key rather than a path, and a name of its own; that is what
                // belongs in the title bar.
                filePath = active?.let { if (it.editor == null) it.name else it.path },
                isDirty = active?.isDirty == true,
                menuGroups = menuGroups,
                branch = rememberGitBranch(project),
                onBranch = if (project != null) {
                    { runCommand(WorkspaceCommand.ToggleGitPanel) }
                } else {
                    null
                },
            )
            DockDivider()
            // Compact screens have no room to split: the dock takes the whole
            // work area, and the docks decide between themselves how much of
            // it each gets — see planDocks, which is where that argument lives
            // and where its cases are tested.
            //
            // A dock whose subject has gone shows nothing rather than holding
            // the screen: the preview needs a buffer to follow (a picture *is*
            // the preview), and search and git need a project.
            docks.reconcile(settings)
            fun panelHasSubject(panel: WorkspacePanel): Boolean = when (panel) {
                WorkspacePanel.Preview -> canPreviewActiveFile()
                WorkspacePanel.Search, WorkspacePanel.Git -> project != null
                WorkspacePanel.Project -> true
            }
            val plan = planDocks(
                window = windowWidth,
                leftWanted = docks.left?.takeIf(::panelHasSubject)
                    ?.let { docks.leftWidth ?: it.widthIn(settings) },
                rightWanted = docks.right?.takeIf(::panelHasSubject)
                    ?.let { docks.rightWidth ?: it.widthIn(settings) },
                lastOpened = docks.lastOpened,
                minEditor = MinEditorWidth,
                minDock = DockMinWidth,
                canSplit = isWide,
            )
            dockTookWorkArea = plan.fullScreen
            drawnDocks = DockSide.entries.filter { plan.draws(it) }.toSet()
            val terminalIsFullScreen = !isWide && terminals.isOpen && plan.fullScreen == null
            Box(modifier = Modifier.weight(1f)) {
                val fullScreen = plan.fullScreen
                if (fullScreen != null) {
                    DockPanel(
                        panel = docks.active(fullScreen)!!,
                        project = project,
                        file = active,
                        settings = settings,
                        searchFocus = projectSearchFocus,
                        gitFocus = gitPanelFocus,
                        revealRequest = revealInPanel,
                        onRevealHandled = { revealInPanel = false },
                        onOpenEntry = onOpenEntry,
                        onOpenMatch = ::openMatch,
                        onOpenPath = ::openFromDock,
                        onOpenDiff = ::openDiff,
                        onOpenGraph = ::openGraph,
                        onEntryRemoved = ::closeTabsUnder,
                        onEntryMoved = ::retitleTabs,
                        onPanelFocusChanged = { projectPanelFocused = it },
                        openedPath = files.active?.path,
                        onDismiss = {
                            docks.closeDock(fullScreen)
                            rootFocus.requestFocus()
                        },
                    )
                } else if (terminalIsFullScreen) {
                    TerminalDock(
                        state = terminals,
                        cwd = project?.rootPath,
                        fontSizeSp = settings.bufferFontSize,
                        onCommand = { runCommand(it) },
                        onFocusChanged = { focused -> terminalFocused = focused },
                    )
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        for (side in DockSide.entries) {
                            val panel = docks.active(side)?.takeIf { plan.draws(side) }
                            // The editor sits between the two, so it is drawn
                            // when the loop reaches the right-hand dock.
                            if (side == DockSide.Right) {
                                EditorArea(
                                    files = files,
                                    dismissed = dismissedConflicts,
                                    onSave = ::save,
                                    onReload = ::reload,
                                    onReopen = { runCommand(WorkspaceCommand.ReopenClosedTab) },
                                    onNavigateBack = { runCommand(WorkspaceCommand.GoBack) },
                                    onNavigateForward = { runCommand(WorkspaceCommand.GoForward) },
                                    onNewFile = if (project != null) {
                                        { runCommand(WorkspaceCommand.NewFile) }
                                    } else {
                                        null
                                    },
                                    searchOpen = searchBarOpen,
                                    onSearchDismissed = {
                                        searchBarOpen = false
                                        rootFocus.requestFocus()
                                    },
                                    onToggleSearch = { searchBarOpen = !searchBarOpen },
                                    onOpenOutline = { outlineOpen = true },
                                    isPreviewOpen = docks.isOpen(WorkspacePanel.Preview, settings),
                                    onTogglePreview = { runCommand(WorkspaceCommand.TogglePreview) },
                                    diffProject = project,
                                    onOpenPath = { path -> project?.let { openFile(it, path) } },
                                    softWrap = settings.softWrap,
                                    showInlineBlame = settings.inlineBlame,
                                    onOpenDefinition = { target ->
                                        val open = project
                                        val root = open?.rootPath
                                        val relative =
                                            if (root != null && target.path.startsWith("$root/")) {
                                                target.path.removePrefix("$root/")
                                            } else {
                                                null
                                            }
                                        // A target outside the project — a
                                        // header out of /usr/include — has no
                                        // project-relative name, and this
                                        // opener knows only those. Dropped
                                        // rather than opened at a path the
                                        // panel could never show.
                                        if (open != null && relative != null) {
                                            openFile(open, relative) { file ->
                                                file.editor?.revealDefinitionTarget(target)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (panel != null) {
                                Dock(
                                    side = side,
                                    width = plan.widthOf(side),
                                    // The drag moves the width the dock *asked
                                    // for*, not the one it was given: when two
                                    // docks are sharing a tight screen the plan
                                    // shrinks both, and resizing from the
                                    // shrunk figure would fight the planner and
                                    // barely move.
                                    onResize = { delta ->
                                        val current = docks.width(side)
                                            ?: panel.widthIn(settings)
                                        docks.setWidth(
                                            side,
                                            (current + delta).coerceIn(
                                                DockMinWidth,
                                                (windowWidth - MinEditorWidth)
                                                    .coerceAtLeast(DockMinWidth),
                                            ),
                                        )
                                    },
                                ) {
                                    DockPanel(
                                        panel = panel,
                                        project = project,
                                        file = active,
                                        settings = settings,
                                        searchFocus = projectSearchFocus,
                                        gitFocus = gitPanelFocus,
                                        revealRequest = revealInPanel,
                                        onRevealHandled = { revealInPanel = false },
                                        onOpenEntry = onOpenEntry,
                                        onOpenMatch = ::openMatch,
                                        onOpenPath = ::openFromDock,
                                        onOpenDiff = ::openDiff,
                                        onOpenGraph = ::openGraph,
                                        onEntryRemoved = ::closeTabsUnder,
                                        onEntryMoved = ::retitleTabs,
                        onPanelFocusChanged = { projectPanelFocused = it },
                                        openedPath = files.active?.path,
                                        onDismiss = {
                                            docks.closeDock(side)
                                            rootFocus.requestFocus()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // Over the editor, at the top, where Zed's own sits — and only
                // while the editor is the thing on screen: it moves the caret
                // as you type, which is pointless behind a full-screen panel.
                val goToLineEditor = active?.editor
                if (goToLineOpen && goToLineEditor != null &&
                    plan.fullScreen == null && !terminalIsFullScreen
                ) {
                    GoToLine(
                        editor = goToLineEditor,
                        onDismiss = {
                            goToLineOpen = false
                            rootFocus.requestFocus()
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                // Gated like go-to-line: previewing moves the caret, which is
                // pointless — and invisible — behind a full-screen panel.
                val outlineEditor = active?.editor
                if (outlineOpen && outlineEditor != null &&
                    plan.fullScreen == null && !terminalIsFullScreen
                ) {
                    OutlinePicker(
                        editor = outlineEditor,
                        onDismiss = {
                            outlineOpen = false
                            rootFocus.requestFocus()
                        },
                    )
                }
            }
            if (terminals.isOpen && !terminalIsFullScreen && plan.fullScreen == null) {
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
            // Each panel's button sits with its dock, so moving a panel in
            // settings moves the way you reach it.
            val panelButtons = WorkspacePanel.entries
                .filter { it != WorkspacePanel.Preview || canPreviewActiveFile() }
                .filter { it != WorkspacePanel.Project || project != null }
                .filter { it !in setOf(WorkspacePanel.Search, WorkspacePanel.Git) || project != null }
                .map { panel ->
                    PanelButton(
                        panel = panel,
                        isOpen = panelIsDrawn(panel),
                        onClick = {
                            when (panel) {
                                WorkspacePanel.Search -> if (docks.isOpen(panel, settings)) {
                                    togglePanel(panel)
                                } else {
                                    openProjectSearch()
                                }
                                WorkspacePanel.Git -> runCommand(WorkspaceCommand.ToggleGitPanel)
                                WorkspacePanel.Preview ->
                                    runCommand(WorkspaceCommand.TogglePreview)
                                WorkspacePanel.Project ->
                                    runCommand(WorkspaceCommand.ToggleProjectPanel)
                            }
                        },
                    )
                }
            // One counter for everything this project's servers have said.
            // Polling it is also what *starts* a server for a file that was
            // open before its project, or before `apt install` put the binary
            // in the userland — so it runs whenever a project is open, not
            // only when the status bar has something to show.
            val lsp = rememberLspState(project?.id)
            StatusBar(
                cursorRow = active?.editor?.cursorRow ?: 0,
                cursorCol = active?.editor?.cursorCol ?: 0,
                language = active?.language,
                hasFile = active?.editor != null,
                leftPanels = panelButtons.filter { it.panel.sideIn(settings) == DockSide.Left },
                rightPanels = panelButtons.filter { it.panel.sideIn(settings) == DockSide.Right },
                isTerminalOpen = terminals.isOpen,
                onToggleTerminal = if (project != null) {
                    { runCommand(WorkspaceCommand.ToggleTerminal) }
                } else {
                    null
                },
                // Zed's diagnostic summary and its LSP button, both of which
                // it registers as *left* items (zed.rs:640-641). Null with no
                // project: there is nothing to summarise, and Zed hides the
                // indicator in the same case.
                diagnostics = if (project != null) lsp.summary else null,
                servers = lsp.servers,
                cursorDiagnostic = active?.editor?.diagnosticAtCursor(),
                onGoToDiagnostic = active?.editor?.let { it::goToNextDiagnostic },
                onInstallServer = { server: LspServer ->
                    // The grammar the server is actually registered against
                    // beats the table's first one: clangd opened from a .cpp
                    // file should offer C++.
                    serverPromptGrammar =
                        server.languages.firstOrNull() ?: grammarForServer(server.name)
                    serverPromptOpen = true
                }.takeIf { LanguageServerInstaller.isSupported },
            )
        }
    }

    if (settingsOpen) {
        SettingsScreen(
            settings = settings,
            settingsPath = settingsPath,
            isFileValid = settingsValid,
            refusal = settingsRefusal,
            onSet = { keyPath, valueJson ->
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AppSettings.set(keyPath, valueJson)
                    }
                    if (updated != null) {
                        onSettingsChanged(updated)
                        settingsValid = true
                        settingsRefusal = null
                    } else {
                        // The engine refuses a write that would leave the file
                        // unparseable, and settings.json is untouched. Saying
                        // nothing here is what made a broken command — the
                        // soft-wrap toggle, which sent a malformed value —
                        // look like one that simply did nothing.
                        settingsRefusal = "$keyPath could not be set to $valueJson. " +
                            "settings.json is unchanged."
                    }
                }
            },
            onDismiss = {
                settingsOpen = false
                settingsRefusal = null
            },
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
                hasActiveBuffer = files.active?.editor != null,
                tabCount = files.tabs.size,
                terminalCount = terminals.sessions.size,
                canClone = GitClone.isSupported,
                canInstallLanguageServer = LanguageServerInstaller.isSupported,
                canPreview = canPreviewActiveFile(),
                canGoBack = files.canGoBack,
                canGoForward = files.canGoForward,
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

    if (serverPromptOpen) {
        LanguageServerPrompt(
            grammar = serverPromptGrammar,
            onDismiss = {
                serverPromptOpen = false
                // Compose hands focus nowhere when an overlay leaves, and the
                // whole keymap goes with it.
                rootFocus.requestFocus()
            },
        )
    }

    val newFileProject = project
    if (newFileOpen && newFileProject != null) {
        EntryNameDialog(
            title = "NEW FILE",
            confirmLabel = "Create",
            initial = "",
            selectionEnd = 0,
            placeholder = "Name, or a path like src/main.rs",
            // A trailing slash is how one says "directory", and this dialog
            // only makes files — refuse it rather than quietly making a file
            // with a slash-shaped name that then blocks the directory.
            errorFor = { name ->
                if (name.trimEnd().endsWith('/')) {
                    "This makes a file — drop the trailing slash"
                } else {
                    ProjectFiles.pathError(name, File(newFileProject.rootPath))
                }
            },
            onConfirm = { name ->
                newFileOpen = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ProjectFiles.create(File(newFileProject.rootPath), "", name, isDir = false)
                    }
                    when (result) {
                        is FileOpResult.Done -> openFile(newFileProject, result.path)
                        // The validation runs before the create and the disk
                        // can move underneath it; a create that fails has to
                        // say so rather than closing on nothing.
                        is FileOpResult.Failed -> newFileError = result.reason
                    }
                    rootFocus.requestFocus()
                }
            },
            onDismiss = {
                newFileOpen = false
                rootFocus.requestFocus()
            },
        )
    }
    newFileError?.let { message ->
        PanelErrorDialog(message = message) {
            newFileError = null
            rootFocus.requestFocus()
        }
    }
}

@Composable
private fun EditorArea(
    files: OpenFilesState,
    dismissed: androidx.compose.runtime.MutableState<Set<String>>,
    onSave: (OpenFile) -> Unit,
    onReload: (OpenFile) -> Unit,
    onReopen: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
    /** Null with no project; the `+` group then stays hidden. */
    onNewFile: (() -> Unit)?,
    searchOpen: Boolean,
    onSearchDismissed: () -> Unit,
    /** The toolbar magnifier — the touch twin of Ctrl+F. */
    onToggleSearch: () -> Unit,
    /** A tap on the breadcrumbs — Zed's own button into the outline. */
    onOpenOutline: () -> Unit,
    isPreviewOpen: Boolean,
    onTogglePreview: () -> Unit,
    /** For a diff tab, which needs the project rather than a buffer. */
    diffProject: ProjectSession?,
    onOpenPath: (String) -> Unit,
    softWrap: SoftWrapMode,
    showInlineBlame: Boolean,
    /**
     * A definition in another file. This pane has one buffer and no way to
     * make a second, so the workspace opens the file and then puts the caret
     * on the target — the same shape as a project-search hit.
     */
    onOpenDefinition: (DefinitionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = files.active
    Column(modifier = modifier.fillMaxSize()) {
        if (files.tabs.isNotEmpty()) {
            EditorTabs(
                files,
                onSave = onSave,
                onReopen = onReopen,
                onNavigateBack = onNavigateBack,
                onNavigateForward = onNavigateForward,
                onNewFile = onNewFile,
            )
            DockDivider()
        }
        // Zed's toolbar: breadcrumbs on the left — the file name, then the
        // engine's symbol path at the caret — and the quick action bar on the
        // right. Shown for every text buffer, as Zed shows it; a picture is
        // not previewable — it *is* the preview — so a media tab never gets
        // one.
        val activeEditor = active?.editor
        if (active != null && activeEditor != null) {
            val previewKind = PreviewKind.of(active.path)
            var symbolPath by remember(active.path) {
                mutableStateOf<List<String>>(emptyList())
            }
            // Re-asked when the caret settles or a reparse lands — all
            // observable state, and the JNI read runs off the main thread.
            LaunchedEffect(
                activeEditor,
                activeEditor.cursorRow,
                activeEditor.cursorCol,
                activeEditor.highlightVersion,
            ) {
                delay(BREADCRUMB_SETTLE_MS)
                val id = activeEditor.session.id
                val row = activeEditor.cursorRow.toLong()
                val col = activeEditor.cursorCol.toLong()
                symbolPath = withContext(Dispatchers.Default) {
                    parseOutlinePath(CoreBridge.bufferOutlinePath(id, row, col))
                }
            }
            EditorToolbar(
                fileName = active.name,
                symbolPath = symbolPath,
                onToggleSearch = onToggleSearch,
                onOpenOutline = onOpenOutline,
                kind = previewKind,
                isPreviewOpen = isPreviewOpen,
                onTogglePreview = if (previewKind != null) onTogglePreview else null,
            )
            DockDivider()
        }
        // Find-in-file is a text question; a picture has nothing to search.
        if (searchOpen && activeEditor != null) {
            BufferSearchBar(
                editor = activeEditor,
                onDismiss = {
                    activeEditor.clearSearchMatches()
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
            // An empty pane is empty. Zed's placeholder for a workspace with a
            // project open renders no hint text at all — and no fill of its
            // own, so what shows is the workspace body's `background`
            // (workspace/src/pane.rs:4550-4566, workspace.rs:9111); the
            // welcome hints belong to the no-project page, which for us is
            // the project picker.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(LocalZedTheme.current.color("background")),
            )
        } else if (activeEditor != null) {
            EditorPane(
                state = activeEditor,
                modifier = Modifier.weight(1f),
                onSave = { onSave(active) },
                softWrap = softWrap,
                showInlineBlame = showInlineBlame,
                onOpenDefinition = onOpenDefinition,
            )
        } else if (active.graph && diffProject != null) {
            GitGraphPane(
                project = diffProject,
                onOpenFile = onOpenPath,
                modifier = Modifier.weight(1f),
            )
        } else if (active.diff != null && diffProject != null) {
            DiffPane(
                project = diffProject,
                target = active.diff,
                onOpenFile = onOpenPath,
                modifier = Modifier.weight(1f),
            )
        } else {
            MediaPane(
                absolutePath = active.absolutePath.orEmpty(),
                kind = active.media ?: MediaKind.Image,
                modifier = Modifier.weight(1f),
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
/**
 * A dock: the panel in it, and the edge that resizes it.
 *
 * The handle is on the side facing the editor — the *inner* edge — because
 * that is the edge that moves, and it is the same 6dp grip the terminal dock
 * has had all along. A drag on the left dock's edge widens it; the same drag
 * on the right dock's edge narrows it, which is why the sign follows the side.
 */
@Composable
private fun Dock(
    side: DockSide,
    width: Dp,
    onResize: (Dp) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.width(width).fillMaxHeight()) {
        if (side == DockSide.Right) DockHandle(side, onResize)
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
        if (side == DockSide.Left) DockHandle(side, onResize)
    }
}

/** The grip, and the border it sits on. */
@Composable
private fun DockHandle(side: DockSide, onResize: (Dp) -> Unit) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(side) {
                detectHorizontalDragGestures { _, delta ->
                    val moved = with(density) { delta.toDp() }
                    onResize(if (side == DockSide.Left) moved else -moved)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        DockDivider(vertical = true)
    }
}

/** Whichever panel the dock is showing. */
@Composable
private fun DockPanel(
    panel: WorkspacePanel,
    project: ProjectSession?,
    file: OpenFile?,
    settings: AppSettings,
    searchFocus: Int,
    gitFocus: Int,
    revealRequest: Boolean,
    openedPath: String?,
    onRevealHandled: () -> Unit,
    onOpenEntry: (ProjectEntry) -> Unit,
    onOpenMatch: (String, ProjectSearchMatch) -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenDiff: (String?) -> Unit,
    onOpenGraph: () -> Unit,
    onEntryRemoved: (String) -> Unit,
    onEntryMoved: (String, String) -> Unit,
    /** The project panel reporting whether it holds the keyboard. */
    onPanelFocusChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (panel) {
        WorkspacePanel.Project -> ProjectPanel(
            project = project,
            onOpenFile = onOpenEntry,
            openedPath = openedPath,
            gitignoredFiles = settings.gitignoredFiles,
            revealRequest = revealRequest,
            onRevealHandled = onRevealHandled,
            onFocusChanged = onPanelFocusChanged,
            onEntryRemoved = onEntryRemoved,
            onEntryMoved = onEntryMoved,
        )
        WorkspacePanel.Search -> ProjectSearchPanel(
            project = project ?: return,
            focusToken = searchFocus,
            onOpenMatch = onOpenMatch,
            onDismiss = onDismiss,
        )
        WorkspacePanel.Preview -> PreviewPanel(
            editor = file?.editor ?: return,
            path = file.path,
            onDismiss = onDismiss,
            onOpenPath = onOpenPath,
        )
        WorkspacePanel.Git -> GitPanel(
            project = project ?: return,
            focusToken = gitFocus,
            onOpenFile = onOpenPath,
            onOpenDiff = onOpenDiff,
            onOpenGraph = onOpenGraph,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Whichever preview the open file has — Zed shows one button and one panel,
 * and which of the two it is follows the file rather than a second command.
 *
 * A file with no preview keeps the panel and gets its empty state, rather than
 * having the panel vanish under it: switching to a `.rs` for one lookup and
 * back should not cost the reader their preview.
 */
@Composable
private fun PreviewPanel(
    editor: EditorState,
    path: String,
    onDismiss: () -> Unit,
    onOpenPath: (String) -> Unit,
) {
    when (PreviewKind.of(path)) {
        PreviewKind.Svg -> SvgPreview(
            editor = editor,
            path = path,
            onDismiss = onDismiss,
        )
        else -> MarkdownPreview(
            editor = editor,
            path = path,
            onDismiss = onDismiss,
            onOpenPath = onOpenPath,
        )
    }
}

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
