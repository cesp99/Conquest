package to.eyed.conquest.code.ui.workspace

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.GitignoredFiles
import to.eyed.conquest.code.core.ProjectEntry
import to.eyed.conquest.code.core.GitFileStatus as EngineStatus
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import java.io.File

private val RowHeightPadding = 5.dp
private val IndentPerLevel = 14.dp

/** How often to check the engine for a newer worktree snapshot. */
private const val SCANNING_POLL_MS = 120L
private const val IDLE_POLL_MS = 1_000L

/**
 * How long the panel keeps polling quickly after a file operation.
 *
 * The worktree's own watcher is what makes a new file appear — this only
 * shortens the wait for a change we know is on its way, because a second is a
 * long time to look at a file you just created and not see it. If the watcher
 * never delivers, nothing here invents the row.
 */
private const val EXPECT_CHANGE_MS = 3_000L

/**
 * Where the panel gets per-path git status: the engine, which runs Debian's
 * git through proot behind a version counter of the same shape as the worktree
 * snapshot's. Both are cheap reads of a cache; neither ever waits on git.
 *
 * In a build with no Linux userland the counter stays 0 and the table is
 * empty, so the tree looks exactly as it always has.
 */
fun gitStatusSourceFor(project: ProjectSession): GitStatusSource = EngineGitStatusSource(project)

private class EngineGitStatusSource(private val project: ProjectSession) : GitStatusSource {

    override val version: Long get() = project.gitStatusVersion

    override fun snapshot(): GitStatusSnapshot {
        // Read the version first: a bump between here and the table means the
        // next poll picks the change up, rather than this one recording a new
        // version against older rows.
        val version = project.gitStatusVersion
        val engine = project.gitStatus()
        if (engine.isEmpty()) return GitStatusSnapshot.of(version, emptyMap())
        val byPath = HashMap<String, GitFileStatus>(engine.size)
        for ((path, status) in engine) byPath[path] = status.forPanel()
        return GitStatusSnapshot.of(version, byPath)
    }
}

/** The engine's vocabulary, in the panel's. */
private fun EngineStatus.forPanel(): GitFileStatus = when (this) {
    EngineStatus.Modified -> GitFileStatus.Modified
    EngineStatus.Added -> GitFileStatus.Added
    EngineStatus.Deleted -> GitFileStatus.Deleted
    EngineStatus.Renamed -> GitFileStatus.Renamed
    EngineStatus.Conflicted -> GitFileStatus.Conflicted
    EngineStatus.Untracked -> GitFileStatus.Untracked
    EngineStatus.Ignored -> GitFileStatus.Ignored
}

/** What the panel is asking the user before it touches the disk. */
private sealed interface PanelPrompt {
    data class NewEntry(val parent: String, val isDir: Boolean) : PanelPrompt
    data class Rename(val entry: ProjectEntry) : PanelPrompt
    data class Delete(val entry: ProjectEntry) : PanelPrompt

    /** An operation that didn't happen, in the words it failed with. */
    data class Failure(val message: String) : PanelPrompt
}

/**
 * An entry waiting to be pasted.
 *
 * The panel's own clipboard, not the system one: cutting a directory and
 * pasting it elsewhere is a move within this project, and putting a path on
 * the system clipboard would mean something else entirely to every other app.
 * "Copy Path" is what talks to the system clipboard.
 */
private data class PanelClipboard(val path: String, val isCut: Boolean)

/** An open context menu: which row it belongs to, and where it was asked for. */
private data class PanelMenu(
    /** Null for the panel header — the menu for the project root. */
    val entry: ProjectEntry?,
    val at: Offset,
)

/**
 * The project tree, rendered from the engine's worktree, and the file manager
 * that goes with it.
 *
 * Directories are read one level at a time and only while expanded, so the
 * panel never walks the whole project. The engine scans asynchronously, so
 * this polls its snapshot version — fast while the initial scan is running,
 * lazily afterwards, where it doubles as external-change detection. Git status
 * rides the same poll: it is a second cheap version counter, and the rows are
 * re-coloured in place when it moves.
 *
 * File operations (see [ProjectFiles]) write to disk and stop; the rows that
 * follow come back through the watcher, which is what also makes changes from
 * the terminal appear. Everything is reachable three ways — pointer, touch and
 * keyboard — because on DeX there is no touchscreen and on a phone there is no
 * right mouse button.
 */
