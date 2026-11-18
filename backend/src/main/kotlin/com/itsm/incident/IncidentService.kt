package com.itsm.incident

import com.itsm.auth.AuthenticatedUser
import com.itsm.auth.UnauthorizedOperationException
import com.itsm.category.Category
import com.itsm.category.CategoryRepository
import com.itsm.common.exception.DomainValidationException
import com.itsm.common.exception.domainRequire
import com.itsm.events.IncidentAssigned
import com.itsm.events.IncidentClosed
import com.itsm.events.IncidentReported
import com.itsm.events.IncidentResolved
import com.itsm.events.InfoRequested
import com.itsm.events.NoteAdded
import com.itsm.events.StatusChanged
import com.itsm.service.ItServiceRepository
import com.itsm.user.PageResponse
import com.itsm.user.Role
import com.itsm.user.TechnicianRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * The use cases of the incident module. Each public method is one transaction: it loads the incident, checks that
 * the caller may see it, lets the [Incident] aggregate enforce the lifecycle (409 for a move the lifecycle does not
 * have, 403 for a move this actor may not make), saves, and publishes the events the other modules rely on
 * (see docs/p4.md, "Rules for publishers"). Methods return DTOs because the transaction ends here (no open session in the view).
 *
 * The caller is passed in rather than read from the security context so the demo seeder and tests can act as any user.
 */
