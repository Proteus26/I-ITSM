package com.itsm.sla

import com.itsm.events.SlaBreached
import com.itsm.events.SlaWarningRaised
import com.itsm.incident.Incident
import com.itsm.incident.IncidentStatus
import com.itsm.incident.Priority
import com.itsm.sla.escalation.EscalationChain
import com.itsm.sla.escalation.EscalationContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** P3's own view on incidents, so the monitor does not need a method on P2's IncidentRepository. */
interface SlaIncidentRepository : JpaRepository<Incident, Long> {
    @Query(
        "select i from Incident i where i.status in :statuses and i.priority is not null " +
            "and (i.responseDueAt is not null or i.resolutionDueAt is not null)",
    )
    fun findMonitored(@Param("statuses") statuses: Collection<IncidentStatus>): List<Incident>
}

/**
 * Checks every open incident against its SLA deadlines.
 *
 * - The response deadline counts until the incident is acknowledged; the resolution deadline until it is resolved.
 * - There is ONE warning per incident (a deliberate, minimal choice): the first time the elapsed share of any active
 *   deadline reaches the policy's warning threshold, a single WARNING is recorded and [SlaWarningRaised] published,
 *   whichever window (response or resolution) got there first. The other window never produces a second warning.
 *   A warning is not raised for a deadline that is already breached.
 * - Once a deadline has passed, a RESPONSE_BREACH or RESOLUTION_BREACH is recorded, [SlaBreached] published and the
 *   [EscalationChain] runs.
 * - Each (incident, type) is recorded at most once (unique constraint on `sla_events`), so repeated runs are harmless.
 *   A reopened incident does not re-arm. Run on a single node, or add row locking, if the app is ever scaled out.
 * - Every incident is handled in its own transaction (REQUIRES_NEW): a failure for one incident (no policy configured,
 *   a failing listener, ...) is logged and does not roll back or stop the others.
 *
 * The start of each window is derived from its deadline and the policy (start = due minus policy minutes), so deadlines
 * computed from a later point than creation (re-prioritisation, reopen) are measured correctly.
 * Pausing the clock while an incident is PENDING_INFO is not implemented.
 */
@Component
class SlaMonitor(
    private val incidents: SlaIncidentRepository,
    private val events: SlaEventRepository,
    private val policyRepository: SlaPolicyRepository,
    private val escalation: EscalationChain,
    private val publisher: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(SlaMonitor::class.java)
    private val tx = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /** One monitoring pass at time [now]; returns how many SLA events (warnings and breaches) were recorded. */
    fun runOnce(now: Instant): Int {
        val candidateIds = tx.execute { incidents.findMonitored(IncidentStatus.OPEN).mapNotNull { it.id } }.orEmpty()
        if (candidateIds.isEmpty()) return 0
        val policies = policyRepository.findAll().associateBy { it.serviceId to it.priority }
        val recorded = events.findByIncidentIdIn(candidateIds).groupBy({ it.incidentId }, { it.type })
        var total = 0
        for (id in candidateIds) {
            total += try {
                tx.execute { checkOne(id, recorded[id].orEmpty().toSet(), policies, now) } ?: 0
            } catch (e: DataIntegrityViolationException) {
                log.debug("SLA events for incident {} were already recorded by another run", id)
                0
            } catch (e: Exception) {
                log.error("SLA check failed for incident {}", id, e)
                0
            }
        }
        return total
    }

    private fun checkOne(id: Long, existing: Set<SlaEventType>, policies: Map<Pair<Long?, Priority>, SlaPolicy>, now: Instant): Int {
        val incident = incidents.findById(id).orElse(null) ?: return 0
        val priority = incident.priority ?: return 0
        val policy = policies[incident.serviceId to priority] ?: policies[null to priority] ?: throw SlaNotConfiguredException(priority)
        return check(incident, policy, existing, now)
    }

    private fun check(incident: Incident, policy: SlaPolicy, existing: Set<SlaEventType>, now: Instant): Int {
        val id = requireNotNull(incident.id)
        // The deadlines still counting: event type recorded when missed, the deadline and the policy window length.
        val active = buildList {
            incident.responseDueAt?.takeIf { incident.acknowledgedAt == null }
                ?.let { add(Window(SlaEventType.RESPONSE_BREACH, it, policy.responseMinutes)) }
            incident.resolutionDueAt?.takeIf { incident.resolvedAt == null }
                ?.let { add(Window(SlaEventType.RESOLUTION_BREACH, it, policy.resolutionMinutes)) }
        }
        var count = 0

        for (w in active) {
            if (now.isAfter(w.due) && w.type !in existing) {
                events.saveAndFlush(SlaEvent(id, w.type, now)) // a unique violation surfaces here, before anything is published
                publisher.publishEvent(SlaBreached(id, null, w.type.name))
                escalation.escalate(EscalationContext(incident, w.type, now))
                count++
            }
        }

        if (SlaEventType.WARNING !in existing) {
            val percent = active.filter { !now.isAfter(it.due) }.maxOfOrNull { percentElapsed(it, now) }
            if (percent != null && percent >= policy.warningThresholdPct) {
                events.saveAndFlush(SlaEvent(id, SlaEventType.WARNING, now))
                publisher.publishEvent(SlaWarningRaised(id, null, percent))
                count++
            }
        }
        return count
    }

    private class Window(val type: SlaEventType, val due: Instant, val policyMinutes: Int)

    private fun percentElapsed(w: Window, now: Instant): Int {
        val start = w.due.minus(Duration.ofMinutes(w.policyMinutes.toLong()))
        val total = Duration.between(start, w.due).toMillis()
        if (total <= 0) return 100
        return (Duration.between(start, now).toMillis() * 100 / total).toInt().coerceIn(0, 100)
    }
}

/**
 * Calls [SlaMonitor.runOnce] on a fixed delay. Switched off with `itsm.sla.monitor-enabled=false` (as in tests,
 * which call runOnce themselves). Scheduling itself is enabled in P4Config.
 */
@Component
@ConditionalOnProperty("itsm.sla.monitor-enabled", havingValue = "true", matchIfMissing = true)
class SlaMonitorScheduler @Autowired constructor(private val monitor: SlaMonitor, private val clock: Clock) {

    @Scheduled(fixedDelayString = "\${itsm.sla.monitor-interval:60000}")
    fun tick() {
        monitor.runOnce(clock.instant())
    }
}
