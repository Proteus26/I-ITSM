package com.itsm.sla

import com.itsm.common.exception.ItsmException
import com.itsm.incident.Priority
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Response and resolution targets for one priority, for one service ([serviceId] set) or as the default (null). */
@Entity
@Table(name = "sla_policies")
class SlaPolicy(
    @Column(name = "service_id")
    val serviceId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    val priority: Priority,

    @Column(name = "response_minutes", nullable = false)
    val responseMinutes: Int,

    @Column(name = "resolution_minutes", nullable = false)
    val resolutionMinutes: Int,

    /** Percentage of the time window after which a warning is raised. */
    @Column(name = "warning_threshold_pct", nullable = false)
    val warningThresholdPct: Int = 75,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}

interface SlaPolicyRepository : JpaRepository<SlaPolicy, Long> {
    fun findByServiceIdAndPriority(serviceId: Long, priority: Priority): SlaPolicy?
    fun findByServiceIdIsNullAndPriority(priority: Priority): SlaPolicy?
}

/** No policy (not even the default one) exists for a priority; only possible if the seeded defaults were deleted. */
class SlaNotConfiguredException(priority: Priority) :
    ItsmException("No SLA policy is configured for priority $priority", HttpStatus.INTERNAL_SERVER_ERROR)

/** Picks the policy for a service and priority: the service's own policy if it has one, else the default. */
@Component
class SlaPolicyResolver(private val policies: SlaPolicyRepository) {

    @Transactional(readOnly = true)
    fun resolve(serviceId: Long?, priority: Priority): SlaPolicy =
        (serviceId?.let { policies.findByServiceIdAndPriority(it, priority) } ?: policies.findByServiceIdIsNullAndPriority(priority))
            ?: throw SlaNotConfiguredException(priority)
}
