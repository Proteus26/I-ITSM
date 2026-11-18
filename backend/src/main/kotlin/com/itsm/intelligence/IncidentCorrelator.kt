package com.itsm.intelligence

import com.itsm.auth.AuthenticatedUser
import com.itsm.auth.UnauthorizedOperationException
import com.itsm.auth.CurrentUser
import com.itsm.incident.Actor
import com.itsm.incident.Incident
import com.itsm.incident.IncidentNotFoundException
import com.itsm.incident.IncidentRepository
import com.itsm.incident.IncidentStatus
import com.itsm.service.ItServiceRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What the correlator needs to know about an open incident. */
data class CorrelationCandidate(
    val id: Long,
    val number: String,
    val title: String,
    val status: IncidentStatus,
    val serviceId: Long?,
    val categoryCode: String?,
    val createdAt: Instant,
)

/** What the incidents of a cluster have in common. */
enum class CorrelationKind { SERVICE, CATEGORY }

/** A group of open incidents that probably have one cause. [key] is stable for the same service or category. */
data class IncidentCluster(
    val key: String,
    val kind: CorrelationKind,
    val serviceId: Long?,
    val categoryCode: String?,
    val members: List<CorrelationCandidate>,
) {
    val count: Int get() = members.size
    val firstAt: Instant get() = members.minOf { it.createdAt }
    val lastAt: Instant get() = members.maxOf { it.createdAt }
}

/**
 * Incident correlation (intelligent feature B). Open incidents created inside a sliding window ending at "now"
 * (default the last 2 hours) are grouped by the affected service, or by category when they have no service; a group
 * of at least [minClusterSize] (default 3) is reported as a possible major incident with a common cause.
 *
 * An incident is in at most one cluster: the service wins over the category (an incident with a service is never
 * grouped by its category). Pure and deterministic: the clusters are ordered by size (largest first), then by the
 * latest incident, and the members of a cluster by creation time. Fetching and visibility are the
 * [IncidentCorrelationService]'s job.
 */
class IncidentCorrelator(
    val window: Duration = DEFAULT_WINDOW,
    val minClusterSize: Int = DEFAULT_MIN_SIZE,
) {
    init {
        require(!window.isNegative && !window.isZero) { "The correlation window must be positive" }
        require(minClusterSize >= 2) { "A cluster needs at least 2 incidents" }
    }

    fun correlate(candidates: List<CorrelationCandidate>, now: Instant): List<IncidentCluster> {
        val since = now.minus(window)
        val recent = candidates.filter { it.status in IncidentStatus.OPEN && !it.createdAt.isBefore(since) && !it.createdAt.isAfter(now) }
        val groups = recent.groupBy { groupKey(it) }.filterKeys { it != null }
        return groups.mapNotNull { (key, members) ->
            if (members.size < minClusterSize) return@mapNotNull null
            val sorted = members.sortedWith(compareBy({ it.createdAt }, { it.id }))
            val first = sorted.first()
            IncidentCluster(
                key = requireNotNull(key),
                kind = if (first.serviceId != null) CorrelationKind.SERVICE else CorrelationKind.CATEGORY,
                serviceId = first.serviceId, categoryCode = if (first.serviceId == null) first.categoryCode else null,
                members = sorted,
            )
        }.sortedWith(compareByDescending<IncidentCluster> { it.count }.thenByDescending { it.lastAt }.thenBy { it.key })
    }

    private fun groupKey(c: CorrelationCandidate): String? = when {
        c.serviceId != null -> "service:${c.serviceId}"
        c.categoryCode != null -> "category:${c.categoryCode}"
        else -> null
    }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofHours(2)
        const val DEFAULT_MIN_SIZE = 3
    }
}

data class ClusterMember(val id: Long, val number: String, val title: String, val status: IncidentStatus, val createdAt: Instant)

/** A cluster as the API shows it. [label] is the service name or the category code. */
data class ClusterResponse(
    val key: String,
    val kind: CorrelationKind,
    val label: String,
    val serviceId: Long?,
    val categoryCode: String?,
    val count: Int,
    val firstAt: Instant,
    val lastAt: Instant,
    val windowMinutes: Long,
    val incidentIds: List<Long>,
    val incidentNumbers: List<String>,
    val incidents: List<ClusterMember>,
)

/** The cluster an incident belongs to, seen by someone who may not see all of its members: [related] holds only the visible ones. */
data class RelatedIncidentsResponse(
    val key: String,
    val kind: CorrelationKind,
    val label: String,
    /** Size of the whole cluster, visible or not. */
    val totalCount: Int,
    val windowMinutes: Long,
    val related: List<ClusterMember>,
)

