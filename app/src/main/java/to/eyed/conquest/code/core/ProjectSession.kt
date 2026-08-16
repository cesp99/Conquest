package to.eyed.conquest.code.core

import org.json.JSONArray

/** One entry in a project's worktree, as the engine reports it. */
data class ProjectEntry(
    /** Path relative to the project root, '/'-separated. Empty for the root. */
    val path: String,
    val name: String,
    val isDir: Boolean,
    /** Ignored by git. Zed only scans an ignored directory once it's expanded. */
    val isIgnored: Boolean,
    /** A dot-file, or inside a dot-directory. */
    val isHidden: Boolean,
    /** A directory whose children haven't been scanned yet — [ProjectSession.expand] first. */
    val isUnloaded: Boolean,
    /** Size in bytes; 0 for directories. */
    val size: Long,
)

/** One fuzzy file-finder hit. */
data class FileMatch(
    /** Path relative to the project root, '/'-separated. */
    val path: String,
    val name: String,
    /** UTF-16 offsets into [path] that matched, for highlighting. */
    val positions: List<Int>,
)

/**
 * Handle for one open project (a Zed worktree inside the engine).
 *
 * Scanning happens on the engine's own thread; nothing here blocks on it.
 * [version] is the staleness token: when it changes, cached children are
 * stale and worth re-reading. Callers drive that polling — see
 * [to.eyed.conquest.code.ui.workspace.ProjectPanel].
 */
class ProjectSession(absolutePath: String) {
    val id: Long = CoreBridge.openProject(absolutePath)

    val version: Long
        get() = CoreBridge.projectVersion(id)

    val scanComplete: Boolean
        get() = CoreBridge.projectScanComplete(id)

    val error: String?
        get() = CoreBridge.projectError(id)

    val rootName: String
        get() = CoreBridge.projectRootName(id).orEmpty()

    /**
     * Direct children of [dir] (project-relative, "" for the root), already
     * sorted directories-first by the engine.
     */
    fun children(dir: String): List<ProjectEntry> {
        val json = JSONArray(CoreBridge.projectEntries(id, dir))
        return List(json.length()) { index ->
            val entry = json.getJSONObject(index)
            ProjectEntry(
                path = entry.getString("path"),
                name = entry.getString("name"),
                isDir = entry.getBoolean("is_dir"),
                isIgnored = entry.getBoolean("is_ignored"),
                isHidden = entry.getBoolean("is_hidden"),
                isUnloaded = entry.getBoolean("is_unloaded"),
                size = entry.getLong("size"),
            )
        }
    }

    /**
     * Fuzzy-match [query] against the project's files, best first. An empty
     * query lists files. **Blocking** — call from
     * [kotlinx.coroutines.Dispatchers.Default].
     */
    fun findFiles(query: String, limit: Int = 50): List<FileMatch> {
        val json = JSONArray(CoreBridge.projectFindFiles(id, query, limit.toLong()))
        return List(json.length()) { index ->
            val entry = json.getJSONObject(index)
            val positions = entry.getJSONArray("positions")
            FileMatch(
                path = entry.getString("path"),
                name = entry.getString("name"),
                positions = List(positions.length()) { positions.getInt(it) },
            )
        }
    }

    /** Ask the engine to scan a directory it deferred. Asynchronous. */
    fun expand(dir: String): Boolean = CoreBridge.expandDirectory(id, dir)

    /** Absolute path of a project-relative entry, or null if it isn't one. */
    fun absolutePathOf(path: String): String? = CoreBridge.projectEntryPath(id, path)

    fun close(): Boolean = CoreBridge.closeProject(id)
}
