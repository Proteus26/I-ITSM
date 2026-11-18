package com.itsm.incident

import com.itsm.sla.Priority
import jakarta.persistence.*
import java.time.Instant

/**
 * STUB: owned by P2. This is only the subset of columns P4 reads (search, reports, notifications, audit).
 * Delete this file when P2's real Incident is merged. P4 expects these property names on it:
 * number, title, description, reporterId, categoryCode, serviceId, assigneeId, priority, status,
 * createdAt, resolvedAt. If P2 uses relations (e.g. `reporter: User`) instead of plain id columns,
 * adjust IncidentSpecifications and ReportService (search for "reporterId" / "assigneeId" / "serviceId").
 */
@Entity
@Table(name = "incidents")
class Incident(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false, unique = true)
    var number: String = "",

    @Column(nullable = false)
    var title: String = "",

    @Column(length = 4000)
    var description: String = "",

    @Column(name = "reporter_id", nullable = false)
    var reporterId: Long = 0,

    @Column(name = "category_code")
    var categoryCode: String? = null,

    @Column(name = "service_id")
    var serviceId: Long? = null,

    @Column(name = "assignee_id")
    var assigneeId: Long? = null,

    @Enumerated(EnumType.STRING)
    var priority: Priority? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: IncidentStatus = IncidentStatus.NEW,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
)
