package com.itsm.intelligence

import com.itsm.auth.AuthenticatedUser
import com.itsm.incident.IncidentStatus

/**
 * A proposed category.
 *
 * @property code category code, always an active category
 * @property confidence 0.0 to 1.0; a hint for the UI, not a probability
 * @property reason which words led to the suggestion
 */
data class CategorySuggestion(val code: String, val confidence: Double, val reason: String)

/** Proposes a category for free text. Swap the implementation (for example for an ML model) without touching callers. */
interface CategorySuggester {
    /** The best active category, or null when nothing in the text points to one. */
    fun suggest(title: String, description: String): CategorySuggestion?
}

/** An existing incident that resembles a new report. [score] is 0.0 to 1.0, higher is more similar. */
data class SimilarIncident(val id: Long, val number: String, val title: String, val status: IncidentStatus, val score: Double)

/** Finds open incidents similar to a text, for "is this a duplicate?" hints. */
interface DuplicateDetector {
    /**
     * Similar open incidents, best first. Only incidents [viewer] may see are returned, so a title never leaks to
     * someone who could not open the incident. [excludeId] leaves one incident out (the one being edited).
     */
    fun findSimilar(title: String, description: String, viewer: AuthenticatedUser, excludeId: Long? = null, limit: Int = 5): List<SimilarIncident>
}
