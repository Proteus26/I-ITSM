package com.itsm.auth

import com.itsm.user.EmailAlreadyRegisteredException
import com.itsm.user.Employee
import com.itsm.user.InvalidUserUpdateException
import com.itsm.user.User
import com.itsm.user.UserFactory
import com.itsm.user.UserNotFoundException
import com.itsm.user.UserRepository
import com.itsm.user.UserResponse
import com.itsm.user.normalizeEmail
import com.itsm.user.translatingEmailConflict
import com.itsm.user.toResponse
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class AuthService(
    private val users: UserRepository,
    private val userFactory: UserFactory,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val loginAttempts: LoginAttemptTracker,
    private val sessionRevoker: SessionRevoker,
) {
    // Matched against when the email is unknown so both failure paths cost about the same.
    private val dummyHash: String = passwordEncoder.encode("dummy-password-for-timing")!!

    @Transactional
    fun register(request: RegisterRequest): User {
        val email = normalizeEmail(request.email)
        if (users.existsByEmail(email)) throw EmailAlreadyRegisteredException(email)
        val employee = userFactory.createEmployee(email, request.fullName, request.password, request.department)
        return translatingEmailConflict({ employee.email }) { users.saveAndFlush(employee) }
    }

    /** Deliberately not transactional: failure events must be published without a rollback-only transaction. */
    fun login(request: LoginRequest): LoginResponse {
        val email = normalizeEmail(request.email)
        // First, and before any hashing: the answer must not depend on whether the account exists.
        // This also counts the attempt (atomically); a failure needs no further bookkeeping.
        loginAttempts.reserveAttempt(email)
        val user = users.findByEmail(email)
        if (user == null) {
            passwordEncoder.matches(request.password, dummyHash)
            throw failed(email, "UNKNOWN_EMAIL", InvalidCredentialsException())
        }
        if (!user.passwordMatches(passwordEncoder, request.password)) {
            throw failed(email, "BAD_PASSWORD", InvalidCredentialsException())
        }
        if (!user.active) throw failed(email, "ACCOUNT_DISABLED", AccountDisabledException())

        val token = tokenService.issue(user)
        val at = clock.instant().truncatedTo(ChronoUnit.MICROS) // database precision, so responses match later reads
        loginAttempts.clear(email) // directly, so it does not depend on the order of event listeners
        events.publishEvent(UserLoggedIn(user.id!!, user.email, at))
        // The login columns are written by a listener in its own transaction, after this user was loaded:
        // report what the database holds after that update (previous = the old last login, last = now).
        val loggedIn = user.toResponse().copy(previousLoginAt = user.lastLoginAt, lastLoginAt = at)
        return LoginResponse(token = token.value, expiresAt = token.expiresAt, user = loggedIn)
    }

    @Transactional(readOnly = true)
    fun me(): UserResponse = loadCurrent().toResponse()

    /** Revokes all existing tokens (including the caller's) and returns a new one at the new token version. */
    @Transactional
    fun changePassword(request: ChangePasswordRequest): LoginResponse {
        val user = loadCurrent()
        user.changePassword(passwordEncoder, request.currentPassword, request.newPassword)
        // The token must carry the version produced by this very increment, not the entity's loaded one.
        val token = tokenService.issue(user, sessionRevoker.revoke(user.id!!))
        return LoginResponse(token = token.value, expiresAt = token.expiresAt, user = user.toResponse())
    }

    @Transactional
    fun logoutAll() {
        sessionRevoker.revoke(currentUser.get().id)
    }

    /** Null fields are unchanged; department is for employees only (400 otherwise). Email and role cannot be changed here. */
    @Transactional
    fun updateProfile(request: UpdateProfileRequest): UserResponse {
        val user = loadCurrent()
        if (request.department != null && user !is Employee) {
            throw InvalidUserUpdateException("department applies to employees only")
        }
        request.fullName?.let { user.rename(it) }
        if (user is Employee) request.department?.let { user.changeDepartment(it) }
        return user.toResponse()
    }

    private fun loadCurrent(): User {
        val id = currentUser.get().id
        return users.findById(id).orElseThrow { UserNotFoundException(id) }
    }

    private fun <T : Exception> failed(email: String, reason: String, ex: T): T {
        events.publishEvent(LoginFailed(email, reason, clock.instant()))
        return ex
    }
}
