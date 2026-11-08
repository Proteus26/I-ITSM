package com.itsm.user

import java.time.Instant

data class TechnicianDetails(
    val level: TechnicianLevel,
    val maxActiveIncidents: Int,
    val available: Boolean,
    val skills: Set<String>,
)

/** API view of a user; never includes the password hash. */
data class UserResponse(
    val id: Long,
    val email: String,
    val fullName: String,
    val role: Role,
    val active: Boolean,
    val createdAt: Instant,
    val department: String? = null,
    val technician: TechnicianDetails? = null,
)

fun User.toResponse(): UserResponse = UserResponse(
    id = requireNotNull(id),
    email = email,
    fullName = fullName,
    role = role,
    active = active,
    createdAt = createdAt,
    department = (this as? Employee)?.department,
    technician = (this as? Technician)?.let {
        TechnicianDetails(it.level, it.maxActiveIncidents, it.available, it.skills.toSortedSet())
    },
)
