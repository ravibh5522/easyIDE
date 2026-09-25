package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.GitCommit

/**
 * One drawn row: a commit, the lane its dot sits in, and every lane that has a
 * line passing through this row.
 */
data class GraphRow(
    val commit: GitCommit,
    val lane: Int,
    /** Lane index -> the commit that lane is currently heading towards. */
    val passing: List<Int>,
    /** Lanes this commit's parents continue into, for the connecting curves. */
    val parentLanes: List<Int>,
    val laneCount: Int,
    /** Lanes with a line entering this row from above; the commit's own lane is among them unless it starts a branch. */
    val incoming: List<Int> = emptyList(),
    /** Other lanes that were waiting for this commit: their lines curve into its dot and stop here. */
    val ending: List<Int> = emptyList(),
)

/**
 * Assigns commits to lanes for a git graph.
 *
 * The rule is the one every graph renderer converges on: a lane is a *claim on
 * a future commit*. When a commit is drawn, its first parent inherits its lane,
 * so a straight line of history stays in one column; each additional parent
 * (a merge) claims a new lane. Lanes whose claim has been satisfied are freed
 * and reused, which is what keeps the graph narrow instead of growing a column
 * per branch ever created.
 *
 * Requires commits in topological order - which is why
 * [dev.easyide.sandbox.git.GitRepository.logAllRefs] sorts that way. Fed a
 * commit-date ordering instead, the lines still connect but wander.
 */
object CommitGraph {

    fun build(commits: List<GitCommit>): List<GraphRow> {
        // lanes[i] holds the commit id lane i is waiting to draw, or null if free.
        val lanes = ArrayList<String?>()
        val rows = ArrayList<GraphRow>(commits.size)

        commits.forEach { commit ->
            val incoming = lanes.indices.filter { lanes[it] != null }
            var lane = lanes.indexOf(commit.id)
            if (lane < 0) {
                // A head: no existing lane was waiting for it.
                lane = lanes.indexOfFirst { it == null }
                if (lane < 0) {
                    lanes.add(commit.id)
                    lane = lanes.lastIndex
                }
            }

            // Every other lane waiting for this same commit is a merge arriving
            // here; those lanes end at this row.
            val ending = lanes.indices.filter { it != lane && lanes[it] == commit.id }
            ending.forEach { lanes[it] = null }

            val parentLanes = ArrayList<Int>(commit.parents.size)
            commit.parents.forEachIndexed { index, parent ->
                if (index == 0) {
                    // First parent inherits the lane, keeping history straight.
                    lanes[lane] = parent
                    parentLanes.add(lane)
                } else {
                    val existing = lanes.indexOf(parent)
                    val target = if (existing >= 0) {
                        existing
                    } else {
                        val free = lanes.indexOfFirst { it == null }
                        if (free >= 0) {
                            lanes[free] = parent; free
                        } else {
                            lanes.add(parent); lanes.lastIndex
                        }
                    }
                    parentLanes.add(target)
                }
            }
            if (commit.parents.isEmpty()) lanes[lane] = null   // root commit

            rows += GraphRow(
                commit = commit,
                lane = lane,
                passing = lanes.indices.filter { lanes[it] != null },
                parentLanes = parentLanes,
                laneCount = lanes.size,
                incoming = incoming,
                ending = ending,
            )
        }

        // Trailing free lanes should not widen the gutter.
        val widest = rows.maxOfOrNull { row -> (row.passing.maxOrNull() ?: row.lane) + 1 } ?: 1
        return rows.map { it.copy(laneCount = maxOf(widest, it.lane + 1)) }
    }
}
