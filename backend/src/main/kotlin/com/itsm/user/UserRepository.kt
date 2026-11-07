package com.itsm.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    /** Callers must pass an email already run through [normalizeEmail]. */
    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean

    @Query("select count(u) > 0 from User u where type(u) = Administrator")
    fun existsAdministrator(): Boolean
}
