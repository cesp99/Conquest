package to.eyed.conquest.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.ui.editor.EditorState

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
    val editor: EditorState,
) {
    val session: BufferSession get() = editor.session

    val name: String = path.substringAfterLast('/')

    var isDirty by mutableStateOf(false)
        private set
    var hasDiskChange by mutableStateOf(false)
        private set
    var isDeleted by mutableStateOf(false)
        private set

    /**
     * Grammar the engine is highlighting with, for the status bar. Read once:
     * the language is chosen when the file is opened and doesn't change.
     */
    val language: String? = session.language

    /** Whether anything changed, so callers can skip needless work. */
    fun refreshStatus(): Boolean {
        val dirty = session.isDirty
        val disk = session.hasDiskChange
        val deleted = session.isFileDeleted
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
 */
class OpenFilesState {
    private val _tabs = mutableStateListOf<OpenFile>()
    val tabs: List<OpenFile> get() = _tabs

    var activeIndex by mutableIntStateOf(-1)
        private set

    val active: OpenFile? get() = _tabs.getOrNull(activeIndex)

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
    }

    /**
     * Close a tab and release its engine buffer. The neighbour to the left
     * becomes active, which is what every editor does and what keeps the
     * selection stable when closing several in a row.
     */
    fun close(index: Int) {
        val file = _tabs.getOrNull(index) ?: return
        _tabs.removeAt(index)
        file.session.close()
        activeIndex = when {
            _tabs.isEmpty() -> -1
            index <= activeIndex -> (activeIndex - 1).coerceAtLeast(0)
            else -> activeIndex.coerceAtMost(_tabs.lastIndex)
        }
    }

    fun refreshStatuses() {
        for (tab in _tabs) tab.refreshStatus()
    }
}
