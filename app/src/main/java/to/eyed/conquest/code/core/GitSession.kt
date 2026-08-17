package to.eyed.conquest.code.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The git side of a project: what has changed, what is staged, and the four
 * things the panel can do about it.
 *
 * Same shape as [ProjectSearchSession] and the worktree itself — a cheap
 * counter to poll, and a blocking read taken only when the counter moves. The
 * counter here is [ProjectSession.gitStatusVersion], the very one the project
 * panel already polls for its colours: one `git status` inside the userland
 * feeds both, and a panel open beside the tree costs nothing extra.
 *
 * Everything below is empty and quiet in a build with no Linux userland. That
 * is not this class's decision to explain — the panel is simply not offered
 * there (`isGitPanelSupported`), because an editor should not show a git panel
 * it can never fill.
 */
class GitSession(private val project: ProjectSession) {
    /**
     * Staleness token, of the same shape as [ProjectSession.version]: it moves
     * whenever anything git says about the project changes, staging included.
     * Reading it is also what schedules a refresh, so it must keep being read.
     */
    val version: Long
        get() = project.gitStatusVersion

    /**
     * Everything the panel draws. Reads a cache the engine filled on a thread
     * of its own — it never waits for git — but it does parse JSON, so read it
     * when [version] has moved rather than every frame.
     */
    fun state(): GitPanelState = GitPanelState.parse(CoreBridge.gitChanges(project.id))

    /**
     * Stage those paths, deletions included. Null when it worked, and the
     * reason — usually git's own sentence — when it did not.
     *
     * **Blocking** — call it from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun stage(paths: List<String>): String? =
        CoreBridge.gitStage(project.id, JSONArray(paths).toString())

    /** Take those paths back out of the index. **Blocking**. */
    fun unstage(paths: List<String>): String? =
        CoreBridge.gitUnstage(project.id, JSONArray(paths).toString())

    /**
     * **Destructive.** Throw away every uncommitted change to those paths.
     *
     * A path the last commit has goes back to what the commit holds. A path it
     * does not — untracked, newly staged, or the new name of a rename — has
     * nowhere to go back to, so it is moved to the app's trash instead of being
     * deleted; [GitChange.inHead] is which of the two will happen, and the
     * confirmation should say so. A rename is both at once: [GitChange.original]
     * comes back from the commit and the new name goes to the trash.
     *
     * A row the engine cannot explain — a conflict above all, where discarding
     * would keep one side of a merge and say nothing — comes back as a refusal
     * with the reason in it, and nothing is touched.
     *
     * **Ask the user first, naming the files.** Nothing below here asks.
     * **Blocking**.
     */
    fun discard(paths: List<String>): String? =
        CoreBridge.gitDiscard(project.id, JSONArray(paths).toString())

    /**
     * Commit what is staged. An empty message is refused rather than making an
     * empty commit, and nothing is staged implicitly: committing with an empty
     * index comes back with git's own "nothing added to commit".
     *
     * The other refusal worth knowing is "unable to auto-detect email address",
     * which means the userland's git has no identity yet. It is fixed in the
     * terminal, once, with `git config --global user.email`; the panel shows
     * git's words rather than paraphrasing them. **Blocking**.
     */
    fun commit(message: String): String? = CoreBridge.gitCommit(project.id, message)
}

/** Which branch the repository is on, and how far it has drifted. */
data class GitBranch(
    /** Null on a detached HEAD, which is on no branch. */
    val name: String?,
    /** Commits this branch has that its upstream does not, and the reverse. */
    val ahead: Int = 0,
    val behind: Int = 0,
    /** The branch exists but has no commits yet — a repository just created. */
    val unborn: Boolean = false,
)

/**
 * One changed file.
 *
 * [staged] and [unstaged] are the two halves of git's status pair, and a file
 * can have both: editing a file, staging it, then editing it again puts it in
 * both sections of the panel with different contents in each — which is exactly
 * what git means and what a single rolled-up status cannot say.
 */
