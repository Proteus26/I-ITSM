package com.itsm.user

import org.springframework.data.jpa.repository.JpaRepository

// STUB: owned by P1. P4 uses these two finder methods.
interface UserRepository : JpaRepository<User, Long> {
    fun findByUsername(username: String): User?
    fun findByRoleInAndActiveTrue(roles: Collection<Role>): List<User>
}
