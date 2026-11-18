package com.itsm.reports

import com.itsm.incident.IncidentStatus
import com.itsm.reference.SlaEventRef
import com.itsm.incident.Priority
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import com.itsm.common.exception.InvalidSearchException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Not in IncidentStatus's companion next to OPEN and ACTIVE (that file belongs to P2). Move it there if you prefer;
 * this extension then becomes redundant and can be deleted.
 */
val IncidentStatus.Companion.RESOLVED_OR_CLOSED: Set<IncidentStatus>
    get() = setOf(IncidentStatus.RESOLVED, IncidentStatus.VERIFIED, IncidentStatus.CLOSED)

/** open = IncidentStatus.OPEN. critical = open P1 incidents. resolved = RESOLVED, VERIFIED or CLOSED. */
data class OverviewReport(val total: Long, val open: Long, val critical: Long, val resolved: Long, val slaViolations: Long)

data class WorkloadRow(val technicianId: Long, val technicianName: String, val activeIncidents: Long)

data class ServiceCount(val serviceId: Long?, val serviceName: String, val incidents: Long)

/** percentage = incidents without an SLA breach / all non-cancelled incidents. 100.0 when there are none. */
data class SlaCompliance(val total: Long, val withinSla: Long, val breached: Long, val percentage: Double)

data class MttrRow(val priority: Priority, val resolvedCount: Long, val meanMinutes: Double?)

data class TrendPoint(val date: LocalDate, val count: Long)

/** All aggregation happens in the database; only the grouped result rows come back. */
@Service
@Transactional(readOnly = true)
class ReportService {
    @PersistenceContext
    private lateinit var em: EntityManager

    fun overview(): OverviewReport = OverviewReport(
        total = count("select count(i) from Incident i"),
        open = count("select count(i) from Incident i where i.status in :s", "s" to IncidentStatus.OPEN),
        critical = count(
            "select count(i) from Incident i where i.priority = :p and i.status in :s",
            "p" to Priority.P1, "s" to IncidentStatus.OPEN,
        ),
        resolved = count(
            "select count(i) from Incident i where i.status in :s", "s" to IncidentStatus.RESOLVED_OR_CLOSED,
        ),
        slaViolations = count(
            "select count(distinct e.incidentId) from SlaEventRef e where e.type in :t",
            "t" to SlaEventRef.BREACH_TYPES,
        ),
    )

    /** Active incidents (IncidentStatus.ACTIVE) per technician. Technicians with nothing assigned show up with 0. */
    fun technicianWorkload(): List<WorkloadRow> = rows(
        """select t.id, t.fullName, count(i.id)
           from Technician t left join Incident i on i.assigneeId = t.id and i.status in :active
           where t.active = true
           group by t.id, t.fullName
           order by count(i.id) desc, t.fullName""",
        "active" to IncidentStatus.ACTIVE,
    ).map { WorkloadRow((it[0] as Number).toLong(), it[1] as String, (it[2] as Number).toLong()) }

    fun byService(): List<ServiceCount> = rows(
        """select s.id, s.name, count(i.id)
           from Incident i left join ItServiceRef s on s.id = i.serviceId
           group by s.id, s.name
           order by count(i.id) desc""",
    ).map { ServiceCount((it[0] as Number?)?.toLong(), (it[1] as String?) ?: "Unspecified", (it[2] as Number).toLong()) }

    fun slaCompliance(): SlaCompliance {
        val total = count("select count(i) from Incident i where i.status <> :c", "c" to IncidentStatus.CANCELLED)
        val breached = count(
            """select count(distinct e.incidentId) from SlaEventRef e, Incident i
               where e.incidentId = i.id and i.status <> :c and e.type in :t""",
            "c" to IncidentStatus.CANCELLED, "t" to SlaEventRef.BREACH_TYPES,
        )
        val within = total - breached
        val pct = if (total == 0L) 100.0 else Math.round(within * 1000.0 / total) / 10.0
        return SlaCompliance(total, within, breached, pct)
    }

    /**
     * Mean time to resolve, in minutes, per priority: the average of resolved_at - created_at over incidents that
     * currently have a resolved_at. Reopening an incident clears resolved_at, so this measures the time from
     * creation to the LATEST resolution, and an incident that is reopened right now is left out until it is
     * resolved again.
     */
    fun mttr(): List<MttrRow> {
        val byPriority = rows(
            """select i.priority, avg((i.resolvedAt - i.createdAt) by second), count(i)
               from Incident i
               where i.resolvedAt is not null and i.priority is not null
               group by i.priority""",
        ).associate { r ->
            (r[0] as Priority) to Pair((r[1] as Number?)?.toDouble(), (r[2] as Number).toLong())
        }
        return Priority.entries.map { p ->
            val (avgSeconds, resolved) = byPriority[p] ?: (null to 0L)
            MttrRow(p, resolved, avgSeconds?.let { Math.round(it / 60.0 * 10) / 10.0 })
        }
    }

    /**
     * Incidents created per day for the last `days` days, today included. Days with none show 0.
     * The day boundary follows the database session time zone: run the database and JVM in UTC (or set
     * hibernate.jdbc.time_zone=UTC) so "today" matches the UTC dates the rest of P4 uses.
     */
    fun trend(days: Int): List<TrendPoint> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val start = today.minusDays(days - 1L)
        val perDay = rows(
            """select cast(i.createdAt as LocalDate), count(i)
               from Incident i
               where i.createdAt >= :since
               group by cast(i.createdAt as LocalDate)""",
            "since" to start.atStartOfDay(ZoneOffset.UTC).toInstant(),
        ).associate { (it[0] as LocalDate) to (it[1] as Number).toLong() }
        return (0 until days).map { start.plusDays(it.toLong()) }.map { TrendPoint(it, perDay[it] ?: 0L) }
    }

    private fun count(jpql: String, vararg params: Pair<String, Any>): Long {
        val q = em.createQuery(jpql, Long::class.javaObjectType)
        params.forEach { (k, v) -> q.setParameter(k, v) }
        return q.singleResult
    }

    private fun rows(jpql: String, vararg params: Pair<String, Any>): List<Array<Any?>> {
        val q = em.createQuery(jpql, Array<Any?>::class.java)
        params.forEach { (k, v) -> q.setParameter(k, v) }
        return q.resultList
    }
}

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasRole('ADMIN')")
class ReportController(private val reports: ReportService) {
    @GetMapping("/overview")
    fun overview() = reports.overview()

    @GetMapping("/technician-workload")
    fun technicianWorkload() = reports.technicianWorkload()

    @GetMapping("/by-service")
    fun byService() = reports.byService()

    @GetMapping("/sla-compliance")
    fun slaCompliance() = reports.slaCompliance()

    @GetMapping("/mttr")
    fun mttr() = reports.mttr()

    @GetMapping("/trend")
    fun trend(@RequestParam(defaultValue = "30") days: Int): List<TrendPoint> {
        if (days !in 1..365) throw InvalidSearchException("days must be between 1 and 365")
        return reports.trend(days)
    }
}
