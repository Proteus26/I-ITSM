package com.itsm.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    /** Callers must pass an email already run through [normalizeEmail]. */
    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean

    /** Lightweight lookup for the per-request authentication check. role holds the discriminator value (a Role name). */
    @Query("select id as id, email as email, role as role, active as active from users where id = :id", nativeQuery = true)
    fun findAuthViewById(@Param("id") id: Long): UserAuthView?

    @Query("select count(u) > 0 from User u where type(u) = Administrator")
    fun existsAdministrator(): Boolean
}

/** What authentication needs per request; avoids loading the whole entity (technician skills are EAGER). */
interface UserAuthView {
    val id: Long
    val email: String
    val role: String
    val active: Boolean
}
