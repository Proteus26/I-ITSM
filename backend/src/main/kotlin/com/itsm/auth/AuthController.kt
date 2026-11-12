package com.itsm.auth

import com.itsm.user.UserResponse
import com.itsm.user.toResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
class AuthController(private val authService: AuthService) {

    @SecurityRequirements
    @Operation(summary = "Register an employee account")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest): UserResponse =
        authService.register(request).toResponse()

    @SecurityRequirements
    @Operation(
        summary = "Log in",
        description = "Returns a bearer token. Every attempt for an email counts towards a per-email throttle " +
            "(failed ones and ones for disabled accounts); once the limit is reached further attempts get 429.",
    )
    @ApiResponse(responseCode = "200", description = "Logged in")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    @ApiResponse(responseCode = "403", description = "Account is disabled")
    @ApiResponse(
        responseCode = "429",
        description = "Too many login attempts for this email",
        headers = [Header(name = "Retry-After", description = "Seconds until the lock ends (at least 1)", schema = Schema(type = "integer"))],
    )
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse = authService.login(request)

    @Operation(summary = "Current user")
    @ApiResponse(responseCode = "200", description = "The authenticated user")
    @GetMapping("/me")
    fun me(): UserResponse = authService.me()

    @Operation(summary = "Update own profile", description = "Null fields are unchanged; department is for employees only.")
    @ApiResponse(responseCode = "200", description = "Updated profile")
    @ApiResponse(responseCode = "400", description = "Validation failed, or department given for a non-employee")
    @PatchMapping("/me")
    fun updateMe(@Valid @RequestBody request: UpdateProfileRequest): UserResponse = authService.updateProfile(request)

        /** Revokes all existing tokens of the caller, including the one used for this request, and returns a replacement. */
    @Operation(
        summary = "Change password",
        description = "Revokes every token of the caller (the one used here included) and returns a new token.",
    )
    @ApiResponse(responseCode = "200", description = "Password changed; the response carries the replacement token")
    @ApiResponse(responseCode = "400", description = "Wrong current password or invalid new password")
    @PostMapping("/change-password")
    fun changePassword(@Valid @RequestBody request: ChangePasswordRequest): LoginResponse =
        authService.changePassword(request)

        /** Revokes every token of the caller, including the one used for this request. No replacement is issued. */
    @Operation(summary = "Log out of all devices", description = "Revokes every token of the caller; log in again to continue.")
    @ApiResponse(responseCode = "204", description = "All tokens revoked", content = [Content()])
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logoutAll() = authService.logoutAll()
}
