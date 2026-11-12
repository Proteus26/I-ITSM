package com.itsm.user

import com.itsm.auth.CurrentUser
import com.itsm.auth.SessionRevoker
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminUserService(
    private val users: UserRepository,
    private val userFactory: UserFactory,
    private val passwordEncoder: PasswordEncoder,
    private val currentUser: CurrentUser,
    private val sessionRevoker: SessionRevoker,
) {

    @Transactional(readOnly = true)
    fun list(role: Role?, active: Boolean?, q: String?, page: Int, size: Int): PageResponse<UserResponse> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100), Sort.by("id"))
        val result = users.findAll(UserSpecifications.filter(role, active, q), pageable)
        return PageResponse(
            content = result.content.map { it.toResponse() },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun get(id: Long): UserResponse = find(id).toResponse()

    @Transactional
    fun create(request: CreateUserRequest): UserResponse {
        val email = normalizeEmail(request.email)
        if (users.existsByEmail(email)) throw EmailAlreadyRegisteredException(email)
        val user = userFactory.create(
            request.role, email, request.fullName, request.password,
            request.department, request.level, request.maxActiveIncidents, request.skills,
        )
        return translatingEmailConflict({ user.email }) { users.saveAndFlush(user).toResponse() }
    }

    /**
     * Partial update; null fields are left unchanged. Fields that do not apply to the user's type
     * (department for non-employees; level, maxActiveIncidents, available, skills for non-technicians)
     * are rejected with 400. A blank department clears it. Admins cannot deactivate themselves.
     */
    @Transactional
    fun update(id: Long, request: UpdateUserRequest): UserResponse {
        val user = find(id)
        if (request.active == false && user.id == currentUser.get().id) throw CannotDeactivateSelfException()

        if (request.department != null && user !is Employee) {
            throw InvalidUserUpdateException("department applies to employees only")
        }
        val techFields = request.level != null || request.maxActiveIncidents != null ||
            request.available != null || request.skills != null
        if (techFields && user !is Technician) {
            throw InvalidUserUpdateException("level, maxActiveIncidents, available and skills apply to technicians only")
        }

        request.fullName?.let { user.rename(it) }
        request.email?.let {
            val email = normalizeEmail(it)
            if (email != user.email) {
                if (users.existsByEmail(email)) throw EmailAlreadyRegisteredException(email)
                user.changeEmail(email)
            }
        }
        request.active?.let {
            if (it) {
                user.activate()
            } else {
                user.deactivate()
                sessionRevoker.revoke(id)
            }
        }
        if (user is Employee) request.department?.let { user.changeDepartment(it) }
        if (user is Technician) {
            request.level?.let { user.changeLevel(it) }
            request.maxActiveIncidents?.let { user.changeCapacity(it) }
            request.available?.let { if (it) user.markAvailable() else user.markUnavailable() }
            request.skills?.let { user.replaceSkills(it) }
        }
        return translatingEmailConflict({ user.email }) { users.saveAndFlush(user).toResponse() }
    }

    @Transactional
    fun resetPassword(id: Long, request: ResetPasswordRequest) {
        find(id).resetPassword(passwordEncoder, request.newPassword)
        sessionRevoker.revoke(id)
    }

    private fun find(id: Long): User = users.findById(id).orElseThrow { UserNotFoundException(id) }
}
