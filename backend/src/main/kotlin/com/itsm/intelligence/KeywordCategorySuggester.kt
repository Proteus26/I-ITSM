package com.itsm.intelligence

import com.itsm.category.CategoryRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import kotlin.math.min

/**
 * Rule-based suggester: each seeded category has a list of keywords; the category with the most distinct
 * keywords found in "title + description" wins. Matching is case-insensitive and on whole words or phrases
 * ("dns" does not match "dnsmasq-free"). Categories that are inactive or missing from the database are never suggested.
 *
 * Confidence is `n / (n + 1)` for n matched keywords (0.5, 0.67, 0.75, ...), capped at 0.95, and scaled down by
 * the winner's share of all matches, so a text that fits two categories equally gets a low value.
 * Add words to [KEYWORDS] to tune it; a category added by an administrator has no keywords until you add them.
 */
@Component
class KeywordCategorySuggester(private val categories: CategoryRepository) : CategorySuggester {

    @Transactional(readOnly = true)
    override fun suggest(title: String, description: String): CategorySuggestion? {
        val activeCodes = categories.findByActiveTrueOrderByNameAsc().mapTo(HashSet()) { it.code }
        val text = "$title $description".lowercase()
        val matches = PATTERNS.filterKeys { it in activeCodes }
            .mapValues { (_, patterns) -> patterns.filter { it.second.containsMatchIn(text) }.map { it.first } }
            .filterValues { it.isNotEmpty() }
        if (matches.isEmpty()) return null

        val (code, words) = matches.entries.maxBy { it.value.size }.let { it.key to it.value }
        val total = matches.values.sumOf { it.size }
        val n = words.size
        val confidence = min(0.95, n.toDouble() / (n + 1)) * n / total
        return CategorySuggestion(code, Math.round(confidence * 100) / 100.0, "Matched ${words.joinToString(", ") { "'$it'" }}")
    }

    companion object {
        /** Keywords per category code (lower case). Order matters only for ties: the first listed category wins. */
        val KEYWORDS: Map<String, List<String>> = linkedMapOf(
            "NETWORK" to listOf("vpn", "wifi", "wi-fi", "dns", "network", "firewall", "router", "ethernet", "internet", "latency", "proxy", "switch"),
            "AUTHENTICATION" to listOf("password", "login", "log in", "sign in", "mfa", "2fa", "sso", "two-factor", "locked out", "authenticator", "credentials"),
            "SECURITY" to listOf("phishing", "malware", "virus", "ransomware", "breach", "suspicious", "antivirus", "spam", "hacked", "security"),
            "DATABASE" to listOf("database", "sql", "postgres", "mysql", "oracle", "query", "deadlock", "schema", "replication"),
            "CLOUD" to listOf("cloud", "aws", "azure", "gcp", "kubernetes", "docker", "ec2", "s3", "instance", "container"),
            "STORAGE" to listOf("storage", "disk", "backup", "file share", "shared drive", "quota", "nas", "out of space", "restore"),
            "HARDWARE" to listOf("laptop", "desktop", "monitor", "keyboard", "mouse", "printer", "battery", "charger", "docking", "headset", "hardware", "webcam"),
            "APPLICATION" to listOf("application", "app", "crm", "erp", "portal", "payroll", "web app", "error 500", "timesheet"),
            "SOFTWARE" to listOf("software", "install", "installation", "update", "upgrade", "license", "excel", "outlook", "office", "operating system"),
        )

        private val PATTERNS: Map<String, List<Pair<String, Regex>>> = KEYWORDS.mapValues { (_, words) ->
            words.map { it to Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])") }
        }
    }
}
