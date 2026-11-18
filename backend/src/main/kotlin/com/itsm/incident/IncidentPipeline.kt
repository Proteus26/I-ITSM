package com.itsm.incident

import com.itsm.assignment.AssignmentRequest
import com.itsm.assignment.AssignmentService
import com.itsm.category.Category
import com.itsm.category.CategoryRepository
import com.itsm.events.IncidentAssigned
import com.itsm.events.IncidentClassified
import com.itsm.events.PriorityChanged
import com.itsm.events.StatusChanged
import com.itsm.intelligence.CategorySuggester
import com.itsm.priority.PriorityInput
import com.itsm.priority.PriorityService
import com.itsm.service.ServiceCatalog
import com.itsm.sla.SlaService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * What a pipeline run knows besides the incident.
 *
 * @property actor who triggered the run: [Actor.SYSTEM] for a new report, the administrator for a manual classification
 * @property category the category the caller already settled on (validated, active), or null to let the pipeline suggest one
 */
class ProcessingContext(val actor: Actor, val now: Instant, var category: Category? = null)

/**
 * Template Method: the order in which a new or manually classified incident is processed is fixed here and cannot be
 * changed by a subclass; subclasses only provide the steps. Every step must tolerate being a no-op (an incident that
 * is already classified, or that nobody can be assigned to, simply passes through).
 *
 * suggest category -> classify -> prioritise -> apply SLA -> assign
 */
abstract class IncidentProcessingPipeline {

    fun process(incident: Incident, context: ProcessingContext) {
        suggestCategory(incident, context)
        classify(incident, context)
        prioritise(incident, context)
        applySla(incident, context)
        assign(incident, context)
    }

    /** Sets [ProcessingContext.category] when the incident has none and a suggestion is trustworthy. */
    protected abstract fun suggestCategory(incident: Incident, context: ProcessingContext)

    /** NEW -> CLASSIFIED when a category is known. */
    protected abstract fun classify(incident: Incident, context: ProcessingContext)

    /** Calculates and stores the priority. */
    protected abstract fun prioritise(incident: Incident, context: ProcessingContext)

    /** Calculates and stores the SLA deadlines for that priority. */
    protected abstract fun applySla(incident: Incident, context: ProcessingContext)

    /** CLASSIFIED -> ASSIGNED when a technician is found; otherwise the incident waits for an administrator. */
    protected abstract fun assign(incident: Incident, context: ProcessingContext)
}

/**
 * The standard pipeline. It changes the incident and publishes the matching events (specific event plus
 * [StatusChanged] for every status change); it must run inside the caller's transaction.
 */
@Component
class StandardIncidentPipeline(
    private val suggester: CategorySuggester,
    private val categories: CategoryRepository,
    private val priorities: PriorityService,
    private val catalog: ServiceCatalog,
    private val sla: SlaService,
    private val assignment: AssignmentService,
    private val events: ApplicationEventPublisher,
) : IncidentProcessingPipeline() {

    override fun suggestCategory(incident: Incident, context: ProcessingContext) {
        if (context.category != null || incident.categoryCode != null) return
        val suggestion = suggester.suggest(incident.title, incident.description) ?: return
        if (suggestion.confidence < MIN_SUGGESTION_CONFIDENCE) return
        context.category = categories.findById(suggestion.code).orElse(null)?.takeIf { it.active }
    }

    override fun classify(incident: Incident, context: ProcessingContext) {
        if (incident.status != IncidentStatus.NEW) return
        val category = context.category
            ?: incident.categoryCode?.let { categories.findById(it).orElse(null) }
            ?: return
        val change = incident.classify(category, context.actor, context.now)
        val id = requireNotNull(incident.id)
        events.publishEvent(IncidentClassified(id, context.actor.userId, category.code))
        events.publishEvent(StatusChanged(id, context.actor.userId, change.from, change.to))
    }

    override fun prioritise(incident: Incident, context: ProcessingContext) {
        val result = priorities.calculate(
            PriorityInput(
                impact = incident.impact.weight,
                urgency = incident.urgency.weight,
                serviceCriticality = catalog.criticalityOf(incident.serviceId),
                affectedUsers = incident.affectedUsers,
                categoryCode = incident.categoryCode,
            ),
        )
        val previous = incident.applyPriority(result.priority, result.score, context.now, result.explanation)
        if (previous != result.priority) {
            events.publishEvent(PriorityChanged(requireNotNull(incident.id), null, previous, result.priority, result.explanation))
        }
    }

    override fun applySla(incident: Incident, context: ProcessingContext) {
        val priority = incident.priority ?: return
        val deadlines = sla.computeDeadlines(incident.serviceId, priority, incident.createdAt)
        incident.applySla(deadlines.responseDueAt, deadlines.resolutionDueAt, context.now)
    }

    override fun assign(incident: Incident, context: ProcessingContext) {
        if (incident.status != IncidentStatus.CLASSIFIED) return
        val id = requireNotNull(incident.id)
        val choice = assignment.assign(AssignmentRequest(id, incident.categoryCode, incident.priority)) ?: return
        val change = incident.assign(choice.technicianId, Actor.SYSTEM, context.now)
        events.publishEvent(IncidentAssigned(id, null, choice.technicianId, choice.strategy))
        if (change != null) events.publishEvent(StatusChanged(id, null, change.from, change.to))
    }

    companion object {
        /** A suggestion below this confidence is ignored and the incident stays NEW for an administrator. */
        const val MIN_SUGGESTION_CONFIDENCE = 0.5
    }
}
