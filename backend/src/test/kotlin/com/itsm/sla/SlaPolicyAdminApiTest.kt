package com.itsm.sla

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import com.itsm.support.TestData
import com.itsm.user.Role
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import tools.jackson.databind.json.JsonMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SlaPolicyAdminApiTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: JsonMapper,
    private val data: TestData,
    private val encoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
) {
    private val api = ApiClient(mvc, mapper)
    private val pw = "password123"
    private lateinit var admin: String
    private lateinit var employee: String
    private var serviceId = 0L

    @BeforeEach
    fun setUp() {
        data.reset()
        data.user("emp1", Role.EMPLOYEE, passwordHash = encoder.encode(pw)!!)
        data.user("admin", Role.ADMIN, passwordHash = encoder.encode(pw)!!)
        employee = api.token("emp1@example.test", pw)
        admin = api.token("admin@example.test", pw)
        serviceId = jdbc.queryForObject("insert into it_services (code, name) values ('API_SLA', 'Api Sla Svc') returning id", Long::class.java)!!
    }

    @AfterEach
    fun cleanUp() {
        data.reset()
        jdbc.update("delete from it_services where id = ?", serviceId) // its policies cascade
        jdbc.update("update sla_policies set response_minutes = 5, resolution_minutes = 30, warning_threshold_pct = 75 where service_id is null and priority = 'P1'")
    }

    private fun deleteReq(id: Long, token: String) = mvc.perform(delete("/api/admin/sla-policies/$id").header("Authorization", "Bearer $token")).andReturn()

    private fun create(priority: String = "P1", response: Int = 2, resolution: Int = 10, token: String = admin, warning: Int? = null) =
        api.post(
            "/api/admin/sla-policies",
            buildMap<String, Any> {
                put("serviceId", serviceId); put("priority", priority); put("responseMinutes", response); put("resolutionMinutes", resolution)
                warning?.let { put("warningThresholdPct", it) }
            },
            token,
        )

    @Test
    fun `lists the four defaults first, then overrides with the service name`() {
        assertThat(create().response.status).isEqualTo(201)
        val list = api.json(api.get("/api/admin/sla-policies", admin)).toList()
        assertThat(list.take(4).map { it["priority"].asString() }).containsExactly("P1", "P2", "P3", "P4")
        assertThat(list.take(4).all { it["isDefault"].asBoolean() && it["serviceId"].isNull }).isTrue()
        val override = list.single { it["serviceName"]?.asString() == "Api Sla Svc" }
        assertThat(override["isDefault"].asBoolean()).isFalse()
        assertThat(override["warningThresholdPct"].asInt()).isEqualTo(75)
    }

    @Test
    fun `an override changes new deadlines, edits and deletes work, defaults cannot be deleted`() {
        val created = create(warning = 50)
        val id = api.json(created)["id"].asLong()
        assertThat(api.json(created)["warningThresholdPct"].asInt()).isEqualTo(50)

        val patched = api.patch("/api/admin/sla-policies/$id", mapOf("responseMinutes" to 3, "resolutionMinutes" to 20), admin)
        assertThat(patched.response.status).isEqualTo(200)
        assertThat(api.json(patched)["resolutionMinutes"].asInt()).isEqualTo(20)
        assertThat(api.patch("/api/admin/sla-policies/$id", mapOf("responseMinutes" to 30), admin).response.status).isEqualTo(400) // response > resolution

        assertThat(deleteReq(id, admin).response.status).isEqualTo(204)
        assertThat(deleteReq(id, admin).response.status).isEqualTo(404)

        val defaultP1 = api.json(api.get("/api/admin/sla-policies", admin)).first { it["isDefault"].asBoolean() && it["priority"].asString() == "P1" }["id"].asLong()
        assertThat(deleteReq(defaultP1, admin).response.status).isEqualTo(400)
        val edited = api.patch("/api/admin/sla-policies/$defaultP1", mapOf("warningThresholdPct" to 60), admin)
        assertThat(edited.response.status).isEqualTo(200)
    }

    @Test
    fun `validation and uniqueness`() {
        assertThat(create(response = 0).response.status).isEqualTo(400)
        assertThat(create(response = 20, resolution = 10).response.status).isEqualTo(400)
        assertThat(create(warning = 100).response.status).isEqualTo(400)
        assertThat(create(warning = 0).response.status).isEqualTo(400)
        assertThat(create(priority = "P9").response.status).isEqualTo(400)
        assertThat(api.post("/api/admin/sla-policies", mapOf("priority" to "P1", "responseMinutes" to 1, "resolutionMinutes" to 2), admin).response.status).isEqualTo(400)
        assertThat(api.post("/api/admin/sla-policies", mapOf("serviceId" to 999999, "priority" to "P1", "responseMinutes" to 1, "resolutionMinutes" to 2), admin).response.status).isEqualTo(404)
        assertThat(create().response.status).isEqualTo(201)
        assertThat(create().response.status).isEqualTo(409)
        assertThat(api.patch("/api/admin/sla-policies/999999", mapOf("responseMinutes" to 1), admin).response.status).isEqualTo(404)
    }

    @Test
    fun `only administrators may use the endpoints`() {
        assertThat(api.get("/api/admin/sla-policies", employee).response.status).isEqualTo(403)
        assertThat(create(token = employee).response.status).isEqualTo(403)
        assertThat(api.patch("/api/admin/sla-policies/1", mapOf("responseMinutes" to 1), employee).response.status).isEqualTo(403)
        assertThat(deleteReq(1, employee).response.status).isEqualTo(403)
        assertThat(api.get("/api/admin/sla-policies").response.status).isEqualTo(401)
    }

    @Test
    fun `domain validation of a policy`() {
        assertThatThrownBy { SlaPolicy(null, com.itsm.incident.Priority.P1, 10, 5) }.hasMessageContaining("Resolution")
        assertThatThrownBy { SlaPolicy(null, com.itsm.incident.Priority.P1, 5, 30, 100) }.hasMessageContaining("threshold")
    }
}
