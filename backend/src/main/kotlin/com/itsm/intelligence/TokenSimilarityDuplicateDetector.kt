package com.itsm.intelligence

import com.itsm.auth.AuthenticatedUser
import com.itsm.incident.Actor
import com.itsm.incident.Incident
import com.itsm.incident.IncidentStatus
import com.itsm.user.Role
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/** Text helpers for [TokenSimilarityDuplicateDetector]; pure functions, easy to unit test. */
object TextSimilarity {
    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "have", "has", "not", "are", "was", "were", "but", "can", "cannot",
        "cant", "when", "what", "all", "any", "you", "your", "our", "its", "into", "than", "then", "there", "they", "been",
        "will", "would", "could", "should", "very", "also", "just", "some", "get", "got", "does", "doesn", "didn", "about",
    )
    private const val MIN_TOKEN_LENGTH = 3
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Lower-cased words without punctuation, stopwords and tokens shorter than three characters. */
    fun tokens(text: String): Set<String> =
        text.lowercase().split(SPLIT).filterTo(HashSet()) { it.length >= MIN_TOKEN_LENGTH && it !in STOPWORDS }

    /** |A and B| / |A or B|; 0.0 when either set is empty. */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }

    /**
     * Title similarity weighted [TITLE_WEIGHT], description similarity the rest. If either side has no description
     * tokens the title alone decides, so a short report is not penalised for a missing description.
     */
    fun score(titleA: Set<String>, descA: Set<String>, titleB: Set<String>, descB: Set<String>): Double {
        val title = jaccard(titleA, titleB)
        if (descA.isEmpty() || descB.isEmpty()) return title
        return TITLE_WEIGHT * title + (1 - TITLE_WEIGHT) * jaccard(descA, descB)
    }

    const val TITLE_WEIGHT = 0.7
}

/**
 * Compares the text with the title and description of OPEN incidents created in the last 30 days (at most the
 * newest 500 the viewer may see) using [TextSimilarity.score], and keeps those at or above [THRESHOLD].
 *
 * Visibility is enforced twice: the query only selects incidents the viewer reported or is assigned to (admins:
 * all), and each row is checked again with [Incident.isVisibleTo].
 */
@Component
class TokenSimilarityDuplicateDetector(private val em: EntityManager, private val clock: Clock) : DuplicateDetector {

    @Transactional(readOnly = true)
    override fun findSimilar(title: String, description: String, viewer: AuthenticatedUser, excludeId: Long?, limit: Int): List<SimilarIncident> {
        val titleTokens = TextSimilarity.tokens(title)
        val descTokens = TextSimilarity.tokens(description)
        if (titleTokens.isEmpty() && descTokens.isEmpty()) return emptyList()
        val actor = Actor.of(viewer)

        return recentOpen(viewer).asSequence()
            .filter { it.id != excludeId && it.isVisibleTo(actor) }
            .map { it to TextSimilarity.score(titleTokens, descTokens, TextSimilarity.tokens(it.title), TextSimilarity.tokens(it.description)) }
            .filter { it.second >= THRESHOLD }
            .sortedByDescending { it.second }
            .take(limit.coerceAtLeast(0))
            .map { (i, score) -> SimilarIncident(requireNotNull(i.id), i.number, i.title, i.status, Math.round(score * 100) / 100.0) }
            .toList()
    }

    private fun recentOpen(viewer: AuthenticatedUser): List<Incident> =
        em.createQuery(
            "select i from Incident i where i.status in :open and i.createdAt >= :since " +
                "and (:all = true or i.reporterId = :uid or i.assigneeId = :uid) order by i.createdAt desc",
            Incident::class.java,
        ).setParameter("open", IncidentStatus.OPEN)
            .setParameter("since", clock.instant().minus(WINDOW))
            .setParameter("all", viewer.role == Role.ADMIN)
            .setParameter("uid", viewer.id)
            .setMaxResults(MAX_CANDIDATES)
            .resultList

    companion object {
        /**
         * Minimum score to report. 0.4 keeps two short titles that share only one word ("Printer broken" and
         * "Printer jammed" score 0.33) from being flagged, while real duplicates score well above it.
         */
        const val THRESHOLD = 0.4
        const val MAX_CANDIDATES = 500
        val WINDOW: Duration = Duration.ofDays(30)
    }
}
