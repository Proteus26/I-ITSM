package com.itsm.sla.escalation

import com.itsm.events.IncidentEscalated
import com.itsm.incident.Incident
import com.itsm.incident.Priority
import com.itsm.sla.SlaEventType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** What the chain is asked to handle: the incident and the breach that just happened. */
data class EscalationContext(val incident: Incident, val breach: SlaEventType)

/**
 * One link of the escalation chain (Chain of Responsibility). A handler either deals with the breach and returns
 * true, or passes it on to [next]. Subclasses implement [canHandle] and [escalate]; [EscalationChain] links them in
 * their `@Order`. Handlers only publish [IncidentEscalated]; whoever owns the follow-up (P5 reassignment, notifications)
 * reacts to that event.
 */
abstract class EscalationHandler(protected val publisher: ApplicationEventPublisher) {
    var next: EscalationHandler? = null

    /** True if this handler or a later one handled [context]. */
    fun handle(context: EscalationContext): Boolean =
        if (canHandle(context)) {
            escalate(context)
            true
        } else {
            next?.handle(context) ?: false
        }

    protected abstract fun canHandle(context: EscalationContext): Boolean
    protected abstract fun escalate(context: EscalationContext)
}

/** Resolution breach on a P1 or P2 incident: tell the administrators (event level `ADMIN`). */
@Component
@Order(1)
class AdminEscalationHandler(publisher: ApplicationEventPublisher) : EscalationHandler(publisher) {
    override fun canHandle(context: EscalationContext) =
        context.breach == SlaEventType.RESOLUTION_BREACH && context.incident.priority in setOf(Priority.P1, Priority.P2)

    override fun escalate(context: EscalationContext) {
        publisher.publishEvent(IncidentEscalated(requireNotNull(context.incident.id), null, LEVEL_ADMIN))
    }

    companion object {
        const val LEVEL_ADMIN = "ADMIN"
    }
}

/**
 * Response breach on an incident nobody acknowledged: ask for a new assignee (event level `REASSIGN_REQUESTED`).
 * This handler does not reassign anything; picking a technician belongs to P5 (assignment) and applying it to P2
 * (`Incident.assign`), both of which can listen for this event later.
 */
@Component
@Order(2)
class ReassignEscalationHandler(publisher: ApplicationEventPublisher) : EscalationHandler(publisher) {
    override fun canHandle(context: EscalationContext) =
        context.breach == SlaEventType.RESPONSE_BREACH && context.incident.acknowledgedAt == null

    override fun escalate(context: EscalationContext) {
        publisher.publishEvent(IncidentEscalated(requireNotNull(context.incident.id), null, LEVEL_REASSIGN))
    }

    companion object {
        const val LEVEL_REASSIGN = "REASSIGN_REQUESTED"
    }
}

/** Entry point of the chain: links the handlers (Spring injects them sorted by `@Order`) and starts at the first. */
@Component
class EscalationChain(handlers: List<EscalationHandler>) {
    private val first: EscalationHandler? = handlers.reduceRightOrNull { handler, next -> handler.also { it.next = next } }

    /** True if some handler escalated the breach. */
    fun escalate(context: EscalationContext): Boolean = first?.handle(context) ?: false
}
