package com.itsm.sla.escalation

import com.itsm.events.IncidentAssigned
import com.itsm.events.IncidentEscalated
import com.itsm.events.StatusChanged
import com.itsm.incident.Actor
import com.itsm.incident.Incident
import com.itsm.incident.IncidentStatus
import com.itsm.incident.Priority
import com.itsm.sla.SlaEventType
import com.itsm.user.TechnicianLevel
import com.itsm.user.TechnicianRepository
import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

/** What the chain is asked to handle: the incident and the breach that just happened, at [now]. */
data class EscalationContext(val incident: Incident, val breach: SlaEventType, val now: Instant = Instant.now())

/**
 * One link of the escalation chain (Chain of Responsibility): Technician -> Team Lead -> Administrator.
 * [tryHandle] either deals with the breach and returns true, or returns false and the breach is passed to [next].
 * [EscalationChain] links the handlers in their `@Order`. Handlers run inside the monitor's transaction, change the
 * incident through its domain methods and publish the matching events (see docs/p4.md); they are not idempotent
 * themselves: the monitor calls the chain once per (incident, breach type) thanks to the unique `sla_events` row.
 */
abstract class EscalationHandler(protected val publisher: ApplicationEventPublisher) {
    var next: EscalationHandler? = null

    /** True if this handler or a later one handled [context]. */
    fun handle(context: EscalationContext): Boolean = tryHandle(context) || (next?.handle(context) ?: false)

    protected abstract fun tryHandle(context: EscalationContext): Boolean
}

/** Finds the team lead an unacknowledged incident should move to, or null when there is none. */
fun interface TeamLeadSource {
    fun find(incident: Incident): Long?
}

/**
 * Picks the best team lead: active, available, not the reporter and not the current assignee. A team lead with the
 * incident's category skill wins over one without; then the lowest share of capacity in use, then the lowest id.
 * Capacity is deliberately not a hard limit here: an escalation must not be dropped because the team lead is busy.
 */
@Component
class JpaTeamLeadSource(private val technicians: TechnicianRepository, private val em: EntityManager) : TeamLeadSource {

    override fun find(incident: Incident): Long? {
        val leads = technicians.findByActiveTrueAndAvailableTrue()
            .filter { it.level == TechnicianLevel.TEAM_LEAD && it.id != incident.reporterId && it.id != incident.assigneeId }
        if (leads.isEmpty()) return null
        val load = workload(leads.map { requireNotNull(it.id) })
        val category = incident.categoryCode
        return leads.minWithOrNull(
            compareBy({ category == null || !it.hasSkill(category) }, { (load[it.id] ?: 0L).toDouble() / it.maxActiveIncidents }, { it.id }),
        )?.id
    }

    private fun workload(ids: List<Long>): Map<Long, Long> =
        em.createQuery(
            "select i.assigneeId, count(i) from Incident i where i.assigneeId in :ids and i.status in :statuses group by i.assigneeId",
            Array<Any?>::class.java,
        ).setParameter("ids", ids).setParameter("statuses", IncidentStatus.ACTIVE)
            .resultList.associate { (it[0] as Long) to (it[1] as Long) }
}

/**
 * Link 1: a response breach on an incident nobody acknowledged goes to a team lead. The incident is reassigned
 * with [Actor.SYSTEM] (it goes back to ASSIGNED, so the team lead has to acknowledge) and the events
 * [IncidentEscalated] (level `TEAM_LEAD`), [IncidentAssigned] (strategy `escalation`) and, if the status changed, [StatusChanged]
 * are published. Passes the breach on when there is no team lead, or the incident cannot be reassigned
 * (not yet classified, or the current assignee already is the only team lead).
 */
@Component
@Order(1)
class TeamLeadEscalationHandler(private val leads: TeamLeadSource, publisher: ApplicationEventPublisher) : EscalationHandler(publisher) {

    override fun tryHandle(context: EscalationContext): Boolean {
        val incident = context.incident
        if (context.breach != SlaEventType.RESPONSE_BREACH || incident.acknowledgedAt != null) return false
        if (incident.status != IncidentStatus.CLASSIFIED && incident.status !in IncidentStatus.ACTIVE) return false
        val leadId = leads.find(incident) ?: return false
        val id = requireNotNull(incident.id)
        val change = incident.assign(leadId, Actor.SYSTEM, context.now)
        publisher.publishEvent(IncidentEscalated(id, null, LEVEL_TEAM_LEAD, leadId))
        publisher.publishEvent(IncidentAssigned(id, null, leadId, STRATEGY))
        if (change != null) publisher.publishEvent(StatusChanged(id, null, change.from, change.to))
        return true
    }

    companion object {
        const val LEVEL_TEAM_LEAD = "TEAM_LEAD"
        const val STRATEGY = "escalation"
    }
}

/**
 * Link 2 (last): tells the administrators (event level `ADMIN`) when a P1/P2 resolution deadline is missed, or when
 * an unacknowledged response breach could not be handled by a team lead. P3/P4 resolution breaches are not escalated.
 */
@Component
@Order(2)
class AdminEscalationHandler(publisher: ApplicationEventPublisher) : EscalationHandler(publisher) {

    override fun tryHandle(context: EscalationContext): Boolean {
        val incident = context.incident
        val wanted = when (context.breach) {
            SlaEventType.RESOLUTION_BREACH -> incident.priority in setOf(Priority.P1, Priority.P2)
            SlaEventType.RESPONSE_BREACH -> incident.acknowledgedAt == null
            SlaEventType.WARNING -> false
        }
        if (!wanted) return false
        publisher.publishEvent(IncidentEscalated(requireNotNull(incident.id), null, LEVEL_ADMIN))
        return true
    }

    companion object {
        const val LEVEL_ADMIN = "ADMIN"
    }
}

/** Entry point of the chain: links the handlers (Spring injects them sorted by `@Order`) and starts at the first. */
@Component
class EscalationChain(handlers: List<EscalationHandler>) {
    private val first: EscalationHandler? = handlers.reduceRightOrNull { handler, next -> handler.also { it.next = next } }

    /** True if some handler escalated the breach. */
    fun escalate(context: EscalationContext): Boolean = first?.handle(context) ?: false
}
