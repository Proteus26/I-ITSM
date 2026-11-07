package com.itsm

import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get as mockGet
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch as mockPatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post as mockPost
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Small MockMvc helper shared by the API tests: JSON bodies, bearer tokens, response parsing. */
class ApiClient(val mvc: MockMvc, val mapper: JsonMapper) {

    fun uniqueEmail(prefix: String = "user"): String =
        "$prefix-${UUID.randomUUID().toString().take(8)}@example.com"

    private fun MockHttpServletRequestBuilder.withToken(token: String?): MockHttpServletRequestBuilder =
        if (token != null) header("Authorization", "Bearer $token") else this

    private fun MockHttpServletRequestBuilder.withBody(body: Any?): MockHttpServletRequestBuilder =
        if (body == null) this
        else contentType(MediaType.APPLICATION_JSON)
            .content(if (body is String) body else mapper.writeValueAsString(body))

    fun get(url: String, token: String? = null): MvcResult =
        mvc.perform(mockGet(url).withToken(token)).andReturn()

    fun post(url: String, body: Any? = null, token: String? = null): MvcResult =
        mvc.perform(mockPost(url).withBody(body).withToken(token)).andReturn()

    fun patch(url: String, body: Any? = null, token: String? = null): MvcResult =
        mvc.perform(mockPatch(url).withBody(body).withToken(token)).andReturn()

    fun json(result: MvcResult): JsonNode {
        val text = result.response.contentAsString
        return if (text.isEmpty()) mapper.createObjectNode() else mapper.readTree(text)
    }

    fun register(email: String, password: String = "password123", fullName: String = "Test User"): MvcResult =
        post("/api/auth/register", mapOf("email" to email, "fullName" to fullName, "password" to password))

    fun login(email: String, password: String): MvcResult =
        post("/api/auth/login", mapOf("email" to email, "password" to password))

    /** Logs in and returns the bearer token; fails the test if login does not return 200. */
    fun token(email: String, password: String): String {
        val result = login(email, password)
        check(result.response.status == 200) { "Login failed for $email: ${result.response.status} ${result.response.contentAsString}" }
        return json(result)["token"].asString()
    }
}
