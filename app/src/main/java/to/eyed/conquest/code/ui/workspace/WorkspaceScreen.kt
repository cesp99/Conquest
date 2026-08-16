package to.eyed.conquest.code.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.core.ProjectEntry
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.core.ProjectsRoot
import to.eyed.conquest.code.ui.editor.EditorPane
import to.eyed.conquest.code.ui.editor.EditorState

private val WideLayoutMinWidth = 840.dp
private val ProjectPanelWidth = 260.dp

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
 * Root of the IDE UI, in the spirit of Zed's workspace: a project panel, a tab
 * strip, an editor area and a status bar. Wide screens (tablets, unfolded
 * foldables) get a fixed sidebar; compact screens (phones, folded) get a
 * slimmer layout with the panel in a drawer.
 */
@Composable
fun WorkspaceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Resolving the project root can write to disk (it seeds the sample on a
    // fresh install), so it happens off the main thread and the UI starts with
    // no project — which is also the state P3-4's project picker will use.
    var project by remember { mutableStateOf<ProjectSession?>(null) }
    val files = remember { OpenFilesState() }
    val scope = rememberCoroutineScope()
    // Conflicts the user chose to live with, so the bar doesn't nag.
    val dismissedConflicts = remember { mutableStateOf(setOf<String>()) }

    fun openFile(project: ProjectSession, path: String) {
        val existing = files.indexOfPath(path)
        if (existing >= 0) {
            files.select(existing)
            return
        }
        scope.launch {
            val absolutePath = project.absolutePathOf(path) ?: return@launch
            val session = withContext(Dispatchers.IO) { BufferSession.openFile(absolutePath) }
                ?: return@launch
            files.open(OpenFile(path, EditorState(session)))
        }
    }

    LaunchedEffect(Unit) {
        val root = withContext(Dispatchers.IO) { ProjectsRoot.defaultProject(context) }
        val opened = ProjectSession(root)
        project = opened
        openFile(opened, STARTUP_FILE)
    }
    DisposableEffect(project) {
        val opened = project
        onDispose { opened?.close() }
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
                files.close(files.activeIndex)
            }
            WorkspaceCommand.NextTab -> files.selectRelative(1)
            WorkspaceCommand.PreviousTab -> files.selectRelative(-1)
            WorkspaceCommand.ToggleProjectPanel -> if (isWide) {
                panelVisible = !panelVisible
            } else {
                scope.launch { if (drawerState.isOpen) drawerState.close() else drawerState.open() }
            }
        }
        return true
    }

    // Workspace shortcuts are matched in a *preview* pass at the root, so they
    // work wherever focus sits — including while the editor holds it. Editor
    // chords are never matched here, so they still reach EditorPane.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                tabIndexFor(event, files.tabs.size)?.let { index ->
                    files.select(index)
                    return@onPreviewKeyEvent true
                }
                workspaceCommandFor(event)?.let { return@onPreviewKeyEvent runCommand(it) }
                false
            }
    ) {
        isWide = maxWidth >= WideLayoutMinWidth
        // The status bar spans the whole window, below the panel as well as
        // the editor — it reports on the workspace, not on the editor pane.
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (isWide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (panelVisible) {
                            Box(
                                modifier = Modifier
                                    .width(ProjectPanelWidth)
                                    .fillMaxHeight()
                            ) {
                                ProjectPanel(project, onOpenEntry, openedPath = files.active?.path)
                            }
                            VerticalDivider()
                        }
                        EditorArea(
                            files = files,
                            dismissed = dismissedConflicts,
                            onSave = ::save,
                            onReload = ::reload,
                            modifier = Modifier.weight(1f),
                        )
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
                                )
                            }
                        }
                    ) {
                        EditorArea(
                            files = files,
                            dismissed = dismissedConflicts,
                            onSave = ::save,
                            onReload = ::reload,
                        )
                    }
                }
            }
            HorizontalDivider()
            StatusBar(
                cursorRow = files.active?.editor?.cursorRow ?: 0,
                cursorCol = files.active?.editor?.cursorCol ?: 0,
                filePath = files.active?.path,
                isDirty = files.active?.isDirty == true,
                onSave = files.active?.let { file -> { save(file) } },
                onToggleProjectPanel = { runCommand(WorkspaceCommand.ToggleProjectPanel) },
            )
        }
    }
}

@Composable
private fun EditorArea(
    files: OpenFilesState,
    dismissed: androidx.compose.runtime.MutableState<Set<String>>,
    onSave: (OpenFile) -> Unit,
    onReload: (OpenFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = files.active
    Column(modifier = modifier.fillMaxSize()) {
        if (files.tabs.isNotEmpty()) {
            EditorTabs(files, onSave = onSave)
            HorizontalDivider()
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
