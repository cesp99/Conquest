package to.eyed.conquest.code.ui.git

import to.eyed.conquest.code.core.Commit

/**
 * One row of the graph: a commit, the lane it sits in, and the lines around it.
 *
 * "Lane" is a column in the little diagram down the left — the thing that makes
 * a branch visible as a line that leaves the trunk and comes back.
 */
data class GraphRow(
    val commit: Commit,
    /** The column this commit's dot is drawn in. */
    val lane: Int,
    /**
     * Lanes that pass this row without stopping at it — the vertical lines
     * drawn straight through, belonging to branches this commit is not on.
     */
    val through: List<Int>,
    /** Where each parent continues, for the lines leaving the dot downwards. */
    val parentLanes: List<Int>,
    /** How many lanes exist at this row, which is how wide the diagram is. */
    val laneCount: Int,
)

/**
 * Lay commits out into lanes — the standard newest-first walk.
 *
 * Each lane remembers which commit it is *waiting for*. A commit takes the lane
 * that was waiting for it (or a free one if nobody was), then hands that lane
 * to its first parent; any further parents — a merge — claim lanes of their
 * own, which is what draws the fork. A lane whose commit has arrived and has no
 * parent is freed, so the diagram does not grow a column per root commit.
 *
 * Pure, and deliberately: this is arithmetic about ordering, it is where a
 * graph goes wrong, and none of it needs a canvas to be checked.
 */
fun layoutGraph(commits: List<Commit>): List<GraphRow> {
    // Each slot holds the sha that lane is waiting for, or null when free.
    val lanes = mutableListOf<String?>()

    fun claim(sha: String): Int {
        val existing = lanes.indexOf(sha)
        if (existing >= 0) return existing
        val free = lanes.indexOf(null)
        if (free >= 0) {
            lanes[free] = sha
            return free
        }
        lanes.add(sha)
        return lanes.size - 1
    }

    return commits.map { commit ->
        val lane = claim(commit.sha)
        // Everything else still open passes this row by. Read *before* the
        // parents are placed, so a lane a parent is about to take is not
        // counted as passing through.
        val through = lanes.indices.filter { it != lane && lanes[it] != null }

        // The first parent keeps this lane; the rest fork off.
        lanes[lane] = commit.parents.firstOrNull()
        val parentLanes = commit.parents.mapIndexed { index, parent ->
            if (index == 0) {
                // It may *already* be somewhere — two branches whose next
                // commit is the same one — in which case this lane merges
                // into that one rather than duplicating it.
                val elsewhere = lanes.indexOfFirst { it == parent }
                if (elsewhere != lane && elsewhere >= 0) {
                    lanes[lane] = null
                    elsewhere
                } else {
                    lane
                }
            } else {
                claim(parent)
            }
        }

        // Trailing free lanes are dropped so the diagram narrows again once a
        // branch has been merged.
        while (lanes.isNotEmpty() && lanes.last() == null) lanes.removeAt(lanes.size - 1)

        GraphRow(
            commit = commit,
            lane = lane,
            through = through,
            parentLanes = parentLanes,
            laneCount = maxOf(lanes.size, lane + 1, (through.maxOrNull() ?: -1) + 1),
        )
    }
}
