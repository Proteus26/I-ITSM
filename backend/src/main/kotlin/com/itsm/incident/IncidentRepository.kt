package com.itsm.incident

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

/** JpaSpecificationExecutor lets the search module (P4) build its filters without touching this interface. */
interface IncidentRepository : JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {
    fun findByNumber(number: String): Incident?

    fun findByReporterId(reporterId: Long, pageable: Pageable): Page<Incident>

    fun existsByReporterIdAndTitle(reporterId: Long, title: String): Boolean

    fun findByAssigneeIdAndStatusIn(assigneeId: Long, statuses: Collection<IncidentStatus>, pageable: Pageable): Page<Incident>

    /** Active incidents per technician; assignment strategies (P5) use this as workload. */
    fun countByAssigneeIdAndStatusIn(assigneeId: Long, statuses: Collection<IncidentStatus>): Long
}

interface IncidentNoteRepository : JpaRepository<IncidentNote, Long> {
    fun findByIncidentIdOrderByCreatedAtAscIdAsc(incidentId: Long): List<IncidentNote>
}
