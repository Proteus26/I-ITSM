package com.itsm.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** department applies to EMPLOYEE only; level, maxActiveIncidents and skills to TECHNICIAN only (else 400). */
data class CreateUserRequest(
    @field:NotNull val role: Role,
    @field:NotBlank @field:Email @field:Size(max = 255) val email: String,
    @field:NotBlank @field:Size(max = 255) val fullName: String,
    @field:NotBlank @field:Size(min = 8, max = 100) val password: String,
    @field:Size(max = 255) val department: String? = null,
    val level: TechnicianLevel? = null,
    @field:Min(1) val maxActiveIncidents: Int? = null,
    val skills: Set<String>? = null,
)

/** Every field is optional; null means "leave unchanged". See [AdminUserService.update]. */
data class UpdateUserRequest(
    @field:Size(max = 255) val fullName: String? = null,
    @field:Email @field:Size(max = 255) val email: String? = null,
    val active: Boolean? = null,
    @field:Size(max = 255) val department: String? = null,
    val level: TechnicianLevel? = null,
    @field:Min(1) val maxActiveIncidents: Int? = null,
    val available: Boolean? = null,
    val skills: Set<String>? = null,
)

data class ResetPasswordRequest(
    @field:NotBlank @field:Size(min = 8, max = 100) val newPassword: String,
)
