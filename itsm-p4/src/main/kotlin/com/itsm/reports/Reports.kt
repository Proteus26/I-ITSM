package com.itsm.reports

import com.itsm.common.StatusGroups
import com.itsm.incident.IncidentStatus
import com.itsm.reference.SlaEventRef
import com.itsm.sla.Priority
import com.itsm.user.Role
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** open = not yet resolved or cancelled. critical = open P1 incidents. resolved = RESOLVED, VERIFIED or CLOSED. */
data class OverviewReport(val total: Long, val open: Long, val critical: Long, val resolved: Long, val slaViolations: Long)

data class WorkloadRow(val technicianId: Long, val technicianName: String, val activeIncidents: Long)

data class ServiceCount(val serviceId: Long?, val serviceName: String, val incidents: Long)

/** percentage = incidents without an SLA breach / all non-cancelled incidents. 100.0 when there are none. */
data class SlaCompliance(val total: Long, val withinSla: Long, val breached: Long, val percentage: Double)

data class MttrRow(val priority: Priority, val resolvedCount: Long, val meanMinutes: Double?)

data class TrendPoint(val date: LocalDate, val count: Long)

@Service
@Transactional(readOnly = true)
class ReportService {
    @PersistenceContext
    private lateinit var em: EntityManager

    fun overview(): OverviewReport = OverviewReport(
        total = count("select count(i) from Incident i"),
        open = count("select count(i) from Incident i where i.status in :s", "s" to StatusGroups.OPEN),
        critical = count(
            "select count(i) from Incident i where i.priority = :p and i.status in :s",
            "p" to Priority.P1, "s" to StatusGroups.OPEN,
        ),
        resolved = count("select count(i) from Incident i where i.status in :s", "s" to StatusGroups.RESOLVED_OR_CLOSED),
        slaViolations = count(
            "select count(distinct e.incidentId) from SlaEventRef e where e.type in :t",
            "t" to SlaEventRef.BREACH_TYPES,
        ),
    )

    /** Active incidents per technician. Technicians with nothing assigned show up with 0. */
    fun technicianWorkload(): List<WorkloadRow> = rows(
        """select u.id, u.fullName, count(i.id)
           from User u left join Incident i on i.assigneeId = u.id and i.status in :active
           where u.role = :role and u.active = true
           group by u.id, u.fullName
           order by count(i.id) desc, u.fullName""",
        "active" to StatusGroups.ACTIVE, "role" to Role.TECHNICIAN,
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

    /** Mean time to resolve, in minutes, from creation to first recorded resolution, per priority. */
    fun mttr(): List<MttrRow> {
        val resolved = rows("select i.priority, i.createdAt, i.resolvedAt from Incident i where i.resolvedAt is not null and i.priority is not null")
        val byPriority = resolved.groupBy { it[0] as Priority }
        return Priority.entries.map { p ->
            val list = byPriority[p].orEmpty()
            val mean = if (list.isEmpty()) null else {
                val avgSeconds = list.map { Duration.between(it[1] as Instant, it[2] as Instant).seconds }.average()
                Math.round(avgSeconds / 60.0 * 10) / 10.0
            }
            MttrRow(p, list.size.toLong(), mean)
        }
    }

    /** Incidents created per day for the last `days` days, today included. Days with none show 0. */
    fun trend(days: Int): List<TrendPoint> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val start = today.minusDays(days - 1L)
        val since = start.atStartOfDay(ZoneOffset.UTC).toInstant()
        val created = em.createQuery("select i.createdAt from Incident i where i.createdAt >= :since", Instant::class.java)
            .setParameter("since", since)
            .resultList
        val perDay = created.groupingBy { it.atZone(ZoneOffset.UTC).toLocalDate() }.eachCount()
        return (0 until days).map { start.plusDays(it.toLong()) }.map { TrendPoint(it, (perDay[it] ?: 0).toLong()) }
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
@PreAuthorize("hasAnyRole('ADMIN','IT_MANAGER')")
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
        if (days !in 1..365) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be between 1 and 365")
        return reports.trend(days)
    }
}
