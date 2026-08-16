package to.eyed.conquest.code.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import to.eyed.conquest.code.core.GitignoredFiles
import to.eyed.conquest.code.core.ProjectEntry
import to.eyed.conquest.code.core.ProjectSession

/** One visible line of the project tree: an entry plus its indent depth. */
data class ProjectTreeRow(val entry: ProjectEntry, val depth: Int)

/**
 * Flattens a project's worktree into the rows the panel draws.
 *
 * Only expanded directories are ever queried, so the engine's tree stays
 * lazy: collapsing a directory drops its children from the cache, and a
 * directory the worktree hasn't scanned yet ([ProjectEntry.isUnloaded], or an
 * ignored one) is expanded on demand through the engine.
 *
 * [rebuild] does the JNI reads and JSON parsing, so callers should run it off
 * the main thread and then publish the result with [publish].
 */
class ProjectTreeState(
    private val session: ProjectSession,
    /** How gitignored entries are treated (listed, dimmed, or left out). */
    private val gitignoredFiles: GitignoredFiles = GitignoredFiles.Dimmed,
) {
    /** Project-relative paths of the expanded directories. */
    private val expanded = mutableStateListOf<String>()

    /** Engine snapshot version the current rows were built from. */
    var version by mutableLongStateOf(-1L)
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
    fun rebuild(): List<ProjectTreeRow> {
        val rows = mutableListOf<ProjectTreeRow>()
        appendChildren("", 0, rows)
        return rows
    }

    fun publish(version: Long, rows: List<ProjectTreeRow>) {
        this.version = version
        this.rows = rows
    }

    private fun appendChildren(dir: String, depth: Int, into: MutableList<ProjectTreeRow>) {
        // Guard against a pathological tree (or a symlink loop the worktree
        // followed) turning a UI refresh into an unbounded walk.
        if (depth > MAX_DEPTH) return
        for (entry in session.children(dir)) {
            if (entry.isIgnored && gitignoredFiles == GitignoredFiles.Hide) continue
            into += ProjectTreeRow(entry, depth)
            if (entry.isDir && expanded.contains(entry.path)) {
                appendChildren(entry.path, depth + 1, into)
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 64
    }
}
