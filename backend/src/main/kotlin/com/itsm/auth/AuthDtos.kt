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
    @field:NotBlank @field:ValidPassword val password: String,
    @field:Size(max = 255) val department: String? = null,
)

data class LoginRequest(
    @field:NotBlank @field:Email val email: String,
    // No length limit: an over-long password simply fails the credential check (401), it is never a 400.
    @field:NotBlank val password: String,
)

data class ChangePasswordRequest(
    // No length limit: an over-long current password is reported as incorrect, like any other wrong one.
    @field:NotBlank val currentPassword: String,
    @field:NotBlank @field:ValidPassword val newPassword: String,
)

data class LoginResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
    val user: UserResponse,
)
