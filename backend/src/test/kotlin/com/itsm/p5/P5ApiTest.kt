package com.itsm.p5

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import com.itsm.assignment.AssignmentRequest
import com.itsm.assignment.AssignmentService
import com.itsm.incident.IncidentStatus
import com.itsm.incident.Priority
import com.itsm.intelligence.KeywordCategorySuggester
import com.itsm.support.TestData
import com.itsm.user.Role
import com.itsm.user.Technician
import com.itsm.user.TechnicianLevel
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
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Assignment against real technicians and incidents, the admin recommendations endpoint and the intelligence endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class P5ApiTest @Autowired constructor(
    private val mvc: MockMvc,
    mapper: JsonMapper,
    private val data: TestData,
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val assignment: AssignmentService,
    private val suggester: KeywordCategorySuggester,
    private val jdbc: JdbcTemplate,
) {
    private val api = ApiClient(mvc, mapper)
    private val pw = "password123"
    private lateinit var admin: User
    private lateinit var emp1: User
    private lateinit var emp2: User
    private var parkedTechnicians = emptyList<Long>()

    @BeforeEach
    fun setUp() {
        data.reset()
        // Technicians left behind by other test classes would be candidates too; park them while this test runs.
        parkedTechnicians = jdbc.queryForList("select id from users where role = 'TECHNICIAN' and available", Long::class.javaObjectType)
        parkedTechnicians.forEach { jdbc.update("update users set available = false where id = ?", it) }
        val hash = encoder.encode(pw)!!
        admin = data.user("p5admin", Role.ADMIN, passwordHash = hash)
        emp1 = data.user("p5emp1", Role.EMPLOYEE, passwordHash = hash)
        emp2 = data.user("p5emp2", Role.EMPLOYEE, passwordHash = hash)
    }

    @AfterEach
    fun cleanUp() {
        jdbc.update("update categories set active = true where code = 'CLOUD'")
        data.reset()
        parkedTechnicians.forEach { jdbc.update("update users set available = true where id = ?", it) }
    }

    private fun tech(name: String, level: TechnicianLevel, skills: Set<String>, max: Int = 5, available: Boolean = true): Technician {
        val t = data.user(name, Role.TECHNICIAN) as Technician
        t.changeLevel(level)
        t.changeCapacity(max)
        t.replaceSkills(skills)
        if (!available) t.markUnavailable()
        return users.save(t)
    }

    private fun busy(owner: User, assignee: User, count: Int, tag: String) = repeat(count) {
        data.incident("P5-$tag-$it", "Busywork $tag $it", owner, status = IncidentStatus.ASSIGNED, assignee = assignee, category = "NETWORK")
    }

    private val net = AssignmentRequest(null, "NETWORK", Priority.P3)

    @Test
    fun `assign picks the skilled, least loaded technician and skips full or unavailable ones`() {
        val ravi = tech("p5ravi", TechnicianLevel.L2, setOf("NETWORK"))
        val full = tech("p5full", TechnicianLevel.L1, setOf("NETWORK"), max = 1)
        tech("p5away", TechnicianLevel.L1, setOf("NETWORK"), available = false)
        val loaded = tech("p5loaded", TechnicianLevel.L1, setOf("NETWORK"))
        busy(emp1, full, 1, "full")
        busy(emp1, loaded, 3, "loaded")
        busy(emp1, ravi, 1, "ravi")

        val result = assertNotNull(assignment.assign(net))
        assertEquals(requireNotNull(ravi.id), result.technicianId)
        assertEquals("skill-least-loaded", result.strategy)
        assertEquals("P5ravi (L2) has NETWORK skill and 1/5 active incidents", result.reason)

        val ranked = assignment.recommend(net, limit = 5).map { it.technicianId }
        assertEquals(listOf(requireNotNull(ravi.id), requireNotNull(loaded.id)), ranked)
    }

    @Test
    fun `assign returns null when nobody has capacity`() {
        val only = tech("p5only", TechnicianLevel.L1, setOf("NETWORK"), max = 1)
        busy(emp1, only, 1, "only")
        assertNull(assignment.assign(net))
        assertTrue(assignment.recommend(net).isEmpty())
        assertEquals(setOf("skill-least-loaded", "round-robin"), assignment.availableStrategies)
    }

    @Test
    fun `recommendations endpoint is admin only and names the technicians`() {
        val ravi = tech("p5ravi", TechnicianLevel.L2, setOf("NETWORK"))
        val adminToken = api.token(admin.email, pw)
        val res = api.get("/api/admin/assignment/recommendations?category=network&priority=P1", adminToken)
        assertEquals(200, res.response.status)
        val body = api.json(res)
        val mine = body.first { it["technicianId"].asLong() == ravi.id }
        assertEquals("P5ravi", mine["technicianName"].asString())
        assertEquals("skill-least-loaded", mine["strategy"].asString())

        assertEquals(403, api.get("/api/admin/assignment/recommendations", api.token(emp1.email, pw)).response.status)
        assertEquals(401, api.get("/api/admin/assignment/recommendations").response.status)
        assertEquals(400, api.get("/api/admin/assignment/recommendations?priority=P9", adminToken).response.status)
    }

    @Test
    fun `suggest-category returns a suggestion, 204 without a match and 400 for long input`() {
        val token = api.token(emp1.email, pw)
        val res = api.get("/api/intelligence/suggest-category?title=${enc("VPN keeps disconnecting")}&description=${enc("wifi at home")}", token)
        assertEquals(200, res.response.status)
        assertEquals("NETWORK", api.json(res)["code"].asString())
        assertTrue(api.json(res)["confidence"].asDouble() in 0.0..1.0)

        assertEquals(204, api.get("/api/intelligence/suggest-category?title=${enc("Hello there")}", token).response.status)
        assertEquals(400, api.get("/api/intelligence/suggest-category?title=${"x".repeat(201)}", token).response.status)
        assertEquals(400, api.get("/api/intelligence/suggest-category?title=ok&description=${"x".repeat(4001)}", token).response.status)
        assertEquals(401, api.get("/api/intelligence/suggest-category?title=vpn").response.status)
    }

    @Test
    fun `an inactive category is never suggested`() {
        assertEquals("CLOUD", suggester.suggest("AWS bucket unreachable", "")?.code)
        jdbc.update("update categories set active = false where code = 'CLOUD'")
        assertNull(suggester.suggest("AWS bucket unreachable", ""))
    }

    @Test
    fun `similar incidents respect visibility`() {
        val theirs = data.incident("P5-SIM-1", "VPN cannot connect from home", emp2, description = "Timeout when dialling in")
        val mine = data.incident("P5-SIM-2", "Cannot connect to VPN from home", emp1, description = "Dial in times out")
        val query = "/api/intelligence/similar?title=${enc("VPN cannot connect from home office")}&description=${enc("Timeout when dialling in")}"

        fun ids(token: String) = api.json(api.get(query, token)).map { it["id"].asLong() }.toSet()
        assertEquals(setOf(requireNotNull(mine.id)), ids(api.token(emp1.email, pw)))
        assertEquals(setOf(requireNotNull(mine.id), requireNotNull(theirs.id)), ids(api.token(admin.email, pw)))

        val excluded = api.json(api.get("$query&excludeId=${mine.id}", api.token(admin.email, pw))).map { it["id"].asLong() }
        assertEquals(listOf(requireNotNull(theirs.id)), excluded)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
