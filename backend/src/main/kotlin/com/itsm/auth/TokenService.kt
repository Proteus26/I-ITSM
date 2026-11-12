package com.itsm.auth

import com.itsm.user.User
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Clock

interface TokenService {
    /** [tokenVersion] defaults to the user's loaded version; pass the result of a revocation to stay signed in. */
    fun issue(user: User, tokenVersion: Int = user.tokenVersion): IssuedToken
}

/** Issues HS256-signed JWTs with claims sub, email, name, role, ver (token version), iss, iat, exp. */
@Service
class JwtTokenService(
    private val encoder: JwtEncoder,
    private val properties: AuthProperties,
    private val clock: Clock,
) : TokenService {

    override fun issue(user: User, tokenVersion: Int): IssuedToken {
        val now = clock.instant().let { it.minusNanos(it.nano % 1_000_000L) }
        val expiresAt = now.plus(properties.tokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .subject(requireNotNull(user.id) { "Cannot issue a token for an unsaved user" }.toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim("email", user.email)
            .claim("name", user.fullName)
            .claim("role", user.role.name)
            .claim("ver", tokenVersion)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val value = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedToken(value, expiresAt)
    }
}
