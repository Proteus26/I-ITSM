package com.itsm.auth

import com.itsm.user.EmailAlreadyRegisteredException
import com.itsm.user.User
import com.itsm.user.UserFactory
import com.itsm.user.UserNotFoundException
import com.itsm.user.UserRepository
import com.itsm.user.UserResponse
import com.itsm.user.normalizeEmail
import com.itsm.user.toResponse
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class AuthService(
    private val users: UserRepository,
    private val userFactory: UserFactory,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    // Matched against when the email is unknown so both failure paths cost about the same.
    private val dummyHash: String by lazy { passwordEncoder.encode("dummy-password-for-timing")!! }

    @Transactional
    fun register(request: RegisterRequest): User {
        val email = normalizeEmail(request.email)
        if (users.existsByEmail(email)) throw EmailAlreadyRegisteredException(email)
        val employee = userFactory.createEmployee(email, request.fullName, request.password, request.department)
        try {
            return users.saveAndFlush(employee)
        } catch (_: DataIntegrityViolationException) {
            throw EmailAlreadyRegisteredException(email)
        }
    }

    @Transactional(readOnly = true)
    fun login(request: LoginRequest): LoginResponse {
        val email = normalizeEmail(request.email)
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
        events.publishEvent(UserLoggedIn(user.id!!, clock.instant()))
        return LoginResponse(token = token.value, expiresAt = token.expiresAt, user = user.toResponse())
    }

    @Transactional(readOnly = true)
    fun me(): UserResponse = loadCurrent().toResponse()

    @Transactional
    fun changePassword(request: ChangePasswordRequest) {
        loadCurrent().changePassword(passwordEncoder, request.currentPassword, request.newPassword)
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
