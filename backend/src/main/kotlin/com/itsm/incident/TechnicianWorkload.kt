package com.itsm.incident

import com.itsm.auth.AuthenticatedUser
import com.itsm.auth.CurrentUser
import com.itsm.auth.UnauthorizedOperationException
import com.itsm.sla.risk.SlaRiskService
import com.itsm.sla.risk.SlaRiskState
import com.itsm.user.Role
import com.itsm.user.TechnicianRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

/** How an active incident stands against its deadlines: OVERDUE (breached), AT_RISK (predicted breach) or ON_TRACK. */
enum class DeadlineState { OVERDUE, AT_RISK, ON_TRACK }

data class WorkloadIncident(
    val id: Long,
    val number: String,
    val title: String,
    val status: IncidentStatus,
    val priority: Priority?,
    val responseDueAt: Instant?,
    val resolutionDueAt: Instant?,
    val deadlineState: DeadlineState,
    /** Why it is OVERDUE or AT_RISK; null when ON_TRACK. */
    val riskReason: String?,
)

/**
 * The signed-in technician's workload. [activeCount] counts ASSIGNED, ACKNOWLEDGED, IN_PROGRESS and PENDING_INFO
 * incidents (the same as the assignment strategies), [capacity] is the technician's maximum. [nextDueAt] is the
 * earliest resolution deadline among the active incidents. [incidents] are ordered by resolution deadline (no
 * deadline last).
 */
data class TechnicianWorkloadResponse(
    val technicianId: Long,
    val fullName: String,
    val available: Boolean,
    val activeCount: Long,
    val capacity: Int,
    val overCapacity: Boolean,
    val countsByStatus: Map<IncidentStatus, Long>,
    val nextDueAt: Instant?,
    val overdueCount: Int,
    val atRiskCount: Int,
    val incidents: List<WorkloadIncident>,
)

data class AvailabilityRequest(@field:NotNull val available: Boolean?)

data class AvailabilityResponse(val available: Boolean)

@Service
class TechnicianWorkloadService(
    private val incidents: IncidentRepository,
    private val technicians: TechnicianRepository,
    private val risks: SlaRiskService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun workload(viewer: AuthenticatedUser): TechnicianWorkloadResponse {
        val tech = requireTechnician(viewer)
        val active = incidents.findByAssigneeIdAndStatusIn(viewer.id, IncidentStatus.ACTIVE, Pageable.unpaged()).content
            .sortedWith(compareBy<Incident, Instant?>(nullsLast()) { it.resolutionDueAt }.thenBy { it.id })
        val risk = risks.assess(active, clock.instant()).associateBy { it.incidentId }
        val rows = active.map { i ->
            val r = risk[i.id]
            WorkloadIncident(
                id = requireNotNull(i.id), number = i.number, title = i.title, status = i.status, priority = i.priority,
                responseDueAt = i.responseDueAt, resolutionDueAt = i.resolutionDueAt,
                deadlineState = when (r?.state) {
                    SlaRiskState.BREACHED -> DeadlineState.OVERDUE
                    SlaRiskState.AT_RISK -> DeadlineState.AT_RISK
                    null -> DeadlineState.ON_TRACK
                },
                riskReason = r?.riskReason,
            )
        }
        val counts = IncidentStatus.ACTIVE.sortedBy { it.ordinal }.associateWith { s -> active.count { it.status == s }.toLong() }
        return TechnicianWorkloadResponse(
            technicianId = viewer.id, fullName = tech.fullName, available = tech.available,
            activeCount = active.size.toLong(), capacity = tech.maxActiveIncidents, overCapacity = active.size > tech.maxActiveIncidents,
            countsByStatus = counts, nextDueAt = active.mapNotNull { it.resolutionDueAt }.minOrNull(),
            overdueCount = rows.count { it.deadlineState == DeadlineState.OVERDUE },
            atRiskCount = rows.count { it.deadlineState == DeadlineState.AT_RISK },
            incidents = rows,
        )
    }

    /** A technician marks themselves (un)available for new automatic assignments; incidents they already have stay theirs. */
    @Transactional
    fun setAvailability(viewer: AuthenticatedUser, available: Boolean): AvailabilityResponse {
        val tech = requireTechnician(viewer)
        if (available) tech.markAvailable() else tech.markUnavailable()
        technicians.save(tech)
        return AvailabilityResponse(tech.available)
    }

    private fun requireTechnician(viewer: AuthenticatedUser) =
        (if (viewer.role == Role.TECHNICIAN) technicians.findById(viewer.id).orElse(null) else null)
            ?: throw UnauthorizedOperationException("Only technicians have a workload")
}

@RestController
@Tag(name = "Technician workload")
class TechnicianWorkloadController(private val service: TechnicianWorkloadService, private val currentUser: CurrentUser) {

    @Operation(
        summary = "My workload (technicians only)",
        description = "Active count against capacity, availability, counts per status and the active incidents ordered by resolution deadline, each marked OVERDUE, AT_RISK or ON_TRACK.",
    )
    @GetMapping("/api/incidents/workload")
    fun workload(): TechnicianWorkloadResponse = service.workload(currentUser.get())

    @Operation(summary = "Set my availability for automatic assignment (technicians only)", description = "Existing assignments are not changed.")
    @PatchMapping("/api/me/availability")
    fun availability(@Valid @RequestBody request: AvailabilityRequest): AvailabilityResponse =
        service.setAvailability(currentUser.get(), requireNotNull(request.available))
}
