package com.itsm.incident

import com.itsm.auth.CurrentUser
import com.itsm.user.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Incident REST API. Who may do what is decided by [IncidentService] and the [Incident] aggregate:
 * 403 when the caller may not, 409 when the lifecycle does not allow it, 404 for an unknown id.
 */
@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidents")
class IncidentController(private val service: IncidentService, private val currentUser: CurrentUser) {

    @Operation(summary = "Report an incident", description = "Any role. Runs the report pipeline: category suggestion, classification, priority, SLA, assignment.")
    @ApiResponse(responseCode = "201", description = "Created; status is NEW, CLASSIFIED or ASSIGNED depending on what the pipeline could decide")
    @ApiResponse(responseCode = "400", description = "Validation failed, or unknown or inactive service or category")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun report(@Valid @RequestBody request: ReportIncidentRequest): IncidentDetail = service.report(currentUser.get(), request)

    @Operation(summary = "Incidents I reported", description = "Newest first; size is capped at 100.")
    @GetMapping("/mine")
    fun mine(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): PageResponse<IncidentSummary> =
        service.mine(currentUser.get(), page, size)

    @Operation(summary = "Active incidents assigned to me", description = "Technicians only (403 otherwise); by priority, then resolution deadline.")
    @GetMapping("/assigned")
    fun assigned(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): PageResponse<IncidentSummary> =
        service.assigned(currentUser.get(), page, size)

    @Operation(summary = "Incident detail", description = "Reporter, assignee or administrator; `actions` and `noteTypes` are for the caller.")
    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): IncidentDetail = service.get(id, currentUser.get())

    @Operation(
        summary = "Edit an incident",
        description = "Partial update. Reporter: all fields until work starts (NEW, CLASSIFIED, ASSIGNED). Administrator: all fields until RESOLVED. " +
            "Assignee: impact, urgency, affectedUsers and service while active. Changing impact, urgency, affectedUsers or service recalculates priority and SLA deadlines.",
    )
    @ApiResponse(responseCode = "400", description = "Invalid value, empty request, or unknown or inactive service")
    @ApiResponse(responseCode = "403", description = "Not allowed to edit this incident or one of the given fields")
    @ApiResponse(responseCode = "409", description = "The incident is RESOLVED or later")
    @PatchMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateIncidentRequest): IncidentDetail =
        service.update(id, currentUser.get(), request)

    @Operation(summary = "Notes of an incident", description = "Employees do not see INVESTIGATION notes.")
    @GetMapping("/{id}/notes")
    fun notes(@PathVariable id: Long): List<IncidentNoteResponse> = service.notes(id, currentUser.get())

    @Operation(summary = "Add a note", description = "INVESTIGATION: assignee or admin. INFO_RESPONSE: reporter while PENDING_INFO. COMMENT: reporter, assignee, admin. INFO_REQUEST only through request-info.")
    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    fun addNote(@PathVariable id: Long, @Valid @RequestBody request: AddNoteRequest): IncidentNoteResponse =
        service.addNote(id, currentUser.get(), request)

    @Operation(summary = "Acknowledge (assignee): ASSIGNED to ACKNOWLEDGED")
    @PostMapping("/{id}/acknowledge")
    fun acknowledge(@PathVariable id: Long): IncidentDetail = service.acknowledge(id, currentUser.get())

    @Operation(summary = "Start work (assignee): ACKNOWLEDGED to IN_PROGRESS")
    @PostMapping("/{id}/start")
    fun start(@PathVariable id: Long): IncidentDetail = service.start(id, currentUser.get())

    @Operation(summary = "Ask the reporter for information (assignee): IN_PROGRESS to PENDING_INFO")
    @PostMapping("/{id}/request-info")
    fun requestInfo(@PathVariable id: Long, @Valid @RequestBody request: RequestInfoRequest): IncidentDetail =
        service.requestInfo(id, currentUser.get(), request)

    @Operation(summary = "Continue after a request for information (reporter or assignee): PENDING_INFO to IN_PROGRESS")
    @PostMapping("/{id}/resume")
    fun resume(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ResumeRequest?): IncidentDetail =
        service.resume(id, currentUser.get(), request ?: ResumeRequest())

    @Operation(summary = "Resolve (assignee): IN_PROGRESS to RESOLVED")
    @PostMapping("/{id}/resolve")
    fun resolve(@PathVariable id: Long, @Valid @RequestBody request: ResolveRequest): IncidentDetail =
        service.resolve(id, currentUser.get(), request)

    @Operation(summary = "Confirm the fix (reporter or admin): RESOLVED to VERIFIED")
    @PostMapping("/{id}/verify")
    fun verify(@PathVariable id: Long): IncidentDetail = service.verify(id, currentUser.get())

    @Operation(summary = "The fix did not work (reporter or admin): RESOLVED to IN_PROGRESS")
    @PostMapping("/{id}/reopen")
    fun reopen(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ReasonRequest?): IncidentDetail =
        service.reopen(id, currentUser.get(), request ?: ReasonRequest())

    @Operation(summary = "Close (reporter or admin): VERIFIED to CLOSED")
    @PostMapping("/{id}/close")
    fun close(@PathVariable id: Long): IncidentDetail = service.close(id, currentUser.get())

    @Operation(summary = "Cancel (reporter or admin): NEW or CLASSIFIED to CANCELLED")
    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: Long, @Valid @RequestBody(required = false) request: ReasonRequest?): IncidentDetail =
        service.cancel(id, currentUser.get(), request ?: ReasonRequest())

    @Operation(summary = "Rate the resolution (reporter), once")
    @PostMapping("/{id}/feedback")
    fun feedback(@PathVariable id: Long, @Valid @RequestBody request: FeedbackRequest): IncidentDetail =
        service.feedback(id, currentUser.get(), request)
}

/** Administrator-only incident operations. */
@RestController
@RequestMapping("/api/admin/incidents")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Incidents (admin)")
class AdminIncidentController(private val service: IncidentService, private val currentUser: CurrentUser) {

    @Operation(summary = "Classify a NEW incident", description = "Then recalculates the priority and SLA and attempts automatic assignment.")
    @PostMapping("/{id}/classify")
    fun classify(@PathVariable id: Long, @Valid @RequestBody request: ClassifyRequest): IncidentDetail =
        service.classify(id, currentUser.get(), request)

    @Operation(summary = "Assign or reassign to an active technician", description = "400 if the user is not an active technician; 409 if the incident is not CLASSIFIED or active.")
    @PostMapping("/{id}/assign")
    fun assign(@PathVariable id: Long, @Valid @RequestBody request: AssignRequest): IncidentDetail =
        service.assign(id, currentUser.get(), request)
}
