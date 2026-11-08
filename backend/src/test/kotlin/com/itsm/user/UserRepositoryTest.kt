package com.itsm.user

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import com.itsm.TestcontainersConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class UserRepositoryTest @Autowired constructor(
    private val users: UserRepository,
    private val technicians: TechnicianRepository,
    private val employees: EmployeeRepository,
    private val em: EntityManager,
    private val jdbc: JdbcTemplate,
) {
    private val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
    private val factory = UserFactory(encoder)

    private fun flushAndClear() {
        em.flush()
        em.clear()
    }

    private fun role(id: Long): String =
        jdbc.queryForObject("select role from users where id = ?", String::class.java, id)!!

    @Test
    fun `employee technician and administrator load polymorphically with fields intact`() {
        val emp = users.save(factory.createEmployee("emp@x.com", "Emp", "pw", "Finance"))
        val tech = users.save(
            factory.createTechnician(
                "tech@x.com", "Tech", "pw", TechnicianLevel.L2, 7, setOf("network", "email"),
            ).also { it.markUnavailable() },
        )
        val admin = users.save(factory.createAdministrator("admin@x.com", "Admin", "pw"))
        flushAndClear()

        val loadedEmp = users.findById(emp.id!!).orElseThrow()
        assertIs<Employee>(loadedEmp)
        assertEquals("Finance", loadedEmp.department)
        assertEquals(Role.EMPLOYEE, loadedEmp.role)

        val loadedTech = users.findByEmail("tech@x.com")
        assertIs<Technician>(loadedTech)
        assertEquals(TechnicianLevel.L2, loadedTech.level)
        assertEquals(7, loadedTech.maxActiveIncidents)
        assertFalse(loadedTech.available)
        assertEquals(setOf("NETWORK", "EMAIL"), loadedTech.skills)
        assertEquals(Role.TECHNICIAN, loadedTech.role)
        assertEquals(tech.id, loadedTech.id)

        val loadedAdmin = users.findById(admin.id!!).orElseThrow()
        assertIs<Administrator>(loadedAdmin)
        assertEquals(Role.ADMIN, loadedAdmin.role)
        assertEquals("Admin", loadedAdmin.fullName)
        assertTrue(loadedAdmin.active)
        assertNotNull(loadedAdmin.createdAt)
        assertEquals(loadedAdmin, users.findById(admin.id!!).orElseThrow())
    }

    @Test
    fun `existsByEmail reports presence`() {
        users.save(factory.createEmployee("here@x.com", "H", "pw"))
        flushAndClear()
        assertTrue(users.existsByEmail("here@x.com"))
        assertFalse(users.existsByEmail("gone@x.com"))
        assertNull(users.findByEmail("gone@x.com"))
    }

    @Test
    fun `duplicate normalized email violates the unique constraint`() {
        users.save(factory.createEmployee("alice@example.com", "Alice", "pw"))
        em.flush()
        // IDENTITY generation inserts eagerly, so the violation may surface on save or on flush.
        assertFailsWith<DataIntegrityViolationException> {
            users.save(factory.createEmployee("Alice@Example.com ", "Alice 2", "pw"))
            em.flush()
        }
    }

    @Test
    fun `discriminator column stores role names`() {
        val e = users.save(factory.createEmployee("e@x.com", "E", "pw"))
        val t = users.save(factory.createTechnician("t@x.com", "T", "pw"))
        val a = users.save(factory.createAdministrator("a@x.com", "A", "pw"))
        flushAndClear()
        assertEquals("EMPLOYEE", role(e.id!!))
        assertEquals("TECHNICIAN", role(t.id!!))
        assertEquals("ADMIN", role(a.id!!))
    }

    @Test
    fun `password hash is stored hashed and still verifies after reload`() {
        val saved = users.save(factory.createEmployee("pw@x.com", "P", "raw-secret"))
        flushAndClear()
        val stored = jdbc.queryForObject(
            "select password_hash from users where id = ?", String::class.java, saved.id!!,
        )!!
        assertNotEquals("raw-secret", stored)
        assertFalse(stored.contains("raw-secret"))
        assertTrue(stored.startsWith("{bcrypt}"))
        val loaded = users.findById(saved.id!!).orElseThrow()
        assertTrue(loaded.passwordMatches(encoder, "raw-secret"))
        assertFalse(loaded.passwordMatches(encoder, "other"))
    }

    @Test
    fun `changed password persists`() {
        val saved = users.save(factory.createEmployee("pw@x.com", "P", "old"))
        flushAndClear()
        users.findById(saved.id!!).orElseThrow().changePassword(encoder, "old", "new")
        flushAndClear()
        val loaded = users.findById(saved.id!!).orElseThrow()
        assertTrue(loaded.passwordMatches(encoder, "new"))
        assertFalse(loaded.passwordMatches(encoder, "old"))
    }

    @Test
    fun `findAvailableWithSkill returns only active available technicians with the skill`() {
        val match = technicians.save(factory.createTechnician("match@x.com", "M", "pw", skills = setOf("network", "email")))
        technicians.save(
            factory.createTechnician("unavail@x.com", "U", "pw", skills = setOf("network")).also { it.markUnavailable() },
        )
        technicians.save(
            factory.createTechnician("inactive@x.com", "I", "pw", skills = setOf("network")).also { it.deactivate() },
        )
        technicians.save(factory.createTechnician("noskill@x.com", "N", "pw", skills = setOf("email")))
        flushAndClear()

        val result = technicians.findAvailableWithSkill("NETWORK")
        assertEquals(listOf(match.id), result.map { it.id })
        assertEquals(setOf("NETWORK", "EMAIL"), result.single().skills)
    }

    @Test
    fun `findByActiveTrueAndAvailableTrue excludes deactivated and unavailable`() {
        val ok = technicians.save(factory.createTechnician("ok@x.com", "Ok", "pw"))
        technicians.save(factory.createTechnician("off@x.com", "Off", "pw").also { it.markUnavailable() })
        technicians.save(factory.createTechnician("dead@x.com", "Dead", "pw").also { it.deactivate() })
        flushAndClear()
        assertEquals(listOf(ok.id), technicians.findByActiveTrueAndAvailableTrue().map { it.id })
    }

    @Test
    fun `technician repository does not return other roles`() {
        employees.save(factory.createEmployee("e@x.com", "E", "pw"))
        users.save(factory.createAdministrator("a@x.com", "A", "pw"))
        val t = technicians.save(factory.createTechnician("t@x.com", "T", "pw"))
        flushAndClear()
        assertEquals(listOf(t.id), technicians.findAll().map { it.id })
        assertEquals(1, employees.findAll().size)
        assertEquals(3, users.findAll().size)
    }

    @Test
    fun `skill removal and addition persist`() {
        val saved = technicians.save(factory.createTechnician("t@x.com", "T", "pw", skills = setOf("network", "email")))
        flushAndClear()

        val loaded = technicians.findById(saved.id!!).orElseThrow()
        assertTrue(loaded.removeSkill("network"))
        loaded.addSkill("vpn")
        technicians.save(loaded)
        flushAndClear()

        assertEquals(setOf("EMAIL", "VPN"), technicians.findById(saved.id!!).orElseThrow().skills)
        val rows = jdbc.queryForObject(
            "select count(*) from technician_skills where technician_id = ?", Int::class.java, saved.id!!,
        )
        assertEquals(2, rows)
    }

    @Test
    fun `deleting a technician removes skill rows`() {
        val saved = technicians.save(factory.createTechnician("t@x.com", "T", "pw", skills = setOf("network")))
        flushAndClear()
        technicians.deleteById(saved.id!!)
        flushAndClear()
        val rows = jdbc.queryForObject(
            "select count(*) from technician_skills where technician_id = ?", Int::class.java, saved.id!!,
        )
        assertEquals(0, rows)
    }

    @Test
    fun `check constraint rejects an invalid role`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "insert into users (role, email, full_name, password_hash, created_at) " +
                    "values ('HACKER', 'h@x.com', 'H', 'x', now())",
            )
        }
    }

    @Test
    fun `check constraint requires technician columns for technician rows`() {
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "insert into users (role, email, full_name, password_hash, created_at) " +
                    "values ('TECHNICIAN', 'bad@x.com', 'B', 'x', now())",
            )
        }
    }
}
