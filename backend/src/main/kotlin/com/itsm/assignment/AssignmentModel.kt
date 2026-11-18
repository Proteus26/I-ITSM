package com.itsm.assignment

import com.itsm.incident.Priority

/**
 * What the assignment needs to know about an incident. All fields are optional so callers can ask before an
 * incident exists (for example while a form is being filled in).
 *
 * @property incidentId the incident, for logging and future strategies; not used for ranking today
 * @property categoryCode upper-case category code such as NETWORK; matched against technician skills
 * @property priority P1 is the most urgent; P1 and P2 prefer more senior technicians
 * @property excludeTechnicianIds technicians who must not be chosen, for example the reporter (a technician reporting
 *   their own incident) or the current assignee during an escalation reassignment. If every candidate is excluded
 *   the result is null.
 */
data class AssignmentRequest(
    val incidentId: Long?,
    val categoryCode: String?,
    val priority: Priority?,
    val excludeTechnicianIds: Set<Long> = emptySet(),
)

/**
 * A technician who may take one more incident: active, available and below their capacity.
 *
 * @property level the name of the technician's [com.itsm.user.TechnicianLevel] (L1, L2, TEAM_LEAD)
 * @property skills upper-case category codes
 * @property activeIncidents incidents currently ASSIGNED, ACKNOWLEDGED, IN_PROGRESS or PENDING_INFO
 */
data class TechnicianCandidate(
    val id: Long,
    val fullName: String,
    val level: String,
    val skills: Set<String>,
    val activeIncidents: Long,
    val maxActiveIncidents: Int,
) {
    /** Share of the capacity in use, 0.0 (idle) to just under 1.0 (candidates always have spare capacity). */
    val load: Double get() = activeIncidents.toDouble() / maxActiveIncidents

    /** "1/5 active incidents", used in reasons. */
    val loadText: String get() = "$activeIncidents/$maxActiveIncidents active incidents"
}

/**
 * The outcome of a strategy.
 *
 * @property strategy key of the strategy that made the choice
 * @property reason a sentence an administrator can read, for example "Ravi (L2) has NETWORK skill and 1/5 active incidents"
 */
data class AssignmentResult(val technicianId: Long, val strategy: String, val reason: String)

/**
 * Picks one technician from the candidates (Strategy pattern). Implementations are Spring beans, so a new
 * strategy is just a new `@Component`; it becomes selectable by its [key].
 */
interface AssignmentStrategy {
    /** Stable identifier used in configuration, for example "round-robin". */
    val key: String

    /** Returns the chosen technician, or null if [candidates] is empty or none of them suits the request. */
    fun pick(request: AssignmentRequest, candidates: List<TechnicianCandidate>): AssignmentResult?
}
