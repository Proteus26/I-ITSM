package com.itsm.auth

import com.itsm.user.Technician
import com.itsm.user.TechnicianLevel
import com.itsm.user.User
import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwtTokenServiceTest {

    private val secret = "unit-test-secret-0123456789-abcdefghijklmnop"
    private val key: SecretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val issuer = "itsm-backend"
    private val ttl = Duration.ofHours(2)
    private val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

    private fun props(issuerName: String = issuer) = AuthProperties().apply {
        tokenTtl = ttl
        this.issuer = issuerName
    }

    private fun service(signingKey: SecretKey = key, at: Instant = now, issuerName: String = issuer) =
        JwtTokenService(
            NimbusJwtEncoder(ImmutableSecret<com.nimbusds.jose.proc.SecurityContext>(signingKey)),
            props(issuerName),
            Clock.fixed(at, ZoneOffset.UTC),
        )

    private fun decoder(expectedIssuer: String = issuer, decodeKey: SecretKey = key): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(decodeKey).macAlgorithm(MacAlgorithm.HS256).build().apply {
            setJwtValidator(JwtValidators.createDefaultWithIssuer(expectedIssuer))
        }

    /** User.id is assigned by JPA, so set it reflectively for these database-free tests. */
    private fun user(id: Long = 42): User {
        val tech = Technician(
            "tech@example.com", "Tara Tech", "hash",
            TechnicianLevel.L2, 5, setOf("net"),
        )
        User::class.java.getDeclaredField("id").apply { isAccessible = true }.set(tech, id)
        return tech
    }

    @Test
    fun `issued token decodes with claims and expiry derived from the clock and ttl`() {
        val issued = service().issue(user())

        val jwt = decoder().decode(issued.value)

        assertEquals("42", jwt.subject)
        assertEquals("tech@example.com", jwt.getClaimAsString("email"))
        assertEquals("Tara Tech", jwt.getClaimAsString("name"))
        assertEquals("TECHNICIAN", jwt.getClaimAsString("role"))
        assertEquals(issuer, jwt.getClaimAsString("iss"))
        assertEquals(now, jwt.issuedAt)
        assertEquals(now.plus(ttl), jwt.expiresAt)
        assertEquals(now.plus(ttl), issued.expiresAt)
    }

    @Test
    fun `token signed with a different secret is rejected`() {
        val otherKey = SecretKeySpec("a-completely-different-secret-0123456789".toByteArray(), "HmacSHA256")
        val token = service(signingKey = otherKey, at = Instant.now()).issue(user()).value

        assertFailsWith<BadJwtException> { decoder().decode(token) }
    }

    @Test
    fun `expired token is rejected`() {
        val token = service(at = Instant.now().minus(ttl).minus(Duration.ofMinutes(10))).issue(user()).value

        assertFailsWith<BadJwtException> { decoder().decode(token) }
    }

    @Test
    fun `token with a different issuer is rejected`() {
        val token = service(at = Instant.now(), issuerName = "someone-else").issue(user()).value

        assertFailsWith<BadJwtException> { decoder().decode(token) }
    }

    @Test
    fun `token issued for an unsaved user fails`() {
        val unsaved = Technician("t@example.com", "T", "hash")

        assertFailsWith<IllegalArgumentException> { service().issue(unsaved) }
    }
}
