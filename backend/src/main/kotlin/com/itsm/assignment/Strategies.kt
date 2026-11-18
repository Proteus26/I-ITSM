package com.itsm.assignment

import com.itsm.incident.Priority
import com.itsm.user.TechnicianLevel
import org.springframework.stereotype.Component

/**
 * Prefers technicians whose skills contain the incident's category, then the least loaded of them.
 *
 * Order among the preferred technicians: for P1 and P2, among those whose load ratio (active / max) is below
 * [SENIOR_LOAD_LIMIT] the higher level first; then the lowest load ratio, then the fewest active incidents, then
 * the lowest id so the result is deterministic. If nobody is below the limit, ranking is purely by load ratio, so
 * urgent work does not pile onto the most senior technician.
 * If nobody has the skill (or the request has no category) every candidate is considered and the reason says so.
 */
@Component
class SkillLeastLoadedStrategy : AssignmentStrategy {
    override val key = KEY

    override fun pick(request: AssignmentRequest, candidates: List<TechnicianCandidate>): AssignmentResult? {
        if (candidates.isEmpty()) return null
        val category = request.categoryCode?.trim()?.uppercase()?.ifEmpty { null }
        val skilled = if (category == null) emptyList() else candidates.filter { category in it.skills }
        val pool = skilled.ifEmpty { candidates }
        val urgent = request.priority == Priority.P1 || request.priority == Priority.P2

        val seniorPool = if (urgent) pool.filter { it.load < SENIOR_LOAD_LIMIT } else emptyList()
        val rankPool = seniorPool.ifEmpty { pool }
        val levelFirst = seniorPool.isNotEmpty()

        val comparator = (if (levelFirst) compareByDescending<TechnicianCandidate> { levelRank(it.level) } else compareBy { 0 })
            .thenBy { it.load }
            .thenBy { it.activeIncidents }
            .thenBy { it.id }
        val best = rankPool.minWith(comparator)

        val who = "${best.fullName} (${best.level})"
        val reason = when {
            skilled.isNotEmpty() -> "$who has $category skill and ${best.loadText}"
            category != null -> "No available technician has $category skill; $who is the least loaded with ${best.loadText}"
            else -> "No category given; $who is the least loaded with ${best.loadText}"
        } + if (urgent && skilled.isNotEmpty()) " (${request.priority} prefers senior technicians)" else ""
        return AssignmentResult(best.id, key, reason)
    }

    private fun levelRank(level: String): Int = TechnicianLevel.entries.firstOrNull { it.name == level }?.ordinal ?: -1

    companion object {
        const val KEY = "skill-least-loaded"

        /** For P1/P2, seniority only counts for technicians below this load ratio (active / max). */
        const val SENIOR_LOAD_LIMIT = 0.8
    }
}

/**
 * Hands incidents to the candidates in turn, ignoring skills and priority.
 *
 * The rotation is a pointer to the last technician chosen; the next pick is the candidate with the next higher id
 * (wrapping to the lowest). Because it remembers an id rather than a position, it keeps working when candidates
 * come and go. The pointer lives in memory only: it restarts at the lowest id after a restart and is not shared
 * between application instances, which is fine for a single-node deployment. It advances as soon as a technician is
 * picked, even if the caller then fails to assign (for example a rolled-back transaction), so that turn is skipped.
 */
@Component
class RoundRobinStrategy : AssignmentStrategy {
    override val key = KEY

    private var lastId: Long = Long.MIN_VALUE

    @Synchronized
    override fun pick(request: AssignmentRequest, candidates: List<TechnicianCandidate>): AssignmentResult? {
        if (candidates.isEmpty()) return null
        val ordered = candidates.sortedBy { it.id }
        val next = ordered.firstOrNull { it.id > lastId } ?: ordered.first()
        lastId = next.id
        return AssignmentResult(next.id, key, "Round-robin turn: ${next.fullName} (${next.level}) with ${next.loadText}")
    }

    companion object {
        const val KEY = "round-robin"
    }
}
