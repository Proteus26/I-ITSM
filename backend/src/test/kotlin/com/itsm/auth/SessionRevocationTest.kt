package com.itsm.auth

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import com.itsm.user.UserFactory
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SessionRevocationTest @Autowired constructor(
    mvc: MockMvc,
    mapper: JsonMapper,
    private val users: UserRepository,
    private val factory: UserFactory,
    private val encoder: JwtEncoder,
    private val properties: AuthProperties,
    private val revoker: SessionRevoker,
) {
    private val api = ApiClient(mvc, mapper)
    private val pw = "password123"

    private fun me(token: String) = api.get("/api/auth/me", token).response.status

    private fun admin(): String {
        val email = api.uniqueEmail("admin")
        users.saveAndFlush(factory.createAdministrator(email, "Admin Person", pw))
        return api.token(email, pw)
    }

    private fun changePasswordBody(current: String, new: String) =
        mapOf("currentPassword" to current, "newPassword" to new)

    @Test
    fun `change password returns a fresh token, revokes the old one and swaps the password`() {
        val email = api.uniqueEmail()
        api.register(email, "oldpassword1")
        val old = api.token(email, "oldpassword1")

        val result = api.post("/api/auth/change-password", changePasswordBody("oldpassword1", "newpassword1"), old)

        assertEquals(200, result.response.status)
        val body = api.json(result)
        val fresh = body["token"].asString()
        assertEquals("Bearer", body["tokenType"].asString())
        assertEquals(email, body["user"]["email"].asString())
        assertTrue(body["expiresAt"].asString().isNotEmpty())
        assertEquals(401, me(old))
        assertEquals(200, me(fresh))
        assertEquals(200, api.login(email, "newpassword1").response.status)
        assertEquals(401, api.login(email, "oldpassword1").response.status)
    }

    @Test
    fun `a failed change password keeps the current token valid`() {
        val email = api.uniqueEmail()
        api.register(email, "oldpassword1")
        val token = api.token(email, "oldpassword1")

        assertEquals(400, api.post("/api/auth/change-password", changePasswordBody("wrong-pass1", "newpassword1"), token).response.status)

        assertEquals(200, me(token))
    }

    @Test
    fun `admin reset password revokes the user's existing token`() {
        val adminToken = admin()
        val email = api.uniqueEmail()
        api.register(email, pw)
        val old = api.token(email, pw)
        val id = users.findByEmail(email)!!.id

        val reset = api.post("/api/admin/users/$id/reset-password", mapOf("newPassword" to "brandnew123"), adminToken)

        assertEquals(204, reset.response.status)
        assertEquals(401, me(old))
        assertEquals(200, me(api.token(email, "brandnew123")))
    }

    @Test
    fun `deactivating through the admin api then reactivating still leaves the old token revoked`() {
        val adminToken = admin()
        val email = api.uniqueEmail()
        api.register(email, pw)
        val old = api.token(email, pw)
        val id = users.findByEmail(email)!!.id

        api.patch("/api/admin/users/$id", mapOf("active" to false), adminToken)
        api.patch("/api/admin/users/$id", mapOf("active" to true), adminToken)

        assertEquals(401, me(old))
        assertEquals(200, me(api.token(email, pw)))
    }

    @Test
    fun `logout-all revokes every token including the current one and a fresh login works`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val first = api.token(email, pw)
        val second = api.token(email, pw)

        val result = api.post("/api/auth/logout-all", token = first)

        assertEquals(204, result.response.status)
        assertEquals(401, me(first))
        assertEquals(401, me(second))
        assertEquals(200, me(api.token(email, pw)))
    }

    @Test
    fun `logout-all requires authentication`() {
        assertEquals(401, api.post("/api/auth/logout-all").response.status)
    }

    @Test
    fun `tokens carry the ver claim and it follows the user's version`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val token = api.token(email, pw)
        api.post("/api/auth/logout-all", token = token)
        val fresh = api.token(email, pw)

        val claims = String(java.util.Base64.getUrlDecoder().decode(fresh.split(".")[1]))
        assertTrue(Regex("\"ver\":1\\b").containsMatchIn(claims), claims)
        assertEquals(1, users.findByEmail(email)!!.tokenVersion)
    }

    @Test
    fun `a token without a ver claim counts as version 0`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val id = users.findByEmail(email)!!.id!!

        val legacy = mintToken(id, ver = null)
        assertEquals(200, me(legacy))

        revoker.revoke(id)
        assertEquals(401, me(legacy))
        assertEquals(200, me(mintToken(id, ver = 1)))
        assertEquals(401, me(mintToken(id, ver = 0)))
    }

    @Test
    fun `a new user starts at version 0 and the version only goes up`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val before = users.findByEmail(email)!!.tokenVersion
        assertEquals(0, before)
        revoker.revoke(users.findByEmail(email)!!.id!!)
        assertNotEquals(before, users.findByEmail(email)!!.tokenVersion)
        assertNull(users.findByEmail(email)!!.lastLoginAt)
    }

    @Test
    fun `logout-all after change-password rejects the token that change-password issued`() {
        val email = api.uniqueEmail()
        api.register(email, "oldpassword1")
        val old = api.token(email, "oldpassword1")
        val fresh = api.json(api.post("/api/auth/change-password", changePasswordBody("oldpassword1", "newpassword1"), old))["token"].asString()
        assertEquals(200, me(fresh))

        assertEquals(204, api.post("/api/auth/logout-all", token = fresh).response.status)

        assertEquals(401, me(fresh))
        assertEquals(2, users.findByEmail(email)!!.tokenVersion)
    }

    @Test
    fun `concurrent revocations never lose an increment`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val id = users.findByEmail(email)!!.id!!
        val n = 20
        val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
        val results = (1..n).map { pool.submit<Int> { revoker.revoke(id) } }.map { it.get() }
        pool.shutdown()

        assertEquals((1..n).toList(), results.sorted())
        assertEquals(n, users.findByEmail(email)!!.tokenVersion)
    }

    @Test
    fun `revoking an unknown user fails`() {
        kotlin.test.assertFailsWith<com.itsm.user.UserNotFoundException> { revoker.revoke(-1) }
    }

    @Test
    fun `an invalid ver claim is rejected, an integral zero is accepted`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val id = users.findByEmail(email)!!.id!!

        assertEquals(200, me(mintToken(id, 0)))
        assertEquals(200, me(mintToken(id, 0L)))
        assertEquals(401, me(mintToken(id, "0")))
        assertEquals(401, me(mintToken(id, -1)))
        assertEquals(401, me(mintToken(id, 4294967296L))) // 2^32 would wrap to 0 as an Int
        assertEquals(401, me(mintToken(id, 0.9)))
    }

    private fun mintToken(userId: Long, ver: Any?): String {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val builder = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
        if (ver != null) builder.claim("ver", ver)
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).tokenValue
    }
}
