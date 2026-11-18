package com.itsm.incident

/** Buttons the UI may show for an incident; the names are part of the REST contract. */
enum class IncidentAction {
    ACKNOWLEDGE, START, REQUEST_INFO, RESUME, RESOLVE, VERIFY, REOPEN, CLOSE, CANCEL, FEEDBACK, ASSIGN, CLASSIFY,
}

/** Statuses from which an administrator can (re)assign; mirrors [Incident.assign]. */
private val ASSIGNABLE = setOf(
    IncidentStatus.CLASSIFIED, IncidentStatus.ASSIGNED, IncidentStatus.ACKNOWLEDGED, IncidentStatus.IN_PROGRESS, IncidentStatus.PENDING_INFO,
)

private val FINISHED = setOf(IncidentStatus.CLOSED, IncidentStatus.CANCELLED)

/**
 * What [actor] may do with this incident right now. Built from the domain's own rules ([Incident.availableTransitions],
 * [Incident.canGiveFeedback]) so the UI and the API never disagree. Several actions share a target status and are
 * told apart by the current status: START, RESUME and REOPEN all lead to IN_PROGRESS.
 */
fun Incident.actionsFor(actor: Actor): List<IncidentAction> {
    val targets = availableTransitions(actor)
    fun can(target: IncidentStatus) = target in targets
    return buildList {
        if (can(IncidentStatus.ACKNOWLEDGED)) add(IncidentAction.ACKNOWLEDGE)
        if (can(IncidentStatus.IN_PROGRESS)) {
            when (status) {
                IncidentStatus.ACKNOWLEDGED -> add(IncidentAction.START)
                IncidentStatus.PENDING_INFO -> add(IncidentAction.RESUME)
                IncidentStatus.RESOLVED -> add(IncidentAction.REOPEN)
                else -> Unit
            }
        }
        if (can(IncidentStatus.PENDING_INFO)) add(IncidentAction.REQUEST_INFO)
        if (can(IncidentStatus.RESOLVED)) add(IncidentAction.RESOLVE)
        if (can(IncidentStatus.VERIFIED)) add(IncidentAction.VERIFY)
        if (can(IncidentStatus.CLOSED)) add(IncidentAction.CLOSE)
        if (can(IncidentStatus.CANCELLED)) add(IncidentAction.CANCEL)
        if (canGiveFeedback(actor)) add(IncidentAction.FEEDBACK)
        if (actor.isAdmin && status == IncidentStatus.NEW) add(IncidentAction.CLASSIFY)
        if (actor.isAdmin && status in ASSIGNABLE) add(IncidentAction.ASSIGN)
    }
}

/**
 * Note types [actor] may add right now. INFO_REQUEST and INFO_RESPONSE are never listed: they are created by the
 * request-info and resume actions. INVESTIGATION is staff-only (assignee or administrator); COMMENT is open to everyone involved. Nothing can be added to a finished incident.
 */
fun Incident.noteTypesFor(actor: Actor): List<NoteType> {
    if (status in FINISHED) return emptyList()
    val reporter = isActing(actor, Party.REPORTER)
    val staff = isActing(actor, Party.ASSIGNEE) || actor.isAdmin
    return buildList {
        if (staff) add(NoteType.INVESTIGATION)
        if (reporter || staff) add(NoteType.COMMENT)
    }
}
