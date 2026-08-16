package to.eyed.conquest.code.core

import org.json.JSONArray
import org.json.JSONObject

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

/**
 * What git says about one path, reduced to what a project-panel row can show.
 *
 * A directory carries a rolled-up summary rather than an exact status — it can
 * hold a deletion and an addition at once — so on a directory [Modified] means
 * "something below changed" and [Added] means "everything below is new".
 */
enum class GitFileStatus {
    Modified,
    Added,
    Deleted,
    Renamed,
    Conflicted,
    Untracked,
    Ignored;

    internal companion object {
        /** The engine's snake_case names; anything unknown is ignored. */
        fun parse(name: String): GitFileStatus? = when (name) {
            "modified" -> Modified
            "added" -> Added
            "deleted" -> Deleted
            "renamed" -> Renamed
            "conflicted" -> Conflicted
            "untracked" -> Untracked
            "ignored" -> Ignored
            else -> null
        }
    }
}

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
    /**
     * Where the project lives on disk. The engine works in project-relative
     * paths, but a terminal has to start somewhere real.
     */
    val rootPath: String = absolutePath

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

    /**
     * Staleness token for [gitStatus], of the same shape as [version]: it
     * changes when the statuses change, and reading it is also what tells the
     * engine to go and refresh them. Nothing here waits on git — the engine
     * runs it on a thread of its own, debounced behind worktree changes — so
     * this is safe to poll from the UI loop.
     *
     * Stays 0 forever in builds with no Linux userland: there is no git to
     * ask, and that must look like a clean repository, not like a failure.
     */
    val gitStatusVersion: Long
        get() = CoreBridge.gitStatusVersion(id)

    /**
     * Git status by project-relative path, ready to colour rows with.
     *
     * Ancestor directories of a changed file are present too, with a rolled-up
     * status, so a row lookup is a single map hit whether it is a file or a
     * directory. Empty when the project is not in a repository, or when there
     * is no userland to run git in.
     */
    fun gitStatus(): Map<String, GitFileStatus> {
        val json = JSONObject(CoreBridge.gitStatus(id))
        val statuses = HashMap<String, GitFileStatus>(json.length())
        for (path in json.keys()) {
            GitFileStatus.parse(json.getString(path))?.let { statuses[path] = it }
        }
        return statuses
    }

    /** Ask the engine to scan a directory it deferred. Asynchronous. */
    fun expand(dir: String): Boolean = CoreBridge.expandDirectory(id, dir)

    /** Absolute path of a project-relative entry, or null if it isn't one. */
    fun absolutePathOf(path: String): String? = CoreBridge.projectEntryPath(id, path)

    fun close(): Boolean = CoreBridge.closeProject(id)
}
