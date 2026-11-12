package com.itsm.auth

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each test uses its own random emails, so the shared in-memory tracker cannot leak state between tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class LoginThrottleApiTest @Autowired constructor(
    mvc: MockMvc,
    mapper: JsonMapper,
    private val users: com.itsm.user.UserRepository,
) {
    private val api = ApiClient(mvc, mapper)
    private val pw = "password123"

    private fun failTimes(email: String, n: Int) =
        repeat(n) { assertEquals(401, api.login(email, "wrong-password").response.status) }

    @Test
    fun `five failures then 429 with Retry-After even for the correct password`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        failTimes(email, 5)

        val result = api.login(email, pw)

        assertEquals(429, result.response.status)
        assertTrue(result.response.contentType!!.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
        assertEquals("TooManyLoginAttemptsException", api.json(result)["title"].asString())
        val retry = result.response.getHeader("Retry-After")!!.toLong()
        assertTrue(retry in 1..900, "Retry-After was $retry")
    }

    @Test
    fun `an unknown email is throttled exactly like a known one`() {
        val unknown = api.uniqueEmail("ghost")
        failTimes(unknown, 5)

        val result = api.login(unknown, pw)

        assertEquals(429, result.response.status)
        assertTrue(result.response.getHeader("Retry-After")!!.toLong() in 1..900)
    }

    @Test
    fun `the lockout is case-insensitive on the email`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        failTimes(email, 5)

        assertEquals(429, api.login(email.uppercase(), pw).response.status)
    }

    @Test
    fun `four failures are still allowed and a success resets the counter`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        failTimes(email, 4)
        assertEquals(200, api.login(email, pw).response.status)

        failTimes(email, 4)

        assertEquals(200, api.login(email, pw).response.status)
    }

    @Test
    fun `throttling one email does not affect another`() {
        val locked = api.uniqueEmail("locked")
        val other = api.uniqueEmail("other")
        api.register(locked, pw)
        api.register(other, pw)
        failTimes(locked, 5)

        assertEquals(429, api.login(locked, pw).response.status)
        assertEquals(200, api.login(other, pw).response.status)
    }

    @Test
    fun `logins to a disabled account count towards the throttle`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        users.findByEmail(email)!!.let { it.deactivate(); users.saveAndFlush(it) }

        repeat(5) { assertEquals(403, api.login(email, pw).response.status) }

        assertEquals(429, api.login(email, pw).response.status)
    }

    @Test
    fun `the lockout does not stop an already issued token`() {
        val email = api.uniqueEmail()
        api.register(email, pw)
        val token = api.token(email, pw)
        failTimes(email, 5)

        assertEquals(200, api.get("/api/auth/me", token).response.status)
    }
}
