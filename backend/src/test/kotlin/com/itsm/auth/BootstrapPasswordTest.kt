package com.itsm.auth

import com.itsm.user.UserFactory
import com.itsm.user.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.boot.DefaultApplicationArguments
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BootstrapPasswordTest {
    private fun bootstrap(password: String, adminExists: Boolean = false): AdminBootstrap {
        val props = AuthProperties().apply {
            bootstrapAdmin = AuthProperties.BootstrapAdmin().apply { email = "root@example.com"; this.password = password }
        }
        val users = mock(UserRepository::class.java)
        `when`(users.existsAdministrator()).thenReturn(adminExists)
        return AdminBootstrap(users, mock(UserFactory::class.java), props)
    }

    @Test
    fun `too short or too long bootstrap passwords fail fast with a clear message`() {
        for (password in listOf("short", "a".repeat(73), "é".repeat(37))) {
            val ex = assertFailsWith<IllegalStateException> { bootstrap(password).run(DefaultApplicationArguments()) }
            assertTrue("ADMIN_PASSWORD" in ex.message!! && "72" in ex.message!!, ex.message)
        }
    }

    @Test
    fun `a valid bootstrap password passes validation`() {
        bootstrap("a".repeat(72)).run(DefaultApplicationArguments())
    }

    @Test
    fun `an invalid bootstrap password does not block startup once an administrator exists`() {
        bootstrap("short", adminExists = true).run(DefaultApplicationArguments())
    }
}