@Composable
fun ProjectPanel(
    project: ProjectSession?,
    onOpenFile: (ProjectEntry) -> Unit,
    modifier: Modifier = Modifier,
    openedPath: String? = null,
    gitignoredFiles: GitignoredFiles = GitignoredFiles.Dimmed,
    gitStatus: GitStatusSource? = null,
    /**
     * The workspace asking for [openedPath] to be shown and the keyboard moved
     * into the panel — Zed's `pane::RevealInProjectPanel`.
     *
     * A flag the panel clears through [onRevealHandled] rather than a counter,
     * so a request made while the panel was hidden is still waiting when it
     * appears, and a request already served doesn't fire again every time the
     * layout rebuilds the panel.
     */
    revealRequest: Boolean = false,
    /** Called once [revealRequest] has been acted on. */
    onRevealHandled: () -> Unit = {},
    /** A path that has stopped existing, so its tab can be closed. */
    onEntryRemoved: (String) -> Unit = {},
    /** A path that moved: renamed, or cut and pasted somewhere else. */
    onEntryMoved: (from: String, to: String) -> Unit = { _, _ -> },
) {
    val theme = LocalZedTheme.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    var menu by remember(project) { mutableStateOf<PanelMenu?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("panel.background"))
    ) {
        if (project == null) {
            PanelHeading(name = null, menu = null)
            PanelMessage("No project open")
            return@Column
        }

        val statusSource = remember(project, gitStatus) {
            gitStatus ?: gitStatusSourceFor(project)
        }
        val tree = remember(project, gitignoredFiles, statusSource) {
            ProjectTreeState(project, gitignoredFiles, statusSource)
        }
        // Resolved once per theme, never per row: `ZedTheme.color` is a map
        // read, and this panel draws one row per visible line per frame.
        val onSurface = MaterialTheme.colorScheme.onSurface
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
        val colours = remember(theme, onSurface, onSurfaceVariant) {
            GitStatusColours.from(theme, onSurface, onSurfaceVariant)
        }
        val icons = remember(theme, onSurfaceVariant) {
            EntryIconColours.from(theme, onSurfaceVariant)
        }
        val rowColours = remember(theme, onSurfaceVariant) {
            RowColours(
                open = theme.color("element.selected"),
                hover = theme.color("element.hover", Color.Transparent),
                selection = theme.color("border.focused", onSurfaceVariant),
            )
        }
        val dimIgnored = gitignoredFiles == GitignoredFiles.Dimmed
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        val panelFocus = remember { FocusRequester() }
        val root = remember(project) { File(project.rootPath) }
        var prompt by remember(project) { mutableStateOf<PanelPrompt?>(null) }
        var pending by remember(project) { mutableStateOf<PanelClipboard?>(null) }
        var expectChangeUntil by remember(project) { mutableLongStateOf(0L) }

        // Re-flatten after a change to the tree's shape. The rebuild reads
        // through JNI and parses JSON, so it stays off the main thread, and it
        // is published against the shape it was computed from: if the user
        // expanded something else meanwhile, that toggle's own rebuild is the
        // one that describes the tree now.
        fun reshape(change: () -> Unit) {
            change()
            val shape = tree.shape
            scope.launch {
                val rebuilt = withContext(Dispatchers.Default) { tree.rebuild() }
                tree.publish(tree.version, rebuilt, shape)
            }
        }

        /** Run a file operation off the main thread and report what it did. */
        fun operate(onDone: (String) -> Unit = {}, op: () -> FileOpResult) {
            scope.launch {
                when (val result = withContext(Dispatchers.IO) { op() }) {
                    is FileOpResult.Failed -> prompt = PanelPrompt.Failure(result.reason)
                    is FileOpResult.Done -> {
                        expectChangeUntil = SystemClock.uptimeMillis() + EXPECT_CHANGE_MS
                        onDone(result.path)
                    }
                }
            }
        }

        /** The directory an action on [entry] applies to; the root for null. */
        fun directoryFor(entry: ProjectEntry?): String = when {
            entry == null -> ""
            entry.isDir -> entry.path
            else -> ProjectFiles.parentOf(entry.path)
        }

        fun activate(entry: ProjectEntry) {
            tree.select(entry.path)
            if (entry.isDir) reshape { tree.toggle(entry) } else onOpenFile(entry)
        }

        fun createIn(entry: ProjectEntry?, isDir: Boolean) {
            prompt = PanelPrompt.NewEntry(directoryFor(entry), isDir)
        }

        fun duplicate(entry: ProjectEntry) {
            operate(onDone = { path -> reshape { tree.reveal(path) } }) {
                ProjectFiles.duplicate(root, entry.path)
            }
        }

        fun paste(entry: ProjectEntry?) {
            val source = pending ?: return
            val destination = directoryFor(entry)
            operate(
                onDone = { path ->
                    if (source.isCut) {
                        onEntryMoved(source.path, path)
                        pending = null
                    }
                    reshape { tree.reveal(path) }
                }
            ) {
                if (source.isCut) {
                    ProjectFiles.moveInto(root, source.path, destination)
                } else {
                    ProjectFiles.copyInto(root, source.path, destination)
                }
            }
        }

        fun confirmDelete(entry: ProjectEntry) {
            prompt = PanelPrompt.Delete(entry)
        }

        fun deleteNow(entry: ProjectEntry) {
            // Where the selection lands afterwards: the next row that isn't
            // about to vanish with this one, else the row above.
            val index = tree.rows.indexOfFirst { it.entry.path == entry.path }
            val below = tree.rows
                .drop(index + 1)
                .firstOrNull { !it.entry.path.startsWith("${entry.path}/") }
            val neighbour = below?.entry?.path ?: tree.rows.getOrNull(index - 1)?.entry?.path
            operate(
                onDone = { path ->
                    onEntryRemoved(path)
                    tree.select(neighbour)
                    if (pending?.path == path) pending = null
                }
            ) {
                ProjectFiles.delete(root, entry.path)
            }
        }

        fun copyPath(entry: ProjectEntry, relative: Boolean) {
            val text = if (relative) {
                entry.path
            } else {
                project.absolutePathOf(entry.path) ?: entry.path
            }
            clipboard.setText(AnnotatedString(text))
        }

        fun expandAll() {
            scope.launch {
                val directories = withContext(Dispatchers.Default) { tree.expandableDirectories() }
                reshape { tree.expandAll(directories) }
            }
        }

        fun reveal(path: String) {
            reshape { tree.reveal(path) }
        }

        fun menuFor(entry: ProjectEntry?): List<PanelMenuEntry> = buildList {
            add(PanelMenuEntry.Action("New File…", "Ctrl N") { createIn(entry, isDir = false) })
            add(
                PanelMenuEntry.Action("New Folder…", "Ctrl Shift N") {
                    createIn(entry, isDir = true)
                }
            )
            add(PanelMenuEntry.Separator)
            if (entry != null && !entry.isDir) {
                add(PanelMenuEntry.Action("Open", "Enter") { activate(entry) })
            }
            if (entry != null) {
                add(PanelMenuEntry.Action("Cut", "Ctrl X") {
                    pending = PanelClipboard(entry.path, isCut = true)
                })
                add(PanelMenuEntry.Action("Copy", "Ctrl C") {
                    pending = PanelClipboard(entry.path, isCut = false)
                })
                add(PanelMenuEntry.Action("Duplicate", "Ctrl D") { duplicate(entry) })
            }
            add(
                PanelMenuEntry.Action("Paste", "Ctrl V", enabled = pending != null) {
                    paste(entry)
                }
            )
            if (entry != null) {
                add(PanelMenuEntry.Separator)
                add(PanelMenuEntry.Action("Copy Path", "Ctrl Alt C") {
                    copyPath(entry, relative = false)
                })
                add(PanelMenuEntry.Action("Copy Relative Path") {
                    copyPath(entry, relative = true)
                })
                add(PanelMenuEntry.Separator)
                add(PanelMenuEntry.Action("Rename…", "F2") {
                    prompt = PanelPrompt.Rename(entry)
                })
                add(PanelMenuEntry.Action("Delete…", "Del") { confirmDelete(entry) })
            }
            add(PanelMenuEntry.Separator)
            add(
                PanelMenuEntry.Action(
                    "Reveal Active File",
                    enabled = openedPath != null,
                ) { openedPath?.let(::reveal) }
            )
            add(PanelMenuEntry.Action("Expand All", "Ctrl →") { expandAll() })
            add(PanelMenuEntry.Action("Collapse All", "Ctrl ←") { reshape { tree.collapseAll() } })
        }

        /**
         * The panel's own keyboard. Matched in a preview pass on the panel, so
         * it only fires while focus is in here — the workspace table upstream
         * has already had its say, and the editor's chords live in the editor.
         */
        fun handleKey(event: KeyEvent): Boolean {
            if (event.type != KeyEventType.KeyDown) return false
            val entry = tree.selectedRow?.entry
            if (event.isCtrlPressed) {
                if (event.isAltPressed) {
                    return when (event.key) {
                        Key.C -> {
                            copyPath(entry ?: return false, relative = event.isShiftPressed)
                            true
                        }
                        // Zed's own binding for a new directory, kept next to
                        // the Ctrl Shift N every file manager uses.
                        Key.N -> {
                            createIn(entry, isDir = true)
                            true
                        }
                        else -> false
                    }
                }
                return when (event.key) {
                    Key.N -> {
                        createIn(entry, isDir = event.isShiftPressed)
                        true
                    }
                    Key.X -> {
                        pending = PanelClipboard((entry ?: return false).path, isCut = true)
                        true
                    }
                    Key.C -> {
                        pending = PanelClipboard((entry ?: return false).path, isCut = false)
                        true
                    }
                    Key.V -> {
                        paste(entry)
                        true
                    }
                    Key.D -> {
                        duplicate(entry ?: return false)
                        true
                    }
                    Key.DirectionLeft -> {
                        reshape { tree.collapseAll() }
                        true
                    }
                    Key.DirectionRight -> {
                        expandAll()
                        true
                    }
                    else -> false
                }
            }
            return when (event.key) {
                Key.DirectionUp -> {
                    tree.moveSelection(-1)
                    true
                }
                Key.DirectionDown -> {
                    tree.moveSelection(1)
                    true
                }
                Key.DirectionLeft -> {
                    // Collapse, or step out to the directory this row is in —
                    // the same left-arrow every tree has.
                    when {
                        entry == null -> tree.moveSelection(-1)
                        entry.isDir && tree.isExpanded(entry.path) ->
                            reshape { tree.collapse(entry.path) }
                        entry.path.contains('/') -> tree.select(ProjectFiles.parentOf(entry.path))
                        else -> return false
                    }
                    true
                }
                Key.DirectionRight -> {
                    when {
                        entry == null -> tree.moveSelection(1)
                        entry.isDir && !tree.isExpanded(entry.path) -> reshape { tree.expand(entry) }
                        entry.isDir -> tree.moveSelection(1)
                        else -> return false
                    }
                    true
                }
                Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                    activate(entry ?: return false)
                    true
                }
                Key.F2 -> {
                    prompt = PanelPrompt.Rename(entry ?: return false)
                    true
                }
                Key.Delete, Key.Backspace -> {
                    confirmDelete(entry ?: return false)
                    true
                }
                Key.MoveHome -> {
                    tree.selectEdge(last = false)
                    true
                }
                Key.MoveEnd -> {
                    tree.selectEdge(last = true)
                    true
                }
                // The keyboard's way to the context menu. Both spellings: the
                // menu key isn't on every keyboard, and Shift F10 is what the
                // ones without it use.
                Key.Menu, Key.F10 -> {
                    if (event.key == Key.F10 && !event.isShiftPressed) return false
                    // No pointer to place it under: it drops from the row's start.
                    menu = PanelMenu(entry, Offset.Zero)
                    true
                }
                else -> false
            }
        }

        // Keyed on `tree`, not `project`: changing a setting that affects the
        // tree (showing gitignored files) builds a fresh, empty
        // ProjectTreeState, and an effect still holding the old one would
        // leave the panel permanently blank.
        LaunchedEffect(tree) {
            while (true) {
                val version = project.version
                val shape = tree.shape
                if (version != tree.version) {
                    tree.publish(
                        version,
                        withContext(Dispatchers.Default) { tree.rebuild() },
                        shape,
                    )
                } else if (statusSource.version != tree.statusVersion) {
                    // Statuses normally land after the tree has been drawn.
                    // Re-colouring keeps the same rows and the same keys, so
                    // the list doesn't blink, scroll, or re-measure.
                    val current = tree.rows
                    tree.publish(
                        version,
                        withContext(Dispatchers.Default) { tree.restatus(current) },
                        shape,
                    )
                }
                val eager = !project.scanComplete ||
                    SystemClock.uptimeMillis() < expectChangeUntil
                delay(if (eager) SCANNING_POLL_MS else IDLE_POLL_MS)
            }
        }

        // A reveal can outlive the frame that asked for it: the row may be
        // inside a directory the worktree has yet to scan, and only exists
        // once the engine reports it.
        LaunchedEffect(tree.rows, tree.pendingReveal) {
            val target = tree.pendingReveal ?: return@LaunchedEffect
            val index = tree.rows.indexOfFirst { it.entry.path == target }
            if (index < 0) return@LaunchedEffect
            listState.scrollToItem(index)
            tree.revealed()
        }

        // Keyboard selection has to stay on screen. Clicking a row that is
        // already visible must not scroll anything, so this only moves the
        // list when the selected row isn't fully in the viewport.
        LaunchedEffect(tree.selected) {
            val path = tree.selected ?: return@LaunchedEffect
            if (tree.pendingReveal != null) return@LaunchedEffect
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.firstOrNull { it.key == path }
            if (visible != null &&
                visible.offset >= info.viewportStartOffset &&
                visible.offset + visible.size <= info.viewportEndOffset
            ) {
                return@LaunchedEffect
            }
            val index = tree.rows.indexOfFirst { it.entry.path == path }
            if (index >= 0) listState.animateScrollToItem(index)
        }

        // Zed's `project_panel.auto_reveal_entries`, which is on by default:
        // the tree follows the file being edited. It never takes the keyboard
        // — only an explicit request does that.
        LaunchedEffect(openedPath) {
            openedPath?.let { reshape { tree.reveal(it) } }
        }

        LaunchedEffect(revealRequest) {
            if (!revealRequest) return@LaunchedEffect
            onRevealHandled()
            panelFocus.requestFocus()
            openedPath?.let { reshape { tree.reveal(it) } }
        }

        PanelHeading(
            name = project.rootName,
            menu = {
                // Also where the keyboard's menu key lands when nothing in
                // the tree is selected: a menu for the project root.
                if (menu.let { it != null && it.entry == null }) {
                    ProjectContextMenu(
                        entries = menuFor(null),
                        offset = DpOffset.Zero,
                        onDismiss = { menu = null },
                    )
                }
            },
            onMenu = { menu = PanelMenu(null, Offset.Zero) },
        )

        val error = project.error
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(panelFocus)
                .onPreviewKeyEvent(::handleKey)
                .focusable()
        ) {
            when {
                error != null -> PanelMessage(error)
                tree.rows.isEmpty() && !project.scanComplete -> PanelMessage("Scanning…")
                tree.rows.isEmpty() -> PanelMessage("Empty project")
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(tree.rows, key = { it.entry.path }) { row ->
                        ProjectRow(
                            entry = row.entry,
                            depth = row.depth,
                            status = row.status,
                            colours = colours,
                            icons = icons,
                            rowColours = rowColours,
                            isExpanded = tree.isExpanded(row.entry.path),
                            isOpen = row.entry.path == openedPath,
                            isSelected = row.entry.path == tree.selected,
                            isCut = pending?.isCut == true && pending?.path == row.entry.path,
                            dimIgnored = dimIgnored,
                            onClick = {
                                panelFocus.requestFocus()
                                activate(row.entry)
                            },
                            onContextMenu = { at ->
                                panelFocus.requestFocus()
                                tree.select(row.entry.path)
                                menu = PanelMenu(row.entry, at)
                            },
                            menu = {
                                val open = menu
                                if (open?.entry?.path == row.entry.path) {
                                    ProjectContextMenu(
                                        entries = menuFor(row.entry),
                                        offset = with(density) {
                                            DpOffset(open.at.x.toDp(), open.at.y.toDp())
                                        },
                                        onDismiss = { menu = null },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        when (val current = prompt) {
            null -> Unit

            is PanelPrompt.NewEntry -> EntryNameDialog(
                title = if (current.isDir) "NEW FOLDER" else "NEW FILE",
                confirmLabel = "Create",
                initial = "",
                selectionEnd = 0,
                placeholder = if (current.parent.isEmpty()) {
                    "Name, or a path like src/main.rs"
                } else {
                    "Name, inside ${current.parent}"
                },
                errorFor = { name ->
                    ProjectFiles.pathError(name, ProjectFiles.resolve(root, current.parent))
                },
                onConfirm = { name ->
                    prompt = null
                    operate(
                        onDone = { path ->
                            reshape { tree.reveal(path) }
                            if (!current.isDir) {
                                onOpenFile(newFileEntry(path))
                            }
                        }
                    ) {
                        ProjectFiles.create(root, current.parent, name, current.isDir)
                    }
                },
                onDismiss = { prompt = null },
            )

            is PanelPrompt.Rename -> EntryNameDialog(
                title = "RENAME",
                confirmLabel = "Rename",
                initial = current.entry.name,
                selectionEnd = stemLength(current.entry.name, current.entry.isDir),
                placeholder = "Name",
                errorFor = { name ->
                    if (name.trim() == current.entry.name) {
                        null
                    } else {
                        ProjectFiles.nameError(
                            name,
                            ProjectFiles.resolve(root, ProjectFiles.parentOf(current.entry.path)),
                        )
                    }
                },
                onConfirm = { name ->
                    prompt = null
                    val from = current.entry.path
                    operate(
                        onDone = { path ->
                            if (path != from) onEntryMoved(from, path)
                            reshape { tree.reveal(path) }
                        }
                    ) {
                        ProjectFiles.rename(root, from, name)
                    }
                },
                onDismiss = { prompt = null },
            )

            is PanelPrompt.Delete -> ConfirmDeleteDialog(
                path = current.entry.path,
                isDir = current.entry.isDir,
                onConfirm = {
                    prompt = null
                    deleteNow(current.entry)
                },
                onDismiss = { prompt = null },
            )

            is PanelPrompt.Failure -> PanelErrorDialog(
                message = current.message,
                onDismiss = { prompt = null },
            )
        }
    }
}

/** The panel's title, and the button that opens the project-root menu. */
@Composable
private fun PanelHeading(
    name: String?,
    menu: (@Composable () -> Unit)?,
    onMenu: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 8.dp)
            .then(
                if (onMenu == null) Modifier else rememberPointerContextMenu { onMenu() }
            ),
    ) {
        Text(
            text = name?.uppercase().orEmpty().ifEmpty { "PROJECT" },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onMenu != null) {
            Box {
                Text(
                    text = "⋯",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onMenu)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                menu?.invoke()
            }
        }
    }
}

/** The three backgrounds a row can have, resolved once per theme. */
private class RowColours(val open: Color, val hover: Color, val selection: Color)

@Composable
private fun ProjectRow(
    entry: ProjectEntry,
    depth: Int,
    status: GitFileStatus,
    colours: GitStatusColours,
    icons: EntryIconColours,
    rowColours: RowColours,
    isExpanded: Boolean,
    isOpen: Boolean,
    isSelected: Boolean,
    /** Cut and waiting to be pasted: shown faded, as every file manager does. */
    isCut: Boolean,
    dimIgnored: Boolean,
    onClick: () -> Unit,
    onContextMenu: (Offset) -> Unit,
    menu: @Composable () -> Unit,
) {
    // Zed tints the *name* by git status and greys gitignored entries rather
    // than hiding them; "show" opts out of even that, for people who don't
    // want their tree to editorialise. Ignored still wins over status, as in
    // Zed: an ignored file that also has changes reads as ignored.
    //
    // Colours were resolved once for the whole panel, so this is a `when` over
    // an enum — no theme lookup and no allocation per row, per frame.
    val color = colours.colorFor(status, entry.isIgnored, dimIgnored)
    val icon = remember(entry.name, entry.isDir) { entryIconFor(entry.name, entry.isDir) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        isOpen -> rowColours.open
        isSelected || hovered -> rowColours.hover
        else -> Color.Transparent
    }
    val selectionMark = rowColours.selection
    // A long press has no coordinates of its own, so the last press is
    // remembered: the menu should open under the finger, not at the row's edge.
    val pressed = remember { mutableStateOf(Offset.Zero) }
    val contextGesture = rememberPointerContextMenu(
        onPress = { pressed.value = it },
        onContext = onContextMenu,
    )
    // The menu is a child of this box rather than of the row's content, so the
    // popup is placed against the whole row and the offset it is given is the
    // press position unchanged.
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .drawBehind {
                    // A stripe rather than a border: it marks where the
                    // keyboard is without moving the row's text by a pixel.
                    if (isSelected) {
                        drawRect(color = selectionMark, size = Size(2.dp.toPx(), size.height))
                    }
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .then(contextGesture)
                .hoverable(interaction)
                // The panel is the single focus target; rows would otherwise
                // take it in turn and fight the arrows for the selection.
                .focusProperties { canFocus = false }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { onContextMenu(pressed.value) },
                )
                .padding(
                    start = 12.dp + IndentPerLevel * depth,
                    end = 12.dp,
                    top = RowHeightPadding,
                    bottom = RowHeightPadding,
                ),
        ) {
            Text(
                text = when {
                    entry.isDir && isExpanded -> "▾"
                    entry.isDir -> "▸"
                    else -> " "
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
            Box(
                modifier = Modifier.width(EntryIconWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                EntryIconMark(
                    icon = icon,
                    color = icons.colorFor(icon).copy(alpha = if (isCut) CUT_ALPHA else 1f),
                )
            }
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCut) color.copy(alpha = CUT_ALPHA) else color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        menu()
    }
}

@Composable
private fun PanelMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A right-click opens the context menu where it happened, before anything else
 * sees the press.
 *
 * Watched in the initial pass and consumed only when it is the secondary
 * button, so an ordinary click still reaches the clickable below — and a mouse
 * gets the menu without the half-second a long press costs. [onPress] sees
 * every press, which is how a long press knows where the finger was.
 *
 * The gesture is installed once and reads its callbacks through state, because
 * restarting a `pointerInput` on every recomposition would drop presses that
 * are in flight.
 */
@Composable
private fun rememberPointerContextMenu(
    onPress: (Offset) -> Unit = {},
    onContext: (Offset) -> Unit,
): Modifier {
    val press by rememberUpdatedState(onPress)
    val context by rememberUpdatedState(onContext)
    return remember {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val change = event.changes.firstOrNull() ?: continue
                    press(change.position)
                    if (!event.buttons.isSecondaryPressed) continue
                    change.consume()
                    context(change.position)
                }
            }
        }
    }
}

/** A file the panel just created, before the worktree has reported it. */
private fun newFileEntry(path: String): ProjectEntry {
    val name = path.substringAfterLast('/')
    return ProjectEntry(
        path = path,
        name = name,
        isDir = false,
        isIgnored = false,
        isHidden = name.startsWith("."),
        isUnloaded = false,
        size = 0L,
    )
}

/** How much of a name a rename should start out selecting. */
private fun stemLength(name: String, isDir: Boolean): Int {
    val dot = name.lastIndexOf('.')
    return if (isDir || dot <= 0) name.length else dot
}

private const val CUT_ALPHA = 0.45f
