package com.itsm.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface UserRepository : JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    /** Callers must pass an email already run through [normalizeEmail]. */
    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean
}
