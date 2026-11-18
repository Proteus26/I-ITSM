package com.itsm.reference

import jakarta.persistence.*
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/*
 * Read-only views of tables owned by P3. P4 only needs a couple of columns from each, so it maps its own
 * small entities instead of depending on P3's classes. @Entity(name = ...) keeps the JPQL names unique even
 * if P3 maps the same tables with its own entities.
 *
 * Assumptions about P3's schema: it_services(id, name) and sla_events(id, incident_id, type, occurred_at)
 * with `type` stored as a string (WARNING / RESPONSE_BREACH / RESOLUTION_BREACH).
 */

@Entity(name = "ItServiceRef")
@Table(name = "it_services")
@Immutable
class ItServiceRef(
    @Id var id: Long = 0,
    @Column(nullable = false) var name: String = "",
)

@Entity(name = "SlaEventRef")
@Table(name = "sla_events")
@Immutable
class SlaEventRef(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "incident_id", nullable = false)
    var incidentId: Long = 0,

    @Column(nullable = false)
    var type: String = "",

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.now(),
) {
    companion object {
        const val WARNING = "WARNING"
        val BREACH_TYPES = listOf("RESPONSE_BREACH", "RESOLUTION_BREACH")
    }
}

interface ItServiceRefRepository : JpaRepository<ItServiceRef, Long>
interface SlaEventRefRepository : JpaRepository<SlaEventRef, Long>
