package com.itsm.assignment

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Says which strategy key is configured right now. The default reads the `itsm.assignment.strategy` property.
 *
 * TODO(P5/P3): read system_settings assignment.strategy once P3's SettingsService exists. Publish a bean that
 * implements this interface and mark it `@Primary` (or replace [PropertyStrategyKeySource]); nothing else changes,
 * and because [currentKey] is called on every assignment an administrator's change takes effect immediately.
 */
fun interface StrategyKeySource {
    fun currentKey(): String?
}

/** Reads `itsm.assignment.strategy` (env ASSIGNMENT_STRATEGY). */
@Component
class PropertyStrategyKeySource(@Value("\${itsm.assignment.strategy:$DEFAULT_STRATEGY}") private val key: String) : StrategyKeySource {
    override fun currentKey(): String = key
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
