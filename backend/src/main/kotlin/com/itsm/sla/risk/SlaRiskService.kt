package com.itsm.sla.risk

import com.itsm.incident.Incident
import com.itsm.incident.IncidentStatus
import com.itsm.incident.Priority
import com.itsm.incident.PersonRef
import com.itsm.service.ItServiceRepository
import com.itsm.sla.SlaPolicy
import com.itsm.sla.SlaPolicyRepository
import com.itsm.user.TechnicianRepository
import com.itsm.user.UserRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.EntityManager
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One incident that is breached or at risk, with the reason; see [SlaRiskDetector] for the rules. */
data class SlaRiskItem(
    val incidentId: Long,
    val number: String,
    val title: String,
    val status: IncidentStatus,
    val priority: Priority?,
    val serviceName: String?,
    val assignee: PersonRef?,
    val state: SlaRiskState,
    val riskReason: String,
    val reasons: List<String>,
    val dueKind: SlaWindowKind,
    val dueAt: Instant,
    /** Minutes until [dueAt]; negative when it has passed (the incident is overdue by that much). */
    val minutesRemaining: Long,
    val percentElapsed: Int,
)

data class SlaRiskReport(val generatedAt: Instant, val breached: List<SlaRiskItem>, val atRisk: List<SlaRiskItem>)

/**
 * Loads the open incidents that have SLA deadlines and runs them through [SlaRiskDetector]. Reads only; it does not
 * record SLA events or escalate (that is the SLA monitor's job), so it can be called as often as an admin refreshes.
 * Breached incidents are listed most overdue first, at-risk ones with the least time left first.
 */
@Service
class SlaRiskService(
    private val em: EntityManager,
    private val policies: SlaPolicyRepository,
    private val users: UserRepository,
    private val technicians: TechnicianRepository,
    private val services: ItServiceRepository,
    private val clock: Clock,
    private val detector: SlaRiskDetector,
) {
    @Transactional(readOnly = true)
    fun report(): SlaRiskReport {
        val now = clock.instant()
        val open = em.createQuery(
            "select i from Incident i where i.status in :s and i.priority is not null " +
                "and (i.responseDueAt is not null or i.resolutionDueAt is not null)",
            Incident::class.java,
        ).setParameter("s", IncidentStatus.OPEN).resultList
        val items = assess(open, now)
        return SlaRiskReport(
            generatedAt = now,
            breached = items.filter { it.state == SlaRiskState.BREACHED }.sortedBy { it.minutesRemaining },
            atRisk = items.filter { it.state == SlaRiskState.AT_RISK }.sortedBy { it.minutesRemaining },
        )
    }

    /**
     * The risk of each of [open] incidents that has one (breached or at risk); the others are left out. Incidents
     * that are not open or have no deadlines are ignored too.
     */
    @Transactional(readOnly = true)
    fun assess(open: List<Incident>, now: Instant): List<SlaRiskItem> {
        if (open.isEmpty()) return emptyList()
        val policyByKey = policies.findAll().associateBy { it.serviceId to it.priority }
        val assigneeIds = open.mapNotNullTo(mutableSetOf()) { it.assigneeId }
        val load = activeCounts(assigneeIds)
        val capacity = if (assigneeIds.isEmpty()) emptyMap() else technicians.findAllById(assigneeIds).associate { requireNotNull(it.id) to it.maxActiveIncidents }
        val people = if (assigneeIds.isEmpty()) emptyMap() else users.findAllById(assigneeIds).associateBy { requireNotNull(it.id) }
        val serviceIds = open.mapNotNullTo(mutableSetOf()) { it.serviceId }
        val serviceNames = if (serviceIds.isEmpty()) emptyMap() else services.findAllById(serviceIds).associate { requireNotNull(it.id) to it.name }

        return open.mapNotNull { i ->
            if (i.status !in IncidentStatus.OPEN) return@mapNotNull null
            val priority = i.priority ?: return@mapNotNull null
            val policy = policyByKey[i.serviceId to priority] ?: policyByKey[null to priority]
            val assignee = i.assigneeId
            val over = assignee != null && (load[assignee] ?: 0L) > (capacity[assignee] ?: Int.MAX_VALUE)
            val risk = detector.assess(inputFor(i, policy, over), now) ?: return@mapNotNull null
            SlaRiskItem(
                incidentId = requireNotNull(i.id), number = i.number, title = i.title, status = i.status, priority = priority,
                serviceName = i.serviceId?.let { serviceNames[it] },
                assignee = assignee?.let { PersonRef(it, people[it]?.fullName ?: "Unknown user") },
                state = risk.state, riskReason = risk.riskReason, reasons = risk.reasons, dueKind = risk.dueKind,
                dueAt = risk.dueAt, minutesRemaining = risk.minutesRemaining, percentElapsed = risk.percentElapsed,
            )
        }
    }

    /** Without a policy (only if the defaults were deleted) a window is as long as it was from creation to its deadline. */
    private fun inputFor(i: Incident, policy: SlaPolicy?, over: Boolean) = SlaRiskInput(
        responseDueAt = i.responseDueAt,
        resolutionDueAt = i.resolutionDueAt,
        acknowledged = i.acknowledgedAt != null,
        assigned = i.assigneeId != null,
        responseMinutes = policy?.responseMinutes ?: minutesFromCreation(i, i.responseDueAt),
        resolutionMinutes = policy?.resolutionMinutes ?: minutesFromCreation(i, i.resolutionDueAt),
        warningThresholdPct = policy?.warningThresholdPct ?: DEFAULT_WARNING_PCT,
        assigneeOverCapacity = over,
    )

    private fun minutesFromCreation(i: Incident, due: Instant?): Int =
        due?.let { Duration.between(i.createdAt, it).toMinutes().coerceIn(1, Int.MAX_VALUE.toLong()).toInt() } ?: 1

    private fun activeCounts(ids: Collection<Long>): Map<Long, Long> {
        if (ids.isEmpty()) return emptyMap()
        return em.createQuery(
            "select i.assigneeId, count(i) from Incident i where i.assigneeId in :ids and i.status in :s group by i.assigneeId",
            Array<Any?>::class.java,
        ).setParameter("ids", ids).setParameter("s", IncidentStatus.ACTIVE).resultList.associate { (it[0] as Long) to (it[1] as Long) }
    }

    private companion object {
        const val DEFAULT_WARNING_PCT = 75
    }
}

@RestController
@RequestMapping("/api/admin/sla")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "SLA monitoring")
class SlaRiskController(private val service: SlaRiskService) {

    @Operation(
        summary = "Open incidents that breached their SLA or are at risk of doing so",
        description = "Administrators only. Each item carries a riskReason. See SlaRiskDetector for the rules.",
    )
    @GetMapping("/at-risk")
    fun atRisk(): SlaRiskReport = service.report()
}
