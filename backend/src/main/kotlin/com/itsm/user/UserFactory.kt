package com.itsm.user

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
        level: TechnicianLevel = TechnicianLevel.L1,
        maxActiveIncidents: Int = 5,
        skills: Set<String> = emptySet(),
    ): Technician = Technician(email, fullName, hash(rawPassword), level, maxActiveIncidents, skills)

    fun createAdministrator(email: String, fullName: String, rawPassword: String): Administrator =
        Administrator(email, fullName, hash(rawPassword))

    /** Creates the subclass matching [role]; technician/employee extras take their defaults. */
    fun create(role: Role, email: String, fullName: String, rawPassword: String): User = when (role) {
        Role.EMPLOYEE -> createEmployee(email, fullName, rawPassword)
        Role.TECHNICIAN -> createTechnician(email, fullName, rawPassword)
        Role.ADMIN -> createAdministrator(email, fullName, rawPassword)
    }

    private fun hash(raw: String): String {
        require(raw.isNotBlank()) { "Password must not be blank" }
        return encoder.encode(raw)!!
    }
}
