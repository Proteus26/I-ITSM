package com.itsm.user

import com.itsm.common.exception.DomainValidationException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UserDomainTest {

    private val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
    private val factory = UserFactory(encoder)

    private fun employee(password: String = "secret-pass") =
        factory.createEmployee("alice@example.com", "Alice", password)

    @Test
    fun `email is trimmed and lowercased on construction`() {
        val user = Employee("  Alice@Example.COM \t", "Alice", "hash")
        assertEquals("alice@example.com", user.email)
    }

    @Test
    fun `email is trimmed and lowercased on changeEmail`() {
        val user = employee()
        user.changeEmail("  Bob@Example.ORG ")
        assertEquals("bob@example.org", user.email)
    }

    @Test
    fun `blank email is rejected on construction and change`() {
        assertFailsWith<DomainValidationException> { Employee("   ", "Alice", "hash") }
        assertFailsWith<DomainValidationException> { Employee("", "Alice", "hash") }
        val user = employee()
        assertFailsWith<DomainValidationException> { user.changeEmail(" ") }
        assertEquals("alice@example.com", user.email)
    }

    @Test
    fun `blank full name is rejected on construction and rename`() {
        assertFailsWith<DomainValidationException> { Employee("a@b.com", "  ", "hash") }
        val user = employee()
        assertFailsWith<DomainValidationException> { user.rename("") }
        assertEquals("Alice", user.fullName)
        user.rename("  Alice B  ")
        assertEquals("Alice B", user.fullName)
    }

    @Test
    fun `factory hashes the password and verification works`() {
        val user = employee("correct horse")
        assertTrue(user.passwordMatches(encoder, "correct horse"))
        assertFalse(user.passwordMatches(encoder, "wrong horse"))
    }

    @Test
    fun `factory rejects blank raw password`() {
        assertFailsWith<DomainValidationException> { factory.createEmployee("a@b.com", "A", "  ") }
    }

    @Test
    fun `toString exposes neither raw password nor hash`() {
        val raw = "very-secret-raw-password"
        val user = employee(raw)
        val hash = encoder.encode(raw)!!
        // Extract the stored hash through reflection to check the real value is not printed.
        val field = User::class.java.getDeclaredField("passwordHash").apply { isAccessible = true }
        val storedHash = field.get(user) as String
        val text = user.toString()
        assertFalse(text.contains(raw))
        assertFalse(text.contains(storedHash))
        assertFalse(text.contains("{bcrypt}"))
        assertNotEquals(raw, storedHash)
        assertTrue(hash.isNotEmpty())
    }

    @Test
    fun `changePassword with correct current password updates it`() {
        val user = employee("old-pass")
        user.changePassword(encoder, "old-pass", "new-pass")
        assertFalse(user.passwordMatches(encoder, "old-pass"))
        assertTrue(user.passwordMatches(encoder, "new-pass"))
    }

    @Test
    fun `changePassword with wrong current password throws and leaves password unchanged`() {
        val user = employee("old-pass")
        assertFailsWith<IncorrectPasswordException> { user.changePassword(encoder, "nope", "new-pass") }
        assertTrue(user.passwordMatches(encoder, "old-pass"))
        assertFalse(user.passwordMatches(encoder, "new-pass"))
    }

    @Test
    fun `changePassword rejects blank new password and keeps the old one`() {
        val user = employee("old-pass")
        assertFailsWith<DomainValidationException> { user.changePassword(encoder, "old-pass", " ") }
        assertTrue(user.passwordMatches(encoder, "old-pass"))
    }

    @Test
    fun `resetPassword works without current password`() {
        val user = employee("old-pass")
        user.resetPassword(encoder, "reset-pass")
        assertFalse(user.passwordMatches(encoder, "old-pass"))
        assertTrue(user.passwordMatches(encoder, "reset-pass"))
    }

    @Test
    fun `resetPassword rejects blank new password`() {
        val user = employee("old-pass")
        assertFailsWith<DomainValidationException> { user.resetPassword(encoder, "") }
        assertFailsWith<DomainValidationException> { user.resetPassword(encoder, "   ") }
        assertTrue(user.passwordMatches(encoder, "old-pass"))
    }

    @Test
    fun `deactivate and activate toggle active`() {
        val user = employee()
        assertTrue(user.active)
        user.deactivate()
        assertFalse(user.active)
        user.activate()
        assertTrue(user.active)
    }

    @Test
    fun `each subclass reports its role`() {
        assertEquals(Role.EMPLOYEE, Employee("e@x.com", "E", "h").role)
        assertEquals(Role.TECHNICIAN, Technician("t@x.com", "T", "h").role)
        assertEquals(Role.ADMIN, Administrator("a@x.com", "A", "h").role)
    }

    @Test
    fun `factory create returns the right subclass for each role`() {
        val employee = factory.create(Role.EMPLOYEE, "e@x.com", "E", "pw")
        val technician = factory.create(Role.TECHNICIAN, "t@x.com", "T", "pw")
        val admin = factory.create(Role.ADMIN, "a@x.com", "A", "pw")
        assertIs<Employee>(employee)
        assertIs<Technician>(technician)
        assertIs<Administrator>(admin)
        assertEquals(Role.EMPLOYEE, employee.role)
        assertEquals(Role.TECHNICIAN, technician.role)
        assertEquals(Role.ADMIN, admin.role)
        assertTrue(admin.passwordMatches(encoder, "pw"))
    }

    @Test
    fun `employee department is trimmed and blank becomes null`() {
        assertEquals("IT", Employee("e@x.com", "E", "h", "  IT ").department)
        assertEquals(null, Employee("e@x.com", "E", "h", "   ").department)
        val e = Employee("e@x.com", "E", "h", "IT")
        e.changeDepartment(" ")
        assertEquals(null, e.department)
    }

    @Test
    fun `technician skills are uppercased and trimmed`() {
        val tech = Technician("t@x.com", "T", "h", skills = setOf(" network ", "Email"))
        assertEquals(setOf("NETWORK", "EMAIL"), tech.skills)
        assertTrue(tech.addSkill("  vpn "))
        assertTrue("VPN" in tech.skills)
    }

    @Test
    fun `hasSkill is case insensitive`() {
        val tech = Technician("t@x.com", "T", "h", skills = setOf("network"))
        assertTrue(tech.hasSkill("NETWORK"))
        assertTrue(tech.hasSkill("network"))
        assertTrue(tech.hasSkill(" Network "))
        assertFalse(tech.hasSkill("email"))
    }

    @Test
    fun `skills getter returns a snapshot unaffected by later changes`() {
        val tech = Technician("t@x.com", "T", "h", skills = setOf("network"))
        val snapshot = tech.skills
        tech.addSkill("email")
        assertEquals(setOf("NETWORK"), snapshot)
        assertEquals(setOf("NETWORK", "EMAIL"), tech.skills)
        tech.removeSkill("network")
        assertEquals(setOf("NETWORK"), snapshot)
    }

    @Test
    fun `addSkill and removeSkill return whether the set changed`() {
        val tech = Technician("t@x.com", "T", "h")
        assertTrue(tech.addSkill("net"))
        assertFalse(tech.addSkill("NET"))
        assertTrue(tech.removeSkill("Net"))
        assertFalse(tech.removeSkill("net"))
        assertTrue(tech.skills.isEmpty())
    }

    @Test
    fun `blank skill is rejected`() {
        val tech = Technician("t@x.com", "T", "h")
        assertFailsWith<DomainValidationException> { tech.addSkill("  ") }
        assertFailsWith<DomainValidationException> { tech.removeSkill("") }
        assertFailsWith<DomainValidationException> { tech.hasSkill(" ") }
        assertFailsWith<DomainValidationException> { Technician("t@x.com", "T", "h", skills = setOf("ok", " ")) }
    }

    @Test
    fun `capacity below one is rejected in constructor and changeCapacity`() {
        assertFailsWith<DomainValidationException> { Technician("t@x.com", "T", "h", maxActiveIncidents = 0) }
        assertFailsWith<DomainValidationException> { Technician("t@x.com", "T", "h", maxActiveIncidents = -3) }
        val tech = Technician("t@x.com", "T", "h", maxActiveIncidents = 2)
        assertFailsWith<DomainValidationException> { tech.changeCapacity(0) }
        assertEquals(2, tech.maxActiveIncidents)
        tech.changeCapacity(1)
        assertEquals(1, tech.maxActiveIncidents)
    }

    @Test
    fun `technician availability and level can change`() {
        val tech = Technician("t@x.com", "T", "h")
        assertTrue(tech.available)
        assertEquals(TechnicianLevel.L1, tech.level)
        tech.markUnavailable()
        assertFalse(tech.available)
        tech.markAvailable()
        assertTrue(tech.available)
        tech.changeLevel(TechnicianLevel.TEAM_LEAD)
        assertEquals(TechnicianLevel.TEAM_LEAD, tech.level)
    }

    @Test
    fun `unsaved instances are not equal but equal themselves with stable hashCode`() {
        val a = Employee("same@x.com", "A", "h")
        val b = Employee("same@x.com", "A", "h")
        assertNotEquals(a, b)
        assertEquals(a, a)
        assertEquals(a.hashCode(), a.hashCode())
        val before = a.hashCode()
        a.changeEmail("other@x.com")
        a.deactivate()
        assertEquals(before, a.hashCode())
        assertFalse(a.equals(null))
        assertFalse(a.equals("string"))
    }

    @Test
    fun `replaceSkills swaps the whole set normalizing codes and is atomic on bad input`() {
        val tech = Technician("t@x.com", "T", "h", skills = setOf("net", "vpn"))

        tech.replaceSkills(setOf(" vpn ", "db"))
        assertEquals(setOf("VPN", "DB"), tech.skills)

        assertFailsWith<DomainValidationException> { tech.replaceSkills(setOf("ok", " ")) }
        assertEquals(setOf("VPN", "DB"), tech.skills)

        tech.replaceSkills(emptySet())
        assertTrue(tech.skills.isEmpty())
    }

    @Test
    fun `factory create applies the shared technician defaults and rejects inapplicable extras`() {
        val tech = factory.create(Role.TECHNICIAN, "t@x.com", "T", "password1") as Technician
        assertEquals(Technician.DEFAULT_LEVEL, tech.level)
        assertEquals(Technician.DEFAULT_MAX_ACTIVE_INCIDENTS, tech.maxActiveIncidents)

        assertFailsWith<DomainValidationException> { factory.create(Role.EMPLOYEE, "e@x.com", "E", "password1", level = TechnicianLevel.L2) }
        assertFailsWith<DomainValidationException> { factory.create(Role.ADMIN, "a@x.com", "A", "password1", department = "IT") }
    }

    @Test
    fun `over long passwords are rejected by the factory and resetPassword`() {
        assertFailsWith<DomainValidationException> { factory.createEmployee("a@b.com", "A", "a".repeat(73)) }
        assertFailsWith<DomainValidationException> { employee().resetPassword(encoder, "a".repeat(73)) }
    }

    @Test
    fun `created at is truncated to microseconds`() {
        assertEquals(0, employee().createdAt.nano % 1000)
    }
}
