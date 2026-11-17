package com.itsm.audit

import com.itsm.common.CurrentUserProvider
import com.itsm.common.PageResponse
import com.itsm.incident.IncidentRepository
import com.itsm.user.Role
import com.itsm.user.User
import jakarta.persistence.criteria.Root
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class AuditQueryService(
    private val repo: AuditLogRepository,
    private val incidents: IncidentRepository,
) {
    private val privileged = setOf(Role.ADMIN, Role.IT_MANAGER, Role.TEAM_LEAD)

    /** Timeline for one incident, oldest first. Same visibility rule as the incident detail page. */
    @Transactional(readOnly = true)
    fun history(incidentId: Long, viewer: User): List<AuditEntryDto> {
        val incident = incidents.findById(incidentId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found")
        }
        val allowed = viewer.role in privileged || incident.reporterId == viewer.id || incident.assigneeId == viewer.id
        if (!allowed) throw ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this incident")
        return repo.findByIncidentIdOrderByOccurredAtAscIdAsc(incidentId).map(AuditEntryDto::from)
    }

    @Transactional(readOnly = true)
    fun search(
        actor: Long?, action: String?, incident: Long?, from: LocalDate?, to: LocalDate?, pageable: Pageable,
    ): PageResponse<AuditEntryDto> {
        if (from != null && to != null && from.isAfter(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must not be after 'to'")
        }
        val specs = listOfNotNull<Specification<AuditLog>>(
            actor?.let { a -> Specification<AuditLog> { r: Root<AuditLog>, _, cb -> cb.equal(r.get<Long>("actorId"), a) } },
            incident?.let { i -> Specification<AuditLog> { r, _, cb -> cb.equal(r.get<Long>("incidentId"), i) } },
            action?.takeIf { it.isNotBlank() }?.let { a ->
                Specification<AuditLog> { r, _, cb -> cb.equal(r.get<String>("action"), a.trim().uppercase()) }
            },
            from?.let { f ->
                val start = f.atStartOfDay(ZoneOffset.UTC).toInstant()
                Specification<AuditLog> { r, _, cb -> cb.greaterThanOrEqualTo(r.get<Instant>("occurredAt"), start) }
            },
            to?.let { t ->
                val end = t.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                Specification<AuditLog> { r, _, cb -> cb.lessThan(r.get<Instant>("occurredAt"), end) }
            },
        )
        return PageResponse.of(repo.findAll(Specification.allOf(specs), pageable).map(AuditEntryDto::from))
    }
}

@RestController
class AuditController(
    private val service: AuditQueryService,
    private val currentUser: CurrentUserProvider,
) {
    /** Used by P2's incident detail page for its timeline. */
    @GetMapping("/api/incidents/{id}/history")
    fun history(@PathVariable id: Long): List<AuditEntryDto> = service.history(id, currentUser.current())

    @PreAuthorize("hasAnyRole('ADMIN','IT_MANAGER')")
    @GetMapping("/api/admin/audit")
    fun search(
        @RequestParam(required = false) actor: Long?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) incident: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): PageResponse<AuditEntryDto> {
        val pageable = PageRequest.of(
            page.coerceAtLeast(0), size.coerceIn(1, 100),
            Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id")),
        )
        return service.search(actor, action, incident, from, to, pageable)
    }
}
