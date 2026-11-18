package com.itsm.incident

import com.itsm.category.Category
import com.itsm.category.CategoryRepository
import com.itsm.service.ItServiceRepository
import com.itsm.user.User
import com.itsm.user.UserRepository
import org.springframework.stereotype.Component

/**
 * Turns entities into response DTOs. Names of people, services and categories are loaded with one query per kind
 * for a whole list, so a page of 100 incidents costs three lookups, not 300.
 */
@Component
class IncidentAssembler(
    private val users: UserRepository,
    private val services: ItServiceRepository,
    private val categories: CategoryRepository,
) {
    fun summaries(list: List<Incident>): List<IncidentSummary> {
        val names = lookups(list)
        return list.map { names.summary(it) }
    }

    /** The detail as seen by [viewer]: [IncidentDetail.actions] and [IncidentDetail.noteTypes] are theirs. */
    fun detail(incident: Incident, viewer: Actor): IncidentDetail {
        val names = lookups(listOf(incident))
        val s = names.summary(incident)
        return IncidentDetail(
            id = s.id, number = s.number, title = s.title, status = s.status, priority = s.priority,
            categoryCode = s.categoryCode, serviceName = s.serviceName, reporter = s.reporter, assignee = s.assignee,
            createdAt = s.createdAt, resolutionDueAt = s.resolutionDueAt,
            description = incident.description, impact = incident.impact, urgency = incident.urgency,
            affectedUsers = incident.affectedUsers, priorityScore = incident.priorityScore,
            priorityExplanation = incident.priorityExplanation,
            categoryName = incident.categoryCode?.let { names.categories[it]?.name },
            serviceId = incident.serviceId, responseDueAt = incident.responseDueAt,
            acknowledgedAt = incident.acknowledgedAt, resolvedAt = incident.resolvedAt, closedAt = incident.closedAt,
            resolutionSummary = incident.resolutionSummary, rootCause = incident.rootCause,
            feedbackRating = incident.feedbackRating, feedbackComment = incident.feedbackComment,
            updatedAt = incident.updatedAt, version = incident.version,
            actions = incident.actionsFor(viewer), noteTypes = incident.noteTypesFor(viewer),
            editableFields = incident.editableFieldsFor(viewer).sortedBy { it.ordinal }.map { it.json },
        )
    }

    fun notes(list: List<IncidentNote>): List<IncidentNoteResponse> {
        val authors = loadUsers(list.map { it.authorId })
        return list.map {
            IncidentNoteResponse(requireNotNull(it.id), it.type, it.body, personRef(it.authorId, authors), it.createdAt)
        }
    }

    private fun loadUsers(ids: Collection<Long>): Map<Long, User> =
        if (ids.isEmpty()) emptyMap() else users.findAllById(ids.toSet()).associateBy { requireNotNull(it.id) }

    private fun personRef(id: Long, byId: Map<Long, User>) = PersonRef(id, byId[id]?.fullName ?: "Unknown user")

    /** The lookups for one batch of incidents. */
    private inner class Names(
        val people: Map<Long, User>,
        val serviceNames: Map<Long, String>,
        val categories: Map<String, Category>,
    ) {
        fun summary(i: Incident) = IncidentSummary(
            id = requireNotNull(i.id), number = i.number, title = i.title, status = i.status, priority = i.priority,
            categoryCode = i.categoryCode, serviceName = i.serviceId?.let { serviceNames[it] },
            reporter = personRef(i.reporterId, people), assignee = i.assigneeId?.let { personRef(it, people) },
            createdAt = i.createdAt, resolutionDueAt = i.resolutionDueAt,
        )
    }

    private fun lookups(list: List<Incident>): Names {
        val people = loadUsers(list.flatMap { listOfNotNull(it.reporterId, it.assigneeId) })
        val serviceIds = list.mapNotNullTo(mutableSetOf()) { it.serviceId }
        val serviceNames = if (serviceIds.isEmpty()) emptyMap() else services.findAllById(serviceIds).associate { requireNotNull(it.id) to it.name }
        val codes = list.mapNotNullTo(mutableSetOf()) { it.categoryCode }
        val byCode = if (codes.isEmpty()) emptyMap() else categories.findAllById(codes).associateBy { it.code }
        return Names(people, serviceNames, byCode)
    }
}
