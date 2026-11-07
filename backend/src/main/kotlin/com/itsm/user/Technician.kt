package com.itsm.user

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn

@Entity
@DiscriminatorValue("TECHNICIAN")
class Technician(
    email: String,
    fullName: String,
    passwordHash: String,
    level: TechnicianLevel = TechnicianLevel.L1,
    maxActiveIncidents: Int = DEFAULT_MAX_ACTIVE_INCIDENTS,
    skills: Set<String> = emptySet(),
) : User(email, fullName, passwordHash) {

    @Enumerated(EnumType.STRING)
    @Column(name = "technician_level")
    var level: TechnicianLevel = level
        protected set

    @Column(name = "max_active_incidents")
    var maxActiveIncidents: Int = requireCapacity(maxActiveIncidents)
        protected set

    @Column(name = "available")
    var available: Boolean = true
        protected set

    // Category codes. A later migration will add an FK to categories once that table exists.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "technician_skills", joinColumns = [JoinColumn(name = "technician_id")])
    @Column(name = "category_code", nullable = false)
    private val skillCodes: MutableSet<String> = skills.mapTo(mutableSetOf(), ::normalizeSkill)

    override val role: Role get() = Role.TECHNICIAN

    /** Read-only snapshot of the uppercased skill codes. */
    val skills: Set<String> get() = skillCodes.toSet()

    fun addSkill(code: String): Boolean = skillCodes.add(normalizeSkill(code))

    fun removeSkill(code: String): Boolean = skillCodes.remove(normalizeSkill(code))

    fun hasSkill(code: String): Boolean = normalizeSkill(code) in skillCodes

    fun markAvailable() {
        available = true
    }

    fun markUnavailable() {
        available = false
    }

    fun changeLevel(level: TechnicianLevel) {
        this.level = level
    }

    fun changeCapacity(max: Int) {
        maxActiveIncidents = requireCapacity(max)
    }

    private companion object {
        const val DEFAULT_MAX_ACTIVE_INCIDENTS = 5

        fun requireCapacity(max: Int): Int {
            require(max >= 1) { "maxActiveIncidents must be at least 1" }
            return max
        }

        fun normalizeSkill(code: String): String {
            val normalized = code.trim().uppercase()
            require(normalized.isNotEmpty()) { "Skill code must not be blank" }
            return normalized
        }
    }
}
