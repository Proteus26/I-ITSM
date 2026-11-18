package com.itsm.sla.risk

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** Where an incident stands against its SLA right now. */
enum class SlaRiskState { BREACHED, AT_RISK }

/** Which deadline an assessment is mainly about. */
enum class SlaWindowKind { RESPONSE, RESOLUTION }

/**
 * Everything the detector looks at, with no entity in sight so it can be unit tested with plain values.
 *
 * @property responseDueAt deadline for the first response; only counts while [acknowledged] is false
 * @property resolutionDueAt deadline for the fix
 * @property responseMinutes length of the response window in the policy (start = due minus this)
 * @property resolutionMinutes length of the resolution window in the policy
 * @property warningThresholdPct the policy's warning threshold (percentage of a window)
 * @property assigneeOverCapacity the assigned technician has more active incidents than their capacity
 */
data class SlaRiskInput(
    val responseDueAt: Instant?,
    val resolutionDueAt: Instant?,
    val acknowledged: Boolean,
    val assigned: Boolean,
    val responseMinutes: Int,
    val resolutionMinutes: Int,
    val warningThresholdPct: Int = 75,
    val assigneeOverCapacity: Boolean = false,
)

/**
 * The outcome for one incident. [riskReason] is the main sentence for people; [reasons] lists every rule that fired.
 * [dueAt] and [dueKind] are the most pressing deadline; [minutesRemaining] is negative once it has passed.
 */
data class SlaRisk(
    val state: SlaRiskState,
    val riskReason: String,
    val reasons: List<String>,
    val dueKind: SlaWindowKind,
    val dueAt: Instant,
    val minutesRemaining: Long,
    val percentElapsed: Int,
)

/**
 * SLA risk detection. An open incident is
 *
 * - BREACHED when an active deadline has passed (the response deadline while nobody acknowledged it, the resolution
 *   deadline always); and
 * - AT_RISK, which is a prediction that it will breach, when none has passed yet and at least one rule fires:
 *   1. a window has used at least the policy's warning threshold (same rule as the SLA monitor's warning, so the
 *      page already shows the incident before the warning is recorded);
 *   2. it is not acknowledged and less than [RESPONSE_LEFT_PCT] percent of the response window is left (this
 *      catches policies with a high threshold);
 *   3. nobody is assigned and at least [UNASSIGNED_ELAPSED_PCT] percent of the response window has gone: nobody can
 *      acknowledge it;
 *   4. the assignee is over capacity and at least [OVERLOADED_ELAPSED_PCT] percent of the resolution window has gone:
 *      the fix is unlikely to start in time. (Over capacity alone is not enough; most technicians are over capacity
 *      for a short time and the incident may be nearly new.)
 *
 * Pure: time is passed in. Only pass open incidents; resolved or closed ones have nothing left to miss.
 */
@Component
class SlaRiskDetector {

    fun assess(input: SlaRiskInput, now: Instant): SlaRisk? {
        val response = input.responseDueAt?.takeIf { !input.acknowledged }
            ?.let { Window(SlaWindowKind.RESPONSE, it, input.responseMinutes) }
        val resolution = input.resolutionDueAt?.let { Window(SlaWindowKind.RESOLUTION, it, input.resolutionMinutes) }
        val windows = listOfNotNull(response, resolution)
        if (windows.isEmpty()) return null

        val breached = windows.filter { now.isAfter(it.due) }
        if (breached.isNotEmpty()) {
            val reasons = breached.map { "${it.kind.label()} deadline passed ${duration(Duration.between(it.due, now))} ago" }
            val main = breached.maxBy { Duration.between(it.due, now) }
            return build(SlaRiskState.BREACHED, reasons, main, now)
        }

        val reasons = mutableListOf<String>()
        for (w in windows) {
            val pct = w.percent(now)
            if (pct >= input.warningThresholdPct) reasons += "$pct% of the ${w.kind.label()} time used (warning at ${input.warningThresholdPct}%)"
        }
        if (response != null) {
            val left = 100 - response.percent(now)
            if (left < RESPONSE_LEFT_PCT && reasons.none { it.contains("response time") }) {
                reasons += "Not acknowledged and only $left% of the response time is left"
            }
            if (!input.assigned && response.percent(now) >= UNASSIGNED_ELAPSED_PCT) {
                reasons += "Nobody is assigned and ${response.percent(now)}% of the response time has gone"
            }
        }
        if (resolution != null && input.assigneeOverCapacity && resolution.percent(now) >= OVERLOADED_ELAPSED_PCT) {
            reasons += "The assigned technician is over capacity and ${resolution.percent(now)}% of the resolution time has gone"
        }
        if (reasons.isEmpty()) return null

        // The most pressing deadline is the one closest to its end.
        val main = windows.minBy { Duration.between(now, it.due) }
        return build(SlaRiskState.AT_RISK, reasons, main, now)
    }

    private fun build(state: SlaRiskState, reasons: List<String>, main: Window, now: Instant) = SlaRisk(
        state = state,
        riskReason = reasons.first(),
        reasons = reasons,
        dueKind = main.kind,
        dueAt = main.due,
        minutesRemaining = Duration.between(now, main.due).toMinutes().let { if (now.isAfter(main.due) && it == 0L) -1 else it },
        percentElapsed = main.percent(now),
    )

    private class Window(val kind: SlaWindowKind, val due: Instant, val policyMinutes: Int) {
        /** Share of the window used, 0..100; 100 when the window has no length. Same arithmetic as the SLA monitor. */
        fun percent(now: Instant): Int {
            val start = due.minus(Duration.ofMinutes(policyMinutes.toLong()))
            val total = Duration.between(start, due).toMillis()
            if (total <= 0) return 100
            return (Duration.between(start, now).toMillis() * 100 / total).toInt().coerceIn(0, 100)
        }
    }

    private fun SlaWindowKind.label() = if (this == SlaWindowKind.RESPONSE) "response" else "resolution"

    private fun duration(d: Duration): String {
        val minutes = d.toMinutes()
        return when {
            minutes < 1 -> "less than a minute"
            minutes < 60 -> "$minutes min"
            minutes < 48 * 60 -> "${minutes / 60} h ${minutes % 60} min"
            else -> "${minutes / (24 * 60)} days"
        }
    }

    companion object {
        const val RESPONSE_LEFT_PCT = 25
        const val UNASSIGNED_ELAPSED_PCT = 50
        const val OVERLOADED_ELAPSED_PCT = 50
    }
}
