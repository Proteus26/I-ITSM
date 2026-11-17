package com.itsm.search

import com.itsm.common.CurrentUserProvider
import com.itsm.common.PageResponse
import com.itsm.incident.Incident
import com.itsm.incident.IncidentRepository
import com.itsm.incident.IncidentStatus
import com.itsm.reference.ItServiceRefRepository
import com.itsm.sla.Priority
import com.itsm.user.User
import com.itsm.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate

data class IncidentSearchResult(
    val id: Long,
    val number: String,
    val title: String,
    val status: IncidentStatus,
    val priority: Priority?,
    val categoryCode: String?,
    val serviceId: Long?,
    val serviceName: String?,
    val assigneeId: Long?,
    val assigneeName: String?,
    val reporterId: Long,
    val reporterName: String?,
    val createdAt: Instant,
    val resolvedAt: Instant?,
)

@Service
class IncidentSearchService(
    private val incidents: IncidentRepository,
    private val users: UserRepository,
    private val services: ItServiceRefRepository,
) {
    @Transactional(readOnly = true)
    fun search(criteria: IncidentSearchCriteria, pageable: Pageable, viewer: User): org.springframework.data.domain.Page<IncidentSearchResult> {
        val from = criteria.from
        val to = criteria.to
        if (from != null && to != null && from.isAfter(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must not be after 'to'")
        }

        val spec = Specification.allOf(listOf(IncidentSpecifications.matching(criteria), IncidentSpecifications.visibleTo(viewer)))
        val page = incidents.findAll(spec, pageable)

        // Look up display names for just this page, in two queries.
        val userIds = page.content.flatMap { listOfNotNull(it.assigneeId, it.reporterId) }.toSet()
        val serviceIds = page.content.mapNotNull { it.serviceId }.toSet()
        val userNames = users.findAllById(userIds).associate { it.id to it.fullName }
        val serviceNames = services.findAllById(serviceIds).associate { it.id to it.name }

        return page.map { i: Incident ->
            IncidentSearchResult(
                id = i.id, number = i.number, title = i.title, status = i.status, priority = i.priority,
                categoryCode = i.categoryCode,
                serviceId = i.serviceId, serviceName = i.serviceId?.let { serviceNames[it] },
                assigneeId = i.assigneeId, assigneeName = i.assigneeId?.let { userNames[it] },
                reporterId = i.reporterId, reporterName = userNames[i.reporterId],
                createdAt = i.createdAt, resolvedAt = i.resolvedAt,
            )
        }
    }
}

@RestController
@RequestMapping("/api/incidents")
class IncidentSearchController(
    private val service: IncidentSearchService,
    private val currentUser: CurrentUserProvider,
) {
    /**
     * GET /api/incidents/search?number=&from=2026-11-01&to=2026-11-30&status=&priority=&category=&service=
     *     &assignee=&reporter=&slaStatus=&q=&page=0&size=20&sort=createdAt,desc
     */
    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) number: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) status: IncidentStatus?,
        @RequestParam(required = false) priority: Priority?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) service: Long?,
        @RequestParam(required = false) assignee: Long?,
        @RequestParam(required = false) reporter: Long?,
        @RequestParam(required = false) slaStatus: SlaStatus?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "createdAt,desc") sort: String,
    ): PageResponse<IncidentSearchResult> {
        val criteria = IncidentSearchCriteria(number, from, to, status, priority, category, service, assignee, reporter, slaStatus, q)
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), parseSort(sort))
        return PageResponse.of(this.service.search(criteria, pageable, currentUser.current()))
    }

    private fun parseSort(sort: String): Sort {
        val parts = sort.split(",")
        val field = parts[0].trim()
        if (field !in SORTABLE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot sort by '$field'. Allowed: ${SORTABLE.joinToString()}")
        }
        val direction = if (parts.getOrNull(1)?.trim().equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC
        return Sort.by(direction, field).and(Sort.by(Sort.Direction.DESC, "id"))
    }

    private companion object {
        val SORTABLE = setOf("createdAt", "number", "title", "status", "priority", "resolvedAt")
    }
}
