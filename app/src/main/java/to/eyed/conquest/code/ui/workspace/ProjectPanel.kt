package to.eyed.conquest.code.ui.workspace

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.GitFileStatus as EngineStatus
import to.eyed.conquest.code.core.GitignoredFiles
import to.eyed.conquest.code.core.ProjectEntry
import to.eyed.conquest.code.core.ProjectSession
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/** Zed's `indent_size` (assets/settings/default.json:828). */
private val IndentPerLevel = 20.dp

/** `px(Base06)` on the row; the indent is applied inside it (list_item.rs:364). */
private val RowPadding = 6.dp

/** `gap_1` between a row's icon and its name (list_item.rs:363). */
private val RowGap = 4.dp

/**
 * Zed's row: a fixed `h_6` (24px) content box (project_panel.rs:6264) inside a
 * wrapper that always carries a 1px border — usually painted in the row's own
 * background, so invisible (project_panel.rs:5793) — for a 26px pitch. Per the
 * 2026-08-17 density decision in DECISIONS.md that is our row too: the whole
 * row is the tap target, and everything a small target does is also reachable
 * from the long-press menu or the keyboard.
 */
private val RowHeight = 26.dp

/** Guides are 1px, at every indent level a row is nested under. */
private val IndentGuideWidth = 1.dp

/**
 * Guides sit 15px right of each level's start, lining up with the icon column
 * (ui::LIST_ITEM_INDENT_GUIDE_LEFT_OFFSET, indent_guides.rs:33, applied in
 * project_panel.rs:7212-7260).
 */
private val IndentGuideOffset = 15.dp

/**
 * The open file's border is 1px around plus a 2px rail on the right edge —
 * `border_1().border_r_2()` in `panel.focused_border` (project_panel.rs:5793-5797).
 */
private val ActiveRowRail = 2.dp

/**
 * A guide run stops 4px short of each of its real ends — `PADDING_Y`
 * (project_panel.rs:7214, applied at 7231-7247). Ours reads those ends from
 * the rows either side in the flattened tree, so only a run's true first and
 * last rows inset their slice and a run cut off by the viewport keeps running
 * where it is cut.
 *
 * **One deliberate deviation.** Zed decides this per *run*, not per end: a run
 * that reaches the last row of the computed window is marked
 * `continues_offscreen` (indent_guides.rs:490-498), and the single `offset` it
 * yields moves the origin *and* shortens the length, so both ends lose their
 * inset together (project_panel.rs:7231-7247). A run that starts on screen and
 * continues past the viewport bottom therefore draws flush at its on-screen top
 * in Zed, where ours still insets. Matching it would mean telling every row
 * where the viewport ends — a per-row read of the layout on every scroll frame,
 * on the main thread — to reproduce a 4px gap that only appears while a run is
 * cut off, and that reads as an artefact rather than as an end.
 */
private val GuideEndInset = 4.dp

/**
 * The gradient shadow under the pinned stack: `h_1p5` (6px) hanging off the
 * last sticky row, black at 10% fading downward to nothing
 * (project_panel.rs:6893-6907). It belongs to that row, not the stack, so the
 * push-off drags the shadow with it, exactly as Zed's does.
 */
private val StickyShadowHeight = 6.dp

/** The end slot keeps `pr_3` (12px) from the row's right edge (project_panel.rs:6160). */
private val StatusSlotEndPadding = 6.dp

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
    /**
     * Asked for on a pinned sticky row rather than the entry's real row. The
     * two can be on screen at once (the real row half-scrolled under the
     * stack), and only the one that was actually clicked should anchor the
     * popup.
     */
    val sticky: Boolean = false,
)

/**
 * Which ancestor rows are pinned over the list's top, and where.
 *
 * [indices] index into the flattened rows, outermost first. [driftPx] is ≤ 0:
 * how far the last pinned row has been pushed up by the anchor row scrolling
 * in under it — Zed's `drifting_y_offset`, which slides continuously with the
 * scroll rather than swapping (sticky_items.rs:179-186, 250-257).
 */
