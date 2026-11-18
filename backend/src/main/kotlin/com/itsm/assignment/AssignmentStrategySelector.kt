package com.itsm.assignment

import com.itsm.settings.SettingKeys
import com.itsm.settings.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Says which strategy key is configured right now. [currentKey] is called on every assignment, so a change
 * takes effect immediately. The bean used is [SettingsStrategyKeySource]; [PropertyStrategyKeySource] is its fallback.
 */
fun interface StrategyKeySource {
    fun currentKey(): String?
}

/** Reads `itsm.assignment.strategy` (env ASSIGNMENT_STRATEGY). */
@Component
class PropertyStrategyKeySource(@Value("\${itsm.assignment.strategy:$DEFAULT_STRATEGY}") private val key: String) : StrategyKeySource {
    override fun currentKey(): String = key
}

/**
 * Reads the admin setting `assignment.strategy` on every call and falls back to [fallback] (the configured property)
 * when the setting is missing, blank or not a known assignment strategy (the latter logs a warning once per distinct value).
 */
@Primary
@Component
class SettingsStrategyKeySource(private val settings: SettingsService, private val fallback: PropertyStrategyKeySource) : StrategyKeySource {
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun currentKey(): String? {
        val configured = fallback.currentKey()
        val stored = settings.get(SettingKeys.ASSIGNMENT_STRATEGY, configured).trim().lowercase()
        if (stored.isEmpty()) return configured
        if (stored in SettingKeys.ASSIGNMENT_STRATEGIES) return stored
        if (warned.add(stored)) log.warn("Ignoring unknown assignment.strategy setting '{}'; using '{}'", stored, configured)
        return configured
    }

    private companion object {
        val log = LoggerFactory.getLogger(SettingsStrategyKeySource::class.java)
    }
}

/** Strategy used when nothing is configured or the configured key is unknown. */
const val DEFAULT_STRATEGY = SkillLeastLoadedStrategy.KEY

/** Resolves the configured key to a strategy bean; an unknown key logs a warning (once per distinct key) and falls back to [DEFAULT_STRATEGY]. */
@Component
class AssignmentStrategySelector(strategies: List<AssignmentStrategy>, private val keySource: StrategyKeySource) {
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val byKey: Map<String, AssignmentStrategy> = strategies.associateBy { it.key }

    /** Keys of all registered strategies. */
    val keys: Set<String> get() = byKey.keys

    fun select(): AssignmentStrategy {
        val wanted = keySource.currentKey()?.trim()?.lowercase()
        byKey[wanted]?.let { return it }
        if (warned.add(wanted ?: "")) log.warn("Unknown assignment strategy '{}'; falling back to '{}'. Available: {}", wanted, DEFAULT_STRATEGY, byKey.keys)
        return byKey.getValue(DEFAULT_STRATEGY)
    }

    private companion object {
        val log = LoggerFactory.getLogger(AssignmentStrategySelector::class.java)
    }
}
