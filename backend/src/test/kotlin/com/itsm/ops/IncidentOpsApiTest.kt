package com.itsm.ops

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import com.itsm.audit.AuditLogRepository
import com.itsm.notification.NotificationRepository
import com.itsm.support.TestData
import com.itsm.support.uid
import com.itsm.user.Role
import com.itsm.user.Technician
import com.itsm.user.User
import com.itsm.user.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Incident editing, technician workload, SLA risk and correlation over HTTP. Every row it creates is removed again. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class IncidentOpsApiTest @Autowired constructor(
    private val mvc: MockMvc,
    mapper: JsonMapper,
    private val data: TestData,
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
    private val audit: AuditLogRepository,
    private val notifications: NotificationRepository,
) {
    private val api = ApiClient(mvc, mapper)
    private val pw = "password123"
    private lateinit var admin: User
    private lateinit var emp: User
    private lateinit var other: User
    private lateinit var tech: Technician
    private lateinit var empToken: String
    private lateinit var otherToken: String
    private lateinit var techToken: String
    private lateinit var adminToken: String
    private var parked = emptyList<Long>()
    private val incidentIds = mutableListOf<Long>()
    private val serviceIds = mutableListOf<Long>()

    @BeforeEach
    fun setUp() {
        data.reset()
        // Technicians left behind by other test classes would be assignment candidates too; park them meanwhile.
        parked = jdbc.queryForList("select id from users where role = 'TECHNICIAN' and available", Long::class.javaObjectType)
        parked.forEach { jdbc.update("update users set available = false where id = ?", it) }
        val hash = encoder.encode(pw)!!
        admin = data.user("opsadmin", Role.ADMIN, passwordHash = hash)
        emp = data.user("opsemp", Role.EMPLOYEE, passwordHash = hash)
        other = data.user("opsother", Role.EMPLOYEE, passwordHash = hash)
        tech = (data.user("opstech", Role.TECHNICIAN, passwordHash = hash) as Technician).also {
            it.replaceSkills(setOf("NETWORK", "SOFTWARE"))
            users.save(it)
        }
        empToken = api.token(emp.email, pw)
        otherToken = api.token(other.email, pw)
        techToken = api.token(tech.email, pw)
        adminToken = api.token(admin.email, pw)
    }

    @AfterEach
    fun cleanUp() {
        incidentIds.forEach {
            jdbc.update("delete from sla_events where incident_id = ?", it)
            jdbc.update("delete from notifications where incident_id = ?", it)
            jdbc.update("delete from audit_log where incident_id = ?", it)
            jdbc.update("delete from incidents where id = ?", it)
        }
        incidentIds.clear()
        serviceIds.forEach { jdbc.update("delete from it_services where id = ?", it) }
        serviceIds.clear()
        data.reset()
        parked.forEach { jdbc.update("update users set available = true where id = ?", it) }
    }

    private fun service(name: String): Long {
        val id = jdbc.queryForObject(
            "insert into it_services (name, code) values (?, ?) returning id", Long::class.javaObjectType, name, "OPS_" + name.uppercase().replace(' ', '_'),
        )!!
        serviceIds += id
        return id
    }

    private fun report(token: String = empToken, title: String = "Printer room is dark", extra: Map<String, Any?> = emptyMap()): JsonNode {
        val body = mutableMapOf<String, Any?>(
            "title" to title, "description" to "Nothing happens at all", "impact" to "INDIVIDUAL", "urgency" to "LOW", "categoryCode" to "network",
        )
        body.putAll(extra)
        val r = api.post("/api/incidents", body, token)
        assertEquals(201, r.response.status, r.response.contentAsString)
        return api.json(r).also { incidentIds += it["id"].asLong() }
    }

    private fun patch(id: Long, body: Map<String, Any?>, token: String) = api.patch("/api/incidents/$id", body, token)

    private fun detail(id: Long, token: String) = api.json(api.get("/api/incidents/$id", token))

    private fun act(id: Long, action: String, token: String, body: Any? = null) {
        val r = api.post("/api/incidents/$id/$action", body ?: emptyMap<String, Any>(), token)
        assertEquals(200, r.response.status, "$action: ${r.response.contentAsString}")
    }

    private fun audited(id: Long) = audit.findByIncidentIdOrderByOccurredAtAscIdAsc(id).map { it.action }

    @Test
    fun `the reporter edits before work starts and priority and deadlines follow`() {
        val d = report()
        val id = d["id"].asLong()
        assertEquals("P4", d["priority"].asString())
        assertEquals("ASSIGNED", d["status"].asString())
        assertTrue(d["actions"].map { it.asString() }.contains("EDIT"))
        assertEquals(6, d["editableFields"].size())
        val before = Instant.parse(d["resolutionDueAt"].asString())

        val r = patch(id, mapOf("title" to "  Printer room is completely dark ", "impact" to "ORGANISATION", "urgency" to "CRITICAL", "affectedUsers" to 200), empToken)
        assertEquals(200, r.response.status, r.response.contentAsString)
        val after = api.json(r)
        assertEquals("Printer room is completely dark", after["title"].asString())
        assertEquals("P2", after["priority"].asString())
        assertTrue(Instant.parse(after["resolutionDueAt"].asString()).isBefore(before), "a higher priority has a shorter deadline")
        assertEquals(200, after["affectedUsers"].asInt())

        val actions = audited(id)
        assertTrue("INCIDENT_UPDATED" in actions && "PRIORITY_CHANGED" in actions, actions.toString())
        val updated = audit.findByIncidentIdOrderByOccurredAtAscIdAsc(id).first { it.action == "INCIDENT_UPDATED" }
        assertTrue(updated.description.contains("title") && updated.description.contains("affectedUsers"), updated.description)
        // the assignee is told, the editor is not
        val techNotes = notifications.findByRecipientIdOrderByCreatedAtDesc(tech.uid, Pageable.unpaged()).content.filter { it.incidentId == id }
        assertTrue(techNotes.any { it.title.endsWith("updated") }, techNotes.map { it.title }.toString())
        val empNotes = notifications.findByRecipientIdOrderByCreatedAtDesc(emp.uid, Pageable.unpaged()).content.filter { it.incidentId == id }
        assertFalse(empNotes.any { it.title.endsWith("updated") })
    }

    @Test
    fun `staff edits notify the reporter and an edit that changes nothing publishes nothing`() {
        val id = report()["id"].asLong()
        act(id, "acknowledge", techToken)
        assertEquals(200, patch(id, mapOf("urgency" to "HIGH"), techToken).response.status)
        val empNotes = notifications.findByRecipientIdOrderByCreatedAtDesc(emp.uid, Pageable.unpaged()).content.filter { it.incidentId == id }
        assertTrue(empNotes.any { it.title.endsWith("updated") })

        val count = audited(id).size
        val same = patch(id, mapOf("urgency" to "HIGH"), techToken)
        assertEquals(200, same.response.status)
        assertEquals(count, audited(id).size)
    }

    @Test
    fun `who may edit what`() {
        val id = report()["id"].asLong()
        assertEquals(403, patch(id, mapOf("title" to "Hijack"), otherToken).response.status)
        assertEquals(403, patch(id, mapOf("title" to "Technician title"), techToken).response.status)
        assertEquals(200, patch(id, mapOf("impact" to "TEAM"), techToken).response.status)

        act(id, "acknowledge", techToken)
        act(id, "start", techToken)
        assertEquals(403, patch(id, mapOf("title" to "Too late"), empToken).response.status)
        assertFalse(detail(id, empToken)["actions"].map { it.asString() }.contains("EDIT"))
        assertEquals(listOf("impact", "urgency", "affectedUsers", "serviceId"), detail(id, techToken)["editableFields"].map { it.asString() })
        assertEquals(200, patch(id, mapOf("title" to "Admin title"), adminToken).response.status)

        act(id, "resolve", techToken, mapOf("summary" to "Replaced the fuse"))
        assertEquals(409, patch(id, mapOf("title" to "After the fact"), adminToken).response.status)
        assertEquals(409, patch(id, mapOf("impact" to "TEAM"), empToken).response.status)
    }

    @Test
    fun `invalid edits are rejected`() {
        val id = report()["id"].asLong()
        assertEquals(400, patch(id, emptyMap(), empToken).response.status)
        assertEquals(400, patch(id, mapOf("title" to "   "), empToken).response.status)
        assertEquals(400, patch(id, mapOf("affectedUsers" to 0), empToken).response.status)
        assertEquals(400, patch(id, mapOf("serviceId" to 999_999_999), empToken).response.status)
        assertEquals(400, patch(id, mapOf("impact" to "HUGE"), empToken).response.status)
        assertEquals(404, patch(999_999_999, mapOf("title" to "x"), empToken).response.status)
        assertEquals(401, api.patch("/api/incidents/$id", mapOf("title" to "x")).response.status)
    }

    @Test
    fun `the service can be set and cleared and the deadlines keep their original start`() {
        val svc = service("Ops printing")
        val d = report()
        val id = d["id"].asLong()
        val created = Instant.parse(d["createdAt"].asString())
        val set = api.json(patch(id, mapOf("serviceId" to svc, "impact" to "TEAM"), empToken))
        assertEquals("Ops printing", set["serviceName"].asString())
        val response = Instant.parse(set["responseDueAt"].asString())
        assertTrue(response.isAfter(created) && response.isBefore(created.plus(2, ChronoUnit.DAYS)))
        val cleared = api.json(patch(id, mapOf("clearService" to true), empToken))
        assertTrue(cleared["serviceName"].isNull)
    }

    @Test
    fun `the workload shows capacity, counts and overdue marks, and the technician can toggle availability`() {
        val a = report(title = "First network issue")["id"].asLong()
        val b = report(title = "Second network issue")["id"].asLong()
        jdbc.update("update incidents set resolution_due_at = ? where id = ?", Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), a)

        val w = api.json(api.get("/api/incidents/workload", techToken))
        assertEquals(2, w["activeCount"].asInt())
        assertEquals(5, w["capacity"].asInt())
        assertTrue(w["available"].asBoolean())
        assertEquals(2, w["countsByStatus"]["ASSIGNED"].asInt())
        assertEquals(0, w["countsByStatus"]["IN_PROGRESS"].asInt())
        assertEquals(a, w["incidents"][0]["id"].asLong(), "ordered by deadline")
        assertEquals("OVERDUE", w["incidents"][0]["deadlineState"].asString())
        assertEquals(1, w["overdueCount"].asInt())
        assertEquals(b, w["incidents"][1]["id"].asLong())
        assertFalse(w["overCapacity"].asBoolean())

        assertEquals(403, api.get("/api/incidents/workload", empToken).response.status)
        assertEquals(403, api.get("/api/incidents/workload", adminToken).response.status)

        val off = api.patch("/api/me/availability", mapOf("available" to false), techToken)
        assertEquals(200, off.response.status)
        assertFalse(api.json(off)["available"].asBoolean())
        assertFalse(api.json(api.get("/api/incidents/workload", techToken))["available"].asBoolean())
        assertEquals(400, api.patch("/api/me/availability", emptyMap<String, Any>(), techToken).response.status)
        assertEquals(403, api.patch("/api/me/availability", mapOf("available" to true), empToken).response.status)
        assertEquals(200, api.patch("/api/me/availability", mapOf("available" to true), techToken).response.status)
    }

    @Test
    fun `the SLA monitor lists breached and at risk incidents for administrators`() {
        val late = report(title = "Late response")["id"].asLong()
        val risky = report(title = "Nearly late")["id"].asLong()
        val fine = report(title = "Fresh")["id"].asLong()
        val now = Instant.now()
        jdbc.update("update incidents set response_due_at = ? where id = ?", Timestamp.from(now.minus(30, ChronoUnit.MINUTES)), late)
        // 90% of the longest possible window used: the response deadline is a few minutes away
        jdbc.update("update incidents set response_due_at = ? where id = ?", Timestamp.from(now.plus(2, ChronoUnit.MINUTES)), risky)

        val res = api.get("/api/admin/sla/at-risk", adminToken)
        assertEquals(200, res.response.status)
        val body = api.json(res)
        val breached = body["breached"].associateBy { it["incidentId"].asLong() }
        val atRisk = body["atRisk"].associateBy { it["incidentId"].asLong() }
        assertTrue(late in breached, "late incident is breached")
        assertEquals("BREACHED", breached.getValue(late)["state"].asString())
        assertTrue(breached.getValue(late)["riskReason"].asString().startsWith("response deadline passed"))
        assertTrue(breached.getValue(late)["minutesRemaining"].asLong() < 0)
        assertEquals("Opstech", breached.getValue(late)["assignee"]["fullName"].asString())
        assertTrue(risky in atRisk, "nearly late incident is at risk")
        assertEquals("AT_RISK", atRisk.getValue(risky)["state"].asString())
        assertTrue(fine !in breached && fine !in atRisk)

        assertEquals(403, api.get("/api/admin/sla/at-risk", empToken).response.status)
        assertEquals(403, api.get("/api/admin/sla/at-risk", techToken).response.status)
    }

    @Test
    fun `three open incidents on one service form a cluster, shown to admins and as related incidents with visibility`() {
        val svc = service("Ops VPN")
        val mine1 = report(empToken, "VPN broken one", mapOf("serviceId" to svc))["id"].asLong()
        val mine2 = report(empToken, "VPN broken two", mapOf("serviceId" to svc))["id"].asLong()
        val theirs = report(otherToken, "VPN broken three", mapOf("serviceId" to svc))["id"].asLong()
        val lone = report(empToken, "Unrelated", mapOf("categoryCode" to "software"))["id"].asLong()

        val res = api.get("/api/admin/correlations", adminToken)
        assertEquals(200, res.response.status)
        val cluster = api.json(res).first { it["serviceId"].asLong() == svc }
        assertEquals("Ops VPN", cluster["label"].asString())
        assertEquals("SERVICE", cluster["kind"].asString())
        assertEquals(3, cluster["count"].asInt())
        assertEquals(setOf(mine1, mine2, theirs), cluster["incidentIds"].map { it.asLong() }.toSet())
        assertEquals(3, cluster["incidentNumbers"].size())
        assertTrue(cluster["firstAt"].asString() <= cluster["lastAt"].asString())
        assertEquals(403, api.get("/api/admin/correlations", empToken).response.status)
        assertEquals(403, api.get("/api/admin/correlations", techToken).response.status)

        // the reporter sees only their own other incident, but the size of the cluster
        val seen = api.json(api.get("/api/incidents/$mine1/related", empToken))
        assertEquals(3, seen["totalCount"].asInt())
        assertEquals(listOf(mine2), seen["related"].map { it["id"].asLong() })
        // the assigned technician and an administrator see the other two
        val techSees = api.json(api.get("/api/incidents/$mine1/related", techToken))
        assertEquals(setOf(mine2, theirs), techSees["related"].map { it["id"].asLong() }.toSet())
        assertEquals(2, api.json(api.get("/api/incidents/$theirs/related", adminToken))["related"].size())

        assertEquals(204, api.get("/api/incidents/$lone/related", empToken).response.status)
        assertEquals(403, api.get("/api/incidents/$mine1/related", otherToken).response.status)
        assertEquals(404, api.get("/api/incidents/999999999/related", empToken).response.status)

        // a resolved incident leaves the cluster
        act(mine1, "acknowledge", techToken)
        act(mine1, "start", techToken)
        act(mine1, "resolve", techToken, mapOf("summary" to "Restarted the gateway"))
        assertTrue(api.json(api.get("/api/admin/correlations", adminToken)).none { it["serviceId"].asLong() == svc })
        assertEquals(204, api.get("/api/incidents/$mine2/related", empToken).response.status)
    }
}
