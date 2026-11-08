package com.itsm.auth

import com.itsm.user.Role
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * The authenticated caller. [email] and [role] are the current database values, loaded once per request
 * by [UserJwtAuthenticationConverter]; deactivated or deleted users never get this far (401).
 */
data class AuthenticatedUser(val id: Long, val email: String, val role: Role)

/**
 * Access to the authenticated caller from any service or controller.
 *
 * ```
 * class IncidentService(private val currentUser: CurrentUser) {
 *     fun open(...) { val me = currentUser.get(); if (me.role != Role.EMPLOYEE) throw UnauthorizedOperationException() }
 * }
 * ```
 */
@Component
class CurrentUser {

    /** @throws UnauthenticatedException if there is no authenticated JWT caller. */
    fun get(): AuthenticatedUser = getOrNull() ?: throw UnauthenticatedException()

    fun getOrNull(): AuthenticatedUser? {
        val auth = SecurityContextHolder.getContext().authentication
        if (auth is UserAuthenticationToken) return auth.user
        // Fallback for tokens built without the converter, e.g. spring-security-test's jwt() in a test.
        if (auth !is JwtAuthenticationToken) return null
        val jwt = auth.token
        val id = jwt.subject?.toLongOrNull() ?: return null
        val email = jwt.getClaimAsString("email") ?: return null
        val role = jwt.getClaimAsString("role")?.let { name -> Role.entries.firstOrNull { it.name == name } } ?: return null
        return AuthenticatedUser(id, email, role)
    }
}
