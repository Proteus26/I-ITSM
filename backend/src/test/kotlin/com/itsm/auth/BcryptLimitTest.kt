package com.itsm.auth

import com.itsm.user.UserFactory
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pins down how the BCrypt encoder behaves at the 72-byte limit, and that the domain guards against it. */
class BcryptLimitTest {
    private val encoder = BCryptPasswordEncoder()
    private val ok = "a".repeat(72)
    private val tooLong = "a".repeat(73)

    @Test
    fun `encode accepts 72 bytes and rejects 73`() {
        assertNotNull(encoder.encode(ok))
        assertFailsWith<IllegalArgumentException> { encoder.encode(tooLong) }
    }

    @Test
    fun `matches silently ignores bytes past 72 so the domain must reject them itself`() {
        val hash = encoder.encode(ok)!!
        assertTrue(encoder.matches(tooLong, hash))

        val user = UserFactory(encoder).createEmployee("a@b.com", "A", ok)
        assertTrue(user.passwordMatches(encoder, ok))
        assertFalse(user.passwordMatches(encoder, tooLong))
    }

    @Test
    fun `byte limit counts utf8 bytes not characters`() {
        val multibyte = "é".repeat(37) // 37 characters, 74 bytes
        assertEquals(37, multibyte.length)
        assertTrue(com.itsm.user.PasswordPolicy.violation(multibyte) != null)
        assertTrue(com.itsm.user.PasswordPolicy.violation("é".repeat(36)) == null)
        assertTrue(com.itsm.user.PasswordPolicy.violation("a".repeat(72)) == null)
        assertTrue(com.itsm.user.PasswordPolicy.violation("a".repeat(73)) != null)
        assertTrue(com.itsm.user.PasswordPolicy.violation("a".repeat(7)) != null)
    }
}