data class GitChange(
    /**
     * Project-relative, '/'-separated — [ProjectEntry.path]'s spelling.
     *
     * Ends in '/' when git collapsed a whole new directory into one record,
     * which is what it does with every file in a directory it has never seen.
     * That row is a folder, and [isDirectory] is how the panel knows.
     */
    val path: String,
    val staged: GitFileStatus?,
    val unstaged: GitFileStatus?,
    /** A merge conflict: in neither section, and nothing to stage until it is resolved. */
    val conflicted: Boolean,
    /**
     * The last commit has a version of **this path**. False for an untracked or
     * newly staged file, and false for the destination of a rename, whose old
     * name is the one the commit holds — discarding those *trashes* rather than
     * restores, which is the difference the confirmation has to state.
     */
    val inHead: Boolean,
    /**
     * What the last commit calls this file, when it has been renamed or copied.
     * Discarding a rename cannot be done without it: the old name is restored
     * and the new one goes to the trash.
     */
    val original: String? = null,
) {
    /** One record for a whole new directory — `?? newdir/`. */
    val isDirectory: Boolean get() = path.endsWith('/')

    /**
     * The row's label. Keeps the trailing slash for a directory: "src" and
     * "src/" are two different promises when the next tap discards one of them.
     */
    val name: String get() = path.trimEnd('/').substringAfterLast('/') + if (isDirectory) "/" else ""

    val directory: String get() = path.trimEnd('/').substringBeforeLast('/', "")
}

/** A snapshot of everything the git panel draws. */
data class GitPanelState(
    /**
     * A status run has completed. Until it has, "no changes" is not yet true —
     * it is unknown, and the panel says so rather than claiming a clean tree.
     */
    val scanned: Boolean = false,
    /**
     * git actually ran. False and [scanned] both true means it could not be
     * run at all — no Linux userland, or no git inside it — which is *not*
     * the same as a clean tree, though both arrive as an empty list.
     */
    val ran: Boolean = false,
    /** The project is inside a git repository at all. */
    val hasRepo: Boolean = false,
    val branch: GitBranch? = null,
    val entries: List<GitChange> = emptyList(),
) {
    val staged: List<GitChange> get() = entries.filter { it.staged != null }
    val unstaged: List<GitChange> get() = entries.filter { it.unstaged != null }
    val conflicts: List<GitChange> get() = entries.filter { it.conflicted }

    val isClean: Boolean get() = entries.isEmpty()

    internal companion object {
        fun parse(json: String): GitPanelState {
            val root = JSONObject(json)
            val entries = root.optJSONArray("entries") ?: JSONArray()
            val branch = root.optJSONObject("branch")
            return GitPanelState(
                scanned = root.optBoolean("scanned"),
                ran = root.optBoolean("ran"),
                hasRepo = root.optBoolean("has_repo"),
                branch = branch?.let {
                    GitBranch(
                        name = if (it.isNull("name")) null else it.optString("name"),
                        ahead = it.optInt("ahead"),
                        behind = it.optInt("behind"),
                        unborn = it.optBoolean("unborn"),
                    )
                },
                entries = List(entries.length()) { index ->
                    val entry = entries.getJSONObject(index)
                    GitChange(
                        path = entry.getString("path"),
                        staged = status(entry, "staged"),
                        unstaged = status(entry, "unstaged"),
                        conflicted = entry.optBoolean("conflicted"),
                        inHead = entry.optBoolean("in_head"),
                        original = if (entry.isNull("original")) null else entry.getString("original"),
                    )
                },
            )
        }

        private fun status(entry: JSONObject, key: String): GitFileStatus? =
            if (entry.isNull(key)) null else GitFileStatus.parse(entry.getString(key))
    }
}

/** What happened to a run of rows, as the gutter paints it. */
enum class GitHunkKind { Added, Modified, Deleted }

