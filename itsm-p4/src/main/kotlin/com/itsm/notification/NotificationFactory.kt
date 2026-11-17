package com.itsm.notification

import com.itsm.events.*
import com.itsm.incident.Incident
import com.itsm.user.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Factory pattern: turns an event into the message one particular recipient should see. */
@Component
class NotificationFactory(
    @Value("\${itsm.notifications.incident-link:/incidents/{id}}") private val linkTemplate: String,
) {
    fun create(event: IncidentEvent, incident: Incident, recipient: User): Notification {
        val n = incident.number
        val t = incident.title

        val (title, body) = when (event) {
            is IncidentReported -> "$n reported" to "Your incident \"$t\" was received."
            is IncidentClassified -> "$n classified" to "\"$t\" was categorised as ${event.categoryCode}."
            is PriorityChanged ->
                "$n priority changed" to "Priority is now ${event.to}" + (event.explanation?.let { " ($it)" } ?: "") + "."
            is IncidentAssigned ->
                if (recipient.id == event.assigneeId) {
                    "$n assigned to you" to "You have been assigned \"$t\"."
                } else {
                    "$n assigned" to "\"$t\" has been assigned to a technician."
                }
            is StatusChanged -> "$n is now ${event.to}" to "Status changed from ${event.from} to ${event.to}."
            is NoteAdded ->
                "New note on $n" to "A ${event.noteType.lowercase().replace('_', ' ')} was added to \"$t\"."
            is InfoRequested -> "More information needed for $n" to event.question
            is SlaWarningRaised ->
                "SLA warning for $n" to "${event.percentElapsed}% of the SLA time has elapsed for \"$t\"."
            is SlaBreached -> "SLA breached for $n" to "\"$t\" breached its SLA (${event.breachType})."
            is IncidentEscalated -> "$n escalated" to "\"$t\" was escalated to ${event.level}."
            is IncidentResolved -> "$n resolved" to (event.summary ?: "Please check that the problem is fixed.")
            is IncidentClosed -> "$n closed" to "\"$t\" has been closed."
        }

        return Notification(
            recipientId = recipient.id,
            recipientEmail = recipient.email,
            title = title,
            body = body,
            link = linkTemplate.replace("{id}", incident.id.toString()),
            incidentId = incident.id,
        )
    }
}
