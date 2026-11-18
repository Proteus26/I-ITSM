package com.itsm.assignment

import com.itsm.incident.IncidentStatus
import com.itsm.user.TechnicianRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Chooses a technician for an incident. It does not change the incident: P2's incident service calls [assign]
 * and, if a technician comes back, performs `Incident.assign(...)`. A null result means nobody suits, and the
 * incident stays CLASSIFIED for an administrator (who can use [recommend]).
 *
 * Capacity is a soft limit. Workloads are read as a snapshot without locking, so two concurrent assignments can
 * both see a technician one short of their maximum and both pick them, exceeding it by one. If strictness is ever
 * needed, the caller's transaction should re-check the count (or lock the technician row) before assigning.
 * Technicians in [AssignmentRequest.excludeTechnicianIds] are never candidates.
 */
@Service
class AssignmentService(
    private val technicians: TechnicianRepository,
    private val selector: AssignmentStrategySelector,
    private val skillLeastLoaded: SkillLeastLoadedStrategy,
    private val em: EntityManager,
) {
    /** Keys of the strategies that can be configured. */
    val availableStrategies: Set<String> get() = selector.keys

    /** Picks one technician with the configured strategy, or null when there is no candidate. */
    @Transactional(readOnly = true)
    fun assign(request: AssignmentRequest): AssignmentResult? = selector.select().pick(request, candidates(request))

    /**
     * Up to [limit] ranked suggestions for an administrator. Always uses [SkillLeastLoadedStrategy], whatever is
     * configured, because round-robin has no ranking and picking from it would move its rotation.
     */
    @Transactional(readOnly = true)
    fun recommend(request: AssignmentRequest, limit: Int = 3): List<AssignmentResult> {
        val remaining = candidates(request).toMutableList()
        val ranked = mutableListOf<AssignmentResult>()
        while (ranked.size < limit) {
            val pick = skillLeastLoaded.pick(request, remaining) ?: break
            ranked += pick
            remaining.removeIf { it.id == pick.technicianId }
        }
        return ranked
    }

    /** Active, available, non-excluded technicians below their capacity, with their workload from a single grouped query. */
    private fun candidates(request: AssignmentRequest): List<TechnicianCandidate> {
        val all = technicians.findByActiveTrueAndAvailableTrue().filter { it.id !in request.excludeTechnicianIds }
        if (all.isEmpty()) return emptyList()
        val load = workload(all.map { requireNotNull(it.id) })
        return all.map {
            TechnicianCandidate(requireNotNull(it.id), it.fullName, it.level.name, it.skills, load[it.id] ?: 0L, it.maxActiveIncidents)
        }.filter { it.activeIncidents < it.maxActiveIncidents }
    }

    private fun workload(ids: List<Long>): Map<Long, Long> =
        em.createQuery(
            "select i.assigneeId, count(i) from Incident i where i.assigneeId in :ids and i.status in :statuses group by i.assigneeId",
            Array<Any?>::class.java,
        ).setParameter("ids", ids).setParameter("statuses", IncidentStatus.ACTIVE)
            .resultList.associate { (it[0] as Long) to (it[1] as Long) }
}
