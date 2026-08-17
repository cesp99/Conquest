package to.eyed.conquest.code.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * What to search for. One shape drives both searches, so a search bar's
 * toggles can be handed to either without translation; [includeIgnored],
 * [includeGlobs] and [excludeGlobs] are simply ignored when searching a
 * buffer.
 */
data class SearchQuery(
    val query: String = "",
    /** Treat [query] as a regular expression rather than literal text. */
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    /** Project search: also search files git ignores. */
    val includeIgnored: Boolean = false,
    /** Project search: only these paths. Empty means every file. */
    val includeGlobs: List<String> = emptyList(),
    /** Project search: never these paths. Applied after [includeGlobs]. */
    val excludeGlobs: List<String> = emptyList(),
) {
    internal fun toJson(): String = JSONObject()
        .put("query", query)
        .put("regex", regex)
        .put("case_sensitive", caseSensitive)
        .put("whole_word", wholeWord)
        .put("include_ignored", includeIgnored)
        .put("include_globs", JSONArray(includeGlobs))
        .put("exclude_globs", JSONArray(excludeGlobs))
        .toString()

    /**
     * Why this query won't compile, or null if it will. Only a regex can
     * fail; show it next to the field rather than showing no results.
     */
    fun error(): String? = CoreBridge.searchQueryError(toJson())
}

/** One hit in a buffer, in the coordinates the editor works in. */
data class BufferMatch(
    /** Byte offsets into the buffer, on character boundaries. */
    val start: Long,
    val end: Long,
    /** 0-based row and *byte* column — what [CoreBridge.pointToOffset] takes. */
    val row: Int,
    val column: Int,
)

/** Everything [searchBuffer] found. */
data class BufferSearch(
    val matches: List<BufferMatch>,
    /**
     * Matches in the buffer in all. Larger than `matches.size` when the limit
     * bit, which is what lets the bar say "3 of 12 000" honestly.
     */
    val total: Int,
) {
    val truncated: Boolean get() = total > matches.size

    companion object {
        val Empty = BufferSearch(emptyList(), 0)
    }
}

/**
 * Every match of [query] in a buffer, ascending. Returns [BufferSearch.Empty]
 * for an unknown buffer or a query that doesn't compile — ask
 * [SearchQuery.error] to tell the two apart.
 *
 * Cheap enough to call on every keystroke of the query: the engine scans the
 * whole buffer in a few milliseconds even at 100k lines, which is why there is
 * no incremental variant of this and no result to keep between calls.
 */
fun searchBuffer(bufferId: Long, query: SearchQuery, limit: Int = 10_000): BufferSearch {
    val flat = CoreBridge.bufferSearch(bufferId, query.toJson(), limit.toLong())
    if (flat == null || flat.isEmpty()) return BufferSearch.Empty
    // Element 0 is the total; the rest are groups of four.
    val matches = ArrayList<BufferMatch>((flat.size - 1) / 4)
    var index = 1
    while (index + 3 < flat.size) {
        matches.add(
            BufferMatch(
                start = flat[index],
                end = flat[index + 1],
                row = flat[index + 2].toInt(),
                column = flat[index + 3].toInt(),
            )
        )
        index += 4
    }
    return BufferSearch(matches, flat[0].toInt())
}

/** How far a project search has got. */
enum class ProjectSearchState {
    Running,
    Done,

    /** Cancelled, superseded by a newer search, or an id the engine forgot. */
    Cancelled;

    internal companion object {
        fun parse(name: String): ProjectSearchState = when (name) {
            "running" -> Running
            "done" -> Done
            else -> Cancelled
        }
    }
}

/**
 * One hit in a project search, shaped for a results panel: it carries the line
 * it lives on, so the panel draws a result without opening the file.
 */
data class ProjectSearchMatch(
    /**
     * 1-based, for display. To put a cursor on it, open the file and ask for
     * `CoreBridge.pointToOffset(bufferId, line - 1, column)`.
     */
    val line: Int,
    /**
     * Byte column of the match in the *whole* line — what
     * [CoreBridge.pointToOffset] wants. Equal to [start] unless [text] was
     * windowed.
     */
    val column: Int,
    /** Byte range of the match within [text]. Kotlin wants [startUtf16]. */
    val start: Int,
    val end: Int,
    /** The same range as UTF-16 offsets: how to index [text] to highlight it. */
    val startUtf16: Int,
    val endUtf16: Int,
    /** The line, windowed around the match if it was very long. */
    val text: String,
    /** [text] starts / ends mid-line, so draw an ellipsis. */
    val clippedStart: Boolean,
    val clippedEnd: Boolean,
)

