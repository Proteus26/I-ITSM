package com.itsm.p2

import com.itsm.TestcontainersConfiguration
import com.itsm.demo.DemoDataSeeder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The seeder runs at startup because this class turns `itsm.demo-data` on; it removes everything it created afterwards. */
@SpringBootTest(properties = ["itsm.demo-data=true"])
@Import(TestcontainersConfiguration::class)
class DemoDataSeederTest @Autowired constructor(private val jdbc: JdbcTemplate, private val seeder: DemoDataSeeder) {

    private val demoUsers = "(select id from users where email like '%@itsm.local' and email <> 'admin@itsm.local')"

    @AfterEach
    fun cleanUp() {
        val incidents = "(select id from incidents where reporter_id in $demoUsers)"
        jdbc.update("delete from notifications where incident_id in $incidents")
        jdbc.update("delete from audit_log where incident_id in $incidents")
        jdbc.update("delete from incidents where reporter_id in $demoUsers")
        jdbc.update("delete from notifications where recipient_id in $demoUsers")
        jdbc.update("delete from audit_log where actor_id in $demoUsers")
        jdbc.update("delete from technician_skills where technician_id in $demoUsers")
        jdbc.update("delete from users where id in $demoUsers")
    }

    @Test
    fun `demo users and incidents are created through the real services`() {
        seeder.run(DefaultApplicationArguments()) // the startup run may have been cleaned up by another test; running again is idempotent
        assertEquals(4, jdbc.queryForObject("select count(*) from users where id in $demoUsers", Int::class.javaObjectType))
        val statuses = jdbc.queryForList(
            "select status from incidents where reporter_id = (select id from users where email = 'employee@itsm.local')", String::class.java,
        )
        assertEquals(5, statuses.size)
        assertTrue("NEW" in statuses, statuses.toString())
        assertTrue(statuses.any { it != "NEW" })
    }

    @Test
    fun `a second run completes missing items and creates no duplicates`() {
        seeder.run(DefaultApplicationArguments())
        val employee = "(select id from users where email = 'employee@itsm.local')"
        val victim = "(select id from incidents where reporter_id = $employee and title = 'Excel crashes on large files')"
        jdbc.update("delete from notifications where incident_id = $victim")
        jdbc.update("delete from audit_log where incident_id = $victim")
        jdbc.update("delete from incidents where id = $victim")
        assertEquals(4, jdbc.queryForObject("select count(*) from incidents where reporter_id = $employee", Int::class.javaObjectType))

        seeder.run(DefaultApplicationArguments())

        assertEquals(5, jdbc.queryForObject("select count(*) from incidents where reporter_id = $employee", Int::class.javaObjectType))
        assertEquals(4, jdbc.queryForObject("select count(*) from users where id in $demoUsers", Int::class.javaObjectType))
    }
}
