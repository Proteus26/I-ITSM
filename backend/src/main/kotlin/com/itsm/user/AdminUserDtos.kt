package com.itsm.user

import com.itsm.auth.ValidPassword
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

private const val MAX_SKILLS = 20
private const val MAX_SKILL_CODE_LENGTH = 255 // technician_skills.category_code is varchar(255)
private const val NOT_BLANK = ".*\\S.*" // null stays valid (field unchanged); present-but-blank is a validation error

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
    @field:NotBlank @field:ValidPassword val password: String,
    @field:Size(max = 255) val department: String? = null,
    val level: TechnicianLevel? = null,
    @field:Min(1) val maxActiveIncidents: Int? = null,
    @field:Size(max = MAX_SKILLS) val skills: Set<@NotBlank @Size(max = MAX_SKILL_CODE_LENGTH) String>? = null,
)

/** Every field is optional; null means "leave unchanged". See [AdminUserService.update]. */
data class UpdateUserRequest(
    @field:Pattern(regexp = NOT_BLANK, message = "must not be blank") @field:Size(max = 255) val fullName: String? = null,
    @field:Pattern(regexp = NOT_BLANK, message = "must not be blank") @field:Email @field:Size(max = 255) val email: String? = null,
    val active: Boolean? = null,
    @field:Size(max = 255) val department: String? = null,
    val level: TechnicianLevel? = null,
    @field:Min(1) val maxActiveIncidents: Int? = null,
    val available: Boolean? = null,
    @field:Size(max = MAX_SKILLS) val skills: Set<@NotBlank @Size(max = MAX_SKILL_CODE_LENGTH) String>? = null,
)

data class ResetPasswordRequest(
    @field:NotBlank @field:ValidPassword val newPassword: String,
)