/**
 * Loads the recent open incidents (at most [MAX_CANDIDATES], newest first) and runs the [IncidentCorrelator].
 * Configurable with `itsm.intelligence.correlation.window-minutes` (default 120) and `.min-size` (default 3).
 */
@Service
class IncidentCorrelationService(
    private val em: EntityManager,
    private val incidents: IncidentRepository,
    private val services: ItServiceRepository,
    private val clock: Clock,
    @Value("\${itsm.intelligence.correlation.window-minutes:120}") windowMinutes: Long,
    @Value("\${itsm.intelligence.correlation.min-size:3}") minSize: Int,
) {
    private val correlator = IncidentCorrelator(Duration.ofMinutes(windowMinutes.coerceAtLeast(1)), minSize.coerceAtLeast(2))

    @Transactional(readOnly = true)
    fun clusters(): List<ClusterResponse> {
        val found = findClusters()
        return respond(found)
    }

    /** The cluster [incidentId] is part of, with the members [viewer] may see (never the incident itself), or null. */
    @Transactional(readOnly = true)
    fun relatedTo(incidentId: Long, viewer: AuthenticatedUser): RelatedIncidentsResponse? {
        val incident = incidents.findById(incidentId).orElseThrow { IncidentNotFoundException(incidentId) }
        val actor = Actor.of(viewer)
        if (!incident.isVisibleTo(actor)) throw UnauthorizedOperationException("You are not allowed to see this incident")
        val cluster = findClusters().firstOrNull { c -> c.members.any { it.id == incidentId } } ?: return null
        val response = respond(listOf(cluster)).single()
        val visibleIds = incidents.findAllById(cluster.members.map { it.id }.filter { it != incidentId })
            .filter { it.isVisibleTo(actor) }.mapNotNullTo(mutableSetOf()) { it.id }
        return RelatedIncidentsResponse(
            key = response.key, kind = response.kind, label = response.label, totalCount = response.count,
            windowMinutes = response.windowMinutes, related = response.incidents.filter { it.id in visibleIds },
        )
    }

    private fun findClusters(): List<IncidentCluster> {
        val now = clock.instant()
        val since = now.minus(correlator.window)
        val recent = em.createQuery(
            "select i from Incident i where i.status in :s and i.createdAt >= :since " +
                "and (i.serviceId is not null or i.categoryCode is not null) order by i.createdAt desc",
            Incident::class.java,
        ).setParameter("s", IncidentStatus.OPEN).setParameter("since", since).setMaxResults(MAX_CANDIDATES).resultList
        return correlator.correlate(
            recent.map { CorrelationCandidate(requireNotNull(it.id), it.number, it.title, it.status, it.serviceId, it.categoryCode, it.createdAt) },
            now,
        )
    }

    private fun respond(found: List<IncidentCluster>): List<ClusterResponse> {
        val serviceIds = found.mapNotNullTo(mutableSetOf()) { it.serviceId }
        val names = if (serviceIds.isEmpty()) emptyMap() else services.findAllById(serviceIds).associate { requireNotNull(it.id) to it.name }
        return found.map { c ->
            val members = c.members.map { ClusterMember(it.id, it.number, it.title, it.status, it.createdAt) }
            ClusterResponse(
                key = c.key, kind = c.kind,
                label = c.serviceId?.let { names[it] ?: "Service $it" } ?: c.categoryCode.orEmpty(),
                serviceId = c.serviceId, categoryCode = c.categoryCode, count = c.count, firstAt = c.firstAt, lastAt = c.lastAt,
                windowMinutes = correlator.window.toMinutes(),
                incidentIds = members.map { it.id }, incidentNumbers = members.map { it.number }, incidents = members,
            )
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 500
    }
}

@RestController
@Tag(name = "Incident correlation")
class CorrelationController(private val service: IncidentCorrelationService, private val currentUser: CurrentUser) {

    @Operation(
        summary = "Possible major incidents",
        description = "Administrators only. Clusters of at least 3 open incidents created in the last 2 hours for the same service (or the same category when they have no service).",
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/correlations")
    fun clusters(): List<ClusterResponse> = service.clusters()

    @Operation(
        summary = "Related incidents of an incident",
        description = "The other incidents in the same cluster that the caller may see. 204 when the incident is in no cluster. 403 if the caller cannot see the incident.",
    )
    @GetMapping("/api/incidents/{id}/related")
    fun related(@Parameter(description = "Incident id") @PathVariable id: Long): ResponseEntity<RelatedIncidentsResponse> =
        service.relatedTo(id, currentUser.get())?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
}
