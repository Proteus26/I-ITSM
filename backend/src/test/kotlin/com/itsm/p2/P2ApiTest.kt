package com.itsm.p2

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import com.itsm.audit.AuditLogRepository
import com.itsm.notification.NotificationRepository
import com.itsm.sla.SlaMonitor
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

/** The incident API end to end over HTTP: reporting pipeline, lifecycle, visibility, lists, admin actions and categories. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class P2ApiTest @Autowired constructor(
    private val mvc: MockMvc,
    mapper: JsonMapper,
    private val data: TestData,
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
    private val audit: AuditLogRepository,
    private val notifications: NotificationRepository,
    private val slaMonitor: SlaMonitor,
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
    private val categoryCodes = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        data.reset()
        // Technicians left behind by other test classes would be assignment candidates too; park them meanwhile.
        parked = jdbc.queryForList("select id from users where role = 'TECHNICIAN' and available", Long::class.javaObjectType)
        parked.forEach { jdbc.update("update users set available = false where id = ?", it) }
        val hash = encoder.encode(pw)!!
        admin = data.user("p2admin", Role.ADMIN, passwordHash = hash)
        emp = data.user("p2emp", Role.EMPLOYEE, passwordHash = hash)
        other = data.user("p2other", Role.EMPLOYEE, passwordHash = hash)
        tech = technician("p2tech", setOf("NETWORK", "SOFTWARE"), hash)
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
            jdbc.update("delete from incidents where id = ?", it) // notes cascade
        }
        incidentIds.clear()
        categoryCodes.forEach { jdbc.update("delete from categories where code = ?", it) }
        categoryCodes.clear()
        data.reset()
        parked.forEach { jdbc.update("update users set available = true where id = ?", it) }
    }

    private fun technician(name: String, skills: Set<String>, hash: String): Technician {
        val t = data.user(name, Role.TECHNICIAN, passwordHash = hash) as Technician
        t.replaceSkills(skills)
        return users.save(t)
    }

    private fun status(r: org.springframework.test.web.servlet.MvcResult) = r.response.status

    private fun report(token: String = empToken, title: String = "Printer room is dark", description: String = "Nothing happens at all", category: String? = null, extra: Map<String, Any?> = emptyMap()): JsonNode {
        val body = mutableMapOf<String, Any?>("title" to title, "description" to description, "impact" to "TEAM", "urgency" to "HIGH")
        category?.let { body["categoryCode"] = it }
        body.putAll(extra)
        val r = api.post("/api/incidents", body, token)
        assertEquals(201, status(r), r.response.contentAsString)
        return api.json(r).also { incidentIds += it["id"].asLong() }
    }

    private fun act(id: Long, action: String, token: String, body: Any? = null): JsonNode {
        val r = api.post("/api/incidents/$id/$action", body ?: emptyMap<String, Any>(), token)
        assertEquals(200, status(r), "$action: ${r.response.contentAsString}")
        return api.json(r)
    }

    private fun actions(detail: JsonNode) = detail["actions"].map { it.asString() }.toSet()

    private fun detail(id: Long, token: String) = api.json(api.get("/api/incidents/$id", token))

    @Test
    fun `a reported incident with a category is classified, priced, given deadlines and assigned`() {
        val d = report(category = "network")
        assertEquals("ASSIGNED", d["status"].asString())
        assertEquals("NETWORK", d["categoryCode"].asString())
        assertEquals("Network", d["categoryName"].asString())
        assertEquals(tech.uid, d["assignee"]["id"].asLong())
        assertEquals("P2tech", d["assignee"]["fullName"].asString())
        assertEquals(emp.uid, d["reporter"]["id"].asLong())
        assertTrue(d["priority"].asString().matches(Regex("P[1-4]")))
        assertTrue(d["priorityScore"].asDouble() > 0)
        assertTrue(d["priorityExplanation"].asString().isNotBlank())
        assertTrue(d["responseDueAt"].isString && d["resolutionDueAt"].isString)
        assertTrue(d["number"].asString().startsWith("INC-"))
        assertEquals(1, d["affectedUsers"].asInt())
        assertTrue(actions(d).isEmpty())
        assertTrue("COMMENT" in d["noteTypes"].map { it.asString() })

        val forTech = detail(d["id"].asLong(), techToken)
        assertEquals(setOf("ACKNOWLEDGE"), actions(forTech))
        assertEquals(setOf("INVESTIGATION", "COMMENT"), forTech["noteTypes"].map { it.asString() }.toSet())
        assertEquals(setOf("ASSIGN"), actions(detail(d["id"].asLong(), adminToken)))
    }

    @Test
    fun `text without a clear category stays NEW and an admin can classify it and trigger assignment`() {
        val d = report(title = "Odd thing", description = "zzz qqq xxx")
        val id = d["id"].asLong()
        assertEquals("NEW", d["status"].asString())
        assertNull(d["categoryCode"].takeUnless { it.isNull })
        assertNull(d["assignee"].takeUnless { it.isNull })
        assertTrue(d["priority"].isString, "priority is calculated even before classification")
        assertEquals(setOf("CANCEL"), actions(d))
        assertEquals(setOf("CLASSIFY", "CANCEL"), actions(detail(id, adminToken)))

        assertEquals(403, status(api.post("/api/admin/incidents/$id/classify", mapOf("categoryCode" to "NETWORK"), empToken)))
        assertEquals(400, status(api.post("/api/admin/incidents/$id/classify", mapOf("categoryCode" to "NOPE"), adminToken)))
        val classified = api.json(api.post("/api/admin/incidents/$id/classify", mapOf("categoryCode" to "network"), adminToken))
        assertEquals("ASSIGNED", classified["status"].asString())
        assertEquals("NETWORK", classified["categoryCode"].asString())
        assertEquals(tech.uid, classified["assignee"]["id"].asLong())
        assertEquals(409, status(api.post("/api/admin/incidents/$id/classify", mapOf("categoryCode" to "SOFTWARE"), adminToken)))
    }

    @Test
    fun `a classified incident nobody can take waits for an admin who can assign it`() {
        jdbc.update("update users set available = false where id = ?", tech.uid)
        val d = report(category = "NETWORK")
        val id = d["id"].asLong()
        assertEquals("CLASSIFIED", d["status"].asString())
        assertEquals(setOf("ASSIGN", "CANCEL"), actions(detail(id, adminToken)))

        assertEquals(400, status(api.post("/api/admin/incidents/$id/assign", mapOf("technicianId" to emp.uid), adminToken)))
        assertEquals(400, status(api.post("/api/admin/incidents/$id/assign", mapOf("technicianId" to 999999999L), adminToken)))
        assertEquals(403, status(api.post("/api/admin/incidents/$id/assign", mapOf("technicianId" to tech.uid), techToken)))
        val assigned = api.json(api.post("/api/admin/incidents/$id/assign", mapOf("technicianId" to tech.uid), adminToken))
        assertEquals("ASSIGNED", assigned["status"].asString())
        assertEquals(tech.uid, assigned["assignee"]["id"].asLong())
        // Manual assignments are audited with the strategy name.
        assertTrue(audit.findByIncidentIdOrderByOccurredAtAscIdAsc(id).any { it.action == "INCIDENT_ASSIGNED" })
    }

    @Test
    fun `keyword text is classified automatically`() {
        val d = report(title = "VPN down", description = "My vpn and wifi keep dropping, the network is unusable")
        assertEquals("NETWORK", d["categoryCode"].asString())
        assertEquals("ASSIGNED", d["status"].asString())
    }

    @Test
    fun `the whole lifecycle works over HTTP and leaves audit rows and notifications`() {
        val id = report(category = "NETWORK")["id"].asLong()

        assertEquals("ACKNOWLEDGED", act(id, "acknowledge", techToken)["status"].asString())
        val started = act(id, "start", techToken)
        assertEquals("IN_PROGRESS", started["status"].asString())
        assertEquals(setOf("REQUEST_INFO", "RESOLVE"), actions(started))

        val pending = act(id, "request-info", techToken, mapOf("question" to "Which floor are you on?"))
        assertEquals("PENDING_INFO", pending["status"].asString())
        assertEquals(setOf("RESUME"), actions(detail(id, empToken)))
        assertFalse("INFO_RESPONSE" in detail(id, empToken)["noteTypes"].map { it.asString() }) // answered through resume

        val resumed = act(id, "resume", empToken, mapOf("response" to "Third floor"))
        assertEquals("IN_PROGRESS", resumed["status"].asString())

        assertEquals(201, status(api.post("/api/incidents/$id/notes", mapOf("type" to "INVESTIGATION", "body" to "Switch port flapping"), techToken)))
        val resolved = act(id, "resolve", techToken, mapOf("summary" to "Replaced the switch", "rootCause" to "Hardware fault"))
        assertEquals("RESOLVED", resolved["status"].asString())
        assertEquals("Replaced the switch", resolved["resolutionSummary"].asString())
        assertEquals(setOf("VERIFY", "REOPEN", "FEEDBACK"), actions(detail(id, empToken)))

        val rated = act(id, "feedback", empToken, mapOf("rating" to 5, "comment" to "Quick"))
        assertEquals(5, rated["feedbackRating"].asInt())
        assertEquals(409, status(api.post("/api/incidents/$id/feedback", mapOf("rating" to 4), empToken)))

        assertEquals("VERIFIED", act(id, "verify", empToken)["status"].asString())
        val closed = act(id, "close", empToken)
        assertEquals("CLOSED", closed["status"].asString())
        assertTrue(closed["closedAt"].isString)
        assertTrue(actions(closed).isEmpty())

        // Notes: the employee sees the question and own answer but not the investigation note; the technician sees all.
        val employeeNotes = api.json(api.get("/api/incidents/$id/notes", empToken)).map { it["type"].asString() }
        assertEquals(listOf("INFO_REQUEST", "INFO_RESPONSE"), employeeNotes)
        val staffNotes = api.json(api.get("/api/incidents/$id/notes", techToken))
        assertEquals(listOf("INFO_REQUEST", "INFO_RESPONSE", "INVESTIGATION"), staffNotes.map { it["type"].asString() })
        assertEquals(emp.uid, staffNotes[1]["author"]["id"].asLong())

        // P4 side effects: one audit row per status change at least, and the reporter was notified.
        val actionsLogged = audit.findByIncidentIdOrderByOccurredAtAscIdAsc(id).map { it.action }
        assertTrue("INCIDENT_REPORTED" in actionsLogged, actionsLogged.toString())
        assertTrue(actionsLogged.count { it == "STATUS_CHANGED" } >= 6, actionsLogged.toString())
        assertTrue(notifications.countByRecipientIdAndReadAtIsNull(emp.uid) > 0)
        assertTrue(jdbc.queryForObject("select count(*) from notifications where incident_id = ? and recipient_id = ?", Long::class.javaObjectType, id, tech.uid)!! > 0)
    }

    @Test
    fun `reopening clears the resolution and the feedback`() {
        val id = report(category = "SOFTWARE")["id"].asLong()
        act(id, "acknowledge", techToken)
        act(id, "start", techToken)
        act(id, "resolve", techToken, mapOf("summary" to "Reinstalled"))
        act(id, "feedback", empToken, mapOf("rating" to 2))

        val reopened = act(id, "reopen", empToken, mapOf("reason" to "Still broken"))
        assertEquals("IN_PROGRESS", reopened["status"].asString())
        assertNull(reopened["resolutionSummary"].takeUnless { it.isNull })
        assertNull(reopened["feedbackRating"].takeUnless { it.isNull })
        assertNull(reopened["resolvedAt"].takeUnless { it.isNull })
        val last = api.json(api.get("/api/incidents/$id/notes", empToken)).last()
        assertEquals("COMMENT", last["type"].asString())
        assertEquals("Still broken", last["body"].asString())
    }

    @Test
    fun `cancelling keeps the reason, and a cancelled incident is final`() {
        val id = report(title = "Odd", description = "zzz qqq")["id"].asLong()
        val cancelled = act(id, "cancel", empToken, mapOf("reason" to "Fixed itself"))
        assertEquals("CANCELLED", cancelled["status"].asString())
        assertEquals(409, status(api.post("/api/incidents/$id/cancel", emptyMap<String, Any>(), empToken)))
        assertEquals(403, status(api.post("/api/incidents/$id/notes", mapOf("type" to "COMMENT", "body" to "hi"), empToken)))
        assertEquals("Fixed itself", api.json(api.get("/api/incidents/$id/notes", empToken))[0]["body"].asString())
    }

    @Test
    fun `wrong roles get 403, impossible moves get 409 and unknown or foreign incidents are refused`() {
        val id = report(category = "NETWORK")["id"].asLong()

        assertEquals(403, status(api.post("/api/incidents/$id/acknowledge", emptyMap<String, Any>(), empToken)))
        assertEquals(403, status(api.post("/api/incidents/$id/acknowledge", emptyMap<String, Any>(), adminToken)))
        assertEquals(409, status(api.post("/api/incidents/$id/start", emptyMap<String, Any>(), techToken)))
        assertEquals(409, status(api.post("/api/incidents/$id/resume", emptyMap<String, Any>(), empToken)))
        assertEquals(409, status(api.post("/api/incidents/$id/close", emptyMap<String, Any>(), empToken)))
        assertEquals(409, status(api.post("/api/incidents/$id/resolve", mapOf("summary" to "x"), techToken)))
        assertEquals(409, status(api.post("/api/incidents/$id/feedback", mapOf("rating" to 3), empToken)))
        assertEquals(400, status(api.post("/api/incidents/$id/feedback", mapOf("rating" to 9), empToken)))
        assertEquals(400, status(api.post("/api/incidents/$id/request-info", mapOf("question" to ""), techToken)))

        assertEquals(403, status(api.get("/api/incidents/$id", otherToken)))
        assertEquals(403, status(api.get("/api/incidents/$id/notes", otherToken)))
        assertEquals(403, status(api.post("/api/incidents/$id/cancel", emptyMap<String, Any>(), otherToken)))
        assertEquals(404, status(api.get("/api/incidents/999999999", empToken)))
        assertEquals(401, status(api.get("/api/incidents/$id")))
        assertEquals(200, status(api.get("/api/incidents/$id", adminToken)))
    }

    @Test
    fun `note rules depend on role and status`() {
        val id = report(category = "NETWORK")["id"].asLong()
        fun note(type: String, token: String) = status(api.post("/api/incidents/$id/notes", mapOf("type" to type, "body" to "text"), token))

        assertEquals(403, note("INVESTIGATION", empToken))
        assertEquals(400, note("INFO_RESPONSE", empToken)) // answers go through resume
        assertEquals(400, note("INFO_RESPONSE", techToken))
        assertEquals(400, note("INFO_REQUEST", techToken))
        assertEquals(201, note("INVESTIGATION", techToken))
        assertEquals(201, note("COMMENT", empToken))
        assertEquals(201, note("COMMENT", adminToken))
        assertEquals(403, note("COMMENT", otherToken))
        assertEquals(400, status(api.post("/api/incidents/$id/notes", mapOf("type" to "COMMENT", "body" to " "), empToken)))
    }

    @Test
    fun `internal notes are hidden from everyone but the assignee and admins, even from a technician reporter`() {
        val hash = encoder.encode(pw)!!
        val reporterTech = technician("p2reptech", emptySet(), hash)
        val reporterToken = api.token(reporterTech.email, pw)
        val id = report(token = reporterToken, category = "NETWORK")["id"].asLong()
        assertEquals(tech.uid, detail(id, reporterToken)["assignee"]["id"].asLong())

        assertEquals(201, status(api.post("/api/incidents/$id/notes", mapOf("type" to "INVESTIGATION", "body" to "internal"), techToken)))
        assertEquals(201, status(api.post("/api/incidents/$id/notes", mapOf("type" to "COMMENT", "body" to "public"), techToken)))

        fun types(token: String) = api.json(api.get("/api/incidents/$id/notes", token)).map { it["type"].asString() }
        assertEquals(listOf("COMMENT"), types(reporterToken))
        assertEquals(listOf("INVESTIGATION", "COMMENT"), types(adminToken))
        assertEquals(listOf("INVESTIGATION", "COMMENT"), types(techToken))
    }

    @Test
    fun `reopening re-arms the SLA so the monitor records no false resolution breach`() {
        val id = report(category = "SOFTWARE")["id"].asLong()
        act(id, "acknowledge", techToken)
        act(id, "start", techToken)
        act(id, "resolve", techToken, mapOf("summary" to "Reinstalled"))
        // Long after the original deadlines.
        jdbc.update("update incidents set response_due_at = now() - interval '3 hours', resolution_due_at = now() - interval '2 hours' where id = ?", id)

        val reopened = act(id, "reopen", empToken, mapOf("reason" to "Still broken"))
        assertEquals("IN_PROGRESS", reopened["status"].asString())
        assertTrue(Instant.parse(reopened["resolutionDueAt"].asString()).isAfter(Instant.now()), reopened["resolutionDueAt"].asString())

        slaMonitor.runOnce(Instant.now())
        val breaches = jdbc.queryForObject("select count(*) from sla_events where incident_id = ? and type = 'RESOLUTION_BREACH'", Int::class.javaObjectType, id)
        assertEquals(0, breaches)
    }

    @Test
    fun `an incident is never assigned to its own reporter, automatically or by an admin`() {
        val hash = encoder.encode(pw)!!
        val reporterTech = technician("p2reptech", setOf("NETWORK"), hash)
        val reporterToken = api.token(reporterTech.email, pw)
        jdbc.update("update users set available = false where id = ?", tech.uid) // the reporter would be the only candidate

        val created = report(token = reporterToken, category = "NETWORK")
        val id = created["id"].asLong()
        assertEquals("CLASSIFIED", created["status"].asString())
        assertTrue(created["assignee"].isNull)

        val r = api.post("/api/admin/incidents/$id/assign", mapOf("technicianId" to reporterTech.uid), adminToken)
        assertEquals(400, status(r), r.response.contentAsString)
        assertTrue(r.response.contentAsString.contains("cannot assign an incident to its reporter"))
        assertEquals(200, status(api.post("/api/admin/incidents/$id/assign", mapOf("technicianId" to tech.uid), adminToken)))
    }

    @Test
    fun `mine is paged newest first and capped, assigned is for technicians and only active`() {
        val first = report(title = "First", category = "NETWORK")["id"].asLong()
        val second = report(title = "Second", category = "NETWORK")["id"].asLong()
        report(token = otherToken, title = "Not mine", category = "NETWORK")

        val mine = api.json(api.get("/api/incidents/mine?size=1", empToken))
        assertEquals(2, mine["totalElements"].asInt())
        assertEquals(2, mine["totalPages"].asInt())
        assertEquals(second, mine["content"][0]["id"].asLong())
        assertEquals("P2emp", mine["content"][0]["reporter"]["fullName"].asString())
        assertEquals(100, api.json(api.get("/api/incidents/mine?size=1000", empToken))["size"].asInt())
        assertEquals(first, api.json(api.get("/api/incidents/mine?size=1&page=1", empToken))["content"][0]["id"].asLong())

        assertEquals(3, api.json(api.get("/api/incidents/assigned", techToken))["totalElements"].asInt())
        act(first, "acknowledge", techToken)
        act(first, "start", techToken)
        act(first, "resolve", techToken, mapOf("summary" to "done"))
        val assigned = api.json(api.get("/api/incidents/assigned", techToken))
        assertEquals(2, assigned["totalElements"].asInt())
        assertFalse(assigned["content"].any { it["id"].asLong() == first })
        assertEquals(403, status(api.get("/api/incidents/assigned", empToken)))
        assertEquals(403, status(api.get("/api/incidents/assigned", adminToken)))
    }

    @Test
    fun `reporting validates the input`() {
        fun post(vararg extra: Pair<String, Any?>) =
            status(api.post("/api/incidents", mapOf("title" to "T", "description" to "D", "impact" to "TEAM", "urgency" to "LOW") + extra, empToken))

        assertEquals(400, post("title" to " "))
        assertEquals(400, post("impact" to "HUGE"))
        assertEquals(400, post("affectedUsers" to 0))
        assertEquals(400, post("categoryCode" to "NO_SUCH"))
        assertEquals(400, post("categoryCode" to "not valid!"))
        assertEquals(400, post("serviceId" to 999999999L))
        assertEquals(401, status(api.post("/api/incidents", emptyMap<String, Any>())))

        jdbc.update("update categories set active = false where code = 'CLOUD'")
        try {
            assertEquals(400, post("categoryCode" to "CLOUD"))
        } finally {
            jdbc.update("update categories set active = true where code = 'CLOUD'")
        }
    }

    @Test
    fun `a service raises the criticality and shows its name`() {
        val serviceId = jdbc.queryForObject(
            "insert into it_services (name, code, criticality) values ('P2 Payments', 'P2_PAYMENTS', 4) returning id", Long::class.javaObjectType,
        )!!
        try {
            val d = report(category = "NETWORK", extra = mapOf("serviceId" to serviceId, "affectedUsers" to 40, "impact" to "ORGANISATION"))
            assertEquals("P2 Payments", d["serviceName"].asString())
            assertEquals(serviceId, d["serviceId"].asLong())
            assertEquals(40, d["affectedUsers"].asInt())
            assertEquals("P1", d["priority"].asString())
        } finally {
            cleanUp()
            jdbc.update("delete from it_services where id = ?", serviceId)
        }
    }

    @Test
    fun `categories can be listed by anyone and managed by admins only`() {
        val code = "P2TMP${System.nanoTime() % 100000}"
        categoryCodes += code
        val active = api.json(api.get("/api/categories", empToken)).map { it["code"].asString() }
        assertTrue("NETWORK" in active)
        assertEquals(401, status(api.get("/api/categories")))

        assertEquals(403, status(api.get("/api/admin/categories", empToken)))
        assertEquals(403, status(api.post("/api/admin/categories", mapOf("code" to code, "name" to "Temp"), empToken)))
        val created = api.post("/api/admin/categories", mapOf("code" to code.lowercase(), "name" to "Temp", "description" to "d"), adminToken)
        assertEquals(201, status(created))
        assertEquals(code, api.json(created)["code"].asString())
        assertTrue(api.json(created)["active"].asBoolean())
        assertEquals(409, status(api.post("/api/admin/categories", mapOf("code" to code, "name" to "Again"), adminToken)))
        assertEquals(400, status(api.post("/api/admin/categories", mapOf("code" to "9bad", "name" to "Bad"), adminToken)))
        assertEquals(400, status(api.post("/api/admin/categories", mapOf("code" to code + "X", "name" to " "), adminToken)))

        val patched = api.json(api.patch("/api/admin/categories/$code", mapOf("name" to "Renamed", "active" to false), adminToken))
        assertEquals("Renamed", patched["name"].asString())
        assertEquals("d", patched["description"].asString())
        assertFalse(patched["active"].asBoolean())
        assertFalse(api.json(api.get("/api/categories", empToken)).any { it["code"].asString() == code })
        assertTrue(api.json(api.get("/api/admin/categories", adminToken)).any { it["code"].asString() == code && !it["active"].asBoolean() })
        assertEquals(400, status(api.post("/api/incidents", mapOf("title" to "t", "description" to "d", "impact" to "TEAM", "urgency" to "LOW", "categoryCode" to code), empToken)))
        assertEquals(404, status(api.patch("/api/admin/categories/NO_SUCH", mapOf("name" to "x"), adminToken)))
        assertEquals(403, status(api.patch("/api/admin/categories/$code", mapOf("active" to true), techToken)))
        assertNotNull(patched)
    }
}
