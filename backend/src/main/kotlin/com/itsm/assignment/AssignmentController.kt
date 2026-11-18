package com.itsm.assignment

import com.itsm.common.exception.DomainValidationException
import com.itsm.incident.Priority
import com.itsm.user.TechnicianRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** One suggested technician for the admin UI. */
data class RecommendationResponse(val technicianId: Long, val technicianName: String, val strategy: String, val reason: String)

/** Admin-only helper for manual assignment (also protected by the /api/admin rule in SecurityConfig). */
@RestController
@RequestMapping("/api/admin/assignment")
@PreAuthorize("hasRole('ADMIN')")
class AssignmentController(private val assignment: AssignmentService, private val technicians: TechnicianRepository) {

    /** Best technicians for a category and priority, both optional; an empty list means nobody has capacity. */
    @GetMapping("/recommendations")
    fun recommendations(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) priority: String?,
    ): List<RecommendationResponse> {
        val results = assignment.recommend(AssignmentRequest(null, category?.trim()?.uppercase()?.ifEmpty { null }, parsePriority(priority)))
        val names = technicians.findAllById(results.map { it.technicianId }).associate { it.id to it.fullName }
        return results.map { RecommendationResponse(it.technicianId, names[it.technicianId] ?: "Unknown", it.strategy, it.reason) }
    }

    /** Case-insensitive ("p1" is P1); blank means none; anything else is a 400. */
    private fun parsePriority(raw: String?): Priority? {
        val value = raw?.trim()?.uppercase()?.ifEmpty { null } ?: return null
        return Priority.entries.firstOrNull { it.name == value }
            ?: throw DomainValidationException("Unknown priority '$raw'; expected one of ${Priority.entries.joinToString(", ")}")
    }
}
