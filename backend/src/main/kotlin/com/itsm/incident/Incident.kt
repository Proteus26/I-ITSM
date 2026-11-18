package com.itsm.incident

import com.itsm.auth.UnauthorizedOperationException
import com.itsm.category.Category
import com.itsm.common.exception.domainRequire
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant
import java.time.temporal.ChronoUnit

/** What a successful transition changed; the service publishes it as a status-change event. */
data class StatusChange(val from: IncidentStatus, val to: IncidentStatus)

/**
 * The aggregate root of the incident lifecycle. Every change goes through a method that checks the rules,
 * so an incident cannot be put into an invalid state from outside. Time is passed in (never read here) so
 * tests control it.
 *
 * People are referenced by id (reporter, assignee) rather than by entity, which keeps this module independent
 * of the user module's classes.
 */
@Entity
@DynamicUpdate
@Table(name = "incidents")
class Incident(
    number: String,
    title: String,
    description: String,
    reporterId: Long,
    impact: Impact,
    urgency: Urgency,
    affectedUsers: Int = 1,
    serviceId: Long? = null,
    categoryCode: String? = null,
    createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    /** Human-readable id such as INC-000123, taken from a database sequence by [IncidentNumberGenerator]. */
    @Column(name = "number", nullable = false, unique = true, updatable = false)
    val number: String = requireText(number, "Number", 20)

    @Column(name = "title", nullable = false)
    final var title: String = requireText(title, "Title", MAX_TITLE)
        private set

    @Column(name = "description", nullable = false)
    final var description: String = requireText(description, "Description", MAX_TEXT)
        private set

    @Column(name = "reporter_id", nullable = false, updatable = false)
    val reporterId: Long = reporterId

    @Column(name = "category_code")
    final var categoryCode: String? = categoryCode?.trim()?.uppercase()?.ifEmpty { null }
        private set

    @Column(name = "service_id")
    final var serviceId: Long? = serviceId
        private set

    @Column(name = "assignee_id")
    final var assigneeId: Long? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "impact", nullable = false)
    final var impact: Impact = impact
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false)
    final var urgency: Urgency = urgency
        private set

    @Column(name = "affected_users", nullable = false)
    final var affectedUsers: Int = requireAffectedUsers(affectedUsers)
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "priority")
    final var priority: Priority? = null
        private set

    @Column(name = "priority_score")
    final var priorityScore: Double? = null
        private set

    /** Why the priority is what it is (from the priority strategy); null until a priority has been calculated. */
    @Column(name = "priority_explanation", length = MAX_EXPLANATION)
    final var priorityExplanation: String? = null
        private set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    final var status: IncidentStatus = IncidentStatus.NEW
        private set

    @Column(name = "response_due_at")
    final var responseDueAt: Instant? = null
        private set

    @Column(name = "resolution_due_at")
    final var resolutionDueAt: Instant? = null
        private set

    @Column(name = "acknowledged_at")
    final var acknowledgedAt: Instant? = null
        private set

    @Column(name = "resolved_at")
    final var resolvedAt: Instant? = null
        private set

    @Column(name = "closed_at")
    final var closedAt: Instant? = null
        private set

    @Column(name = "resolution_summary")
    final var resolutionSummary: String? = null
        private set

    @Column(name = "root_cause")
    final var rootCause: String? = null
        private set

    @Column(name = "feedback_rating")
    final var feedbackRating: Int? = null
        private set

    @Column(name = "feedback_comment")
    final var feedbackComment: String? = null
        private set

    // Truncated to the database precision so a POST response matches a later GET.
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = createdAt.truncatedTo(ChronoUnit.MICROS)

    @Column(name = "updated_at", nullable = false)
    final var updatedAt: Instant = this.createdAt
        private set

    /** Optimistic locking: saving a stale copy fails instead of overwriting someone else's change. */
    @Version
    @Column(name = "version", nullable = false)
    final var version: Long = 0
        private set

    /** The behaviour object for the current status (not persisted; derived from [status]). */
    val state: IncidentState get() = IncidentState.of(status)

    /** True if [actor] plays [party] on this incident. */
    fun isActing(actor: Actor, party: Party): Boolean = when (party) {
        Party.REPORTER -> actor.userId != null && actor.userId == reporterId
        Party.ASSIGNEE -> actor.userId != null && actor.userId == assigneeId
        Party.ADMIN -> actor.isAdmin
        Party.SYSTEM -> actor.isSystem
    }

    /** Whether [actor] may read this incident: the reporter, the assigned technician or an administrator. */
    fun isVisibleTo(actor: Actor): Boolean =
        actor.isSystem || isActing(actor, Party.REPORTER) || isActing(actor, Party.ASSIGNEE) || actor.isAdmin

    fun availableTransitions(actor: Actor): Set<IncidentStatus> = state.availableTargets(this, actor)

    /**
     * Moves the incident to [target] for the transitions that carry no data of their own: acknowledge, start work,
     * ask for information, cancel, verify, close and reopen. Throws if the lifecycle or the actor's role does not
     * allow it. CLASSIFIED, ASSIGNED and RESOLVED are refused here because they must record a category, an assignee
     * and a resolution; use [classify], [assign] and [resolve] for those. Reopening (RESOLVED -> IN_PROGRESS) clears
     * the previous resolution, including any feedback, so the next resolution starts fresh.
     */
    fun transitionTo(target: IncidentStatus, actor: Actor, now: Instant): StatusChange {
        when (target) {
            IncidentStatus.CLASSIFIED -> throw InvalidIncidentOperationException("Use classify() to move an incident to CLASSIFIED")
            IncidentStatus.ASSIGNED -> throw InvalidIncidentOperationException("Use assign() to move an incident to ASSIGNED")
            IncidentStatus.RESOLVED -> throw InvalidIncidentOperationException("Use resolve() to move an incident to RESOLVED")
            else -> Unit
        }
        return move(target, actor, now)
    }

    /** The internal status change shared by [transitionTo], [classify], [assign] and [resolve]; only checks the lifecycle and the actor. */
    private fun move(target: IncidentStatus, actor: Actor, now: Instant): StatusChange {
        state.checkTransition(this, target, actor)
        val from = status
        status = target
        when (target) {
            IncidentStatus.ACKNOWLEDGED -> acknowledgedAt = now
            IncidentStatus.RESOLVED -> resolvedAt = now
            IncidentStatus.CLOSED -> closedAt = now
            IncidentStatus.IN_PROGRESS -> if (from == IncidentStatus.RESOLVED) clearResolution() // reopened
            else -> Unit
        }
        touch(now)
        return StatusChange(from, target)
    }

    private fun clearResolution() {
        resolvedAt = null
        resolutionSummary = null
        rootCause = null
        feedbackRating = null
        feedbackComment = null
    }

    /**
     * NEW -> CLASSIFIED, recording the final [category]. The category must be active; the caller loads it, because
     * this class does not know the repository.
     */
    fun classify(category: Category, actor: Actor, now: Instant): StatusChange {
        domainRequire(category.active) { "Category ${category.code} is not active" }
        val change = move(IncidentStatus.CLASSIFIED, actor, now)
        categoryCode = category.code
        return change
    }

    /**
     * Stores the calculated priority with its [explanation] (cut to 500 characters); returns the previous priority
     * (null the first time) so the caller can detect a change.
     */
    fun applyPriority(priority: Priority, score: Double, now: Instant, explanation: String? = null): Priority? {
        val previous = this.priority
        this.priority = priority
        this.priorityScore = score
        this.priorityExplanation = explanation?.trim()?.ifEmpty { null }?.take(MAX_EXPLANATION)
        touch(now)
        return previous
    }

    fun applySla(responseDueAt: Instant, resolutionDueAt: Instant, now: Instant) {
        domainRequire(!resolutionDueAt.isBefore(responseDueAt)) { "The resolution deadline cannot be before the response deadline" }
        this.responseDueAt = responseDueAt
        this.resolutionDueAt = resolutionDueAt
        touch(now)
    }

    /**
     * Assigns a technician. From CLASSIFIED this moves the incident to ASSIGNED. From any active status (ASSIGNED,
     * ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO) it reassigns, for example on SLA escalation: the new technician is
     * set and the status goes back to ASSIGNED, so the new technician has to acknowledge (acknowledgedAt is cleared). Only the system or an
     * administrator may assign. Assigning the current assignee again changes nothing. Any other status throws
     * [InvalidStateTransitionException].
     *
     * Returns the status change, or null when the status did not change (reassignment while ASSIGNED, or no-op).
     */
    fun assign(technicianId: Long, actor: Actor, now: Instant): StatusChange? = when (status) {
        IncidentStatus.CLASSIFIED -> {
            val change = move(IncidentStatus.ASSIGNED, actor, now)
            assigneeId = technicianId
            change
        }
        in REASSIGNABLE_STATUSES -> {
            if (!(actor.isSystem || actor.isAdmin)) throw UnauthorizedOperationException("Only the system or an administrator can reassign an incident")
            if (assigneeId == technicianId) {
                null
            } else {
                val from = status
                assigneeId = technicianId
                status = IncidentStatus.ASSIGNED
                acknowledgedAt = null // the new technician has not responded yet; keeping it would hide a response-SLA breach
                touch(now)
                if (from == IncidentStatus.ASSIGNED) null else StatusChange(from, IncidentStatus.ASSIGNED)
            }
        }
        else -> throw InvalidStateTransitionException(status, IncidentStatus.ASSIGNED)
    }

    /** IN_PROGRESS -> RESOLVED with the resolution details. */
    fun resolve(summary: String, rootCause: String?, actor: Actor, now: Instant): StatusChange {
        state.checkTransition(this, IncidentStatus.RESOLVED, actor)
        val text = requireText(summary, "Resolution summary", MAX_TEXT)
        val cause = rootCause?.trim()?.ifEmpty { null }?.also {
            domainRequire(it.length <= MAX_ROOT_CAUSE) { "Root cause must be at most $MAX_ROOT_CAUSE characters" }
        }
        resolutionSummary = text
        this.rootCause = cause
        return move(IncidentStatus.RESOLVED, actor, now)
    }

    /** One rating (1-5) from the reporter, once the incident has been resolved. */
    fun submitFeedback(rating: Int, comment: String?, actor: Actor, now: Instant) {
        if (!isActing(actor, Party.REPORTER)) throw UnauthorizedOperationException("Only the reporter can give feedback")
        if (status !in FEEDBACK_STATUSES) throw InvalidIncidentOperationException("Feedback can only be given after the incident is resolved")
        if (feedbackRating != null) throw InvalidIncidentOperationException("Feedback has already been submitted")
        domainRequire(rating in 1..5) { "Rating must be between 1 and 5" }
        val text = comment?.trim()?.ifEmpty { null }
        domainRequire(text == null || text.length <= MAX_COMMENT) { "Comment must be at most $MAX_COMMENT characters" }
        feedbackRating = rating
        feedbackComment = text
        touch(now)
    }

    /** Whether [actor] could submit feedback right now (the same rules as [submitFeedback], without throwing). */
    fun canGiveFeedback(actor: Actor): Boolean =
        isActing(actor, Party.REPORTER) && status in FEEDBACK_STATUSES && feedbackRating == null

    private fun touch(now: Instant) {
        updatedAt = now.truncatedTo(ChronoUnit.MICROS)
    }

    companion object {
        const val MAX_TITLE = 200
        const val MAX_TEXT = 4000
        const val MAX_ROOT_CAUSE = 2000
        const val MAX_COMMENT = 1000
        const val MAX_EXPLANATION = 500
        private val REASSIGNABLE_STATUSES = setOf(
            IncidentStatus.ASSIGNED, IncidentStatus.ACKNOWLEDGED, IncidentStatus.IN_PROGRESS, IncidentStatus.PENDING_INFO,
        )
        private val FEEDBACK_STATUSES = setOf(IncidentStatus.RESOLVED, IncidentStatus.VERIFIED, IncidentStatus.CLOSED)

        private fun requireText(value: String, field: String, max: Int): String {
            val trimmed = value.trim()
            domainRequire(trimmed.isNotEmpty()) { "$field must not be blank" }
            domainRequire(trimmed.length <= max) { "$field must be at most $max characters" }
            return trimmed
        }

        private fun requireAffectedUsers(count: Int): Int {
            domainRequire(count >= 1) { "Affected users must be at least 1" }
            return count
        }
    }
}
