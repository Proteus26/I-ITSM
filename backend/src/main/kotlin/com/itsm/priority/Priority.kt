package com.itsm.priority

import com.itsm.common.exception.domainRequire
import com.itsm.incident.Priority
import com.itsm.settings.SettingKeys
import com.itsm.settings.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything a [PriorityStrategy] may look at. [impact], [urgency] and [serviceCriticality] are weights 1-4
 * (see `Impact.weight`, `Urgency.weight`, `ItService.criticality`); [affectedUsers] is at least 1.
 */
data class PriorityInput(
    val impact: Int,
    val urgency: Int,
    val serviceCriticality: Int,
    val affectedUsers: Int,
    val categoryCode: String?,
) {
    init {
        domainRequire(impact in 1..4) { "Impact must be between 1 and 4" }
        domainRequire(urgency in 1..4) { "Urgency must be between 1 and 4" }
        domainRequire(serviceCriticality in 1..4) { "Service criticality must be between 1 and 4" }
        domainRequire(affectedUsers >= 1) { "Affected users must be at least 1" }
    }
}

/** [score] is 0-10 (higher is more urgent); [explanation] is shown to people and stored in the audit trail. */
data class PriorityResult(val priority: Priority, val score: Double, val explanation: String)

/** One way to turn a [PriorityInput] into a priority. Register a new one by making it a Spring bean with a unique [key]. */
interface PriorityStrategy {
    val key: String
    fun calculate(input: PriorityInput): PriorityResult
}

/**
 * Entry point for priority calculation. Uses the strategy named by the `priority.strategy` setting, and
 * falls back to [DEFAULT_STRATEGY] when that name is not registered.
 */
@Service
class PriorityService(strategies: List<PriorityStrategy>, private val settings: SettingsService) {
    private val byKey: Map<String, PriorityStrategy> = strategies.associateBy { it.key }

    init {
        check(DEFAULT_STRATEGY in byKey) { "The '$DEFAULT_STRATEGY' priority strategy must be registered" }
    }

    /** Keys an administrator can choose for `priority.strategy`. */
    val availableStrategies: Set<String> get() = byKey.keys

    fun calculate(input: PriorityInput): PriorityResult {
        val key = settings.get(SettingKeys.PRIORITY_STRATEGY, DEFAULT_STRATEGY)
        val strategy = byKey[key]
        if (strategy == null && warnedKeys.add(key)) {
            log.warn("Unknown priority.strategy '{}' in settings; falling back to '{}'", key, DEFAULT_STRATEGY)
        }
        return (strategy ?: byKey.getValue(DEFAULT_STRATEGY)).calculate(input)
    }

    private val log = LoggerFactory.getLogger(PriorityService::class.java)
    private val warnedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    companion object {
        const val DEFAULT_STRATEGY = "weighted"
    }
}
