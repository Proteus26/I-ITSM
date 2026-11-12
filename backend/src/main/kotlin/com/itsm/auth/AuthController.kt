package com.itsm.auth

import com.itsm.user.UserResponse
import com.itsm.user.toResponse
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
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest): UserResponse =
        authService.register(request).toResponse()

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse = authService.login(request)

    @GetMapping("/me")
    fun me(): UserResponse = authService.me()

    @PatchMapping("/me")
    fun updateMe(@Valid @RequestBody request: UpdateProfileRequest): UserResponse = authService.updateProfile(request)

    /** Revokes every other session and returns a fresh token so the caller stays signed in. */
    @PostMapping("/change-password")
    fun changePassword(@Valid @RequestBody request: ChangePasswordRequest): LoginResponse =
        authService.changePassword(request)

    /** Revokes every token of the caller, including the one used for this request. */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logoutAll() = authService.logoutAll()
}
