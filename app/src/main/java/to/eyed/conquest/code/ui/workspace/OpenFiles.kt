package to.eyed.conquest.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.ui.editor.EditorState
import to.eyed.conquest.code.ui.git.DiffTarget
import to.eyed.conquest.code.ui.media.MediaKind

/** How many closed files Ctrl+Shift+T can walk back through. */
private const val REOPEN_HISTORY = 24

/** Zed's `MAX_NAVIGATION_HISTORY_LEN` (workspace/src/pane.rs:322). */
private const val MAX_NAVIGATION_HISTORY = 1024

/**
 * One place the user has been — what GoBack returns to.
 *
 * A path and a position, not a tab reference: Zed's entries hold a weak item
 * handle exactly so a closed item doesn't keep the history alive, and falls
 * back to the item's path to reopen it (workspace.rs:2846-2860). Ours are a
 * path from the start, because closing a tab here releases the engine buffer
 * and reopening always goes back through the workspace's own open path.
 */
class NavEntry(
    /** Project-relative path — the same key the tab strip uses. */
    val path: String,
    /** Caret, 0-based — Zed pushes the cursor row with each entry (pane.rs:4664-4678). */
    val row: Int = 0,
    val col: Int = 0,
    /** [EditorState.scrollY] at departure — the vertical anchor Zed's `NavigationData` keeps. */
    val scroll: Float = 0f,
    /**
     * Whether the path can be opened again once its tab is gone. A diff or
     * the graph is opened by a *view*, not by a path the file opener knows —
     * the same reason [OpenFile.isReopenable] exists — so a stale entry for
     * one is discarded rather than returned, mirroring how Zed's loop skips
     * entries it has no path info for (workspace.rs:2845-2853).
     */
    val isReopenable: Boolean = true,
) {
    /**
     * Put the caret and the view back where this entry says they were.
     *
     * Suspending for the same reason `revealProjectSearchMatch` is: a file
     * this navigation has just reopened has no measured viewport yet, so the
     * scroll restore waits two frames — one to compose the pane, one to
     * measure it — before `ensureCursorVisible` clamps everything into range.
     * Clamped against the file as it is *now*: the entry may describe a file
     * that has shrunk since, and Zed treats stale anchors the same way —
     * resolve what still resolves, never refuse.
     */
    suspend fun restoreIn(file: OpenFile) {
        val editor = file.editor ?: return
        val targetRow = row.coerceIn(0, (editor.lineCount - 1).coerceAtLeast(0))
        val targetCol = col.coerceIn(0, editor.line(targetRow).length)
        editor.selectRange(
            EditorState.SelectionRange(targetRow, targetCol, targetRow, targetCol)
        )
        withFrameNanos { }
        withFrameNanos { }
        editor.scrollToY(scroll)
        editor.ensureCursorVisible()
    }
}

/**
 * The jump list behind GoBack/GoForward — Zed's `NavHistory`
 * (workspace/src/pane.rs:4707-4860), reduced to the two stacks.
 *
 * The rules are Zed's own, kept testable in one place:
 * - a normal push lands on the backward stack and **clears the forward
 *   stack** — new navigation after a GoBack throws the "forward" branch away,
 *   as a browser does (pane.rs:4801-4815);
 * - every push first drops older entries at the same place — same path, same
 *   row, Zed's `is_same_location` (pane.rs:4795-4797) — so hopping between
 *   two files doesn't fill the list with copies;
 * - each stack is capped at [MAX_NAVIGATION_HISTORY], oldest dropped
 *   (pane.rs:4806-4808);
 * - going back pushes the departing location onto the forward stack, going
 *   forward pushes it onto the backward stack, and neither clears anything
 *   (pane.rs:4817-4846).
 *
 * The stacks are snapshot state so the tab bar's arrow buttons grey and
 * ungrey themselves as the history changes, the way Zed's do
 * (pane.rs:3407-3452).
 */
class NavHistory {
    private val backward = mutableStateListOf<NavEntry>()
    private val forward = mutableStateListOf<NavEntry>()

    val canGoBack: Boolean get() = backward.isNotEmpty()
    val canGoForward: Boolean get() = forward.isNotEmpty()