@Service
@Transactional
class IncidentService(
    private val incidents: IncidentRepository,
    private val notes: IncidentNoteRepository,
    private val categories: CategoryRepository,
    private val services: ItServiceRepository,
    private val technicians: TechnicianRepository,
    private val numbers: IncidentNumberGenerator,
    private val pipeline: IncidentProcessingPipeline,
    private val assembler: IncidentAssembler,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    // ---- reporting -------------------------------------------------------------------------------------------------

    /**
     * Creates an incident for [reporter] and runs it through the [IncidentProcessingPipeline]. The result is
     * CLASSIFIED or ASSIGNED when a category is given or can be guessed, and stays NEW otherwise.
     */
    fun report(reporter: AuthenticatedUser, request: ReportIncidentRequest): IncidentDetail {
        val category = request.categoryCode?.takeIf { it.isNotBlank() }?.let { requireActiveCategory(it) }
        request.serviceId?.let { id ->
            val service = services.findById(id).orElse(null)
            domainRequire(service != null && service.active) { "Service $id does not exist or is not active" }
        }
        val now = clock.instant()
        val incident = incidents.save(
            Incident(
                number = numbers.next(), title = request.title, description = request.description, reporterId = reporter.id,
                impact = request.impact, urgency = request.urgency, affectedUsers = request.affectedUsers,
                serviceId = request.serviceId, categoryCode = category?.code, createdAt = now,
            ),
        )
        events.publishEvent(IncidentReported(requireNotNull(incident.id), reporter.id, incident.title))
        pipeline.process(incident, ProcessingContext(Actor.SYSTEM, now, category))
        return finish(incident, reporter)
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun get(id: Long, viewer: AuthenticatedUser): IncidentDetail =
        assembler.detail(loadVisible(id, viewer), Actor.of(viewer))

    /** Incidents [viewer] reported, newest first. [size] is capped at [MAX_PAGE_SIZE]. */
    @Transactional(readOnly = true)
    fun mine(viewer: AuthenticatedUser, page: Int, size: Int): PageResponse<IncidentSummary> =
        pageOf(incidents.findByReporterId(viewer.id, pageRequest(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))))

    /** Active incidents assigned to the technician [viewer], most urgent first (priority, then resolution deadline). Technicians only. */
    @Transactional(readOnly = true)
    fun assigned(viewer: AuthenticatedUser, page: Int, size: Int): PageResponse<IncidentSummary> {
        if (viewer.role != Role.TECHNICIAN) throw UnauthorizedOperationException("Only technicians have assigned incidents")
        val sort = Sort.by(Sort.Order.asc("priority"), Sort.Order.asc("resolutionDueAt"), Sort.Order.asc("id"))
        return pageOf(incidents.findByAssigneeIdAndStatusIn(viewer.id, IncidentStatus.ACTIVE, pageRequest(page, size, sort)))
    }

    /** The thread of notes; the reporter, when an employee, does not see INVESTIGATION notes. */
    @Transactional(readOnly = true)
    fun notes(id: Long, viewer: AuthenticatedUser): List<IncidentNoteResponse> {
        val incident = loadVisible(id, viewer)
        val all = notes.findByIncidentIdOrderByCreatedAtAscIdAsc(requireNotNull(incident.id))
        val visible = if (viewer.role == Role.EMPLOYEE) all.filter { it.type != NoteType.INVESTIGATION } else all
        return assembler.notes(visible)
    }

    // ---- notes -----------------------------------------------------------------------------------------------------

    fun addNote(id: Long, viewer: AuthenticatedUser, request: AddNoteRequest): IncidentNoteResponse {
        val incident = loadVisible(id, viewer)
        val actor = Actor.of(viewer)
        if (request.type == NoteType.INFO_REQUEST) {
            throw DomainValidationException("An information request is created with the request-info action, not as a note")
        }
        if (request.type !in incident.noteTypesFor(actor)) {
            throw UnauthorizedOperationException("You cannot add a ${request.type} note to this incident now")
        }
        val note = saveNote(incident, viewer, request.type, request.body)
        events.publishEvent(NoteAdded(requireNotNull(incident.id), viewer.id, request.type.name))
        return assembler.notes(listOf(note)).single()
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    /** ASSIGNED -> ACKNOWLEDGED, by the assignee. */
    fun acknowledge(id: Long, viewer: AuthenticatedUser): IncidentDetail = act(id, viewer) { i, actor ->
        move(i, IncidentStatus.ACKNOWLEDGED, actor)
    }

    /** ACKNOWLEDGED -> IN_PROGRESS, by the assignee. */
    fun start(id: Long, viewer: AuthenticatedUser): IncidentDetail = act(id, viewer) { i, actor ->
        move(i, IncidentStatus.IN_PROGRESS, actor, only = IncidentStatus.ACKNOWLEDGED)
    }

    /** IN_PROGRESS -> PENDING_INFO, by the assignee; the question is kept as an INFO_REQUEST note. */
    fun requestInfo(id: Long, viewer: AuthenticatedUser, request: RequestInfoRequest): IncidentDetail = act(id, viewer) { i, actor ->
        move(i, IncidentStatus.PENDING_INFO, actor, only = IncidentStatus.IN_PROGRESS)
        val note = saveNote(i, viewer, NoteType.INFO_REQUEST, request.question)
        events.publishEvent(InfoRequested(requireNotNull(i.id), viewer.id, note.body))
    }

    /**
     * PENDING_INFO -> IN_PROGRESS, by the reporter (who may answer) or the assignee. A non-blank response is stored
     * as an INFO_RESPONSE note when the reporter sends it, and as a COMMENT when the assignee does.
     */
    fun resume(id: Long, viewer: AuthenticatedUser, request: ResumeRequest): IncidentDetail = act(id, viewer) { i, actor ->
        move(i, IncidentStatus.IN_PROGRESS, actor, only = IncidentStatus.PENDING_INFO)
        request.response?.takeIf { it.isNotBlank() }?.let {
            saveNote(i, viewer, if (i.isActing(actor, Party.REPORTER)) NoteType.INFO_RESPONSE else NoteType.COMMENT, it)
        }
    }

    /** IN_PROGRESS -> RESOLVED, by the assignee, recording the resolution. */
    fun resolve(id: Long, viewer: AuthenticatedUser, request: ResolveRequest): IncidentDetail = act(id, viewer) { i, actor ->
        val change = i.resolve(request.summary, request.rootCause, actor, clock.instant())
        publishStatus(i, actor, change)
        events.publishEvent(IncidentResolved(requireNotNull(i.id), viewer.id, i.resolutionSummary))
    }

    /** RESOLVED -> VERIFIED, by the reporter (or an administrator). */
    fun verify(id: Long, viewer: AuthenticatedUser): IncidentDetail = act(id, viewer) { i, actor ->
        move(i, IncidentStatus.VERIFIED, actor)
    }

    /** RESOLVED -> IN_PROGRESS: the fix did not work. The previous resolution and feedback are cleared; the reason is kept as a COMMENT. */
    fun reopen(id: Long, viewer: AuthenticatedUser, request: ReasonRequest): IncidentDetail = act(id, viewer) { i, actor ->
        move(i, IncidentStatus.IN_PROGRESS, actor, only = IncidentStatus.RESOLVED)
        request.reason?.takeIf { it.isNotBlank() }?.let { saveNote(i, viewer, NoteType.COMMENT, it) }
    }

    /** VERIFIED -> CLOSED. */
    fun close(id: Long, viewer: AuthenticatedUser): IncidentDetail = act(id, viewer) { i, actor ->
        move(i, IncidentStatus.CLOSED, actor)
        events.publishEvent(IncidentClosed(requireNotNull(i.id), viewer.id))
    }

    /** NEW or CLASSIFIED -> CANCELLED, by the reporter or an administrator; the reason is kept as a COMMENT. */
    fun cancel(id: Long, viewer: AuthenticatedUser, request: ReasonRequest): IncidentDetail = act(id, viewer) { i, actor ->
        move(i, IncidentStatus.CANCELLED, actor)
        request.reason?.takeIf { it.isNotBlank() }?.let { saveNote(i, viewer, NoteType.COMMENT, it) }
    }

    /** One rating from the reporter once the incident is resolved. */
    fun feedback(id: Long, viewer: AuthenticatedUser, request: FeedbackRequest): IncidentDetail = act(id, viewer) { i, actor ->
        i.submitFeedback(request.rating, request.comment, actor, clock.instant())
    }

    // ---- administration --------------------------------------------------------------------------------------------

    /**
     * NEW -> CLASSIFIED by an administrator, then the rest of the pipeline runs: the priority is recalculated with the
     * category, the SLA deadlines are renewed and an automatic assignment is attempted.
     */
    fun classify(id: Long, admin: AuthenticatedUser, request: ClassifyRequest): IncidentDetail {
        requireAdmin(admin)
        val incident = load(id)
        if (incident.status != IncidentStatus.NEW) throw InvalidStateTransitionException(incident.status, IncidentStatus.CLASSIFIED)
        val category = requireActiveCategory(request.categoryCode)
        pipeline.process(incident, ProcessingContext(Actor.of(admin), clock.instant(), category))
        return finish(incident, admin)
    }

    /** Assigns or reassigns to [AssignRequest.technicianId], who must be an active technician. */
    fun assign(id: Long, admin: AuthenticatedUser, request: AssignRequest): IncidentDetail {
        requireAdmin(admin)
        val incident = load(id)
        val technician = technicians.findById(request.technicianId).orElse(null)
        domainRequire(technician != null && technician.active) { "User ${request.technicianId} is not an active technician" }
        val previousAssignee = incident.assigneeId
        val change = incident.assign(request.technicianId, Actor.of(admin), clock.instant())
        if (incident.assigneeId != previousAssignee) {
            events.publishEvent(IncidentAssigned(requireNotNull(incident.id), admin.id, request.technicianId, MANUAL_STRATEGY))
        }
        change?.let { events.publishEvent(StatusChanged(requireNotNull(incident.id), admin.id, it.from, it.to)) }
        return finish(incident, admin)
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** Loads a visible incident, runs [block] with the caller as actor, saves and returns the detail for the caller. */
    private fun act(id: Long, viewer: AuthenticatedUser, block: (Incident, Actor) -> Unit): IncidentDetail {
        val incident = loadVisible(id, viewer)
        block(incident, Actor.of(viewer))
        return finish(incident, viewer)
    }

    /** Moves the incident with [Incident.transitionTo] and publishes [StatusChanged]. [only] restricts the status the move may start from. */
    private fun move(incident: Incident, target: IncidentStatus, actor: Actor, only: IncidentStatus? = null) {
        if (only != null && incident.status != only) throw InvalidStateTransitionException(incident.status, target)
        publishStatus(incident, actor, incident.transitionTo(target, actor, clock.instant()))
    }

    private fun publishStatus(incident: Incident, actor: Actor, change: StatusChange) {
        events.publishEvent(StatusChanged(requireNotNull(incident.id), actor.userId, change.from, change.to))
    }

    /**
     * Stores a note without publishing [NoteAdded]: notes created by an action are covered by that action's own events.
     */
    private fun saveNote(incident: Incident, author: AuthenticatedUser, type: NoteType, body: String): IncidentNote =
        notes.save(IncidentNote(requireNotNull(incident.id), author.id, type, body, clock.instant()))

    /** Flushes (so [Incident.version] and [Incident.updatedAt] in the response are final) and builds the viewer's detail. */
    private fun finish(incident: Incident, viewer: AuthenticatedUser): IncidentDetail {
        incidents.saveAndFlush(incident)
        return assembler.detail(incident, Actor.of(viewer))
    }

    private fun load(id: Long): Incident = incidents.findById(id).orElseThrow { IncidentNotFoundException(id) }

    private fun loadVisible(id: Long, viewer: AuthenticatedUser): Incident {
        val incident = load(id)
        if (!incident.isVisibleTo(Actor.of(viewer))) throw UnauthorizedOperationException("You are not allowed to see this incident")
        return incident
    }

    private fun requireAdmin(user: AuthenticatedUser) {
        if (user.role != Role.ADMIN) throw UnauthorizedOperationException("Administrator only")
    }

    private fun requireActiveCategory(code: String): Category {
        val normalized = Category.normalizeCode(code)
        return categories.findById(normalized).orElse(null)?.takeIf { it.active }
            ?: throw DomainValidationException("Category $normalized does not exist or is not active")
    }

    private fun pageRequest(page: Int, size: Int, sort: Sort) = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE), sort)

    private fun pageOf(page: Page<Incident>) = PageResponse(
        content = assembler.summaries(page.content),
        page = page.number, size = page.size, totalElements = page.totalElements, totalPages = page.totalPages,
    )

    companion object {
        const val MAX_PAGE_SIZE = 100

        /** The `strategy` of [IncidentAssigned] when an administrator picked the technician. */
        const val MANUAL_STRATEGY = "manual"
    }
}
