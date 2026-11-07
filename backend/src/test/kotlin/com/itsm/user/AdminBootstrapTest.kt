package com.itsm.user

import com.itsm.ApiClient
import com.itsm.TestcontainersConfiguration
import com.itsm.auth.AdminBootstrap
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "app.auth.bootstrap-admin.email=  Root@Example.COM ",
        "app.auth.bootstrap-admin.password=bootstrap-pass-1",
        "app.auth.bootstrap-admin.full-name=Root Admin",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AdminBootstrapTest @Autowired constructor(
    mvc: MockMvc,
    mapper: JsonMapper,
    private val bootstrap: AdminBootstrap,
    private val users: UserRepository,
    private val jdbc: JdbcTemplate,
) {
    private val api = ApiClient(mvc, mapper)

    private fun adminCount(): Int =
        jdbc.queryForObject("select count(*) from users where role = 'ADMIN'", Int::class.java)!!

    @Test
    fun `bootstrap admin is created from properties and can log in and use admin api`() {
        assertEquals(1, adminCount())
        val admin = users.findByEmail("root@example.com")
        assertTrue(admin is Administrator)
        assertEquals("Root Admin", admin.fullName)

        val token = api.token("root@example.com", "bootstrap-pass-1")
        assertEquals(200, api.get("/api/admin/users", token).response.status)
    }

    @Test
    fun `running the bootstrap again does not create another administrator`() {
        bootstrap.run(DefaultApplicationArguments())
        bootstrap.run(DefaultApplicationArguments())

        assertEquals(1, adminCount())
    }
}
