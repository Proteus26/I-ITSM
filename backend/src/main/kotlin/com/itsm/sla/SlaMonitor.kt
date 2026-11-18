package com.itsm.sla

import com.itsm.events.SlaBreached
import com.itsm.events.SlaWarningRaised
import com.itsm.incident.Incident
import com.itsm.incident.IncidentStatus
import com.itsm.sla.escalation.EscalationChain
import com.itsm.sla.escalation.EscalationContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
 * - Once the elapsed share of an active deadline reaches the policy's warning threshold, a WARNING is recorded and
 *   [SlaWarningRaised] published. Once a deadline has passed, a RESPONSE_BREACH or RESOLUTION_BREACH is recorded,
 *   [SlaBreached] published and the [EscalationChain] runs. A warning is not raised for a deadline that is already breached.
 * - Each (incident, type) is recorded at most once (unique constraint on `sla_events`), so repeated runs are harmless.
 *   A reopened incident does not re-arm. Run on a single node, or add row locking, if the app is ever scaled out.
 *
 * Elapsed time is measured from the incident's creation. Pausing the clock while an incident is PENDING_INFO is not implemented.
 */
@Component
class SlaMonitor(
    private val incidents: SlaIncidentRepository,
    private val events: SlaEventRepository,
    private val policies: SlaPolicyResolver,
    private val escalation: EscalationChain,
    private val publisher: ApplicationEventPublisher,
) {

    /** One monitoring pass at time [now]; returns how many SLA events (warnings and breaches) were recorded. */
    @Transactional
    fun runOnce(now: Instant): Int {
        val candidates = incidents.findMonitored(IncidentStatus.OPEN)
        if (candidates.isEmpty()) return 0
        val recorded = events.findByIncidentIdIn(candidates.mapNotNull { it.id }).groupBy({ it.incidentId }, { it.type })
        return candidates.sumOf { check(it, recorded[it.id].orEmpty().toSet(), now) }
    }

    private fun check(incident: Incident, existing: Set<SlaEventType>, now: Instant): Int {
        val id = requireNotNull(incident.id)
        val created = incident.createdAt
        // The deadlines still counting, with the event type recorded when each is missed.
        val active = buildList {
            incident.responseDueAt?.takeIf { incident.acknowledgedAt == null }?.let { add(SlaEventType.RESPONSE_BREACH to it) }
            incident.resolutionDueAt?.takeIf { incident.resolvedAt == null }?.let { add(SlaEventType.RESOLUTION_BREACH to it) }
        }
        var count = 0

        for ((type, due) in active) {
            if (now.isAfter(due) && type !in existing) {
                events.save(SlaEvent(id, type, now))
                publisher.publishEvent(SlaBreached(id, null, type.name))
                escalation.escalate(EscalationContext(incident, type))
                count++
            }
        }

        if (SlaEventType.WARNING !in existing) {
            val threshold = policies.resolve(incident.serviceId, requireNotNull(incident.priority)).warningThresholdPct
            val percent = active.filter { !now.isAfter(it.second) }.maxOfOrNull { percentElapsed(created, it.second, now) }
            if (percent != null && percent >= threshold) {
                events.save(SlaEvent(id, SlaEventType.WARNING, now))
                publisher.publishEvent(SlaWarningRaised(id, null, percent))
                count++
            }
        }
        return count
    }

    private fun percentElapsed(start: Instant, due: Instant, now: Instant): Int {
        val total = Duration.between(start, due).toMillis()
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
