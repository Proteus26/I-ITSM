package com.itsm.sla

import com.itsm.common.exception.ItsmException
import com.itsm.common.exception.domainRequire
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

    responseMinutes: Int,
    resolutionMinutes: Int,
    warningThresholdPct: Int = DEFAULT_WARNING_PCT,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "response_minutes", nullable = false)
    final var responseMinutes: Int = responseMinutes
        private set

    @Column(name = "resolution_minutes", nullable = false)
    final var resolutionMinutes: Int = resolutionMinutes
        private set

    /** Percentage of the time window after which a warning is raised. */
    @Column(name = "warning_threshold_pct", nullable = false)
    final var warningThresholdPct: Int = warningThresholdPct
        private set

    init {
        validate(responseMinutes, resolutionMinutes, warningThresholdPct)
    }

    val isDefault: Boolean get() = serviceId == null

    /**
     * Changes the targets. Only incidents whose deadlines are computed afterwards (new or re-prioritised ones) see the
     * new values; deadlines already stored on incidents are not recalculated.
     */
    fun update(responseMinutes: Int, resolutionMinutes: Int, warningThresholdPct: Int) {
        validate(responseMinutes, resolutionMinutes, warningThresholdPct)
        this.responseMinutes = responseMinutes
        this.resolutionMinutes = resolutionMinutes
        this.warningThresholdPct = warningThresholdPct
    }

    companion object {
        const val DEFAULT_WARNING_PCT = 75
        const val MAX_MINUTES = 525_600 // one year

        fun validate(responseMinutes: Int, resolutionMinutes: Int, warningThresholdPct: Int) {
            domainRequire(responseMinutes in 1..MAX_MINUTES) { "Response time must be between 1 and $MAX_MINUTES minutes" }
            domainRequire(resolutionMinutes in 1..MAX_MINUTES) { "Resolution time must be between 1 and $MAX_MINUTES minutes" }
            domainRequire(resolutionMinutes >= responseMinutes) { "Resolution time must not be shorter than the response time" }
            domainRequire(warningThresholdPct in 1..99) { "Warning threshold must be between 1 and 99 percent" }
        }
    }
}

interface SlaPolicyRepository : JpaRepository<SlaPolicy, Long> {
    fun findByServiceIdAndPriority(serviceId: Long, priority: Priority): SlaPolicy?
    fun findByServiceIdIsNullAndPriority(priority: Priority): SlaPolicy?
    fun existsByServiceIdAndPriority(serviceId: Long, priority: Priority): Boolean
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
