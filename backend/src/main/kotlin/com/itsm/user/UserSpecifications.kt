package com.itsm.user

import org.springframework.data.jpa.domain.Specification

object UserSpecifications {

    /** Filters by subclass using the JPA type() of the entity (single-table inheritance). */
    fun hasRole(role: Role): Specification<User> = Specification { root, _, cb ->
        val type = when (role) {
            Role.EMPLOYEE -> Employee::class.java
            Role.TECHNICIAN -> Technician::class.java
            Role.ADMIN -> Administrator::class.java
        }
        cb.equal(root.type(), type)
    }

    fun isActive(active: Boolean): Specification<User> = Specification { root, _, cb ->
        cb.equal(root.get<Boolean>("active"), active)
    }

    /** Case-insensitive match on email or full name. */
    fun matches(q: String): Specification<User> = Specification { root, _, cb ->
        val pattern = "%" + q.trim().lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        cb.or(
            cb.like(cb.lower(root.get("email")), pattern, '\\'),
            cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
        )
    }

    fun filter(role: Role?, active: Boolean?, q: String?): Specification<User> {
        val specs = listOfNotNull(
            role?.let(::hasRole),
            active?.let(::isActive),
            q?.takeIf { it.isNotBlank() }?.let(::matches),
        )
        return specs.fold(Specification.unrestricted()) { acc, s -> acc.and(s) }
    }
}
