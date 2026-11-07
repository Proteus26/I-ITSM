package com.itsm.auth

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AuthApiTest @Autowired constructor(
    mvc: MockMvc,
    mapper: JsonMapper,
    private val users: UserRepository,
) {
    private val api = ApiClient(mvc, mapper)

    @Test
    fun `register valid user returns 201 as employee without any password fields`() {
        val email = api.uniqueEmail()
        val result = api.register(email)

        assertEquals(201, result.response.status)
        val body = api.json(result)
        assertEquals("EMPLOYEE", body["role"].asString())
        assertEquals(email, body["email"].asString())
        assertTrue(body["active"].asBoolean())
        val raw = result.response.contentAsString.lowercase()
        assertFalse("password" in raw, raw)
        assertFalse("hash" in raw, raw)
        assertFalse("{bcrypt}" in raw, raw)
    }

    @Test
    fun `register ignores a role field in the body and still creates an employee`() {
        // Jackson 3 does not fail on unknown properties by default, so the extra field is silently dropped.
        val email = api.uniqueEmail()
        val result = api.post(
            "/api/auth/register",
            """{"email":"$email","fullName":"Sneaky","password":"password123","role":"ADMIN"}""",
        )

        assertEquals(201, result.response.status)
        assertEquals("EMPLOYEE", api.json(result)["role"].asString())
        assertEquals("EMPLOYEE", users.findByEmail(email)!!.role.name)
    }

    @Test
    fun `register duplicate email differing by case is rejected with 409 problem`() {
        val email = api.uniqueEmail("dup")
        assertEquals(201, api.register(email).response.status)

        val result = api.register(email.uppercase())

        assertEquals(409, result.response.status)
        assertTrue(result.response.contentType!!.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
        assertEquals(409, api.json(result)["status"].asInt())
    }

    @Test
    fun `register duplicate email with surrounding whitespace is never accepted as a new account`() {
        val email = api.uniqueEmail("ws")
        assertEquals(201, api.register(email).response.status)

        val status = api.register("  $email  ").response.status

        // Either bean validation rejects the padded address (400) or normalization detects the duplicate (409).
        assertTrue(status == 400 || status == 409, "unexpected status $status")
        assertEquals(1, users.findAll().count { it.email == email })
    }

    @Test
    fun `register with invalid fields returns 400 with per-field errors`() {
        val result = api.post(
            "/api/auth/register",
            mapOf("email" to "not-an-email", "fullName" to "  ", "password" to "short"),
        )

        assertEquals(400, result.response.status)
        val errors = api.json(result)["errors"]
        assertNotNull(errors["email"])
        assertNotNull(errors["fullName"])
        assertNotNull(errors["password"])
    }

    @Test
    fun `login with correct credentials returns a bearer token usable on me`() {
        val email = api.uniqueEmail()
        api.register(email, "password123", "Alice Example")

        val result = api.login(email, "password123")

        assertEquals(200, result.response.status)
        val body = api.json(result)
        assertEquals("Bearer", body["tokenType"].asString())
        assertEquals(email, body["user"]["email"].asString())
        assertNotNull(body["expiresAt"])
        val me = api.get("/api/auth/me", body["token"].asString())
        assertEquals(200, me.response.status)
        val meBody = api.json(me)
        assertEquals(email, meBody["email"].asString())
        assertEquals("Alice Example", meBody["fullName"].asString())
        assertEquals("EMPLOYEE", meBody["role"].asString())
    }

    @Test
    fun `login is case insensitive on the email`() {
        val email = api.uniqueEmail("case")
        api.register(email)

        val result = api.login(email.uppercase(), "password123")

        assertEquals(200, result.response.status)
    }

    @Test
    fun `wrong password and unknown email produce identical 401 details`() {
        val email = api.uniqueEmail()
        api.register(email)

        val wrongPassword = api.login(email, "wrong-password")
        val unknown = api.login(api.uniqueEmail("ghost"), "password123")

        assertEquals(401, wrongPassword.response.status)
        assertEquals(401, unknown.response.status)
        assertEquals(api.json(wrongPassword)["detail"].asString(), api.json(unknown)["detail"].asString())
        assertEquals("Invalid email or password", api.json(unknown)["detail"].asString())
    }

    @Test
    fun `me without a token returns 401 problem json with a WWW-Authenticate header`() {
        val result = api.get("/api/auth/me")

        assertEquals(401, result.response.status)
        assertTrue(result.response.contentType!!.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
        assertNotNull(result.response.getHeader("WWW-Authenticate"))
        assertEquals(401, api.json(result)["status"].asInt())
    }

    @Test
    fun `me with garbage token returns 401 problem json`() {
        val result = api.get("/api/auth/me", "not.a.jwt")

        assertEquals(401, result.response.status)
        assertTrue(result.response.contentType!!.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    }

    @Test
    fun `token with a tampered signature is rejected`() {
        val email = api.uniqueEmail()
        api.register(email)
        val token = api.token(email, "password123")
        val sig = token.substringAfterLast('.')
        // Flip a character in the middle of the signature (the last char only carries padding bits).
        val mid = sig.length / 2
        val flipped = sig.substring(0, mid) + (if (sig[mid] == 'A') 'B' else 'A') + sig.substring(mid + 1)
        val tampered = token.substringBeforeLast('.') + "." + flipped
        assertNotEquals(token, tampered)

        assertEquals(401, api.get("/api/auth/me", tampered).response.status)
    }

    @Test
    fun `token with a tampered payload is rejected`() {
        val email = api.uniqueEmail()
        api.register(email)
        val token = api.token(email, "password123")
        val parts = token.split('.')
        val payload = java.util.Base64.getUrlDecoder().decode(parts[1]).decodeToString()
            .replace("EMPLOYEE", "ADMIN")
        val forged = parts[0] + "." +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + "." + parts[2]

        assertEquals(401, api.get("/api/auth/me", forged).response.status)
        assertEquals(401, api.get("/api/admin/users", forged).response.status)
    }

    @Test
    fun `deactivated user with the correct password gets 403 account is disabled`() {
        val email = api.uniqueEmail()
        api.register(email)
        users.saveAndFlush(users.findByEmail(email)!!.also { it.deactivate() })

        val result = api.login(email, "password123")

        assertEquals(403, result.response.status)
        assertEquals("Account is disabled", api.json(result)["detail"].asString())
    }

    @Test
    fun `change password rejects a wrong current password and applies a correct one`() {
        val email = api.uniqueEmail()
        api.register(email, "oldpassword1")
        val token = api.token(email, "oldpassword1")

        val wrong = api.post(
            "/api/auth/change-password",
            mapOf("currentPassword" to "nope-nope", "newPassword" to "newpassword1"),
            token,
        )
        assertEquals(400, wrong.response.status)
        assertEquals(200, api.login(email, "oldpassword1").response.status)

        val ok = api.post(
            "/api/auth/change-password",
            mapOf("currentPassword" to "oldpassword1", "newPassword" to "newpassword1"),
            token,
        )
        assertEquals(204, ok.response.status)
        assertEquals(401, api.login(email, "oldpassword1").response.status)
        assertEquals(200, api.login(email, "newpassword1").response.status)
    }

    @Test
    fun `change password requires authentication`() {
        val result = api.post(
            "/api/auth/change-password",
            mapOf("currentPassword" to "whatever1", "newPassword" to "newpassword1"),
        )

        assertEquals(401, result.response.status)
    }

    @Test
    fun `malformed json on login returns 400 not 500`() {
        val result = api.post("/api/auth/login", "{ this is not json")

        assertEquals(400, result.response.status)
        assertTrue(result.response.contentType!!.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    }

    @Test
    fun `missing body on login returns 400`() {
        assertEquals(400, api.post("/api/auth/login").response.status)
    }

    @Test
    fun `public endpoints work without a token`() {
        assertEquals(200, api.get("/api/ping").response.status)

        val docs = api.get("/v3/api-docs")
        assertEquals(200, docs.response.status)
        val scheme = api.json(docs)["components"]["securitySchemes"]["bearerAuth"]
        assertNotNull(scheme)
        assertEquals("bearer", scheme["scheme"].asString())
    }

    @Test
    fun `unknown protected path without token returns 401 not 404`() {
        assertEquals(401, api.get("/api/does-not-exist").response.status)
    }
}
