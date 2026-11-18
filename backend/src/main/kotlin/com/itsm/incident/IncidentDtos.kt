package com.itsm.incident

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/** Request and response shapes of the incident REST API (see docs/p2.md). */

data class ReportIncidentRequest(
    @field:NotBlank @field:Size(max = Incident.MAX_TITLE) val title: String,
    @field:NotBlank @field:Size(max = Incident.MAX_TEXT) val description: String,
    @field:NotNull val impact: Impact,
    @field:NotNull val urgency: Urgency,
    @field:Min(1) val affectedUsers: Int = 1,
    val serviceId: Long? = null,
    /** Optional. When absent the system suggests one from the text; see [com.itsm.incident.StandardIncidentPipeline]. */
    @field:Size(max = 100) val categoryCode: String? = null,
)

data class AddNoteRequest(
    @field:NotNull val type: NoteType,
    @field:NotBlank @field:Size(max = Incident.MAX_TEXT) val body: String,
)

data class RequestInfoRequest(@field:NotBlank @field:Size(max = Incident.MAX_TEXT) val question: String)

data class ResumeRequest(@field:Size(max = Incident.MAX_TEXT) val response: String? = null)

data class ResolveRequest(
    @field:NotBlank @field:Size(max = Incident.MAX_TEXT) val summary: String,
    @field:Size(max = Incident.MAX_ROOT_CAUSE) val rootCause: String? = null,
)

data class ReasonRequest(@field:Size(max = Incident.MAX_TEXT) val reason: String? = null)

data class FeedbackRequest(
    @field:Min(1) @field:Max(5) val rating: Int,
    @field:Size(max = Incident.MAX_COMMENT) val comment: String? = null,
)

data class ClassifyRequest(@field:NotBlank @field:Size(max = 100) val categoryCode: String)

/**
 * PATCH /api/incidents/{id}: every field is optional and only the given ones change. [clearService] removes the
 * service (a null [serviceId] means "unchanged").
 */
data class UpdateIncidentRequest(
    @field:Size(max = Incident.MAX_TITLE) val title: String? = null,
    @field:Size(max = Incident.MAX_TEXT) val description: String? = null,
    val impact: Impact? = null,
    val urgency: Urgency? = null,
    @field:Min(1) val affectedUsers: Int? = null,
    val serviceId: Long? = null,
    val clearService: Boolean = false,
) {
    fun toEdit() = IncidentEdit(title, description, impact, urgency, affectedUsers, serviceId, clearService)
}

data class AssignRequest(@field:NotNull val technicianId: Long)

data class PersonRef(val id: Long, val fullName: String)

data class IncidentSummary(
    val id: Long,
    val number: String,
    val title: String,
    val status: IncidentStatus,
    val priority: Priority?,
    val categoryCode: String?,
    val serviceName: String?,
    val reporter: PersonRef,
    val assignee: PersonRef?,
    val createdAt: Instant,
    val resolutionDueAt: Instant?,
)

/** [actions] and [noteTypes] are computed for the person asking, so the UI needs no role logic of its own. */
data class IncidentDetail(
    val id: Long,
    val number: String,
    val title: String,
    val status: IncidentStatus,
    val priority: Priority?,
    val categoryCode: String?,
    val serviceName: String?,
    val reporter: PersonRef,
    val assignee: PersonRef?,
    val createdAt: Instant,
    val resolutionDueAt: Instant?,
    val description: String,
    val impact: Impact,
    val urgency: Urgency,
    val affectedUsers: Int,
    val priorityScore: Double?,
    val priorityExplanation: String?,
    val categoryName: String?,
    val serviceId: Long?,
    val responseDueAt: Instant?,
    val acknowledgedAt: Instant?,
    val resolvedAt: Instant?,
    val closedAt: Instant?,
    val resolutionSummary: String?,
    val rootCause: String?,
    val feedbackRating: Int?,
    val feedbackComment: String?,
    val updatedAt: Instant,
    val version: Long,
    val actions: List<IncidentAction>,
    val noteTypes: List<NoteType>,
    /** The request fields the caller may change with PATCH (names as in [UpdateIncidentRequest]); empty when EDIT is not in `actions`. */
    val editableFields: List<String> = emptyList(),
)

data class IncidentNoteResponse(val id: Long, val type: NoteType, val body: String, val author: PersonRef, val createdAt: Instant)
