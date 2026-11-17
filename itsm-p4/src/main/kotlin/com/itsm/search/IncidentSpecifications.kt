package com.itsm.search

import com.itsm.common.StatusGroups
import com.itsm.incident.Incident
import com.itsm.incident.IncidentStatus
import com.itsm.reference.SlaEventRef
import com.itsm.sla.Priority
import com.itsm.user.Role
import com.itsm.user.User
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.data.jpa.domain.Specification
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

enum class SlaStatus { ON_TRACK, AT_RISK, BREACHED }

/** All ten search filters. Every field is optional; the ones that are set are combined with AND. */
data class IncidentSearchCriteria(
    val number: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val status: IncidentStatus? = null,
    val priority: Priority? = null,
    val category: String? = null,
    val service: Long? = null,
    val assignee: Long? = null,
    val reporter: Long? = null,
    val slaStatus: SlaStatus? = null,
    val q: String? = null,
)

/**
 * One small Specification per filter, so each can be tested alone and they combine freely.
 * Dates are read as UTC calendar days; `to` is inclusive.
 *
 * SLA status comes from P3's sla_events table:
 *   BREACHED = has a RESPONSE_BREACH or RESOLUTION_BREACH event
 *   AT_RISK  = still open, has a WARNING event and no breach
 *   ON_TRACK = everything else
 */
object IncidentSpecifications {

    fun matching(c: IncidentSearchCriteria): Specification<Incident> = Specification.allOf(
        listOfNotNull(
            c.number?.takeIf { it.isNotBlank() }?.let { numberContains(it) },
            c.from?.let { createdFrom(it) },
            c.to?.let { createdTo(it) },
            c.status?.let { hasStatus(it) },
            c.priority?.let { hasPriority(it) },
            c.category?.takeIf { it.isNotBlank() }?.let { inCategory(it) },
            c.service?.let { forService(it) },
            c.assignee?.let { assignedTo(it) },
            c.reporter?.let { reportedBy(it) },
            c.slaStatus?.let { withSlaStatus(it) },
            c.q?.takeIf { it.isNotBlank() }?.let { textContains(it) },
        ),
    )

    /** What the person searching is allowed to see. Admins, managers and team leads see everything. */
    fun visibleTo(viewer: User): Specification<Incident> = when (viewer.role) {
        Role.ADMIN, Role.IT_MANAGER, Role.TEAM_LEAD -> Specification<Incident> { _, _, cb -> cb.conjunction() }
        Role.TECHNICIAN -> Specification<Incident> { root, _, cb ->
            cb.or(
                cb.equal(root.get<Long>("assigneeId"), viewer.id),
                cb.equal(root.get<Long>("reporterId"), viewer.id),
            )
        }
        Role.EMPLOYEE -> reportedBy(viewer.id)
    }

    fun numberContains(number: String): Specification<Incident> = Specification { root, _, cb ->
        cb.like(cb.lower(root.get<String>("number")), like(number), ESCAPE)
    }

    fun createdFrom(day: LocalDate): Specification<Incident> = Specification { root, _, cb ->
        cb.greaterThanOrEqualTo(root.get<Instant>("createdAt"), day.atStartOfDay(ZoneOffset.UTC).toInstant())
    }

    fun createdTo(day: LocalDate): Specification<Incident> = Specification { root, _, cb ->
        cb.lessThan(root.get<Instant>("createdAt"), day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
    }

    fun hasStatus(status: IncidentStatus): Specification<Incident> = Specification { root, _, cb ->
        cb.equal(root.get<IncidentStatus>("status"), status)
    }

    fun hasPriority(priority: Priority): Specification<Incident> = Specification { root, _, cb ->
        cb.equal(root.get<Priority>("priority"), priority)
    }

    fun inCategory(code: String): Specification<Incident> = Specification { root, _, cb ->
        cb.equal(cb.upper(root.get<String>("categoryCode")), code.trim().uppercase())
    }

    fun forService(serviceId: Long): Specification<Incident> = Specification { root, _, cb ->
        cb.equal(root.get<Long>("serviceId"), serviceId)
    }

    fun assignedTo(userId: Long): Specification<Incident> = Specification { root, _, cb ->
        cb.equal(root.get<Long>("assigneeId"), userId)
    }

    fun reportedBy(userId: Long): Specification<Incident> = Specification { root, _, cb ->
        cb.equal(root.get<Long>("reporterId"), userId)
    }

    /** Free text: matches the title, description or number, ignoring case. */
    fun textContains(text: String): Specification<Incident> = Specification { root, _, cb ->
        val pattern = like(text)
        cb.or(
            cb.like(cb.lower(root.get<String>("title")), pattern, ESCAPE),
            cb.like(cb.lower(root.get<String>("description")), pattern, ESCAPE),
            cb.like(cb.lower(root.get<String>("number")), pattern, ESCAPE),
        )
    }

    fun withSlaStatus(status: SlaStatus): Specification<Incident> = Specification { root, query, cb ->
        val q = query!!
        val breached = hasSlaEvent(root, q, cb, SlaEventRef.BREACH_TYPES)
        when (status) {
            SlaStatus.BREACHED -> breached
            SlaStatus.AT_RISK -> cb.and(
                cb.not(breached),
                hasSlaEvent(root, q, cb, listOf(SlaEventRef.WARNING)),
                root.get<IncidentStatus>("status").`in`(StatusGroups.OPEN),
            )
            SlaStatus.ON_TRACK -> cb.and(
                cb.not(breached),
                cb.not(
                    cb.and(
                        hasSlaEvent(root, q, cb, listOf(SlaEventRef.WARNING)),
                        root.get<IncidentStatus>("status").`in`(StatusGroups.OPEN),
                    ),
                ),
            )
        }
    }

    private fun hasSlaEvent(
        root: Root<Incident>, query: CriteriaQuery<*>, cb: CriteriaBuilder, types: Collection<String>,
    ): Predicate {
        val sub = query.subquery(Long::class.javaObjectType)
        val event = sub.from(SlaEventRef::class.java)
        sub.select(event.get<Long>("id"))
            .where(
                cb.equal(event.get<Long>("incidentId"), root.get<Long>("id")),
                event.get<String>("type").`in`(types),
            )
        return cb.exists(sub)
    }

    private const val ESCAPE = '\\'

    /** "%text%" for a case-insensitive contains match, with LIKE wildcards in the user's text escaped. */
    private fun like(text: String): String {
        val escaped = text.trim().lowercase()
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}
