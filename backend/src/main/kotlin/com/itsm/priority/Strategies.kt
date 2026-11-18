package com.itsm.priority

import com.itsm.incident.Impact
import com.itsm.incident.Priority
import com.itsm.incident.Urgency
import org.springframework.stereotype.Component
import java.util.Locale
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt

private fun Int.impactName() = Impact.entries.first { it.weight == this }.name
private fun Int.urgencyName() = Urgency.entries.first { it.weight == this }.name
private fun Double.fmt() = String.format(Locale.ROOT, "%.1f", this)

private fun describe(input: PriorityInput) =
    "Impact ${input.impact.impactName()} (${input.impact}) × urgency ${input.urgency.urgencyName()} (${input.urgency}) " +
        "× service criticality ${input.serviceCriticality}, ${input.affectedUsers} affected users"

/**
 * Key `weighted`. Score (0-10, rounded to one decimal, then compared with the thresholds):
 *
 * - base = 8 × ln(impact × urgency × criticality) / ln(64), so 1×1×1 is 0 and 4×4×4 is 8
 * - users boost = min(1.5, 0.5 × log10(affectedUsers)): 1 user adds 0, 100 add 1.0, 1000+ add 1.5
 * - SECURITY category boost = +1.0
 * - the sum is capped at 10
 *
 * Thresholds: score >= 8 is P1, >= 6 is P2, >= 4 is P3, otherwise P4.
 */
@Component
class WeightedScoreStrategy : PriorityStrategy {
    override val key = "weighted"

    override fun calculate(input: PriorityInput): PriorityResult {
        val product = input.impact * input.urgency * input.serviceCriticality
        val base = 8.0 * ln(product.toDouble()) / ln(MAX_PRODUCT)
        val usersBoost = min(MAX_USERS_BOOST, 0.5 * log10(input.affectedUsers.toDouble()))
        val security = isSecurity(input.categoryCode)
        val raw = base + usersBoost + if (security) SECURITY_BOOST else 0.0
        val score = (min(10.0, raw) * 10).roundToInt() / 10.0
        val priority = when {
            score >= P1_THRESHOLD -> Priority.P1
            score >= P2_THRESHOLD -> Priority.P2
            score >= P3_THRESHOLD -> Priority.P3
            else -> Priority.P4
        }
        val explanation = describe(input) + (if (security) ", security incident (+1.0)" else "") + " → score ${score.fmt()} → $priority"
        return PriorityResult(priority, score, explanation)
    }

    private fun isSecurity(code: String?) = code?.trim().equals("SECURITY", ignoreCase = true)

    companion object {
        const val P1_THRESHOLD = 8.0
        const val P2_THRESHOLD = 6.0
        const val P3_THRESHOLD = 4.0
        private const val MAX_PRODUCT = 64.0
        private const val MAX_USERS_BOOST = 1.5
        private const val SECURITY_BOOST = 1.0
    }
}

/**
 * Key `matrix`. The classic ITIL impact × urgency table (rows impact 1-4, columns urgency 1-4):
 *
 * ```
 *            urg 1  urg 2  urg 3  urg 4
 * impact 1     P4     P4     P3     P3
 * impact 2     P4     P3     P3     P2
 * impact 3     P3     P3     P2     P1
 * impact 4     P3     P2     P1     P1
 * ```
 *
 * A service criticality of 4 raises the result by one level (P3 becomes P2; P1 stays P1). Affected users and
 * category are ignored. The score is 10, 7.5, 5 or 2.5 for P1..P4, so scores stay comparable across strategies.
 */
@Component
class MatrixStrategy : PriorityStrategy {
    override val key = "matrix"

    override fun calculate(input: PriorityInput): PriorityResult {
        val base = MATRIX[input.impact - 1][input.urgency - 1]
        val bumped = input.serviceCriticality == 4 && base != Priority.P1
        val priority = if (bumped) Priority.entries[base.ordinal - 1] else base
        val score = (4 - priority.ordinal) * 2.5
        val explanation = "${describe(input)} → matrix $base" + (if (bumped) ", raised one level for a critical service" else "") + " → $priority"
        return PriorityResult(priority, score, explanation)
    }

    private companion object {
        val MATRIX: List<List<Priority>> = listOf(
            listOf(Priority.P4, Priority.P4, Priority.P3, Priority.P3),
            listOf(Priority.P4, Priority.P3, Priority.P3, Priority.P2),
            listOf(Priority.P3, Priority.P3, Priority.P2, Priority.P1),
            listOf(Priority.P3, Priority.P2, Priority.P1, Priority.P1),
        )
    }
}
