package com.itsm.audit

import com.itsm.events.*
import com.itsm.incident.IncidentRepository
import com.itsm.user.UserRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Plain @EventListener: runs synchronously inside the publisher's database transaction.
 * If the action is saved, its audit row is saved with it; if the action rolls back, so does the row.
 */
@Component
class AuditListener(
    private val repo: AuditLogRepository,
    private val incidents: IncidentRepository,
    private val users: UserRepository,
) {
    @EventListener
    fun on(event: AppEvent) {
        val incidentId = (event as? IncidentEvent)?.incidentId
        val incidentLabel = incidentId?.let { id -> incidents.findById(id).orElse(null)?.number ?: "#$id" } ?: ""
        val actorLabel = event.actorId?.let { userLabel(it) } ?: "system"

        val d = AuditDescriber.describe(event, incidentLabel, actorLabel) { userLabel(it) }

        repo.save(
            AuditLog(
                occurredAt = event.occurredAt,
                actorId = event.actorId,
                action = d.action,
                incidentId = incidentId,
                fromState = d.from,
                toState = d.to,
                description = d.text.take(1000),
                details = d.details,
            ),
        )
    }

    /** "E102" style label: employee code if there is one, else the username. */
    private fun userLabel(id: Long): String =
        users.findById(id).orElse(null)?.let { it.employeeCode ?: it.username } ?: "user#$id"
}

internal data class AuditDescription(
    val action: String,
    val text: String,
    val from: String? = null,
    val to: String? = null,
    val details: Map<String, Any?> = emptyMap(),
)

/** Pure function from event to audit text, so it is easy to unit test. */
internal object AuditDescriber {
    fun describe(e: AppEvent, inc: String, actor: String, labelOf: (Long) -> String): AuditDescription = when (e) {
        is IncidentReported -> AuditDescription(
            "INCIDENT_REPORTED", "$inc created by $actor", details = mapOf("title" to e.title),
        )
        is IncidentClassified -> AuditDescription(
            "INCIDENT_CLASSIFIED", "$inc classified as ${e.categoryCode}",
            details = mapOf("category" to e.categoryCode),
        )
        is PriorityChanged -> AuditDescription(
            "PRIORITY_CHANGED", "$inc priority ${e.from ?: "unset"} → ${e.to} by $actor",
            details = mapOf("from" to e.from?.name, "to" to e.to.name, "explanation" to e.explanation),
        )
        is IncidentAssigned -> AuditDescription(
            "INCIDENT_ASSIGNED", "$inc assigned to ${labelOf(e.assigneeId)} by $actor",
            details = mapOf("assigneeId" to e.assigneeId, "strategy" to e.strategy),
        )
        is StatusChanged -> AuditDescription(
            "STATUS_CHANGED", "$inc ${e.from} → ${e.to} by $actor",
            from = e.from.name, to = e.to.name,
        )
        is NoteAdded -> AuditDescription(
            "NOTE_ADDED", "$inc ${e.noteType.lowercase().replace('_', ' ')} added by $actor",
            details = mapOf("noteType" to e.noteType),
        )
        is InfoRequested -> AuditDescription(
            "INFO_REQUESTED", "$inc more information requested by $actor",
            details = mapOf("question" to e.question.take(500)),
        )
        is SlaWarningRaised -> AuditDescription(
            "SLA_WARNING", "$inc SLA warning: ${e.percentElapsed}% of the time used",
            details = mapOf("percentElapsed" to e.percentElapsed),
        )
        is SlaBreached -> AuditDescription(
            "SLA_BREACHED", "$inc SLA breached (${e.breachType})",
            details = mapOf("breachType" to e.breachType),
        )
        is IncidentEscalated -> AuditDescription(
            "INCIDENT_ESCALATED", "$inc escalated to ${e.level}",
            details = mapOf("level" to e.level, "escalatedToId" to e.escalatedToId),
        )
        is IncidentResolved -> AuditDescription(
            "INCIDENT_RESOLVED", "$inc resolved by $actor",
            details = mapOf("summary" to e.summary?.take(500)),
        )
        is IncidentClosed -> AuditDescription(
            "INCIDENT_CLOSED", "$inc closed by $actor",
        )
        is UserLoggedIn -> AuditDescription(
            "USER_LOGGED_IN", "${e.username} logged in", details = mapOf("ip" to e.ip),
        )
        is LoginFailed -> AuditDescription(
            "LOGIN_FAILED", "Failed login attempt for ${e.username}", details = mapOf("ip" to e.ip),
        )
    }
}
