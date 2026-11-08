package com.itsm.auth

import com.itsm.user.Role
import com.itsm.user.UserRepository
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.DisabledException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/** A validated JWT whose user was confirmed active in the database for this request. */
class UserAuthenticationToken(jwt: Jwt, val user: AuthenticatedUser) :
    JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")), user.id.toString())

/**
 * Runs on every request with a valid signature and expiry. It loads a lightweight projection of the user from the database so that a
 * deactivated or deleted account is rejected straight away (401) instead of when its token expires, and so
 * that email and role always reflect the current database values rather than the token claims.
 */
@Component
class UserJwtAuthenticationConverter(private val users: UserRepository) : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val id = jwt.subject?.toLongOrNull() ?: throw BadCredentialsException("Token has no valid subject")
        val user = users.findAuthViewById(id) ?: throw BadCredentialsException("Token user no longer exists")
        if (!user.active) throw DisabledException("Account is disabled")
        return UserAuthenticationToken(jwt, AuthenticatedUser(id, user.email, Role.valueOf(user.role)))
    }
}
