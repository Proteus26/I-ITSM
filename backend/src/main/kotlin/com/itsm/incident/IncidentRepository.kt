package com.itsm.incident

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

/** JpaSpecificationExecutor lets the search module (P4) build its filters without touching this interface. */
interface IncidentRepository : JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {
    fun findByNumber(number: String): Incident?

    fun findByReporterIdOrderByCreatedAtDesc(reporterId: Long): List<Incident>

    fun findByAssigneeIdAndStatusInOrderByCreatedAtDesc(assigneeId: Long, statuses: Collection<IncidentStatus>): List<Incident>

    /** Active incidents per technician; assignment strategies (P5) use this as workload. */
    fun countByAssigneeIdAndStatusIn(assigneeId: Long, statuses: Collection<IncidentStatus>): Long
}

interface IncidentNoteRepository : JpaRepository<IncidentNote, Long> {
    fun findByIncidentIdOrderByCreatedAtAsc(incidentId: Long): List<IncidentNote>
}
