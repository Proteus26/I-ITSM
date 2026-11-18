package com.itsm.service

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import com.itsm.priority.PriorityService
import com.itsm.settings.SettingsService
import com.itsm.support.TestData
import com.itsm.user.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** HTTP checks for the service catalog and the admin settings. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CatalogAndSettingsApiTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: JsonMapper,
    private val data: TestData,
    private val encoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
    private val catalog: ServiceCatalog,
    private val settings: SettingsService,
    private val priority: PriorityService,
) {
    private val api = ApiClient(mvc, mapper)
    private val pw = "password123"
    private lateinit var employee: String
    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        data.reset()
        data.user("emp1", Role.EMPLOYEE, passwordHash = encoder.encode(pw)!!)
        data.user("admin", Role.ADMIN, passwordHash = encoder.encode(pw)!!)
        employee = api.token("emp1@example.test", pw)
        admin = api.token("admin@example.test", pw)
    }

    @AfterEach
    fun cleanUp() {
        data.reset()
        jdbc.update("delete from it_services where code like 'API_%'")
        settings.set("priority.strategy", "weighted")
    }

    private fun put(url: String, body: Any, token: String) =
        mvc.perform(put(url).header("Authorization", "Bearer $token").contentType("application/json").content(mapper.writeValueAsString(body))).andReturn()

    @Test
    fun `an employee lists the active seeded services`() {
        val result = api.get("/api/services", employee)
        assertEquals(200, result.response.status)
        val names = api.json(result).map { it["name"].asString() }
        assertTrue(names.containsAll(listOf("Payment", "VPN", "Email", "Database", "Authentication", "Cloud Storage", "HR Portal", "Website")))
        assertEquals(4, api.json(result).first { it["code"].asString() == "PAYMENT" }["criticality"].asInt())
        assertEquals(401, api.get("/api/services").response.status)
    }

    @Test
    fun `the catalog gives criticality with a default of 2`() {
        val payment = api.json(api.get("/api/services", employee)).first { it["code"].asString() == "PAYMENT" }["id"].asLong()
        assertEquals(4, catalog.criticalityOf(payment))
        assertEquals(2, catalog.criticalityOf(null))
        assertEquals(2, catalog.criticalityOf(-1))
    }

    @Test
    fun `admin creates, updates and deactivates a service`() {
        val created = api.post("/api/admin/services", mapOf("code" to "api_test", "name" to "API Test", "criticality" to 3), admin)
        assertEquals(201, created.response.status)
        val id = api.json(created)["id"].asLong()
        assertEquals("API_TEST", api.json(created)["code"].asString())

        assertEquals(409, api.post("/api/admin/services", mapOf("code" to "API_TEST", "name" to "Other"), admin).response.status)
        assertEquals(400, api.post("/api/admin/services", mapOf("code" to "API_BAD", "name" to "Bad", "criticality" to 9), admin).response.status)

        val patched = api.patch("/api/admin/services/$id", mapOf("criticality" to 4, "active" to false, "ownerTeam" to "QA"), admin)
        assertEquals(200, patched.response.status)
        assertEquals(4, api.json(patched)["criticality"].asInt())
        assertEquals(false, api.json(patched)["active"].asBoolean())

        assertTrue(api.json(api.get("/api/services", employee)).none { it["code"].asString() == "API_TEST" })
        assertTrue(api.json(api.get("/api/admin/services", admin)).any { it["code"].asString() == "API_TEST" })
        assertEquals(404, api.patch("/api/admin/services/999999", mapOf("active" to true), admin).response.status)
    }

    @Test
    fun `an employee cannot use the admin endpoints`() {
        assertEquals(403, api.get("/api/admin/services", employee).response.status)
        assertEquals(403, api.post("/api/admin/services", mapOf("code" to "API_X", "name" to "X"), employee).response.status)
        assertEquals(403, api.patch("/api/admin/services/1", mapOf("active" to false), employee).response.status)
        assertEquals(403, api.get("/api/admin/settings", employee).response.status)
        assertEquals(403, put("/api/admin/settings/priority.strategy", mapOf("value" to "matrix"), employee).response.status)
    }

    @Test
    fun `settings accept known keys and valid strategies only`() {
        val list = api.json(api.get("/api/admin/settings", admin))
        assertEquals(setOf("priority.strategy", "assignment.strategy"), list.map { it["key"].asString() }.toSet())

        assertEquals(400, put("/api/admin/settings/unknown.key", mapOf("value" to "x"), admin).response.status)
        assertEquals(400, put("/api/admin/settings/priority.strategy", mapOf("value" to "nonsense"), admin).response.status)
        assertEquals(400, put("/api/admin/settings/assignment.strategy", mapOf("value" to "  "), admin).response.status)
        assertEquals("weighted", settings.get("priority.strategy", "?"))

        assertEquals(200, put("/api/admin/settings/priority.strategy", mapOf("value" to "matrix"), admin).response.status)
        assertEquals("matrix", settings.get("priority.strategy", "?"))
        assertTrue("matrix" in priority.availableStrategies)
    }
}
