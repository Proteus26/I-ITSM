package com.itsm.incident

import com.itsm.auth.UnauthorizedOperationException

/** The roles an actor can play with respect to one particular incident. */
enum class Party { REPORTER, ASSIGNEE, ADMIN, SYSTEM }

private val SYSTEM_OR_ADMIN = setOf(Party.SYSTEM, Party.ADMIN)
private val REPORTER_OR_ADMIN = setOf(Party.REPORTER, Party.ADMIN)

/**
 * State pattern: one object per [IncidentStatus]. Each state knows which statuses can follow it and who may
 * trigger each move, so [Incident] never needs a big `when` over statuses.
 *
 * Two different failures are kept apart: a move the lifecycle does not have ([InvalidStateTransitionException], 409)
 * and a move that exists but not for this actor ([UnauthorizedOperationException], 403).
 */
sealed class IncidentState(val status: IncidentStatus) {

    /** Target status -> who may trigger the move. */
    protected abstract val transitions: Map<IncidentStatus, Set<Party>>

    val allowedTransitions: Set<IncidentStatus> get() = transitions.keys

    fun canTransitionTo(target: IncidentStatus): Boolean = target in transitions

    fun partiesFor(target: IncidentStatus): Set<Party> = transitions[target].orEmpty()

    /** Statuses [actor] may move [incident] to right now (for showing the right buttons in the UI). */
    fun availableTargets(incident: Incident, actor: Actor): Set<IncidentStatus> =
        transitions.filterValues { parties -> parties.any { incident.isActing(actor, it) } }.keys

    fun checkTransition(incident: Incident, target: IncidentStatus, actor: Actor) {
        val parties = transitions[target] ?: throw InvalidStateTransitionException(status, target)
        if (parties.none { incident.isActing(actor, it) }) {
            throw UnauthorizedOperationException("You are not allowed to move this incident from $status to $target")
        }
    }

    companion object {
        fun of(status: IncidentStatus): IncidentState = when (status) {
            IncidentStatus.NEW -> NewState
            IncidentStatus.CLASSIFIED -> ClassifiedState
            IncidentStatus.ASSIGNED -> AssignedState
            IncidentStatus.ACKNOWLEDGED -> AcknowledgedState
            IncidentStatus.IN_PROGRESS -> InProgressState
            IncidentStatus.PENDING_INFO -> PendingInfoState
            IncidentStatus.RESOLVED -> ResolvedState
            IncidentStatus.VERIFIED -> VerifiedState
            IncidentStatus.CLOSED -> ClosedState
            IncidentStatus.CANCELLED -> CancelledState
        }
    }
}

object NewState : IncidentState(IncidentStatus.NEW) {
    override val transitions = mapOf(
        IncidentStatus.CLASSIFIED to SYSTEM_OR_ADMIN,
        IncidentStatus.CANCELLED to REPORTER_OR_ADMIN,
    )
}

object ClassifiedState : IncidentState(IncidentStatus.CLASSIFIED) {
    override val transitions = mapOf(
        IncidentStatus.ASSIGNED to SYSTEM_OR_ADMIN,
        IncidentStatus.CANCELLED to REPORTER_OR_ADMIN,
    )
}

/** Reassignment while ASSIGNED is not a status change; see [Incident.assign]. */
object AssignedState : IncidentState(IncidentStatus.ASSIGNED) {
    override val transitions = mapOf(
        IncidentStatus.ACKNOWLEDGED to setOf(Party.ASSIGNEE),
    )
}

object AcknowledgedState : IncidentState(IncidentStatus.ACKNOWLEDGED) {
    override val transitions = mapOf(
        IncidentStatus.IN_PROGRESS to setOf(Party.ASSIGNEE),
    )
}

object InProgressState : IncidentState(IncidentStatus.IN_PROGRESS) {
    override val transitions = mapOf(
        IncidentStatus.PENDING_INFO to setOf(Party.ASSIGNEE),
        IncidentStatus.RESOLVED to setOf(Party.ASSIGNEE),
    )
}

/** Back to IN_PROGRESS when the reporter answers, or when the technician carries on without an answer. */
object PendingInfoState : IncidentState(IncidentStatus.PENDING_INFO) {
    override val transitions = mapOf(
        IncidentStatus.IN_PROGRESS to setOf(Party.ASSIGNEE, Party.REPORTER),
    )
}

/** IN_PROGRESS from here is a reopen: the fix did not work. */
object ResolvedState : IncidentState(IncidentStatus.RESOLVED) {
    override val transitions = mapOf(
        IncidentStatus.VERIFIED to REPORTER_OR_ADMIN,
        IncidentStatus.IN_PROGRESS to REPORTER_OR_ADMIN,
    )
}

/** SYSTEM may close automatically (for example after a quiet period). */
object VerifiedState : IncidentState(IncidentStatus.VERIFIED) {
    override val transitions = mapOf(
        IncidentStatus.CLOSED to setOf(Party.REPORTER, Party.ADMIN, Party.SYSTEM),
    )
}

object ClosedState : IncidentState(IncidentStatus.CLOSED) {
    override val transitions = emptyMap<IncidentStatus, Set<Party>>()
}

object CancelledState : IncidentState(IncidentStatus.CANCELLED) {
    override val transitions = emptyMap<IncidentStatus, Set<Party>>()
}