    /** A place just departed, in Zed's `NavigationMode::Normal` (pane.rs:4801-4815). */
    fun push(entry: NavEntry) {
        pushOnto(backward, entry)
        forward.clear()
    }

    /**
     * Pop back to the nearest [usable] entry, discarding the dead ones on
     * the way — Zed's `navigate_history_impl` loops exactly like this,
     * popping until an entry actually navigates (workspace.rs:2822-2854).
     * [from] is where the user is now; it goes onto the forward stack only
     * when there is somewhere to go back *to*.
     */
    fun back(from: NavEntry?, usable: (NavEntry) -> Boolean): NavEntry? =
        travel(source = backward, opposite = forward, from = from, usable = usable)

    /** The mirror image, replaying what [back] set aside (pane.rs:4831-4846). */
    fun forward(from: NavEntry?, usable: (NavEntry) -> Boolean): NavEntry? =
        travel(source = forward, opposite = backward, from = from, usable = usable)

    /** Put back an entry whose navigation could not be performed. */
    fun restore(entry: NavEntry, toBackward: Boolean) {
        val stack = if (toBackward) backward else forward
        val opposite = if (toBackward) forward else backward
        // The travel that spent it pushed the departure onto the opposite
        // stack; unwind that too, or a failed GoBack would leave a forward
        // entry pointing at a move that never happened.
        opposite.removeLastOrNull()
        stack.add(entry)
    }

    /** Forget everything — the paths belong to a project being left. */
    fun clear() {
        backward.clear()
        forward.clear()
    }

    private fun travel(
        source: MutableList<NavEntry>,
        opposite: MutableList<NavEntry>,
        from: NavEntry?,
        usable: (NavEntry) -> Boolean,
    ): NavEntry? {
        while (source.isNotEmpty()) {
            val entry = source.removeAt(source.lastIndex)
            // An entry for the place the user is already standing navigates
            // nowhere; Zed's loop notices `navigated` stayed false and keeps
            // popping (workspace.rs:2837-2843).
            val standingStill = from != null && entry.path == from.path && entry.row == from.row
            if (standingStill || !usable(entry)) continue
            if (from != null) pushOnto(opposite, from)
            return entry
        }
        return null
    }

    private fun pushOnto(stack: MutableList<NavEntry>, entry: NavEntry) {
        stack.removeAll { it.path == entry.path && it.row == entry.row }
        if (stack.size >= MAX_NAVIGATION_HISTORY) stack.removeAt(0)
        stack.add(entry)
    }
}

/**
 * One open editor tab.
 *
 * The engine is the authority on dirty and on-disk state, but those are plain
 * JNI getters, not observable — so the flags are mirrored here as snapshot
 * state and refreshed by [refreshStatus]. Reading them during composition
 * would be a JNI call in the draw path; this way the tab strip redraws only
 * when something actually changed.
 */
