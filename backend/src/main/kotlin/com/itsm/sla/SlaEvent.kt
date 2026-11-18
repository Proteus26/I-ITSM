package com.itsm.sla

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

enum class SlaEventType { WARNING, RESPONSE_BREACH, RESOLUTION_BREACH }

/** Written once per (incident, type) by [SlaMonitor]; the database enforces that with a unique constraint. P4 reads the same table. */
@Entity(name = "SlaEvent")
@Table(name = "sla_events")
class SlaEvent(
    @Column(name = "incident_id", nullable = false, updatable = false)
    val incidentId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    val type: SlaEventType,

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}

interface SlaEventRepository : JpaRepository<SlaEvent, Long> {
    fun findByIncidentIdIn(incidentIds: Collection<Long>): List<SlaEvent>
}
