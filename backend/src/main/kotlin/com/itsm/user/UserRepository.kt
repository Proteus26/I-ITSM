package com.itsm.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface UserRepository : JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    /** Callers must pass an email already run through [normalizeEmail]. */
    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean

    /** Lightweight lookup for the per-request authentication check. role holds the discriminator value (a Role name). */
    @Query(
        "select id as id, email as email, role as role, active as active, token_version as \"tokenVersion\" from users where id = :id",
        nativeQuery = true,
    )
    fun findAuthViewById(@Param("id") id: Long): UserAuthView?

    /**
     * Bulk update on purpose: it touches only the two login columns, so it cannot overwrite anything else.
     * The previous value of last_login_at becomes previous_login_at. Runs in its own transaction.
     */
    @Transactional
    @Modifying
    @Query("update User u set u.previousLoginAt = u.lastLoginAt, u.lastLoginAt = :at where u.id = :id")
    fun recordLogin(@Param("id") id: Long, @Param("at") at: Instant): Int

    /**
     * Atomic increment; returns the new version, or null when the user does not exist.
     * Must join the caller's read-write transaction (see SessionRevoker), so the bump rolls back with it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update users set token_version = token_version + 1 where id = :id returning token_version", nativeQuery = true)
    fun incrementTokenVersion(@Param("id") id: Long): Int?

    @Query("select count(u) > 0 from User u where type(u) = Administrator")
    fun existsAdministrator(): Boolean
}

/** What authentication needs per request; avoids loading the whole entity (technician skills are EAGER). */
interface UserAuthView {
    val id: Long
    val email: String
    val role: String
    val active: Boolean
    val tokenVersion: Int
}