class OpenFile(
    /** Project-relative path — what the panel and tab strip display. */
    val path: String,
    /**
     * The text editor, or null for a tab that is not text at all.
     *
     * A picture has no buffer: nothing to parse, nothing to save, nothing to
     * be dirty, and closing it cannot lose work. Everything that assumes an
     * editor has to ask first, which is the point of making it nullable
     * rather than inventing an empty one.
     */
    val editor: EditorState?,
    /** What this file is, when it is not text. */
    val media: MediaKind? = null,
    /**
     * A diff, when the tab is one. Like a picture, it has no buffer: it is a
     * view of what git says, and it cannot be edited or saved.
     */
    val diff: DiffTarget? = null,
    /** True for the commit graph, which is a view of the repository itself. */
    val graph: Boolean = false,
    /** The file on disk, for a tab the engine never opened. */
    val absolutePath: String? = null,
) {
    val session: BufferSession? get() = editor?.session

    /**
     * Whether Ctrl+Shift+T could bring this back. A diff, the graph and a
     * picture are opened by a *view*, not by a path the file opener knows, and
     * pushing their keys onto the reopen stack spent a keypress on a file that
     * cannot be opened — and skipped the real last file.
     */
    val isReopenable: Boolean get() = diff == null && !graph

    val name: String = when {
        graph -> "Git graph"
        diff != null -> diff.title
        else -> path.substringAfterLast('/')
    }

    /**
     * A media tab has no buffer, so nothing in the engine is watching its
     * file. The one thing worth knowing is whether it is still there — the
     * pane itself watches for the contents changing.
     */
    private fun refreshMediaStatus(): Boolean {
        val disk = absolutePath ?: return false
        val deleted = !java.io.File(disk).exists()
        if (deleted == isDeleted) return false
        isDeleted = deleted
        return true
    }

    var isDirty by mutableStateOf(false)
        private set
    var hasDiskChange by mutableStateOf(false)
        private set
    var isDeleted by mutableStateOf(false)
        private set

    /**
     * Pinned tabs sit at the left of the strip and are left alone by the bulk
     * closes, as in Zed. Owned by [OpenFilesState], which also keeps the
     * pinned tabs together at the head of the list.
     */
    var isPinned by mutableStateOf(false)
        internal set

    /**
     * Grammar the engine is highlighting with, for the status bar. Read once:
     * the language is chosen when the file is opened and doesn't change.
     */
    val language: String? = session?.language

    /** Whether anything changed, so callers can skip needless work. */
    fun refreshStatus(): Boolean {
        val open = session ?: return refreshMediaStatus()
        val dirty = open.isDirty
        val disk = open.hasDiskChange
        val deleted = open.isFileDeleted
        if (dirty == isDirty && disk == hasDiskChange && deleted == isDeleted) return false
        isDirty = dirty
        hasDiskChange = disk
        isDeleted = deleted
        return true
    }
}

/**
 * The set of open tabs and which one is showing.
 *
 * Opening a file already open selects its tab rather than adding a second —
 * matching the engine, which returns one buffer per path however many times
 * it is asked.
 *
 * Two rules come from Zed and are enforced here rather than in the strip:
 * **pinned tabs live at the head of the list**, so "pinned tabs sit on the
 * left" is a property of the model and not of the drawing; and **a tab with
 * unsaved edits is never closed without asking** — [requestClose] and its
 * siblings hand such tabs to [closeConfirmation] instead of dropping the
 * buffer, which is what the plain [close] would do.
 */
class OpenFilesState {
    private val _tabs = mutableStateListOf<OpenFile>()
    val tabs: List<OpenFile> get() = _tabs

    var activeIndex by mutableIntStateOf(-1)
        private set

    val active: OpenFile? get() = _tabs.getOrNull(activeIndex)

    /** Paths of tabs closed in this session, oldest first — Ctrl+Shift+T's stack. */
    private val closedPaths = mutableStateListOf<String>()

    /**
     * The jump list. Entries are recorded when the active tab *changes* — a
     * tab click, Ctrl+Tab, a file being opened — which is Zed's
     * "deactivated item pushes its position" (editor pushes on deactivate,
     * items.rs via `push_to_nav_history(.., is_deactivate=true, ..)`). Zed
     * also records large caret jumps inside one item
     * (`MIN_NAVIGATION_HISTORY_ROW_DELTA` = 10, editor.rs:295,
     * navigation.rs:1560-1566); that half waits until the editor can report
     * them — noted in the class doc rather than half-built here.
     */
    private val nav = NavHistory()

    /**
     * A path GoBack/GoForward has asked the workspace to reopen. The reopen
     * arrives later as an ordinary [open] call, and *that* open is the
     * navigation itself, not new travel — pushing it would clear the forward
     * stack and break the GoForward that should follow. Zed brackets the
     * open in `NavigationMode::GoingBack` for exactly this
     * (workspace.rs:2833-2835); this is the same bracket for an async open.
     */
    private var pendingNavPath: String? = null

    val canGoBack: Boolean get() = nav.canGoBack
    val canGoForward: Boolean get() = nav.canGoForward

    /** Tabs a close request is still working through, head first. */
    private val closing = mutableStateListOf<OpenFile>()

    /** How many tabs at the head of the strip are pinned. */
    val pinnedCount: Int get() = _tabs.count { it.isPinned }

    /** Whether there is anything for "reopen closed tab" to reopen. */
    val hasClosedTabs: Boolean get() = closedPaths.isNotEmpty()