private data class StickyStack(
    val indices: List<Int>,
    val driftPx: Int,
    /** The measured height of one list row, so overlay and list agree in px. */
    val rowHeightPx: Int,
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
    /**
     * Whether the panel holds the keyboard.
     *
     * The workspace needs to know because two of its chords are the panel's
     * too: Zed binds `ctrl-n` to `workspace::NewFile` *and* to
     * `project_panel::NewFile` (default-linux.json:654, 965), and resolves
     * them by context — the panel's context is the more specific one, so it
     * wins while the panel has focus. Our workspace table is matched in a
     * preview pass above the panel, so it has to be told to stand down.
     */
    onFocusChanged: (Boolean) -> Unit = {},
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
        // The panel's plain name colour is `text.muted`, not `text` —
        // `entry_label_color(false)` (items.rs:2177-2183); a marked row's name
        // is promoted back to `text` in the row itself.
        val colours = remember(theme, onSurfaceVariant) {
            GitStatusColours.forProjectPanel(theme, onSurfaceVariant, onSurfaceVariant)
        }
        // One tint for every icon: Zed's file icons are monochrome and it is
        // the row's *name* that carries git status. `icon.muted` is what its
        // project panel asks for.
        val iconColour = theme.color("icon.muted", onSurfaceVariant)
        // `element.*`, not `ghost_element.*`: the project panel does not take
        // the generic ListItem ramp — `get_item_color` overrides it with
        // `element_hover` for hover and `element_selected` for a marked row
        // (project_panel.rs:611-629), and the active file is marked by a 1px
        // `panel.focused_border` border rather than by a fill
        // (project_panel.rs:5729-5743).
        val rowColours = remember(theme, onSurfaceVariant) {
            RowColours(
                hover = theme.color("element.hover", Color.Transparent),
                pressed = theme.color("element.active", Color.Transparent),
                selected = theme.color("element.selected"),
                activeBorder = theme.color("panel.focused_border"),
                indentGuide = theme.color("panel.indent_guide"),
                indentGuideActive = theme.color("panel.indent_guide_active"),
                stickyBackground = theme.color("panel.overlay_background"),
                stickyHover = theme.color("panel.overlay_hover"),
                selectedText = theme.color("text", onSurface),
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

        // Zed's `sticky_scroll`, on by default (settings/default.json:871):
        // once the list is scrolled, the ancestor directories of the topmost
        // visible entry pin to the panel's top. The anchor row is
        // `find_sticky_anchor` and the push-off is `drifting_y_offset`
        // (sticky_items.rs:179-186, 285-316), both on our depth basis, which
        // is one lower than Zed's because our root is a header above the list
        // rather than the list's first row — [findStickyAnchor] spells out
        // what that changes. The ancestors are `sticky_parents`
        // (project_panel.rs:6824-6846).
        //
        // Kept as a State and read in [StickyOverlay], never in this scope:
        // structural equality already drops the scroll frames that don't move
        // the stack, but the drift slides a pixel at a time for the whole of a
        // push-off, and a read here would invalidate the panel — handing
        // `LazyColumn` a fresh content lambda, and so recomposing every
        // visible row, once per frame on the main thread.
        val stickyStack = remember(tree, listState) {
            derivedStateOf(structuralEqualityPolicy()) {
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                ) {
                    // Not scrolled — Zed's `is_scrolled` gate
                    // (project_panel.rs:6946-6951).
                    return@derivedStateOf null
                }
                val rows = tree.rows
                val visible = listState.layoutInfo.visibleItemsInfo
                if (visible.isEmpty()) return@derivedStateOf null
                val depths = ArrayList<Int>(visible.size)
                for (item in visible) {
                    // The rows and the layout can be one frame apart while the
                    // tree reshapes; a stack computed across that gap is wrong
                    // either way, and next frame recomputes.
                    depths += rows.getOrNull(item.index)?.depth
                        ?: return@derivedStateOf null
                }
                val anchor = findStickyAnchor(depths) ?: return@derivedStateOf null
                val anchorItem = visible[anchor.localIndex]
                val ancestors = stickyAncestorsOf(rows, anchorItem.index)
                if (ancestors.isEmpty()) return@derivedStateOf null
                val drift = stickyDriftPx(
                    anchorOffsetPx = anchorItem.offset,
                    rowHeightPx = anchorItem.size,
                    pinnedCount = ancestors.size,
                    drifting = anchor.drifting,
                )
                StickyStack(ancestors, drift, anchorItem.size)
            }
        }

        // The guide run containing the selection, in `panel.indent_guide_active`
        // (find_active_indent_guide, project_panel.rs:6724-6790). Derived from
        // selection and shape only — nothing here runs per scroll frame.
        val activeGuide by remember(tree) {
            derivedStateOf(structuralEqualityPolicy()) {
                activeGuideRun(tree.rows, tree.selected) { path -> tree.isExpanded(path) }
            }
        }

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
            // Land the row below the ancestors that will pin over the top —
            // Zed offsets every autoscroll by the sticky count
            // (project_panel.rs:3309-3317). A row's pinned ancestors are
            // exactly its depth, and rows are fixed-height, so scrolling that
            // many rows earlier puts it in the first uncovered slot.
            listState.scrollToItem((index - tree.rows[index].depth).coerceAtLeast(0))
            tree.revealed()
        }

        // Keyboard selection has to stay on screen. Clicking a row that is
        // already visible must not scroll anything, so this only moves the
        // list when the selected row isn't fully in the viewport.
        LaunchedEffect(tree.selected) {
            val path = tree.selected ?: return@LaunchedEffect
            if (tree.pendingReveal != null) return@LaunchedEffect
            val pinned = stickyStack.value
            // A row that is *in* the pinned stack is on screen — pinned at the
            // top, selection colour and all. Its real row is by definition
            // scrolled off or covered, so measuring that one would say "not
            // visible" and animate the list back to it: right-clicking a
            // pinned directory would throw away the scroll position it was
            // pinned to preserve.
            if (pinned != null &&
                pinned.indices.any { tree.rows.getOrNull(it)?.entry?.path == path }
            ) {
                return@LaunchedEffect
            }
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.firstOrNull { it.key == path }
            // A row under the pinned ancestor stack is covered, not visible,
            // so the top of the usable viewport starts below it — the same
            // allowance Zed's autoscroll makes with its sticky count
            // (project_panel.rs:3309-3317).
            val stackPx = pinned?.let { it.indices.size * it.rowHeightPx } ?: 0
            if (visible != null &&
                visible.offset >= info.viewportStartOffset + stackPx &&
                visible.offset + visible.size <= info.viewportEndOffset
            ) {
                return@LaunchedEffect
            }
            val index = tree.rows.indexOfFirst { it.entry.path == path }
            if (index >= 0) {
                // As in the reveal above: the row's own ancestors will pin, so
                // aim `depth` rows earlier and it lands just under them.
                listState.animateScrollToItem((index - tree.rows[index].depth).coerceAtLeast(0))
            }
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

        ProjectRootRow(
            name = project.rootName,
            rowColours = rowColours,
            iconColour = iconColour,
            onClick = { reshape { tree.collapseAll() } },
            onContextMenu = { at -> menu = PanelMenu(null, at) },
            menu = {
                // Also where the keyboard's menu key lands when nothing in
                // the tree is selected: a menu for the project root.
                val open = menu
                if (open != null && open.entry == null) {
                    ProjectContextMenu(
                        entries = menuFor(null),
                        offset = with(density) { DpOffset(open.at.x.toDp(), open.at.y.toDp()) },
                        onDismiss = { menu = null },
                    )
                }
            },
        )

        val error = project.error
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(panelFocus)
                .onFocusChanged { onFocusChanged(it.hasFocus) }
                .onPreviewKeyEvent(::handleKey)
                .focusable()
        ) {
            when {
                error != null -> PanelMessage(error)
                tree.rows.isEmpty() && !project.scanComplete -> PanelMessage("Scanning…")
                tree.rows.isEmpty() -> PanelMessage("Empty project")
                else -> {
                    val rows = tree.rows
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 12.dp),
                        ) {
                            itemsIndexed(rows, key = { _, row -> row.entry.path }) { index, row ->
                                val active = activeGuide
                                ProjectRow(
                                    entry = row.entry,
                                    // One level in from the root row above, which is
                                    // where Zed puts a worktree's top-level entries
                                    // (project_panel.rs:5547).
                                    depth = row.depth + 1,
                                    status = row.status,
                                    colours = colours,
                                    iconColour = iconColour,
                                    rowColours = rowColours,
                                    isExpanded = tree.isExpanded(row.entry.path),
                                    isOpen = row.entry.path == openedPath,
                                    isSelected = row.entry.path == tree.selected,
                                    isCut = pending?.isCut == true &&
                                        pending?.path == row.entry.path,
                                    dimIgnored = dimIgnored,
                                    // Neighbour depths, for the 4px guide-run end
                                    // insets: a run's slice is inset only where the
                                    // next row over no longer draws that level. The
                                    // root row above the list and the space below
                                    // it draw nothing, hence 0 at both edges.
                                    prevRenderedDepth = if (index == 0) {
                                        0
                                    } else {
                                        rows[index - 1].depth + 1
                                    },
                                    nextRenderedDepth = if (index == rows.lastIndex) {
                                        0
                                    } else {
                                        rows[index + 1].depth + 1
                                    },
                                    activeGuideLevel = if (
                                        active != null && index >= active.first &&
                                        index <= active.last
                                    ) {
                                        active.level
                                    } else {
                                        -1
                                    },
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
                                        if (open != null && !open.sticky &&
                                            open.entry?.path == row.entry.path
                                        ) {
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

                        StickyOverlay(
                            stack = stickyStack,
                            rows = rows,
                            tree = tree,
                            listState = listState,
                            colours = colours,
                            iconColour = iconColour,
                            rowColours = rowColours,
                            dimIgnored = dimIgnored,
                            isCut = { path ->
                                pending?.isCut == true && pending?.path == path
                            },
                            onClick = { row, index ->
                                panelFocus.requestFocus()
                                // Zed scrolls the clicked directory to its own
                                // sticky slot, so its ancestors stay pinned
                                // above it (project_panel.rs:6087-6101); with
                                // fixed-height rows that slot is `depth` rows
                                // down. Selection follows once the scroll
                                // lands, so the autoscroll effect finds it
                                // already placed and stays put.
                                scope.launch {
                                    listState.scrollToItem(
                                        (index - row.depth).coerceAtLeast(0)
                                    )
                                    tree.select(row.entry.path)
                                }
                            },
                            onContextMenu = { entry, at ->
                                panelFocus.requestFocus()
                                // Safe to move the selection without moving the
                                // list: the autoscroll effect above leaves a
                                // row that is pinned in the stack where it is.
                                tree.select(entry.path)
                                menu = PanelMenu(entry, at, sticky = true)
                            },
                            rowMenu = { entry ->
                                val open = menu
                                if (open != null && open.sticky &&
                                    open.entry?.path == entry.path
                                ) {
                                    ProjectContextMenu(
                                        entries = menuFor(entry),
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

/**
 * The pinned ancestors, over the list's top.
 *
 * Each row is the ordinary row composable on the overlay colours; the last one
 * alone drifts and carries the shadow, and the rest are painted over it so a
 * push-off slides *under* the stack — Zed paints the drifting element first for
 * the same reason (sticky_items.rs:110-132).
 *
 * A composable of its own, and the only place [stack] is read in composition:
 * the drift moves with the scroll, so the value is different every frame of a
 * push-off, and reading it in the panel's scope would rebuild the `LazyColumn`
 * content lambda — and with it every visible row — once per frame on the main
 * thread. Here that invalidation costs the two or three rows of the stack.
 */
@Composable
private fun StickyOverlay(
    stack: State<StickyStack?>,
    rows: List<ProjectTreeRow>,
    tree: ProjectTreeState,
    listState: LazyListState,
    colours: GitStatusColours,
    iconColour: Color,
    rowColours: RowColours,
    dimIgnored: Boolean,
    isCut: (String) -> Boolean,
    /** A pinned row and where it sits in [rows]. */
    onClick: (ProjectTreeRow, Int) -> Unit,
    onContextMenu: (ProjectEntry, Offset) -> Unit,
    rowMenu: @Composable (ProjectEntry) -> Unit,
) {
    val pinned = stack.value ?: return

    @Composable
    fun PinnedRow(stackIndex: Int) {
        val index = pinned.indices[stackIndex]
        val row = rows.getOrNull(index) ?: return
        key(row.entry.path) {
            // Zed's `block_mouse_except_scroll()`, on the sticky row itself
            // (project_panel.rs:5798). A pinned row is the later sibling, so
            // Compose hit-tests it first and stops there — hover and clicks
            // stay on the pinned copy rather than reaching the row beneath,
            // which is what we want, but it also means the list's own gesture
            // never sees a wheel or a drag that starts on the stack. This
            // hands those deltas to the list directly: one `scrollable` above
            // the row, so a tap still lands on the row and only movement past
            // touch slop becomes a scroll. It wraps the row alone, not the
            // shadow below it, because Zed's shadow hangs outside its row's
            // hitbox and blocks nothing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollable(
                        state = listState,
                        orientation = Orientation.Vertical,
                        // What a LazyColumn passes for itself when it isn't
                        // reversed (ScrollableDefaults.reverseDirection): a
                        // finger moves with the content, not the viewport.
                        reverseDirection = true,
                    )
            ) {
                ProjectRow(
                    entry = row.entry,
                    depth = row.depth + 1,
                    status = row.status,
                    colours = colours,
                    iconColour = iconColour,
                    rowColours = rowColours,
                    // Pinned rows are ancestors of a visible row: expanded by
                    // definition.
                    isExpanded = true,
                    isOpen = false,
                    isSelected = row.entry.path == tree.selected,
                    isCut = isCut(row.entry.path),
                    dimIgnored = dimIgnored,
                    isSticky = true,
                    onClick = { onClick(row, index) },
                    onContextMenu = { at -> onContextMenu(row.entry, at) },
                    menu = { rowMenu(row.entry) },
                )
            }
        }
    }

    val lastPos = pinned.indices.lastIndex
    Box(modifier = Modifier.fillMaxWidth()) {
        // The deepest pinned row, at its slot plus the push-off, with the
        // shadow hanging below it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, pinned.rowHeightPx * lastPos + pinned.driftPx) }
        ) {
            PinnedRow(lastPos)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(StickyShadowHeight)
                    .background(
                        Brush.verticalGradient(
                            // hsla(0,0,0,0.1) → clear
                            // (project_panel.rs:6894-6895).
                            listOf(
                                Color.Black.copy(alpha = 0.10f),
                                Color.Black.copy(alpha = 0f),
                            )
                        )
                    )
            )
        }
        // The rest of the stack, painted over it.
        Column(modifier = Modifier.fillMaxWidth()) {
            for (position in 0 until lastPos) PinnedRow(position)
        }
    }
}

/**
 * The worktree root — an ordinary row, not a header.
 *
 * Zed has no panel title: the project's own name is the first row of the tree
 * and everything else is indented under it (project_panel.rs:6138). Ours is
 * held out of the scrolling list because a phone's panel is short and the row
 * that says which project you are in is the one worth never losing.
 *
 * The root cannot be hidden the way a folder can, so its chevron stays open
 * and a tap means the only thing collapsing a root can mean here: shut
 * everything underneath. Its menu is the project's, reached the same three
 * ways every other row's is — right-click, long-press, or the menu key.
 */
@Composable
private fun ProjectRootRow(
    name: String,
    rowColours: RowColours,
    iconColour: Color,
    onClick: () -> Unit,
    onContextMenu: (Offset) -> Unit,
    menu: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isPressed by interaction.collectIsPressedAsState()
    val pressed = remember { mutableStateOf(Offset.Zero) }
    val contextGesture = rememberPointerContextMenu(
        onPress = { pressed.value = it },
        onContext = onContextMenu,
    )
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowGap),
            modifier = Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .background(
                    when {
                        isPressed -> rowColours.pressed
                        hovered -> rowColours.hover
                        else -> Color.Transparent
                    }
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .then(contextGesture)
                .focusProperties { canFocus = false }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = { onContextMenu(pressed.value) },
                )
                .padding(horizontal = RowPadding),
        ) {
            Box(
                modifier = Modifier.width(EntryIconWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                EntryIconMark(
                    name = name,
                    isDir = true,
                    isExpanded = true,
                    color = iconColour,
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                // Muted like every other plain entry (items.rs:2177-2183);
                // the root is an ordinary row in Zed, not a header.
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        menu()
    }
}

/** The backgrounds a row can have, resolved once per theme. */
private class RowColours(
    val hover: Color,
    val pressed: Color,
    val selected: Color,
    /** The 1px border marking the open file (project_panel.rs:5729-5743). */
    val activeBorder: Color,
    val indentGuide: Color,
    /** The guide run the selection hangs from (project_panel.rs:7218-7222). */
    val indentGuideActive: Color,
    /**
     * A pinned row's resting and hover colours — the `is_sticky` branch of
     * `get_item_color`: `panel.overlay_background` / `panel.overlay_hover`
     * (project_panel.rs:611-629). Marked and focused stay the shared colours.
     */
    val stickyBackground: Color,
    val stickyHover: Color,
    /** `text` — what a marked row's plain name turns (items.rs:2177-2183). */
    val selectedText: Color,
)

@Composable
private fun ProjectRow(
    entry: ProjectEntry,
    depth: Int,
    status: GitFileStatus,
    colours: GitStatusColours,
    iconColour: Color,
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
    /**
     * The rendered depths of the rows above and below, deciding where each
     * guide run really ends so its 4px end insets go there and nowhere else
     * (PADDING_Y, project_panel.rs:7214). The defaults draw full-height
     * slices — what a pinned sticky row wants, since Zed's sticky guide
     * decoration has no insets (project_panel.rs:7280-7311).
     */
    prevRenderedDepth: Int = Int.MAX_VALUE,
    nextRenderedDepth: Int = Int.MAX_VALUE,
    /** Guide level painted `panel.indent_guide_active`, or -1 for none. */
    activeGuideLevel: Int = -1,
    /**
     * A pinned copy of the row in the sticky stack: overlay colours instead
     * of transparent-on-panel (`get_item_color`'s `is_sticky` branch,
     * project_panel.rs:611-629); everything else renders identically.
     */
    isSticky: Boolean = false,
) {
    // Zed tints the *name* by git status and greys gitignored entries rather
    // than hiding them; "show" opts out of even that, for people who don't
    // want their tree to editorialise. A real change wins over ignored-ness
    // (`entry_git_aware_label_color` checks conflict/deleted/modified/created
    // before ignored — editor/src/items.rs:2205-2219), and a plain entry is
    // `text.muted`, turning `text` only when its row is marked
    // (`entry_label_color`, items.rs:2177-2183).
    //
    // Colours were resolved once for the whole panel, so this is a `when` over
    // an enum — no theme lookup and no allocation per row, per frame.
    val tinted = colours.colorFor(status, entry.isIgnored, dimIgnored)
    val color = if (
        isSelected && status == GitFileStatus.None && !(entry.isIgnored && dimIgnored)
    ) {
        rowColours.selectedText
    } else {
        tinted
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isPressed by interaction.collectIsPressedAsState()
    // Zed's precedence: a marked row is `element.selected` even under the
    // pointer (bg_hover_color stays `marked` — project_panel.rs:5708-5711).
    // A sticky row rests on `panel.overlay_background` and hovers to
    // `panel.overlay_hover` (project_panel.rs:611-629); Zed gives it no
    // pressed colour of its own, so the hover one stands in.
    val background = when {
        isSelected -> rowColours.selected
        isPressed -> if (isSticky) rowColours.stickyHover else rowColours.pressed
        hovered -> if (isSticky) rowColours.stickyHover else rowColours.hover
        else -> if (isSticky) rowColours.stickyBackground else Color.Transparent
    }
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
            horizontalArrangement = Arrangement.spacedBy(RowGap),
            modifier = Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .background(background)
                .drawBehind {
                    // Zed's indent guides: 1px at every level this row is
                    // nested under, at `level × indent_size + 15` — the offset
                    // lines them up with the icon column (project_panel.rs:
                    // 7212-7260). Drawn per row, they join into the same
                    // continuous runs the uniform-list decoration computes,
                    // because a guide at level ℓ spans exactly the contiguous
                    // rows deeper than ℓ. A run's true ends pull in 4px
                    // (PADDING_Y, project_panel.rs:7214): this row holds an
                    // end of level ℓ's run exactly when its neighbour that way
                    // no longer draws ℓ — the neighbours are the tree's, not
                    // the viewport's, so a run cut off by the viewport edge
                    // keeps running. Zed instead drops the inset at *both*
                    // ends of a run that continues offscreen; see
                    // [GuideEndInset] for why we don't.
                    // The run under the selection is `panel.indent_guide_active`
                    // (find_active_indent_guide, project_panel.rs:6724-6790).
                    val guide = IndentGuideWidth.toPx()
                    val step = IndentPerLevel.toPx()
                    val guideOffset = IndentGuideOffset.toPx()
                    val endInset = GuideEndInset.toPx()
                    for (level in 0 until depth) {
                        val topInset = if (prevRenderedDepth <= level) endInset else 0f
                        val bottomInset = if (nextRenderedDepth <= level) endInset else 0f
                        drawRect(
                            color = if (level == activeGuideLevel) {
                                rowColours.indentGuideActive
                            } else {
                                rowColours.indentGuide
                            },
                            topLeft = Offset(level * step + guideOffset, topInset),
                            size = Size(guide, size.height - topInset - bottomInset),
                        )
                    }
                    // The open file wears a 1px border with a 2px rail on the
                    // right edge, not a fill — `border_1().border_r_2()` in
                    // `panel.focused_border` (project_panel.rs:5729-5797).
                    // Ours shows regardless of panel focus: on a touch screen
                    // the panel is unfocused almost always, and the open file
                    // is worth finding.
                    if (isOpen && !isSelected) {
                        drawRect(
                            color = rowColours.activeBorder,
                            topLeft = Offset(guide / 2f, guide / 2f),
                            size = Size(size.width - guide, size.height - guide),
                            style = Stroke(width = guide),
                        )
                        drawRect(
                            color = rowColours.activeBorder,
                            topLeft = Offset(size.width - ActiveRowRail.toPx(), 0f),
                            size = Size(ActiveRowRail.toPx(), size.height),
                        )
                    }
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .then(contextGesture)
                // The panel is the single focus target; rows would otherwise
                // take it in turn and fight the arrows for the selection.
                .focusProperties { canFocus = false }
                .combinedClickable(
                    interactionSource = interaction,
                    // Zed swaps a row's colour instantly and has no ripple at
                    // all; the pressed background below is the whole feedback.
                    indication = null,
                    onClick = onClick,
                    onLongClick = { onContextMenu(pressed.value) },
                )
                .padding(start = RowPadding + IndentPerLevel * depth, end = RowPadding),
        ) {
            Box(
                modifier = Modifier.width(EntryIconWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                EntryIconMark(
                    name = entry.name,
                    isDir = entry.isDir,
                    isExpanded = isExpanded,
                    color = iconColour.copy(alpha = if (isCut) CUT_ALPHA else 1f),
                )
            }
            // The name takes ALL remaining width — Zed's content group is
            // `flex_grow_1` with `justify_between` against the end slot
            // (list_item.rs:425-441) — so the ellipsis uses the full row and
            // the git mark below is pinned to the row's end.
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCut) color.copy(alpha = CUT_ALPHA) else color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Zed's trailing git mark, in the row's end slot: a status letter
            // for files, a half-opacity dot for a directory with changes
            // (project_panel.rs:6188-6205, 7786-7809).
            val letter = statusLetter(status)
            if (letter != null && !(entry.isIgnored && dimIgnored)) {
                if (entry.isDir) {
                    Box(
                        modifier = Modifier
                            .padding(end = StatusSlotEndPadding)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(tinted.copy(alpha = 0.5f)),
                    )
                } else {
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelMedium,
                        color = tinted,
                        maxLines = 1,
                        modifier = Modifier.padding(end = StatusSlotEndPadding),
                    )
                }
            }
        }
        menu()
    }
}

/**
 * Zed's letter for a change, from `git_status_indicator`
 * (project_panel.rs:7786-7809): conflicts shout, then the worktree's own
 * state. Renames surface as the index modification they are.
 */
private fun statusLetter(status: GitFileStatus): String? = when (status) {
    GitFileStatus.Conflicted -> "!"
    GitFileStatus.Untracked -> "U"
    GitFileStatus.Deleted -> "D"
    GitFileStatus.Modified -> "M"
    GitFileStatus.Added -> "A"
    GitFileStatus.Renamed -> "M"
    GitFileStatus.None, GitFileStatus.Ignored -> null
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
