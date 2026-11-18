package com.itsm.service

import com.itsm.common.exception.domainRequire
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * An IT service in the catalog (Payment, VPN, ...). [criticality] (1-4) feeds the priority calculation.
 * Named ItService (not Service) to avoid clashing with Spring's annotation; P4 maps the same table read-only as ItServiceRef.
 */
@Entity(name = "ItService")
@Table(name = "it_services")
class ItService(
    code: String,
    name: String,
    description: String? = null,
    criticality: Int = 2,
    ownerTeam: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "code", nullable = false, updatable = false)
    val code: String = normalizeCode(code)

    @Column(name = "name", nullable = false)
    final var name: String = requireName(name)
        private set

    @Column(name = "description")
    final var description: String? = optionalText(description, "Description", MAX_DESCRIPTION)
        private set

    @Column(name = "criticality", nullable = false)
    final var criticality: Int = requireCriticality(criticality)
        private set

    @Column(name = "owner_team")
    final var ownerTeam: String? = optionalText(ownerTeam, "Owner team", MAX_TEAM)
        private set

    @Column(name = "active", nullable = false)
    final var active: Boolean = true
        private set

    /** Replaces the editable fields; the code never changes. */
    fun update(name: String, description: String?, criticality: Int, ownerTeam: String?, active: Boolean) {
        this.name = requireName(name)
        this.description = optionalText(description, "Description", MAX_DESCRIPTION)
        this.criticality = requireCriticality(criticality)
        this.ownerTeam = optionalText(ownerTeam, "Owner team", MAX_TEAM)
        this.active = active
    }

    companion object {
        private const val MAX_DESCRIPTION = 500
        private const val MAX_TEAM = 100
        private val CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,49}")

        fun normalizeCode(code: String): String {
            val normalized = code.trim().uppercase()
            domainRequire(normalized.matches(CODE_PATTERN)) { "Service code must be letters, digits and underscores, starting with a letter (max 50)" }
            return normalized
        }

        private fun requireName(name: String): String {
            val trimmed = name.trim()
            domainRequire(trimmed.isNotEmpty()) { "Service name must not be blank" }
            domainRequire(trimmed.length <= 100) { "Service name must be at most 100 characters" }
            return trimmed
        }

        private fun requireCriticality(value: Int): Int {
            domainRequire(value in 1..4) { "Criticality must be between 1 and 4" }
            return value
        }

        private fun optionalText(value: String?, field: String, max: Int): String? {
            val trimmed = value?.trim()?.ifEmpty { null }
            domainRequire(trimmed == null || trimmed.length <= max) { "$field must be at most $max characters" }
            return trimmed
        }
    }
}
