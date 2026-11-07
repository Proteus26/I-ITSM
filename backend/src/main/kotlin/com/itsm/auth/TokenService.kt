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
    fun issue(user: User): IssuedToken
}

/** Issues HS256-signed JWTs with claims sub, email, name, role, iss, iat, exp. */
@Service
class JwtTokenService(
    private val encoder: JwtEncoder,
    private val properties: AuthProperties,
    private val clock: Clock,
) : TokenService {

    override fun issue(user: User): IssuedToken {
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
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val value = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedToken(value, expiresAt)
    }
}
