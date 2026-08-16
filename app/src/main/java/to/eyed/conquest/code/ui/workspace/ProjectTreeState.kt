package to.eyed.conquest.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import to.eyed.conquest.code.core.GitignoredFiles
import to.eyed.conquest.code.core.ProjectEntry
import to.eyed.conquest.code.core.ProjectSession

/**
 * One visible line of the project tree: an entry, its indent depth, and the
 * git status it was last flattened with.
 *
 * Status lives *in the row* rather than being looked up while drawing: rows
 * are built off the main thread and the panel is virtualised, so a per-row map
 * read would otherwise run again for every visible row on every scroll frame.
 */
data class ProjectTreeRow(
    val entry: ProjectEntry,
    val depth: Int,
    val status: GitFileStatus = GitFileStatus.None,
)

/**
 * A flattened tree plus the status version it was flattened at, so the panel
 * can tell whether the rows it is holding already reflect the latest statuses.
 */
data class ProjectTreeSnapshot(
    val rows: List<ProjectTreeRow>,
    val statusVersion: Long,
)

/**
 * Flattens a project's worktree into the rows the panel draws.
 *
 * Only expanded directories are ever queried, so the engine's tree stays
 * lazy: collapsing a directory drops its children from the cache, and a
 * directory the worktree hasn't scanned yet ([ProjectEntry.isUnloaded], or an
 * ignored one) is expanded on demand through the engine.
 *
 * [rebuild] does the JNI reads and JSON parsing, so callers should run it off
 * the main thread and then publish the result with [publish]. [restatus] is
 * the cheap half: statuses usually arrive *after* the tree and change far more
 * often than its shape, so re-colouring never re-reads the worktree.
 */
class ProjectTreeState(
    private val session: ProjectSession,
    /** How gitignored entries are treated (listed, dimmed, or left out). */
    private val gitignoredFiles: GitignoredFiles = GitignoredFiles.Dimmed,
    /** Per-path git status; [GitStatusSource.Absent] leaves every row plain. */
    private val gitStatus: GitStatusSource = GitStatusSource.Absent,
) {
    /** Project-relative paths of the expanded directories. */
    private val expanded = mutableStateListOf<String>()

    /** Engine snapshot version the current rows were built from. */
    var version by mutableLongStateOf(-1L)
        private set

    /** Status-source version the current rows were coloured from. */
    var statusVersion by mutableLongStateOf(-1L)
        private set

    var rows by mutableStateOf<List<ProjectTreeRow>>(emptyList())
        private set

    fun isExpanded(path: String): Boolean = expanded.contains(path)

    /**
     * Expand or collapse a directory. Expanding a directory the worktree
     * deferred also asks the engine to scan it; its contents then arrive as a
     * version bump.
     */
    fun toggle(entry: ProjectEntry) {
        if (!entry.isDir) return
        if (expanded.remove(entry.path)) return
        expanded.add(entry.path)
        if (entry.isUnloaded || entry.isIgnored) {
            session.expand(entry.path)
        }
    }

    /** Walk the expanded directories, reading each one's children. Blocking. */
    fun rebuild(): ProjectTreeSnapshot {
        // Read the status table once per flatten, not once per row.
        val statuses = gitStatus.snapshot()
        val rows = mutableListOf<ProjectTreeRow>()
        appendChildren("", 0, rows, statuses)
        return ProjectTreeSnapshot(rows, statuses.version)
    }

    /**
     * Re-colour [current] (normally [rows], read on the main thread before
     * handing it over) from the latest statuses, leaving the tree's shape —
     * and therefore the LazyColumn's keys and layout — alone. Blocking.
     *
     * Returns the *same* list instance when nothing changed, so a status bump
     * that doesn't touch anything visible costs no recomposition and no
     * flicker; otherwise one fresh row per line, off the main thread.
     */
    fun restatus(current: List<ProjectTreeRow>): ProjectTreeSnapshot {
        val statuses = gitStatus.snapshot()
        var changed = false
        for (row in current) {
            if (row.status != statuses.statusOf(row.entry.path)) {
                changed = true
                break
            }
        }
        if (!changed) return ProjectTreeSnapshot(current, statuses.version)
        return ProjectTreeSnapshot(
            current.map { it.copy(status = statuses.statusOf(it.entry.path)) },
            statuses.version,
        )
    }

    fun publish(version: Long, snapshot: ProjectTreeSnapshot) {
        this.version = version
        this.statusVersion = snapshot.statusVersion
        this.rows = snapshot.rows
    }

    private fun appendChildren(
        dir: String,
        depth: Int,
        into: MutableList<ProjectTreeRow>,
        statuses: GitStatusSnapshot,
    ) {
        // Guard against a pathological tree (or a symlink loop the worktree
        // followed) turning a UI refresh into an unbounded walk.
        if (depth > MAX_DEPTH) return
        for (entry in session.children(dir)) {
            if (entry.isIgnored && gitignoredFiles == GitignoredFiles.Hide) continue
            // Directories carry whatever roll-up the engine published for them
            // and nothing more: a summary invented from the children we happen
            // to have scanned would be wrong for the ones we haven't.
            into += ProjectTreeRow(entry, depth, statuses.statusOf(entry.path))
            if (entry.isDir && expanded.contains(entry.path)) {
                appendChildren(entry.path, depth + 1, into, statuses)
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 64
    }
}
