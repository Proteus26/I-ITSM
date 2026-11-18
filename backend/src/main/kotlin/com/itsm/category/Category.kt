package com.itsm.category

import com.itsm.common.exception.domainRequire
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * An incident category such as NETWORK. Categories are data, not an enum, so an administrator can add one
 * without any code change in the modules that use categories (they only hold the [code]).
 */
@Entity
@Table(name = "categories")
class Category(
    code: String,
    name: String,
    description: String? = null,
) {
    @Id
    @Column(name = "code", nullable = false, updatable = false)
    val code: String = normalizeCode(code)

    @Column(name = "name", nullable = false)
    final var name: String = requireName(name)
        private set

    @Column(name = "description")
    final var description: String? = description?.trim()?.ifEmpty { null }
        private set

    @Column(name = "active", nullable = false)
    final var active: Boolean = true
        private set

    fun update(name: String, description: String?) {
        this.name = requireName(name)
        this.description = description?.trim()?.ifEmpty { null }
    }

    fun activate() {
        active = true
    }

    fun deactivate() {
        active = false
    }

    companion object {
        fun normalizeCode(code: String): String {
            val normalized = code.trim().uppercase()
            domainRequire(normalized.matches(CODE_PATTERN)) { "Category code must be letters, digits and underscores, starting with a letter" }
            return normalized
        }

        private val CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,99}")

        private fun requireName(name: String): String {
            val trimmed = name.trim()
            domainRequire(trimmed.isNotEmpty()) { "Category name must not be blank" }
            domainRequire(trimmed.length <= 100) { "Category name must be at most 100 characters" }
            return trimmed
        }
    }
}
