package com.itsm.user

import com.itsm.common.exception.domainRequire
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/** Factory: builds the right [User] subclass from raw input, hashing the password. */
@Component
class UserFactory(private val encoder: PasswordEncoder) {

    fun createEmployee(email: String, fullName: String, rawPassword: String, department: String? = null): Employee =
        Employee(email, fullName, hash(rawPassword), department)

    fun createTechnician(
        email: String,
        fullName: String,
        rawPassword: String,
        level: TechnicianLevel = Technician.DEFAULT_LEVEL,
        maxActiveIncidents: Int = Technician.DEFAULT_MAX_ACTIVE_INCIDENTS,
        skills: Set<String> = emptySet(),
    ): Technician = Technician(email, fullName, hash(rawPassword), level, maxActiveIncidents, skills)

    fun createAdministrator(email: String, fullName: String, rawPassword: String): Administrator =
        Administrator(email, fullName, hash(rawPassword))

    /**
     * Creates the subclass matching [role]. Null extras take the technician defaults. Extras that do not apply
     * to [role] (department only for employees; level, maxActiveIncidents and skills only for technicians)
     * are rejected.
     */
    fun create(
        role: Role,
        email: String,
        fullName: String,
        rawPassword: String,
        department: String? = null,
        level: TechnicianLevel? = null,
        maxActiveIncidents: Int? = null,
        skills: Set<String>? = null,
    ): User {
        domainRequire(role == Role.EMPLOYEE || department == null) { "department applies to employees only" }
        domainRequire(role == Role.TECHNICIAN || (level == null && maxActiveIncidents == null && skills == null)) {
            "level, maxActiveIncidents and skills apply to technicians only"
        }
        return when (role) {
            Role.EMPLOYEE -> createEmployee(email, fullName, rawPassword, department)
            Role.TECHNICIAN -> createTechnician(
                email, fullName, rawPassword,
                level ?: Technician.DEFAULT_LEVEL,
                maxActiveIncidents ?: Technician.DEFAULT_MAX_ACTIVE_INCIDENTS,
                skills ?: emptySet(),
            )
            Role.ADMIN -> createAdministrator(email, fullName, rawPassword)
        }
    }

    private fun hash(raw: String): String {
        PasswordPolicy.requireUsable(raw)
        return encoder.encode(raw)!!
    }
}
