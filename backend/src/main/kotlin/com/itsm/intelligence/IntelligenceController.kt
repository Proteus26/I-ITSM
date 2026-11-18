package com.itsm.intelligence

import com.itsm.auth.CurrentUser
import com.itsm.common.exception.domainRequire
import com.itsm.incident.Incident
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Smart hints for the incident form; open to every logged-in user. Title longer than 200 characters is a 400;
 * a description is truncated to its first 1000 characters rather than rejected.
 */
@RestController
@RequestMapping("/api/intelligence")
class IntelligenceController(
    private val suggester: CategorySuggester,
    private val detector: DuplicateDetector,
    private val currentUser: CurrentUser,
) {
    /** 200 with the suggestion, or 204 when no category matches. */
    @GetMapping("/suggest-category")
    fun suggestCategory(
        @RequestParam title: String,
        @RequestParam(defaultValue = "") description: String,
    ): ResponseEntity<CategorySuggestion> {
        validateTitle(title)
        return suggester.suggest(title, clip(description))?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
    }

    /** Similar open incidents the caller may see; [excludeId] hides the incident being edited. */
    @GetMapping("/similar")
    fun similar(
        @RequestParam title: String,
        @RequestParam(defaultValue = "") description: String,
        @RequestParam(required = false) excludeId: Long?,
    ): List<SimilarIncident> {
        validateTitle(title)
        return detector.findSimilar(title, clip(description), currentUser.get(), excludeId)
    }

    private fun validateTitle(title: String) {
        domainRequire(title.length <= Incident.MAX_TITLE) { "Title must be at most ${Incident.MAX_TITLE} characters" }
    }

    /** GET query strings are limited (about 8 KB request line), so only the first [MAX_DESCRIPTION_USED] characters count. */
    private fun clip(description: String) = description.take(MAX_DESCRIPTION_USED)

    companion object {
        /** Characters of the description that are used; the rest is ignored, not rejected. The frontend sends at most 500. */
        const val MAX_DESCRIPTION_USED = 1000
    }
}
