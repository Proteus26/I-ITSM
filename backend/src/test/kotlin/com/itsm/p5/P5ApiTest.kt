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

    private fun tech(name: String, level: TechnicianLevel, skills: Set<String>, max: Int = 5, available: Boolean = true, hash: String? = null): Technician {
        val t = (if (hash != null) data.user(name, Role.TECHNICIAN, passwordHash = hash) else data.user(name, Role.TECHNICIAN)) as Technician
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
    fun `an excluded technician is never picked and excluding the only candidate gives null`() {
        val ravi = tech("p5ravi", TechnicianLevel.L2, setOf("NETWORK"))
        val other = tech("p5other", TechnicianLevel.L1, setOf("NETWORK"))
        val raviId = requireNotNull(ravi.id)
        val otherId = requireNotNull(other.id)

        assertEquals(raviId, assignment.assign(net)!!.technicianId)
        assertEquals(otherId, assignment.assign(net.copy(excludeTechnicianIds = setOf(raviId)))!!.technicianId)
        assertEquals(listOf(otherId), assignment.recommend(net.copy(excludeTechnicianIds = setOf(raviId)), limit = 5).map { it.technicianId })
        assertNull(assignment.assign(net.copy(excludeTechnicianIds = setOf(raviId, otherId))))
        assertTrue(assignment.recommend(net.copy(excludeTechnicianIds = setOf(raviId, otherId))).isEmpty())
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
        assertEquals(200, api.get("/api/admin/assignment/recommendations?priority=p1", adminToken).response.status)
    }

    @Test
    fun `suggest-category returns a suggestion, 204 without a match and 400 for a long title`() {
        val token = api.token(emp1.email, pw)
        val res = api.get("/api/intelligence/suggest-category?title=${enc("VPN keeps disconnecting")}&description=${enc("wifi at home")}", token)
        assertEquals(200, res.response.status)
        assertEquals("NETWORK", api.json(res)["code"].asString())
        assertTrue(api.json(res)["confidence"].asDouble() in 0.0..1.0)

        assertEquals(204, api.get("/api/intelligence/suggest-category?title=${enc("Hello there")}", token).response.status)
        assertEquals(400, api.get("/api/intelligence/suggest-category?title=${"x".repeat(201)}", token).response.status)
        // a long description is truncated, not rejected; text past 1000 characters is ignored
        val long = "x".repeat(1000) + " vpn"
        assertEquals(204, api.get("/api/intelligence/suggest-category?title=ok&description=$long", token).response.status)
        assertEquals(401, api.get("/api/intelligence/suggest-category?title=vpn").response.status)
    }

    @Test
    fun `an inactive category is never suggested`() {
        assertEquals("CLOUD", suggester.suggest("AWS bucket unreachable", "")?.code)
        jdbc.update("update categories set active = false where code = 'CLOUD'")
        assertNull(suggester.suggest("AWS bucket unreachable", ""))
    }

    @Test
    fun `overlapping keywords count once and phrases do not span title and description`() {
        // "web app" must not also count "app"; "log in" and "login" are different keywords but a span counts once
        val web = suggester.suggest("web app down", "")!!
        assertEquals("APPLICATION", web.code)
        assertEquals("Matched 'web app'", web.reason)
        assertEquals("Matched 'log in'", suggester.suggest("Cannot log in", "")!!.reason)
        // two real keywords of one category still count twice
        assertEquals("Matched 'password', 'login'", suggester.suggest("password reset", "login page")!!.reason)
        // the phrase spans the boundary between title and description, so it is not a match
        assertNull(suggester.suggest("Cannot log", "in to portal")?.takeIf { it.reason.contains("log in") })
        assertEquals("APPLICATION", suggester.suggest("Cannot log", "in to portal")!!.code)
        // ambiguous single words were pruned
        assertNull(suggester.suggest("Switch the monitor", "mouse update"))
    }

    @Test
    fun `short single word overlaps are not flagged as duplicates but real similarity is`() {
        data.incident("P5-DUP-1", "Printer jammed", emp1)
        data.incident("P5-DUP-2", "Outlook crashes when sending large attachments", emp1, description = "Outlook closes on send")
        val token = api.token(emp1.email, pw)
        fun ids(q: String) = api.json(api.get("/api/intelligence/similar?title=${enc(q)}", token)).map { it["number"].asString() }
        assertTrue(ids("Printer broken").isEmpty())
        assertEquals(listOf("P5-DUP-2"), ids("Outlook crashes when sending large attachments"))
    }

    @Test
    fun `a technician sees only incidents assigned to them or reported by them`() {
        val t = tech("p5viewer", TechnicianLevel.L1, setOf("NETWORK"), hash = encoder.encode(pw)!!)
        val mine = data.incident("P5-TV-1", "VPN cannot connect from home", emp1, status = IncidentStatus.ASSIGNED, assignee = t)
        val reported = data.incident("P5-TV-2", "VPN cannot connect from home", t)
        data.incident("P5-TV-3", "VPN cannot connect from home", emp2)
        val body = api.json(api.get("/api/intelligence/similar?title=${enc("VPN cannot connect from home")}", api.token(t.email, pw)))
        assertEquals(setOf(requireNotNull(mine.id), requireNotNull(reported.id)), body.map { it["id"].asLong() }.toSet())
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
