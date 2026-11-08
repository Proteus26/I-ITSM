package com.itsm.user

import com.itsm.common.exception.domainRequire
import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorColumn
import jakarta.persistence.DiscriminatorType
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Inheritance
import jakarta.persistence.InheritanceType
import jakarta.persistence.Table
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Abstract base of all accounts (single-table inheritance). The password hash is
 * encapsulated: it can only be verified or replaced through the methods below.
 */
@Entity
@Table(name = "users")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "role", discriminatorType = DiscriminatorType.STRING, length = 20)
abstract class User(
    email: String,
    fullName: String,
    passwordHash: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "email", nullable = false, unique = true)
    var email: String = normalizeEmail(email)
        protected set

    @Column(name = "full_name", nullable = false)
    var fullName: String = requireNotBlank(fullName, "Full name")
        protected set

    @Column(name = "password_hash", nullable = false)
    private var passwordHash: String = requireNotBlank(passwordHash, "Password hash")

    @Column(name = "active", nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    // Truncated to the database precision so a POST response matches a later GET.
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    /** Computed by each subclass; has no backing field so it is not persisted (the discriminator stores it). */
    abstract val role: Role

    /**
     * Over-long input never matches (BCrypt would otherwise ignore everything past 72 bytes). It still costs
     * one hash comparison, so response time does not reveal that the account exists.
     */
    fun passwordMatches(encoder: PasswordEncoder, raw: String): Boolean {
        val overLimit = PasswordPolicy.exceedsByteLimit(raw)
        val matched = encoder.matches(if (overLimit) OVER_LIMIT_PLACEHOLDER else raw, passwordHash)
        return matched && !overLimit
    }

    fun changePassword(encoder: PasswordEncoder, current: String, new: String) {
        if (!passwordMatches(encoder, current)) throw IncorrectPasswordException()
        resetPassword(encoder, new)
    }

    /** Admin reset: no current-password check. */
    fun resetPassword(encoder: PasswordEncoder, new: String) {
        PasswordPolicy.requireUsable(new)
        passwordHash = encoder.encode(new)!!
    }

    fun activate() {
        active = true
    }

    fun deactivate() {
        active = false
    }

    fun rename(fullName: String) {
        this.fullName = requireNotBlank(fullName, "Full name")
    }

    fun changeEmail(email: String) {
        this.email = normalizeEmail(email)
    }

    /** Identity is the database id; transient (unsaved) instances are only equal to themselves. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is User) return false
        val thisId = id ?: return false
        return thisId == other.id
    }

    override fun hashCode(): Int = User::class.java.hashCode()

    override fun toString(): String = "${javaClass.simpleName}(id=$id, email=$email, active=$active)"

    private companion object {
        fun requireNotBlank(value: String, name: String): String {
            domainRequire(value.isNotBlank()) { "$name must not be blank" }
            return value.trim()
        }

        const val OVER_LIMIT_PLACEHOLDER = "password-over-the-bcrypt-limit"
    }
}
