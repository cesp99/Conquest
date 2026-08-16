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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.ProjectEntry
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.core.ProjectsRoot
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.ui.editor.EditorPane
import to.eyed.conquest.code.ui.editor.EditorState

private val WideLayoutMinWidth = 840.dp
private val ProjectPanelWidth = 260.dp

/** The file opened on a fresh install, relative to the sample project root. */
private const val STARTUP_FILE = "src/main.rs"

/**
 * Root of the IDE UI, in the spirit of Zed's workspace: a project panel,
 * an editor area and a status bar. Wide screens (tablets, unfolded
 * foldables) get a fixed sidebar; compact screens (phones, folded) get a
 * slimmer layout with the panel in a drawer.
 *
 * The project is a real engine worktree and the editor buffer is a real file
 * read from disk, so both panes talk to the same Rust core.
 */
@Composable
fun WorkspaceScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Resolving the project root can write to disk (it seeds the sample on a
    // fresh install), so it happens off the main thread and the UI starts with
    // no project — which is also the state P3-4's project picker will use.
    var project by remember { mutableStateOf<ProjectSession?>(null) }
    var editorState by remember { mutableStateOf<EditorState?>(null) }
    var openedPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /** Swap the editor to a project file, closing the buffer it replaces. */
    fun openFile(project: ProjectSession, path: String) {
        scope.launch {
            val absolutePath = project.absolutePathOf(path) ?: return@launch
            val session = withContext(Dispatchers.IO) { BufferSession.openFile(absolutePath) }
                ?: return@launch
            editorState?.session?.close()
            editorState = EditorState(session)
            openedPath = path
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

    val onOpenEntry: (ProjectEntry) -> Unit = { entry ->
        project?.let { openFile(it, entry.path) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= WideLayoutMinWidth) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(ProjectPanelWidth)
                        .fillMaxHeight()
                ) {
                    ProjectPanel(project, onOpenEntry, openedPath = openedPath)
                }
                VerticalDivider()
                EditorArea(editorState, openedPath, modifier = Modifier.weight(1f))
            }
        } else {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
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
                            openedPath = openedPath,
                        )
                    }
                }
            ) {
                EditorArea(
                    editorState,
                    openedPath,
                    onOpenProjectPanel = { scope.launch { drawerState.open() } },
                )
            }
        }
    }
}

@Composable
private fun EditorArea(
    editorState: EditorState?,
    openedPath: String?,
    modifier: Modifier = Modifier,
    onOpenProjectPanel: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (editorState == null) {
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
                state = editorState,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider()
        StatusBar(
            cursorRow = editorState?.cursorRow ?: 0,
            cursorCol = editorState?.cursorCol ?: 0,
            filePath = openedPath,
            onOpenProjectPanel = onOpenProjectPanel,
        )
    }
}
