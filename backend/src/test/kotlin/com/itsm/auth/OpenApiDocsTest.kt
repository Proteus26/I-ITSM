package com.itsm.auth

import com.itsm.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OpenApiDocsTest @Autowired constructor(private val mvc: MockMvc) {

    @Test
    fun `api docs describe the auth endpoints and the Retry-After header`() {
        val result = mvc.perform(get("/v3/api-docs")).andReturn()

        assertEquals(200, result.response.status)
        val docs = result.response.contentAsString
        assertTrue("logout-all" in docs)
        assertTrue("Retry-After" in docs)
        assertTrue("change-password" in docs)
    }
}
