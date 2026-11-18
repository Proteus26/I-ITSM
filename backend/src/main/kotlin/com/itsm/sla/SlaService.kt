package com.itsm.sla

import com.itsm.incident.Priority
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/** When an incident must be answered and resolved by. [policyId] is the policy these were computed from. */
data class SlaDeadlines(val responseDueAt: Instant, val resolutionDueAt: Instant, val policyId: Long?)

/** Computes SLA deadlines; P2 calls it when an incident gets its priority and stores the result with `Incident.applySla`. */
interface SlaService {
    /** Deadlines for an incident of [priority] on [serviceId] (null = no service), counted from [from]. */
    fun computeDeadlines(serviceId: Long?, priority: Priority, from: Instant): SlaDeadlines
}

@Service
class DefaultSlaService(private val resolver: SlaPolicyResolver) : SlaService {

    override fun computeDeadlines(serviceId: Long?, priority: Priority, from: Instant): SlaDeadlines {
        val policy = resolver.resolve(serviceId, priority)
        return SlaDeadlines(
            responseDueAt = from.plus(Duration.ofMinutes(policy.responseMinutes.toLong())),
            resolutionDueAt = from.plus(Duration.ofMinutes(policy.resolutionMinutes.toLong())),
            policyId = policy.id,
        )
    }
}
