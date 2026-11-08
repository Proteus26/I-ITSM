package com.itsm.auth

import com.itsm.user.EmailAlreadyRegisteredException
import com.itsm.user.isEmailConflict
import com.itsm.user.translatingEmailConflict
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailConflictTest {
    private fun violation(constraint: String) =
        DataIntegrityViolationException("could not execute statement", RuntimeException("ERROR: duplicate key value violates unique constraint \"$constraint\""))

    @Test
    fun `only the users email constraint counts as an email conflict`() {
        assertTrue(violation("uq_users_email").isEmailConflict())
        assertFalse(violation("ck_users_role").isEmailConflict())
        assertFalse(DataIntegrityViolationException("other").isEmailConflict())
    }

    @Test
    fun `translating maps email conflicts and rethrows everything else`() {
        val conflict = assertFailsWith<EmailAlreadyRegisteredException> {
            translatingEmailConflict({ "a@b.com" }) { throw violation("uq_users_email") }
        }
        assertEquals("Email 'a@b.com' is already registered", conflict.message)

        assertFailsWith<DataIntegrityViolationException> {
            translatingEmailConflict({ "a@b.com" }) { throw violation("ck_users_role") }
        }
    }
}
