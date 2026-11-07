package com.itsm.auth

import com.itsm.user.UserResponse
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/** Self-registration always creates an EMPLOYEE; there is deliberately no role field. */
data class RegisterRequest(
    @field:NotBlank @field:Email @field:Size(max = 255) val email: String,
    @field:NotBlank @field:Size(max = 255) val fullName: String,
    @field:NotBlank @field:Size(min = 8, max = 100) val password: String,
    @field:Size(max = 255) val department: String? = null,
)

data class LoginRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank @field:Size(max = 100) val password: String,
)

data class ChangePasswordRequest(
    @field:NotBlank @field:Size(max = 100) val currentPassword: String,
    @field:NotBlank @field:Size(min = 8, max = 100) val newPassword: String,
)

data class LoginResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
    val user: UserResponse,
)