    /**
     * The unsaved tab a close is waiting on, or null when nothing is pending.
     *
     * One file at a time, as Zed asks: a prompt that names the file is the
     * only kind worth showing, and a list of five is not a decision anyone can
     * make. [confirmClose] answers for the head and carries on to the rest.
     */
    val closeConfirmation: OpenFile? get() = closing.firstOrNull()

    fun indexOfPath(path: String): Int = _tabs.indexOfFirst { it.path == path }

    fun select(index: Int) {
        if (index !in _tabs.indices || index == activeIndex) return
        recordDeparture()
        pendingNavPath = null
        activeIndex = index
    }

    /** Move [delta] tabs along, wrapping — what Ctrl+Tab is expected to do. */
    fun selectRelative(delta: Int) {
        if (_tabs.isEmpty()) return
        val size = _tabs.size
        select(((activeIndex + delta) % size + size) % size)
    }

    /** Add a tab (or select the existing one) and make it active. */
    fun open(file: OpenFile) {
        // The open GoBack/GoForward asked for — the navigation landing, not
        // new travel, so it must not push (which would clear forward). Any
        // *other* open supersedes a pending one, exactly as any keypress
        // between GoBack and its async open would in Zed's synchronous world.
        val navigated = file.path == pendingNavPath
        pendingNavPath = null
        val existing = indexOfPath(file.path)
        if (existing >= 0) {
            if (existing != activeIndex) {
                if (!navigated) recordDeparture()
                activeIndex = existing
            }
            return
        }
        if (!navigated) recordDeparture()
        _tabs.add(file)
        activeIndex = _tabs.lastIndex
        closedPaths.remove(file.path)
    }

    /**
     * Step back along the jump list — Zed's `pane::GoBack` (pane.rs:929-938).
     *
     * If the entry's tab is still open it becomes active here; either way the
     * entry is returned so the caller can restore its caret and scroll
     * ([NavEntry.restoreIn]) — or reopen the file first, through the same
     * open path Ctrl+Shift+T uses, when [indexOfPath] says it is gone. Null
     * when there is nowhere to go.
     */
    fun goBack(): NavEntry? = navigateHistory(back = true)

    /** Zed's `pane::GoForward` (pane.rs:940-950) — replays what [goBack] left. */
    fun goForward(): NavEntry? = navigateHistory(back = false)

    private fun navigateHistory(back: Boolean): NavEntry? {
        val from = active?.let(::locationOf)
        // Usable = still on the strip, or reopenable by path. A dead diff or
        // graph entry is skipped, as Zed skips entries with no path info
        // (workspace.rs:2845-2853).
        val usable = { entry: NavEntry -> indexOfPath(entry.path) >= 0 || entry.isReopenable }
        val entry = (if (back) nav.back(from, usable) else nav.forward(from, usable))
            ?: return null
        val index = indexOfPath(entry.path)
        if (index >= 0) {
            // Straight to the index, not [select]: navigating is what Zed
            // brackets in GoingBack/GoingForward mode so the activation it
            // causes doesn't record as new travel (workspace.rs:2833-2835).
            activeIndex = index
        } else {
            pendingNavPath = entry.path
        }
        return entry
    }

    /**
     * A navigation that could not land — the file would not open at all.
     *
     * Without this the entry is spent and the arrow it lit stays lit for a
     * move that never happened, and the [pendingNavPath] bracket stays armed
     * so the user's *next* open of that path would be mistaken for the
     * landing. Put the entry back where it came from and disarm.
     */
    fun navigationFailed(entry: NavEntry, wasBack: Boolean) {
        if (pendingNavPath == entry.path) pendingNavPath = null
        nav.restore(entry, toBackward = wasBack)
    }

    /** Where [file] is right now, as a history entry. */
    private fun locationOf(file: OpenFile) = NavEntry(
        path = file.path,
        row = file.editor?.cursorRow ?: 0,
        col = file.editor?.cursorCol ?: 0,
        scroll = file.editor?.scrollY ?: 0f,
        isReopenable = file.isReopenable,
    )

    /** The active tab is being left: remember where it was. */
    private fun recordDeparture() {
        val leaving = active ?: return
        nav.push(locationOf(leaving))
    }

