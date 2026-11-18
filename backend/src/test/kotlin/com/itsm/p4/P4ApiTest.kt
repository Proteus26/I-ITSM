package com.itsm.p4

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import com.itsm.notification.NotificationEntity
import com.itsm.notification.NotificationRepository
import com.itsm.support.Seed
import com.itsm.support.TestData
import com.itsm.support.uid
import com.itsm.user.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** HTTP-level checks for search visibility, notifications, the SSE stream and admin-only reports. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class P4ApiTest @Autowired constructor(
    private val mvc: MockMvc,
    mapper: JsonMapper,
    private val data: TestData,
    private val encoder: PasswordEncoder,
    private val notifications: NotificationRepository,
) {
    private val api = ApiClient(mvc, mapper)
    private val pw = "password123"
    private lateinit var s: Seed

    @BeforeEach
    fun seed() {
        data.reset()
        s = data.seed(passwordHash = encoder.encode(pw)!!)
    }

    @AfterEach
    fun cleanUp() = data.reset()

    private fun numbers(token: String): Set<String> =
        api.json(api.get("/api/incidents/search?size=50", token))["content"].map { it["number"].asString() }.toSet()

    @Test
    fun `search shows an employee only their own incidents and an admin everything`() {
        assertEquals(setOf("INC-000001", "INC-000003"), numbers(api.token(s.e1.email, pw)))
        assertEquals(setOf("INC-000001", "INC-000002", "INC-000003", "INC-000004"), numbers(api.token(s.admin.email, pw)))
    }

    @Test
    fun `search requires authentication and rejects an unknown sort field`() {
        assertEquals(401, api.get("/api/incidents/search").response.status)
        assertEquals(400, api.get("/api/incidents/search?sort=password,asc", api.token(s.e1.email, pw)).response.status)
    }

    @Test
    fun `notifications can be listed and marked read, only by their recipient`() {
        val saved = notifications.save(
            NotificationEntity(recipientId = s.e1.uid, incidentId = s.i1.id, title = "INC-000001 resolved", body = "Please check"),
        )
        val token = api.token(s.e1.email, pw)

        val list = api.json(api.get("/api/notifications?unread=true", token))
        assertEquals(1, list["content"].size())
        assertEquals(1L, api.json(api.get("/api/notifications/unread-count", token))["unread"].asLong())

        val read = api.post("/api/notifications/${saved.id}/read", token = token)
        assertEquals(200, read.response.status)
        assertTrue(api.json(read)["read"].asBoolean())
        assertEquals(0L, api.json(api.get("/api/notifications/unread-count", token))["unread"].asLong())

        // Someone else cannot mark it (404, not 403, so ids are not revealed).
        assertEquals(404, api.post("/api/notifications/${saved.id}/read", token = api.token(s.e2.email, pw)).response.status)
    }

    @Test
    fun `the notification stream opens as event-stream for a logged-in user`() {
        val token = api.token(s.e1.email, pw)
        mvc.perform(get("/api/notifications/stream").header("Authorization", "Bearer $token").accept("text/event-stream"))
            .andExpect(request().asyncStarted())
        assertEquals(401, api.get("/api/notifications/stream").response.status)
    }

    @Test
    fun `reports are admin only`() {
        assertEquals(403, api.get("/api/reports/overview", api.token(s.e1.email, pw)).response.status)
        assertEquals(403, api.get("/api/admin/audit", api.token(s.e1.email, pw)).response.status)
        val ok = api.get("/api/reports/overview", api.token(s.admin.email, pw))
        assertEquals(200, ok.response.status)
        assertEquals(4L, api.json(ok)["total"].asLong())
    }

    @Test
    fun `incident history is visible to the reporter but not to an unrelated employee`() {
        val id = s.i1.id
        assertEquals(200, api.get("/api/incidents/$id/history", api.token(s.e1.email, pw)).response.status)
        assertEquals(403, api.get("/api/incidents/$id/history", api.token(s.e2.email, pw)).response.status)
    }
}
