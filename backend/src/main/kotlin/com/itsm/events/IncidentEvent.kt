package com.itsm.events

import com.itsm.incident.IncidentStatus
import com.itsm.incident.Priority
import java.time.Instant

/**
 * THE CONTRACT. Every other module publishes these with ApplicationEventPublisher.publishEvent(...),
 * from inside the same @Transactional method that changes the data:
 *
 *     publisher.publishEvent(StatusChanged(incident.id, actor.id, from, to))
 *
 * Audit listens in the same transaction; notifications listen after commit.
 *
 * Login events are NOT here: main already publishes com.itsm.auth.UserLoggedIn and LoginFailed, and the
 * audit module listens to those directly (see AuditListener).
 */
sealed class IncidentEvent {
    abstract val incidentId: Long

    /** Who did it. null means the system itself (scheduler, auto-assignment). */
    abstract val actorId: Long?
    val occurredAt: Instant = Instant.now()
}

data class IncidentReported(
    override val incidentId: Long, override val actorId: Long?, val title: String,
) : IncidentEvent()

data class IncidentClassified(
    override val incidentId: Long, override val actorId: Long?, val categoryCode: String,
) : IncidentEvent()

data class PriorityChanged(
    override val incidentId: Long, override val actorId: Long?,
    val from: Priority?, val to: Priority, val explanation: String? = null,
) : IncidentEvent()

data class IncidentAssigned(
    override val incidentId: Long, override val actorId: Long?,
    val assigneeId: Long, val strategy: String? = null,
) : IncidentEvent()

/** Publish on EVERY status transition (the audit timeline is built from these). */
data class StatusChanged(
    override val incidentId: Long, override val actorId: Long?,
    val from: IncidentStatus, val to: IncidentStatus,
) : IncidentEvent()

/** noteType is the name of P2's note type: INVESTIGATION, INFO_REQUEST, INFO_RESPONSE or COMMENT. */
data class NoteAdded(
    override val incidentId: Long, override val actorId: Long?, val noteType: String,
) : IncidentEvent()

data class InfoRequested(
    override val incidentId: Long, override val actorId: Long?, val question: String,
) : IncidentEvent()

data class SlaWarningRaised(
    override val incidentId: Long, override val actorId: Long?, val percentElapsed: Int,
) : IncidentEvent()

/** breachType: RESPONSE_BREACH or RESOLUTION_BREACH. */
data class SlaBreached(
    override val incidentId: Long, override val actorId: Long?, val breachType: String,
) : IncidentEvent()

data class IncidentEscalated(
    override val incidentId: Long, override val actorId: Long?,
    val level: String, val escalatedToId: Long? = null,
) : IncidentEvent()

data class IncidentResolved(
    override val incidentId: Long, override val actorId: Long?, val summary: String? = null,
) : IncidentEvent()

data class IncidentClosed(
    override val incidentId: Long, override val actorId: Long?,
) : IncidentEvent()
