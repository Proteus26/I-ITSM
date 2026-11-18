package com.itsm.notification

import com.itsm.events.*
import com.itsm.incident.Incident
import com.itsm.incident.IncidentStatus
import com.itsm.user.Role
import com.itsm.user.User
import com.itsm.user.UserRepository
import com.itsm.user.UserSpecifications
import org.springframework.stereotype.Component

/**
 * Decides who is told about each event. Rules:
 *  - The person who did the action is never notified about it.
 *  - Inactive users are skipped.
 *  - StatusChanged is skipped when a dedicated event already covers that status (assigned, classified,
 *    resolved, closed), and INFO_REQUEST notes are skipped because InfoRequested covers them. This avoids
 *    sending two notifications for one action when a publisher fires both events.
 */
@Component
class RecipientResolver(private val users: UserRepository) {

    fun resolve(event: IncidentEvent, incident: Incident): List<User> {
        val ids = linkedSetOf<Long>()
        fun reporter() { ids += incident.reporterId }
        fun assignee() { incident.assigneeId?.let { ids += it } }
        fun admins() {
            val spec = UserSpecifications.hasRole(Role.ADMIN).and(UserSpecifications.isActive(true))
            users.findAll(spec).forEach { admin -> admin.id?.let { ids += it } }
        }

        when (event) {
            is IncidentReported -> reporter()
            is IncidentClassified -> reporter()
            is PriorityChanged -> assignee()
            is IncidentAssigned -> { ids += event.assigneeId; reporter() }
            is StatusChanged -> if (event.to !in COVERED_BY_OWN_EVENT) { reporter(); assignee() }
            is NoteAdded -> when (event.noteType) {
                "INFO_REQUEST" -> Unit                       // InfoRequested handles this
                "INVESTIGATION" -> assignee()                // internal note: the reporter is not told
                else -> { reporter(); assignee() }
            }
            is InfoRequested -> reporter()
            is SlaWarningRaised -> if (incident.assigneeId != null) assignee() else admins()
            is SlaBreached -> { assignee(); admins() }
            is IncidentEscalated -> { assignee(); event.escalatedToId?.let { ids += it }; admins() }
            is IncidentResolved -> reporter()
            is IncidentClosed -> { reporter(); assignee() }
            // The one who is not the editor hears about it (the editor is removed below): the assignee when the
            // reporter edits, the reporter when staff edit. An administrator editing tells both.
            is IncidentUpdated -> { reporter(); assignee() }
        }

        event.actorId?.let { ids.remove(it) }
        if (ids.isEmpty()) return emptyList()
        return users.findAllById(ids).filter { it.active }
    }

    private companion object {
        val COVERED_BY_OWN_EVENT = setOf(
            IncidentStatus.CLASSIFIED, IncidentStatus.ASSIGNED, IncidentStatus.RESOLVED, IncidentStatus.CLOSED,
        )
    }
}
