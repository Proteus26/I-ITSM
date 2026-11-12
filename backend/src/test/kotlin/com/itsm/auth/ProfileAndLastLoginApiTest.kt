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
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ProfileAndLastLoginApiTest @Autowired constructor(
    mvc: MockMvc,
    mapper: JsonMapper,
    private val users: UserRepository,
    private val factory: UserFactory,
    private val recorder: LastLoginRecorder,
) {
    private val api = ApiClient(mvc, mapper)
    private val pw = "password123"

    private fun lastLogin(json: tools.jackson.databind.JsonNode) = json["lastLoginAt"]

    @Test
    fun `lastLoginAt is null until the first login, then set and visible in login, me and the admin api`() {
        val adminEmail = api.uniqueEmail("admin")
        users.saveAndFlush(factory.createAdministrator(adminEmail, "Admin Person", pw))
        val adminToken = api.token(adminEmail, pw)

        val email = api.uniqueEmail()
        val registered = api.register(email, pw)
        val id = api.json(registered)["id"].asLong()
        assertTrue(lastLogin(api.json(registered)).isNull)
        assertTrue(lastLogin(api.json(api.get("/api/admin/users/$id", adminToken))).isNull)

        val login = api.login(email, pw)
        val body = api.json(login)
        val token = body["token"].asString()
        assertFalse(lastLogin(body["user"]).isNull)

        val meValue = lastLogin(api.json(api.get("/api/auth/me", token)))
        assertFalse(meValue.isNull)
        assertEquals(lastLogin(body["user"]).asString(), meValue.asString())
        assertEquals(meValue.asString(), lastLogin(api.json(api.get("/api/admin/users/$id", adminToken))).asString())
        assertNotNull(users.findByEmail(email)!!.lastLoginAt)
    }

    @Test
    fun `previousLoginAt is null on the first login and then holds the earlier last login`() {
        val email = api.uniqueEmail()
        api.register(email, pw)

        val first = api.json(api.login(email, pw))
        assertTrue(first["user"]["previousLoginAt"].isNull)
        val firstAt = first["user"]["lastLoginAt"].asString()

        val second = api.json(api.login(email, pw))
        assertEquals(firstAt, second["user"]["previousLoginAt"].asString())
        val me = api.json(api.get("/api/auth/me", second["token"].asString()))
        assertEquals(second["user"]["lastLoginAt"].asString(), me["lastLoginAt"].asString())
        assertEquals(firstAt, me["previousLoginAt"].asString())
    }

    @Test
    fun `an over long login email is a validation error`() {
        val result = api.login("a".repeat(250) + "@example.com", pw)

        assertEquals(400, result.response.status)
    }

    @Test
    fun `the recorder swallows failures and ignores unknown users`() {
        recorder.onUserLoggedIn(UserLoggedIn(-1, java.time.Instant.now()))

        val broken = org.mockito.Mockito.mock(UserRepository::class.java)
        val at = java.time.Instant.now()
        org.mockito.Mockito.`when`(broken.recordLogin(1L, at)).thenThrow(IllegalStateException("db down"))
        LastLoginRecorder(broken).onUserLoggedIn(UserLoggedIn(1, at))
    }

    @Test
    fun `a failed login does not set lastLoginAt`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        api.login(email, "wrong-password")

        assertEquals(null, users.findByEmail(email)!!.lastLoginAt)
    }

    @Test
    fun `an employee can update name and department`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val token = api.token(email, pw)

        val result = api.patch("/api/auth/me", mapOf("fullName" to "  New Name ", "department" to "Finance"), token)

        assertEquals(200, result.response.status)
        val body = api.json(result)
        assertEquals("New Name", body["fullName"].asString())
        assertEquals("Finance", body["department"].asString())
        assertEquals(email, body["email"].asString())
        assertEquals("New Name", api.json(api.get("/api/auth/me", token))["fullName"].asString())
    }

    @Test
    fun `omitted fields are unchanged and a blank department clears it`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val token = api.token(email, pw)
        api.patch("/api/auth/me", mapOf("department" to "Finance"), token)

        val nameOnly = api.json(api.patch("/api/auth/me", mapOf("fullName" to "Only Name"), token))
        assertEquals("Finance", nameOnly["department"].asString())

        val cleared = api.json(api.patch("/api/auth/me", mapOf("department" to " "), token))
        assertTrue(cleared["department"].isNull)
        assertEquals("Only Name", cleared["fullName"].asString())
    }

    @Test
    fun `email and role in the body are ignored`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val token = api.token(email, pw)

        val result = api.patch("/api/auth/me", """{"fullName":"Same Person","email":"x@evil.com","role":"ADMIN"}""", token)

        assertEquals(200, result.response.status)
        assertEquals(email, api.json(result)["email"].asString())
        assertEquals("EMPLOYEE", api.json(result)["role"].asString())
    }

    @Test
    fun `a technician sending department gets 400 but can rename`() {
        val email = api.uniqueEmail("tech")
        users.saveAndFlush(factory.createTechnician(email, "Tech Nician", pw))
        val token = api.token(email, pw)

        val bad = api.patch("/api/auth/me", mapOf("department" to "Finance"), token)
        assertEquals(400, bad.response.status)
        assertEquals("department applies to employees only", api.json(bad)["detail"].asString())

        val ok = api.patch("/api/auth/me", mapOf("fullName" to "Renamed Tech"), token)
        assertEquals(200, ok.response.status)
        assertEquals("Renamed Tech", api.json(ok)["fullName"].asString())
    }

    @Test
    fun `a blank name is a validation error`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val token = api.token(email, pw)

        val result = api.patch("/api/auth/me", mapOf("fullName" to "   "), token)

        assertEquals(400, result.response.status)
        assertEquals("ValidationError", api.json(result)["title"].asString())
        assertEquals("must not be blank", api.json(result)["errors"]["fullName"].asString())
    }

    @Test
    fun `an over long name is a validation error`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val token = api.token(email, pw)

        assertEquals(400, api.patch("/api/auth/me", mapOf("fullName" to "a".repeat(256)), token).response.status)
    }

    @Test
    fun `patch me without a token is 401`() {
        assertEquals(401, api.patch("/api/auth/me", mapOf("fullName" to "Nobody")).response.status)
    }
}