/**
 * One difference between the buffer and the last commit.
 *
 * Rows are *buffer* rows and follow unsaved edits: the engine holds the file's
 * text at HEAD and re-diffs it against the live buffer, so a hunk stays under
 * the line it belongs to while you type.
 */
data class GitHunk(
    val kind: GitHunkKind,
    /** First row of the hunk, 0-based. */
    val startRow: Int,
    /**
     * One past its last row — and *equal* to [startRow] for a deletion, which
     * occupies no rows. A gutter draws a deletion as a mark on the boundary
     * above [startRow], not as a filled row.
     */
    val endRow: Int,
    /** How many rows the commit had here. 0 for an addition. */
    val oldRows: Int,
)

/** One run of rows and the commit that last touched it. */
data class BlameLine(
    /** Full commit hash; all zeroes for lines that are not committed yet. */
    val sha: String,
    /** First row of the run, 0-based, in the file **on disk**. */
    val startRow: Int,
    val rowCount: Int,
    val author: String,
    /** Seconds since the Unix epoch, or 0 for an uncommitted line. */
    val authorTime: Long,
    /** The commit's subject line. */
    val summary: String,
) {
    /** What git itself abbreviates a hash to. */
    val shortSha: String get() = sha.take(7)

    val isCommitted: Boolean get() = sha.any { it != '0' }
}

/** Blame for a whole file, or why there is none. */
data class FileBlame(
    val lines: List<BlameLine> = emptyList(),
    /** git's own message: not a repository, no such path in HEAD, no userland. */
    val error: String? = null,
) {
    /** The run covering [row], or null past the end of what git blamed. */
    fun at(row: Int): BlameLine? =
        lines.lastOrNull { row >= it.startRow && row < it.startRow + it.rowCount }
}

/**
 * The git view of one open buffer: the gutter's hunks, and blame.
 *
 * Keyed by buffer rather than by project because that is what it is about — the
 * file you are looking at — and because the engine already knows which project
 * a file is in.
 */
object GitDiff {
    /**
     * Staleness token for [hunks]; 0 while there is nothing to show. Poll it
     * like [GitSession.version]: reading it is what schedules the diff, and it
     * never waits for one.
     */
    fun hunksVersion(bufferId: Long): Long = CoreBridge.gitHunksVersion(bufferId)

    /**
     * The hunks, ascending by row. Reads a cache — it takes the engine's buffer
     * locks briefly and never runs git, so it is safe on the main thread,
     * though there is no reason to call it unless [hunksVersion] has moved.
     */
    fun hunks(bufferId: Long): List<GitHunk> {
        val flat = CoreBridge.gitHunks(bufferId)
        val hunks = ArrayList<GitHunk>(flat.size / 4)
        var index = 0
        while (index + 3 < flat.size) {
            hunks.add(
                GitHunk(
                    kind = KINDS.getOrElse(flat[index]) { GitHunkKind.Modified },
                    startRow = flat[index + 1],
                    endRow = flat[index + 2],
                    oldRows = flat[index + 3],
                )
            )
            index += 4
        }
        return hunks
    }

    /**
     * Who last touched each run of rows. **Blocking and uncached** — it runs
     * git every time — so call it when the user asks for blame, from
     * [kotlinx.coroutines.Dispatchers.IO], and never on a poll loop.
     */
    fun blame(bufferId: Long): FileBlame {
        val root = JSONObject(CoreBridge.gitBlame(bufferId))
        if (!root.isNull("error")) return FileBlame(error = root.getString("error"))
        val entries = root.optJSONArray("entries") ?: JSONArray()
        return FileBlame(
            lines = List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                BlameLine(
                    sha = entry.getString("sha"),
                    startRow = entry.getInt("start_row"),
                    rowCount = entry.getInt("row_count"),
                    author = entry.optString("author"),
                    authorTime = entry.optLong("author_time"),
                    summary = entry.optString("summary"),
                )
            }
        )
    }

    /** Index order matches the engine's, which is what the ints mean. */
    private val KINDS = GitHunkKind.entries.toTypedArray()
}
