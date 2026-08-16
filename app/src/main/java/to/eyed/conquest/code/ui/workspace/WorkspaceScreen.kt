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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import to.eyed.conquest.code.core.ProjectSummary
import to.eyed.conquest.code.core.ProjectsRoot
import to.eyed.conquest.code.core.SafTransfer
import java.io.File
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

    // Project picker state. `projects` is re-listed whenever the dialog opens
    // or a transfer finishes, rather than watched — projects change only when
    // the user changes them.
    var pickerOpen by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf(emptyList<ProjectSummary>()) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var transferError by remember { mutableStateOf<String?>(null) }

    fun refreshProjects() {
        scope.launch {
            projects = withContext(Dispatchers.IO) { ProjectsRoot.list(context) }
        }
    }

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

    /**
     * Switch the workspace to another project: close every tab and the old
     * worktree first, so the engine isn't left scanning a project nobody is
     * looking at.
     */
    fun openProject(path: String, startupFile: String? = null) {
        scope.launch {
            while (files.tabs.isNotEmpty()) files.close(files.tabs.lastIndex)
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
                files.close(files.activeIndex)
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
                pickerOpen = true
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
                projectName = project?.rootName,
                onSave = files.active?.let { file -> { save(file) } },
                onToggleProjectPanel = { runCommand(WorkspaceCommand.ToggleProjectPanel) },
                onOpenProjects = { runCommand(WorkspaceCommand.OpenProjects) },
            )
        }
    }

    if (pickerOpen) {
        ProjectPicker(
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
