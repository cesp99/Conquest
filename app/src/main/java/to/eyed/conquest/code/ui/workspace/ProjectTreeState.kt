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

    /**
     * Bumped whenever the tree's *shape* could change — i.e. by a toggle.
     *
     * A re-colour pass reads the rows on the main thread, does its work on
     * another, and publishes; if the user expanded a directory in that window,
     * publishing the old rows would leave the panel showing an expanded
     * chevron with no children under it, and neither version counter would
     * ever fire again to correct it. [publish] uses this to drop a result that
     * was computed against a shape nobody is looking at any more.
     */
    var shape by mutableLongStateOf(0L)
        private set

    /**
     * The row the keyboard is on, as a project-relative path, or null when the
     * panel has never been driven from the keyboard.
     *
     * Separate from the *open* file the panel highlights: they are usually the
     * same row and occasionally not, exactly as in Zed, where moving the
     * selection through the tree doesn't open anything until you ask.
     */
    var selected by mutableStateOf<String?>(null)
        private set

    /**
     * A path [reveal] was asked for that isn't on screen yet.
     *
     * Revealing a file inside a directory the worktree hasn't scanned can't
     * finish in one pass: the engine is asked to scan, and the row appears a
     * snapshot or two later. The panel scrolls to it when it does, and clears
     * this with [revealed].
     */
    var pendingReveal by mutableStateOf<String?>(null)
        private set

    fun isExpanded(path: String): Boolean = expanded.contains(path)

    /** The row the selection is on, if it is still in the tree. */
    val selectedRow: ProjectTreeRow?
        get() = selected?.let { path -> rows.firstOrNull { it.entry.path == path } }

    fun select(path: String?) {
        selected = path
    }

    /**
     * Move the selection [delta] visible rows, without wrapping — the ends of
     * a file tree are meaningful places to be, and Zed's panel stops there too.
     * Selects the first (or last) row when nothing is selected yet.
     */
    fun moveSelection(delta: Int) {
        if (rows.isEmpty()) return
        val current = rows.indexOfFirst { it.entry.path == selected }
        selected = when {
            current < 0 -> if (delta > 0) rows.first().entry.path else rows.last().entry.path
            else -> rows[(current + delta).coerceIn(0, rows.lastIndex)].entry.path
        }
    }

    /** Jump the selection to the first or last visible row. */
    fun selectEdge(last: Boolean) {
        if (rows.isEmpty()) return
        selected = if (last) rows.last().entry.path else rows.first().entry.path
    }

    /**
     * Expand or collapse a directory. Expanding a directory the worktree
     * deferred also asks the engine to scan it; its contents then arrive as a
     * version bump.
     */
    fun toggle(entry: ProjectEntry) {
        if (!entry.isDir) return
        if (isExpanded(entry.path)) collapse(entry.path) else expand(entry)
    }

    /** Expand a directory. Returns false if it was already open, or is a file. */
    fun expand(entry: ProjectEntry): Boolean {
        if (!entry.isDir || expanded.contains(entry.path)) return false
        shape += 1
        expanded.add(entry.path)
        if (entry.isUnloaded || entry.isIgnored) {
            session.expand(entry.path)
        }
        return true
    }

    /** Collapse a directory. Returns false if it wasn't open. */
    fun collapse(path: String): Boolean {
        if (!expanded.remove(path)) return false
        shape += 1
        return true
    }

    /** Collapse everything, back to the project's top level. */
    fun collapseAll(): Boolean {
        if (expanded.isEmpty()) return false
        shape += 1
        expanded.clear()
        return true
    }

    /**
     * Every directory "expand all" should open: the ones the worktree has
     * already scanned, walked breadth-first so the shallow ones are taken
     * first if [limit] runs out. **Blocking** — one engine read per directory,
     * so callers run it off the main thread and hand the result to [expandAll].
     *
     * Directories the worktree deferred ([ProjectEntry.isUnloaded], and
     * gitignored ones) are left closed rather than triggering a scan of
     * everything: `node_modules` is exactly the thing "expand all" must not
     * pull into memory, and it is one tap away for anyone who does want it.
     */
    fun expandableDirectories(limit: Int = MAX_EXPANDED): List<String> {
        val found = mutableListOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add("" to 0)
        while (queue.isNotEmpty() && found.size < limit) {
            val (dir, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) continue
            for (entry in session.children(dir)) {
                if (!entry.isDir || entry.isUnloaded || entry.isIgnored) continue
                if (found.size >= limit) break
                found += entry.path
                queue.add(entry.path to depth + 1)
            }
        }
        return found
    }

    /** Open [paths] — the result of [expandableDirectories]. */
    fun expandAll(paths: List<String>) {
        if (paths.isEmpty()) return
        shape += 1
        for (path in paths) if (!expanded.contains(path)) expanded.add(path)
    }

    /**
     * Open everything above [path] and put the selection on it — "reveal the
     * active file", and where the tree lands after a file operation.
     *
     * Ancestors are handed to the engine unconditionally: expanding a
     * directory it has already scanned is a no-op there, and the alternative
     * is looking each ancestor up first just to decide not to ask.
     */
    fun reveal(path: String) {
        if (path.isEmpty()) return
        shape += 1
        var prefix = ""
        for (part in path.split('/').dropLast(1)) {
            prefix = if (prefix.isEmpty()) part else "$prefix/$part"
            if (!expanded.contains(prefix)) expanded.add(prefix)
            session.expand(prefix)
        }
        selected = path
        pendingReveal = path
    }

    /** The panel has scrolled to [pendingReveal]; stop watching for it. */
    fun revealed() {
        pendingReveal = null
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

    /**
     * Install a snapshot. [shapeWhenComputed] is the [shape] the caller saw
     * before it started; a mismatch means the tree was expanded or collapsed
     * meanwhile and this result describes a tree that no longer exists, so it
     * is dropped rather than painted.
     */
    fun publish(version: Long, snapshot: ProjectTreeSnapshot, shapeWhenComputed: Long = shape) {
        if (shapeWhenComputed != shape) return
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

        /**
         * Ceiling on what one "expand all" will open. A project with more
         * directories than this is one where expanding everything was never
         * the useful answer, and the flattening cost is linear in what it
         * opens.
         */
        const val MAX_EXPANDED = 2_000
    }
}

