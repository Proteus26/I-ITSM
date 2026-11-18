package com.itsm.intelligence

import com.itsm.category.CategoryRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import kotlin.math.min

/**
 * Rule-based suggester: each seeded category has a list of keywords; the category with the most distinct
 * keywords found in the text wins. Matching is case-insensitive and on whole words or phrases
 * ("dns" does not match "dnsmasq-free"). Categories that are inactive or missing from the database are never suggested.
 *
 * The title and the description are matched separately, so a phrase cannot span the boundary between them
 * ("Cannot log" + "in to portal" does not match "log in"); the matches of both are then combined.
 * Overlapping keywords count once: within a text, all matches are collected, longest keyword first, and a match
 * that overlaps an already accepted span is dropped ("web app" wins over "app" in "web app down").
 *
 * Confidence is `n / (n + 1)` for n matched keywords (0.5, 0.67, 0.75, ...), capped at 0.95, and scaled down by
 * the winner's share of all matches, so a text that fits two categories equally gets a low value.
 * Add words to [KEYWORDS] to tune it; a category added by an administrator has no keywords until you add them.
 */
@Component
class KeywordCategorySuggester(private val categories: CategoryRepository) : CategorySuggester {

    @Transactional(readOnly = true)
    override fun suggest(title: String, description: String): CategorySuggestion? {
        val activeCodes = categories.findByActiveTrue().mapTo(HashSet()) { it.code }
        val matches = (matchedKeywords(title) + matchedKeywords(description))
            .filter { it.first in activeCodes }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, words) -> words.distinct() }
        if (matches.isEmpty()) return null

        val (code, words) = matches.entries.maxBy { it.value.size }.let { it.key to it.value }
        val total = matches.values.sumOf { it.size }
        val n = words.size
        val confidence = min(0.95, n.toDouble() / (n + 1)) * n / total
        return CategorySuggestion(code, Math.round(confidence * 100) / 100.0, "Matched ${words.joinToString(", ") { "'$it'" }}")
    }

    /** (category code, keyword) pairs found in one text, each stretch of text counted once, longest keyword first. */
    private fun matchedKeywords(raw: String): List<Pair<String, String>> {
        val text = raw.lowercase()
        if (text.isBlank()) return emptyList()
        val found = ALL.flatMap { (code, word, regex) ->
            regex.findAll(text).map { Triple(code, word, it.range) }.toList()
        }.sortedWith(compareByDescending<Triple<String, String, IntRange>> { it.second.length }.thenBy { it.third.first })
        val taken = mutableListOf<IntRange>()
        val kept = mutableListOf<Pair<String, String>>()
        for ((code, word, range) in found) {
            if (taken.any { it.first <= range.last && range.first <= it.last }) continue
            taken += range
            kept += code to word
        }
        return kept
    }

    companion object {
        /** Keywords per category code (lower case). Order matters only for ties: the first listed category wins. Very ambiguous single words (switch, monitor, mouse, instance, container, update, query, office, restore, spam) are left out on purpose. */
        val KEYWORDS: Map<String, List<String>> = linkedMapOf(
            "NETWORK" to listOf("vpn", "wifi", "wi-fi", "dns", "network", "firewall", "router", "ethernet", "internet", "latency", "proxy"),
            "AUTHENTICATION" to listOf("password", "login", "log in", "sign in", "mfa", "2fa", "sso", "two-factor", "locked out", "authenticator", "credentials"),
            "SECURITY" to listOf("phishing", "malware", "virus", "ransomware", "breach", "suspicious", "antivirus", "hacked", "security"),
            "DATABASE" to listOf("database", "sql", "postgres", "mysql", "oracle", "deadlock", "schema", "replication"),
            "CLOUD" to listOf("cloud", "aws", "azure", "gcp", "kubernetes", "docker", "ec2", "s3"),
            "STORAGE" to listOf("storage", "disk", "backup", "file share", "shared drive", "quota", "nas", "out of space"),
            "HARDWARE" to listOf("laptop", "desktop", "keyboard", "printer", "battery", "charger", "docking", "headset", "hardware", "webcam"),
            "APPLICATION" to listOf("application", "app", "crm", "erp", "portal", "payroll", "web app", "error 500", "timesheet"),
            "SOFTWARE" to listOf("software", "install", "installation", "upgrade", "license", "excel", "outlook", "operating system"),
        )

        private data class Entry(val code: String, val word: String, val regex: Regex)

        private val ALL: List<Entry> = KEYWORDS.flatMap { (code, words) ->
            words.map { Entry(code, it, Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])")) }
        }
    }
}