/** Every hit in one file. */
data class ProjectSearchFile(
    /** Project-relative, '/'-separated — the same spelling [ProjectEntry] uses. */
    val path: String,
    val matches: List<ProjectSearchMatch>,
    /** Matches in the file in all; larger than `matches.size` when capped. */
    val matchCount: Int,
)

/**
 * A snapshot of a project search: the counters, and the files added since the
 * caller last read.
 */
data class ProjectSearchResults(
    val state: ProjectSearchState,
    val version: Long,
    /** Set only for a failure that stopped the search, not a skipped file. */
    val error: String?,
    val filesSearched: Int,
    /** Files the worktree offered, for a progress bar. */
    val totalFiles: Int,
    /** Files with a match in all — not just in [newFiles]. */
    val fileCount: Int,
    val matchCount: Int,
    /** One of the engine's caps bit, so this is not the whole truth. */
    val truncated: Boolean,
    /** Files found since the offset the caller asked from. */
    val newFiles: List<ProjectSearchFile>,
)

/**
 * A running search over a project's worktree.
 *
 * This never blocks: the engine does the reading on a thread of its own and
 * publishes [version], which moves whenever there is something new. Poll it
 * the way [ProjectSession.version] is polled, and call [poll] when it changes
 * — results only ever grow, so each call hands back what is new and the caller
 * appends.
 *
 * Starting a search cancels whatever was running for the same project, so a
 * search bar can simply start a new one on every change of the query.
 */
class ProjectSearchSession(project: ProjectSession, query: SearchQuery) {
    /** -1 if the project is unknown or the query didn't compile. */
    val id: Long = CoreBridge.projectSearchStart(project.id, query.toJson())

    /** How many files the caller has already taken. */
    private var taken = 0

    /**
     * Staleness token, of the same shape as [ProjectSession.version]: it moves
     * when there is something new to [poll]. 0 until the first results.
     */
    val version: Long
        get() = if (id < 0) 0 else CoreBridge.projectSearchVersion(id)

    /**
     * The counters, plus every file found since the last call. Advances the
     * read cursor, so calling twice in a row gives no files the second time.
     */
    fun poll(): ProjectSearchResults {
        if (id < 0) {
            return ProjectSearchResults(
                state = ProjectSearchState.Cancelled,
                version = 0,
                error = null,
                filesSearched = 0,
                totalFiles = 0,
                fileCount = 0,
                matchCount = 0,
                truncated = false,
                newFiles = emptyList(),
            )
        }
        val json = JSONObject(CoreBridge.projectSearchResults(id, taken.toLong()))
        val files = json.optJSONArray("files") ?: JSONArray()
        taken = json.optInt("from_file", taken) + files.length()
        return ProjectSearchResults(
            state = ProjectSearchState.parse(json.optString("state")),
            version = json.optLong("version"),
            error = if (json.isNull("error")) null else json.optString("error"),
            filesSearched = json.optInt("files_searched"),
            totalFiles = json.optInt("total_files"),
            fileCount = json.optInt("file_count"),
            matchCount = json.optInt("match_count"),
            truncated = json.optBoolean("truncated"),
            newFiles = List(files.length()) { index -> parseFile(files.getJSONObject(index)) },
        )
    }

    /** Stop the search. Safe to call more than once. */
    fun cancel(): Boolean = id >= 0 && CoreBridge.projectSearchCancel(id)

    private fun parseFile(file: JSONObject): ProjectSearchFile {
        val matches = file.getJSONArray("matches")
        return ProjectSearchFile(
            path = file.getString("path"),
            matchCount = file.getInt("match_count"),
            matches = List(matches.length()) { index ->
                val match = matches.getJSONObject(index)
                ProjectSearchMatch(
                    line = match.getInt("line"),
                    column = match.getInt("column"),
                    start = match.getInt("start"),
                    end = match.getInt("end"),
                    startUtf16 = match.getInt("start_utf16"),
                    endUtf16 = match.getInt("end_utf16"),
                    text = match.getString("text"),
                    clippedStart = match.getBoolean("clipped_start"),
                    clippedEnd = match.getBoolean("clipped_end"),
                )
            },
        )
    }
}
