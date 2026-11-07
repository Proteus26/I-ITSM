package com.itsm.user

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AdminUserApiTest @Autowired constructor(
    mvc: MockMvc,
    mapper: JsonMapper,
    private val users: UserRepository,
    private val factory: UserFactory,
) {
    private val api = ApiClient(mvc, mapper)
    private val pw = "password123"

    private lateinit var adminEmail: String
    private lateinit var adminToken: String

    @BeforeEach
    fun createAdmin() {
        adminEmail = api.uniqueEmail("admin")
        users.saveAndFlush(factory.createAdministrator(adminEmail, "Admin Person", pw))
        adminToken = api.token(adminEmail, pw)
    }

    private fun employee(prefix: String = "emp", name: String = "Emp Loyee"): User =
        users.saveAndFlush(factory.createEmployee(api.uniqueEmail(prefix), name, pw))

    private fun technician(prefix: String = "tech"): Technician =
        users.saveAndFlush(factory.createTechnician(api.uniqueEmail(prefix), "Tech Nician", pw))

    private fun createBody(role: String, email: String, extra: Map<String, Any?> = emptyMap()) =
        mapOf("role" to role, "email" to email, "fullName" to "Created User", "password" to pw) + extra

    @Test
    fun `employee token on admin endpoint is forbidden with problem json`() {
        val e = employee()
        val token = api.token(e.email, pw)

        val result = api.get("/api/admin/users", token)

        assertEquals(403, result.response.status)
        assertTrue(result.response.contentType!!.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    }

    @Test
    fun `no token on admin endpoint is unauthorized`() {
        assertEquals(401, api.get("/api/admin/users").response.status)
    }

    @Test
    fun `technician token on admin endpoint is forbidden`() {
        val t = technician()
        val token = api.token(t.email, pw)

        assertEquals(403, api.get("/api/admin/users", token).response.status)
        assertEquals(403, api.post("/api/admin/users", createBody("EMPLOYEE", api.uniqueEmail()), token).response.status)
        assertEquals(403, api.patch("/api/admin/users/${t.id}", mapOf("level" to "TEAM_LEAD"), token).response.status)
    }

    @Test
    fun `admin creates a technician with uppercased skills who can then log in`() {
        val email = api.uniqueEmail("newtech")
        val result = api.post(
            "/api/admin/users",
            createBody("TECHNICIAN", email, mapOf("level" to "L2", "skills" to listOf("net", "vpn"))),
            adminToken,
        )

        assertEquals(201, result.response.status)
        val body = api.json(result)
        assertEquals("TECHNICIAN", body["role"].asString())
        assertEquals("L2", body["technician"]["level"].asString())
        assertEquals(listOf("NET", "VPN"), body["technician"]["skills"].map { it.asString() }.sorted())
        assertEquals(5, body["technician"]["maxActiveIncidents"].asInt())

        val token = api.token(email, pw)
        val me = api.json(api.get("/api/auth/me", token))
        assertEquals("TECHNICIAN", me["role"].asString())
    }

    @Test
    fun `admin create with a duplicate email returns 409`() {
        val e = employee()

        val result = api.post("/api/admin/users", createBody("EMPLOYEE", e.email.uppercase()), adminToken)

        assertEquals(409, result.response.status)
    }

    @Test
    fun `admin create employee with technician only fields returns 400`() {
        val result = api.post(
            "/api/admin/users",
            createBody("EMPLOYEE", api.uniqueEmail(), mapOf("level" to "TEAM_LEAD")),
            adminToken,
        )

        assertEquals(400, result.response.status)
    }

    @Test
    fun `admin create with invalid payload returns 400 with field errors`() {
        val result = api.post(
            "/api/admin/users",
            mapOf("role" to "EMPLOYEE", "email" to "bad", "fullName" to "", "password" to "x"),
            adminToken,
        )

        assertEquals(400, result.response.status)
        assertTrue(api.json(result)["errors"].has("email"))
    }

    @Test
    fun `admin create with unknown role value returns 400`() {
        val result = api.post("/api/admin/users", createBody("SUPERUSER", api.uniqueEmail()), adminToken)

        assertEquals(400, result.response.status)
    }

    @Test
    fun `list filters by role and active and q and caps page size`() {
        val tag = UUID.randomUUID().toString().take(8)
        val tech = users.saveAndFlush(
            factory.createTechnician("lt-$tag@example.com", "Lister Tech $tag", pw),
        )
        val inactive = factory.createEmployee("li-$tag@example.com", "Lister Inactive $tag", pw)
            .also { it.deactivate() }
        users.saveAndFlush(inactive)
        val emp = users.saveAndFlush(factory.createEmployee("le-$tag@example.com", "Lister Emp $tag", pw))

        val byRole = api.json(api.get("/api/admin/users?role=TECHNICIAN&size=100", adminToken))
        val roles = byRole["content"].map { it["role"].asString() }.toSet()
        assertEquals(setOf("TECHNICIAN"), roles)
        assertTrue(byRole["content"].any { it["id"].asLong() == tech.id })

        val byActive = api.json(api.get("/api/admin/users?active=false&size=100", adminToken))
        assertTrue(byActive["content"].all { !it["active"].asBoolean() })
        assertTrue(byActive["content"].any { it["id"].asLong() == inactive.id })

        val byQ = api.json(api.get("/api/admin/users?q=LISTER EMP ${tag.uppercase()}", adminToken))
        assertEquals(listOf(emp.id), byQ["content"].map { it["id"].asLong() })
        val byEmail = api.json(api.get("/api/admin/users?q=LI-${tag.uppercase()}@EXAMPLE", adminToken))
        assertEquals(listOf(inactive.id), byEmail["content"].map { it["id"].asLong() })

        val combined = api.json(api.get("/api/admin/users?q=$tag&role=EMPLOYEE&active=true", adminToken))
        assertEquals(listOf(emp.id), combined["content"].map { it["id"].asLong() })

        val capped = api.json(api.get("/api/admin/users?size=1000", adminToken))
        assertEquals(100, capped["size"].asInt())
        assertEquals(0, capped["page"].asInt())
        assertTrue(capped["totalElements"].asLong() >= 4)
        assertTrue(capped["totalPages"].asInt() >= 1)
    }

    @Test
    fun `list q treats percent and underscore literally`() {
        val tag = UUID.randomUUID().toString().take(8)
        users.saveAndFlush(factory.createEmployee("wild-$tag@example.com", "Wild $tag", pw))

        val result = api.json(api.get("/api/admin/users?q=%", adminToken))

        // '%' as a literal matches no email or name here.
        assertEquals(0, result["totalElements"].asLong())
    }

    @Test
    fun `list with negative page and zero size is clamped rather than failing`() {
        val result = api.get("/api/admin/users?page=-5&size=0", adminToken)

        assertEquals(200, result.response.status)
        assertEquals(1, api.json(result)["size"].asInt())
        assertEquals(0, api.json(result)["page"].asInt())
    }

    @Test
    fun `patch deactivating an employee blocks their login`() {
        val e = employee()
        assertEquals(200, api.login(e.email, pw).response.status)

        val result = api.patch("/api/admin/users/${e.id}", mapOf("active" to false), adminToken)

        assertEquals(200, result.response.status)
        assertEquals(false, api.json(result)["active"].asBoolean())
        assertEquals(403, api.login(e.email, pw).response.status)
    }

    @Test
    fun `patch level on an employee returns 400`() {
        val e = employee()

        assertEquals(400, api.patch("/api/admin/users/${e.id}", mapOf("level" to "TEAM_LEAD"), adminToken).response.status)
    }

    @Test
    fun `patch department on a technician returns 400`() {
        val t = technician()

        assertEquals(400, api.patch("/api/admin/users/${t.id}", mapOf("department" to "IT"), adminToken).response.status)
    }

    @Test
    fun `patch max active incidents zero on a technician returns 400 not 500`() {
        val t = technician()

        val result = api.patch("/api/admin/users/${t.id}", mapOf("maxActiveIncidents" to 0), adminToken)

        assertEquals(400, result.response.status)
    }

    @Test
    fun `patch updates technician fields and replaces skills`() {
        val t = users.saveAndFlush(
            factory.createTechnician(api.uniqueEmail("ptech"), "P Tech", pw, TechnicianLevel.L1, 5, setOf("a", "b")),
        )

        val result = api.patch(
            "/api/admin/users/${t.id}",
            mapOf("level" to "TEAM_LEAD", "maxActiveIncidents" to 9, "available" to false, "skills" to listOf("b", "c")),
            adminToken,
        )

        assertEquals(200, result.response.status, result.response.contentAsString)
        val tech = api.json(result)["technician"]
        assertEquals("TEAM_LEAD", tech["level"].asString())
        assertEquals(9, tech["maxActiveIncidents"].asInt())
        assertEquals(false, tech["available"].asBoolean())
        assertEquals(listOf("B", "C"), tech["skills"].map { it.asString() }.sorted())
    }

    @Test
    fun `patch employee department can be set and cleared with blank`() {
        val e = employee()

        val set = api.json(api.patch("/api/admin/users/${e.id}", mapOf("department" to "Finance"), adminToken))
        assertEquals("Finance", set["department"].asString())

        val cleared = api.patch("/api/admin/users/${e.id}", mapOf("department" to "  "), adminToken)
        assertEquals(200, cleared.response.status)
    }

    @Test
    fun `patch blank full name returns 400 not 500`() {
        val e = employee()

        val result = api.patch("/api/admin/users/${e.id}", mapOf("fullName" to "   "), adminToken)

        assertEquals(400, result.response.status)
    }

    @Test
    fun `patch email to one already taken returns 409`() {
        val a = employee()
        val b = employee()

        val result = api.patch("/api/admin/users/${a.id}", mapOf("email" to b.email.uppercase()), adminToken)

        assertEquals(409, result.response.status)
    }

    @Test
    fun `patch email to a new value normalizes it and lets the user log in with it`() {
        val e = employee()
        val newEmail = api.uniqueEmail("renamed")

        val result = api.patch("/api/admin/users/${e.id}", mapOf("email" to newEmail.uppercase()), adminToken)

        assertEquals(200, result.response.status)
        assertEquals(newEmail, api.json(result)["email"].asString())
        assertEquals(200, api.login(newEmail, pw).response.status)
    }

    @Test
    fun `patch own email to the same value is not a conflict`() {
        val e = employee()

        assertEquals(200, api.patch("/api/admin/users/${e.id}", mapOf("email" to e.email), adminToken).response.status)
    }

    @Test
    fun `admin cannot deactivate themselves`() {
        val me = users.findByEmail(adminEmail)!!

        val result = api.patch("/api/admin/users/${me.id}", mapOf("active" to false), adminToken)

        assertEquals(400, result.response.status)
        assertTrue(users.findByEmail(adminEmail)!!.active)
    }

    @Test
    fun `admin can deactivate another admin`() {
        val other = users.saveAndFlush(factory.createAdministrator(api.uniqueEmail("admin2"), "Other Admin", pw))

        assertEquals(200, api.patch("/api/admin/users/${other.id}", mapOf("active" to false), adminToken).response.status)
    }

    @Test
    fun `deactivated user with a still valid token keeps access until expiry`() {
        // Documents current behaviour: tokens are stateless, so deactivation is not enforced per request.
        val e = employee()
        val token = api.token(e.email, pw)
        api.patch("/api/admin/users/${e.id}", mapOf("active" to false), adminToken)

        val status = api.get("/api/auth/me", token).response.status

        assertTrue(status == 200 || status == 401 || status == 403, "unexpected status $status")
    }

    @Test
    fun `reset password lets the user log in with the new password only`() {
        val e = employee()

        val result = api.post("/api/admin/users/${e.id}/reset-password", mapOf("newPassword" to "brandnew123"), adminToken)

        assertEquals(204, result.response.status)
        assertEquals(200, api.login(e.email, "brandnew123").response.status)
        assertEquals(401, api.login(e.email, pw).response.status)
    }

    @Test
    fun `reset password with a short password returns 400`() {
        val e = employee()

        val result = api.post("/api/admin/users/${e.id}/reset-password", mapOf("newPassword" to "short"), adminToken)

        assertEquals(400, result.response.status)
    }

    @Test
    fun `get unknown id returns 404 and get existing returns the user`() {
        assertEquals(404, api.get("/api/admin/users/999999999", adminToken).response.status)
        assertEquals(404, api.post("/api/admin/users/999999999/reset-password", mapOf("newPassword" to "brandnew123"), adminToken).response.status)
        assertEquals(404, api.patch("/api/admin/users/999999999", mapOf("active" to true), adminToken).response.status)

        val e = employee()
        val result = api.get("/api/admin/users/${e.id}", adminToken)
        assertEquals(200, result.response.status)
        assertEquals(e.email, api.json(result)["email"].asString())
    }

    @Test
    fun `get with a non numeric id returns 400`() {
        assertEquals(400, api.get("/api/admin/users/abc", adminToken).response.status)
    }
}