/**
 * The row Zed's sticky headers are computed *for*: the first visible row that
 * is guaranteed to sit below the pinned stack of its own ancestors. The stack
 * itself is that row's ancestor chain.
 *
 * [drifting] is the push-off: the anchor is the last row of the deepest pinned
 * directory and is about to slide under the stack, so the stack's last row
 * rides up with the anchor's bottom edge instead of sitting in its slot
 * (`StickyAnchor.drifting`, ui/src/components/sticky_items.rs:279-283).
 */
internal data class StickyAnchor(
    /** Index into the visible depths handed to [findStickyAnchor]. */
    val localIndex: Int,
    val drifting: Boolean,
)

/**
 * Zed's `find_sticky_anchor` (ui/src/components/sticky_items.rs:285-316), on
 * our depth basis: walk the visible rows from the top; a row whose depth is
 * smaller than its position sits below a fully-formed stack of its ancestors,
 * so it anchors. Before that, a row whose *next* sibling outdents by exactly
 * one is the last child of the deepest pinned directory — it anchors at its
 * own slot (`depth == ix`), or one slot early and still drifting
 * (`depth == ix + 1`).
 *
 * [visibleDepths] are the flattened rows' [ProjectTreeRow.depth]s from the
 * first visible row down.
 *
 * **The basis is one lower than Zed's, and that is not cosmetic.** Zed's list
 * begins with the worktree root at depth 0, so a top-level entry is depth 1
 * (`calculate_depth_and_difference` returns `depth + 1` for any entry whose
 * parent is visible, and the root is visible — project_panel.rs:5529-5553),
 * and the stack pinned for a row of depth `d` is `d` rows *including* that
 * root. Ours holds the root out of the list entirely — it is the permanent
 * header above it — so a top-level row is depth 0 and the stack for depth `d`
 * is `d` rows *without* the root. Every comparison below is between a row's
 * depth and its slot, and the stack covering those slots is shorter by the
 * same one row, so the geometry — which slots the stack covers, when its
 * deepest row starts drifting — comes out where Zed's does.
 *
 * What it costs is the choice of anchor. Fed the same viewport, this picks the
 * state Zed reaches one scroll row earlier, because Zed's root row occupies a
 * list slot that ours does not. At a fold boundary that shows: with rows `a/`,
 * `a/b/`, two files, `a/c/`, three files, and the viewport starting at the
 * first file, this pins `a` and `a/b` (drifting) over the two files they own,
 * while Zed pins root, `a` and `a/c` and covers `a/c`'s own row with the pin.
 * Feeding `depth + 1` here would buy Zed's choice and break the geometry that
 * makes it work: the stack we can draw is one row shorter than Zed's, so its
 * deepest pin would land one slot above the real row it is meant to cover, and
 * the panel would show that directory twice.
 */
