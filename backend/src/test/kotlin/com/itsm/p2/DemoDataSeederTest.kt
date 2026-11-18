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

    private fun count(sql: String) = jdbc.queryForObject(sql, Int::class.javaObjectType)!!

    @Test
    fun `demo users and incidents are created through the real services`() {
        seeder.run(DefaultApplicationArguments()) // the startup run may have been cleaned up by another test; running again is idempotent
        assertEquals(8, count("select count(*) from users where id in $demoUsers"))
        assertEquals(3, count("select count(*) from users where id in $demoUsers and role = 'EMPLOYEE'"))
        assertEquals(3, count("select count(distinct department) from users where id in $demoUsers and role = 'EMPLOYEE'"))
        // every category is covered by some technician's skills
        assertEquals(9, count("select count(distinct category_code) from technician_skills where technician_id in $demoUsers"))
        val statuses = jdbc.queryForList(
            "select status from incidents where reporter_id in $demoUsers", String::class.java,
        )
        for (wanted in listOf("NEW", "ASSIGNED", "IN_PROGRESS", "PENDING_INFO", "RESOLVED", "CLOSED")) {
            assertTrue(wanted in statuses, "$wanted missing in $statuses")
        }
        assertTrue(count("select count(*) from incidents where status = 'CLOSED' and feedback_rating is not null and reporter_id in $demoUsers") >= 2)
        // the payment cluster: at least three incidents of the Payment service reported within the last hour
        assertTrue(
            count(
                "select count(*) from incidents i join it_services s on s.id = i.service_id where s.code = 'PAYMENT' " +
                    "and i.reporter_id in $demoUsers and i.created_at > now() - interval '1 hour'",
            ) >= 3,
        )
        // past-due incidents were recorded as SLA breaches by the monitor, at-risk ones as warnings
        assertTrue(count("select count(distinct incident_id) from sla_events where type like '%BREACH' and incident_id in (select id from incidents where reporter_id in $demoUsers)") >= 1)
        assertTrue(count("select count(*) from sla_events where type = 'WARNING' and incident_id in (select id from incidents where reporter_id in $demoUsers)") >= 1)
    }

    @Test
    fun `a second run completes missing items and creates no duplicates`() {
        seeder.run(DefaultApplicationArguments())
        val employee = "(select id from users where email = 'employee@itsm.local')"
        val total = count("select count(*) from incidents where reporter_id in $demoUsers")
        val victim = "(select id from incidents where reporter_id = $employee and title = 'Excel crashes on large files')"
        jdbc.update("delete from notifications where incident_id = $victim")
        jdbc.update("delete from audit_log where incident_id = $victim")
        jdbc.update("delete from incidents where id = $victim")
        assertEquals(total - 1, count("select count(*) from incidents where reporter_id in $demoUsers"))

        seeder.run(DefaultApplicationArguments())

        assertEquals(total, count("select count(*) from incidents where reporter_id in $demoUsers"))
        assertEquals(8, count("select count(*) from users where id in $demoUsers"))
    }
}
