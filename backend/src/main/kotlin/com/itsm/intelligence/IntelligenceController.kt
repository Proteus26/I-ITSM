package com.itsm.intelligence

import com.itsm.auth.CurrentUser
import com.itsm.common.exception.domainRequire
import com.itsm.incident.Incident
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Smart hints for the incident form; open to every logged-in user. */
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
        validate(title, description)
        return suggester.suggest(title, description)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
    }

    /** Similar open incidents the caller may see; [excludeId] hides the incident being edited. */
    @GetMapping("/similar")
    fun similar(
        @RequestParam title: String,
        @RequestParam(defaultValue = "") description: String,
        @RequestParam(required = false) excludeId: Long?,
    ): List<SimilarIncident> {
        validate(title, description)
        return detector.findSimilar(title, description, currentUser.get(), excludeId)
    }

    private fun validate(title: String, description: String) {
        domainRequire(title.length <= Incident.MAX_TITLE) { "Title must be at most ${Incident.MAX_TITLE} characters" }
        domainRequire(description.length <= Incident.MAX_TEXT) { "Description must be at most ${Incident.MAX_TEXT} characters" }
    }
}