internal fun findStickyAnchor(visibleDepths: List<Int>): StickyAnchor? {
    for (ix in visibleDepths.indices) {
        val depth = visibleDepths[ix]
        if (depth < ix) return StickyAnchor(ix, drifting = false)
        val nextDepth = visibleDepths.getOrNull(ix + 1) ?: continue
        if (nextDepth + 1 == depth && (depth == ix || depth == ix + 1)) {
            return StickyAnchor(ix, drifting = depth == ix + 1)
        }
    }
    return null
}

/**
 * The rows that pin for [anchorIndex]: its ancestor directories, outermost
 * first — Zed's `sticky_parents` (project_panel.rs:6824-6846). Zed rebuilds
 * the chain from path prefixes; the flattened list makes it a backward walk,
 * because the parent of a row at depth `d` is the nearest earlier row at depth
 * `d − 1` (the flatten is a strict depth-first walk, so nothing shallower can
 * intervene). One pass, integer compares only.
 */
internal fun stickyAncestorsOf(rows: List<ProjectTreeRow>, anchorIndex: Int): List<Int> {
    val anchor = rows.getOrNull(anchorIndex) ?: return emptyList()
    var want = anchor.depth - 1
    if (want < 0) return emptyList()
    val found = ArrayList<Int>(anchor.depth)
    var i = anchorIndex - 1
    while (i >= 0 && want >= 0) {
        if (rows[i].depth <= want) {
            found += i
            want = rows[i].depth - 1
        }
        i--
    }
    found.reverse()
    return found
}

/**
 * The push-off: how far up the deepest pinned row has been pushed, in pixels,
 * and never positive.
 *
 * Zed's `drifting_y_offset` (sticky_items.rs:179-186): while [drifting], the
 * bottom of the stack is held to the bottom edge of the anchor row — the last
 * child of the deepest pinned directory — so the stack slides out continuously
 * with the scroll instead of swapping when the boundary passes. It is 0 while
 * the anchor still sits in the stack's last slot, and goes negative as the
 * anchor scrolls up past it.
 *
 * [anchorOffsetPx] is the anchor row's top in viewport coordinates and
 * [rowHeightPx] the measured row pitch, so `offset + rowHeight` is Zed's
 * `anchor_top - scroll_top` and `rowHeight * pinnedCount` its
 * `sticky_area_height`.
 */
internal fun stickyDriftPx(
    anchorOffsetPx: Int,
    rowHeightPx: Int,
    pinnedCount: Int,
    drifting: Boolean,
): Int {
    if (!drifting) return 0
    return (anchorOffsetPx + rowHeightPx - rowHeightPx * pinnedCount).coerceAtMost(0)
}

/**
 * The indent-guide run the selection lights up, in `panel.indent_guide_active`.
 *
 * Zed's `find_active_indent_guide` (project_panel.rs:6724-6790): walk up from
 * the selected entry to the nearest *expanded* directory — the selection
 * itself when it is one — and the active guide is the one its children hang
 * from. Every ancestor of a visible row is expanded by construction, so the
 * walk here is one step: the row, else its parent, else the root — whose run
 * is the whole list, exactly as Zed's worktree root is the expanded ancestor
 * of a top-level selection.
 *
 * [level] is in the panel's rendered guide coordinates (a row at
 * [ProjectTreeRow.depth] `d` draws levels `0..d`, being drawn one level in
 * from the root row). Recomputed only when the selection or the tree's shape
 * moves, never per frame.
 */
internal data class ActiveGuideRun(
    val level: Int,
    /** First and last row index (inclusive) the active run spans. */
    val first: Int,
    val last: Int,
)

internal fun activeGuideRun(
    rows: List<ProjectTreeRow>,
    selected: String?,
    isExpanded: (String) -> Boolean,
): ActiveGuideRun? {
    if (selected == null) return null
    val index = rows.indexOfFirst { it.entry.path == selected }
    if (index < 0) return null
    val row = rows[index]
    val dirIndex: Int
    if (row.entry.isDir && isExpanded(row.entry.path)) {
        dirIndex = index
    } else {
        if (row.depth == 0) {
            // The parent is the root row above the list: its run is every row.
            return ActiveGuideRun(level = 0, first = 0, last = rows.lastIndex)
        }
        var i = index - 1
        while (i >= 0 && rows[i].depth >= row.depth) i--
        if (i < 0) return null
        dirIndex = i
    }
    val dirDepth = rows[dirIndex].depth
    var last = dirIndex
    while (last < rows.lastIndex && rows[last + 1].depth > dirDepth) last++
    // An expanded directory with nothing under it hangs no guide.
    if (last == dirIndex) return null
    return ActiveGuideRun(level = dirDepth + 1, first = dirIndex + 1, last = last)
}