    /**
     * Close a tab and release its engine buffer. The neighbour to the left
     * becomes active, which is what every editor does and what keeps the
     * selection stable when closing several in a row.
     *
     * Unconditional: unsaved edits go with it. Only callers that have already
     * asked — [requestClose] and friends — or that are tearing the workspace
     * down should use it.
     */
    fun close(index: Int) {
        val file = _tabs.getOrNull(index) ?: return
        _tabs.removeAt(index)
        closing.remove(file)
        file.session?.close()
        if (file.isReopenable) rememberClosed(file.path)
        activeIndex = when {
            _tabs.isEmpty() -> -1
            index <= activeIndex -> (activeIndex - 1).coerceAtLeast(0)
            else -> activeIndex.coerceAtMost(_tabs.lastIndex)
        }
    }

    /** Pin or unpin a tab, moving it across the pinned/unpinned boundary. */
    fun togglePin(index: Int) {
        val file = _tabs.getOrNull(index) ?: return
        val current = active
        file.isPinned = !file.isPinned
        _tabs.removeAt(index)
        // The boundary counts only the *other* pinned tabs now that this one is
        // out of the list, so both directions land the tab on the right side.
        _tabs.add(_tabs.count { it.isPinned }, file)
        activeIndex = if (current == null) -1 else _tabs.indexOfFirst { it === current }
    }

    /** Close one tab, asking first if it has unsaved edits. */
    fun requestClose(index: Int) {
        val file = _tabs.getOrNull(index) ?: return
        request(listOf(file))
    }

    /** Close every other tab, leaving the pinned ones alone. */
    fun requestCloseOthers(index: Int) {
        val kept = _tabs.getOrNull(index) ?: return
        request(_tabs.filter { it !== kept && !it.isPinned })
    }

    /** Close everything to the right of [index], leaving the pinned ones alone. */
    fun requestCloseToTheRight(index: Int) {
        if (index !in _tabs.indices) return
        request(_tabs.drop(index + 1).filter { !it.isPinned })
    }

    /** Close every tab. Pinned tabs survive, which is the point of pinning them. */
    fun requestCloseAll() {
        request(_tabs.filter { !it.isPinned })
    }

    /** The user said discard, or has just saved: close the tab and move on. */
    fun confirmClose() {
        val file = closing.firstOrNull() ?: return
        closing.removeAt(0)
        close(indexOfPath(file.path))
        drainClean()
    }

    /** The user said no. The rest of the request goes with it — nothing closes. */
    fun cancelClose() {
        closing.clear()
    }

    /**
     * The most recently closed file that isn't open again, or null.
     *
     * A path, not a buffer: closing released the engine's buffer, so reopening
     * goes back through the workspace's normal open path rather than trying to
     * resurrect one.
     */
    fun takeReopenPath(): String? {
        while (closedPaths.isNotEmpty()) {
            val path = closedPaths.removeAt(closedPaths.lastIndex)
            if (indexOfPath(path) < 0) return path
        }
        return null
    }

    /** Forget the reopen history — the paths belong to a project being left. */
    fun clearClosedHistory() {
        closedPaths.clear()
        // The jump list goes with it, for the same reason: its paths are
        // relative to a root that is about to change, which is Zed's
        // `NavHistory::clear` on workspace teardown (pane.rs:4748-4768).
        nav.clear()
        pendingNavPath = null
    }

    fun refreshStatuses() {
        for (tab in _tabs) tab.refreshStatus()
    }

    private fun request(targets: List<OpenFile>) {
        closing.clear()
        closing.addAll(targets)
        drainClean()
    }

    /** Close as far as the first tab that has something to lose. */
    private fun drainClean() {
        while (closing.isNotEmpty()) {
            val file = closing.first()
            // The poll loop is up to a quarter of a second behind; ask the
            // engine now rather than about a state that has already changed.
            file.refreshStatus()
            if (file.isDirty) return
            closing.removeAt(0)
            close(indexOfPath(file.path))
        }
    }

    private fun rememberClosed(path: String) {
        closedPaths.remove(path)
        closedPaths.add(path)
        if (closedPaths.size > REOPEN_HISTORY) closedPaths.removeAt(0)
    }
}
