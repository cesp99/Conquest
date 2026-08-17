package to.eyed.conquest.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.ui.editor.EditorState
import to.eyed.conquest.code.ui.media.MediaKind

/** How many closed files Ctrl+Shift+T can walk back through. */
private const val REOPEN_HISTORY = 24

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
    /** The file on disk, for a tab the engine never opened. */
    val absolutePath: String? = null,
) {
    val session: BufferSession? get() = editor?.session

    val name: String = path.substringAfterLast('/')

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
        if (index in _tabs.indices) activeIndex = index
    }

    /** Move [delta] tabs along, wrapping — what Ctrl+Tab is expected to do. */
    fun selectRelative(delta: Int) {
        if (_tabs.isEmpty()) return
        val size = _tabs.size
        activeIndex = ((activeIndex + delta) % size + size) % size
    }

    /** Add a tab (or select the existing one) and make it active. */
    fun open(file: OpenFile) {
        val existing = indexOfPath(file.path)
        if (existing >= 0) {
            activeIndex = existing
            return
        }
        _tabs.add(file)
        activeIndex = _tabs.lastIndex
        closedPaths.remove(file.path)
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
        rememberClosed(file.path)
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
