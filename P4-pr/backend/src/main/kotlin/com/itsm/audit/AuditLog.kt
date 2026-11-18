package com.itsm.audit

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.Instant

@Entity
@Table(name = "audit_log")
class AuditLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.now(),

    @Column(name = "actor_id")
    var actorId: Long? = null,

    @Column(nullable = false, length = 64)
    var action: String = "",

    @Column(name = "incident_id")
    var incidentId: Long? = null,

    @Column(name = "from_state", length = 32)
    var fromState: String? = null,

    @Column(name = "to_state", length = 32)
    var toState: String? = null,

    @Column(nullable = false, length = 1000)
    var description: String = "",

    /** Event-specific extras. JSONB on PostgreSQL. Keep values to strings and numbers. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    var details: Map<String, Any?> = emptyMap(),
)

interface AuditLogRepository : JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    fun findByIncidentIdOrderByOccurredAtAscIdAsc(incidentId: Long): List<AuditLog>
}

data class AuditEntryDto(
    val id: Long?,
    val occurredAt: Instant,
    val actorId: Long?,
    val action: String,
    val incidentId: Long?,
    val fromState: String?,
    val toState: String?,
    val description: String,
) {
    companion object {
        fun from(a: AuditLog) = AuditEntryDto(
            a.id, a.occurredAt, a.actorId, a.action, a.incidentId, a.fromState, a.toState, a.description,
        )
    }
}
