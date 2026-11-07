package com.itsm.auth

import com.itsm.user.Role
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/** The caller as described by the validated JWT (no database access). */
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
        val auth = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken ?: return null
        val jwt = auth.token
        val id = jwt.subject?.toLongOrNull() ?: return null
        val email = jwt.getClaimAsString("email") ?: return null
        val role = jwt.getClaimAsString("role")?.let { name -> Role.entries.firstOrNull { it.name == name } } ?: return null
        return AuthenticatedUser(id, email, role)
    }
}
