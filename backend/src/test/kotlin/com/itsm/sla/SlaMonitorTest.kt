package com.itsm.sla

import com.itsm.TestcontainersConfiguration
import com.itsm.events.IncidentEscalated
import com.itsm.events.SlaBreached
import com.itsm.events.SlaWarningRaised
import com.itsm.incident.IncidentRepository
import com.itsm.incident.IncidentStatus
import com.itsm.incident.Priority
import com.itsm.support.TestData
import com.itsm.user.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.time.Instant
import java.time.temporal.ChronoUnit

/** SlaMonitor against a real database: deadlines, idempotency, events and escalation. The scheduler is off in tests. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class SlaMonitorTest @Autowired constructor(
    private val monitor: SlaMonitor,
    private val sla: SlaService,
    private val data: TestData,
    private val incidents: IncidentRepository,
    private val policies: SlaPolicyRepository,
    private val jdbc: JdbcTemplate,
) {
    @Autowired
    private lateinit var events: ApplicationEvents

    private val t0: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private var serviceId: Long? = null
    private val users = mutableMapOf<String, com.itsm.user.User>()

    @BeforeEach
    fun setUp() {
        data.reset()
        users.clear()
    }

    @AfterEach
    fun cleanUp() {
        data.reset()
        serviceId?.let { jdbc.update("delete from it_services where id = ?", it) } // its policies cascade
    }

    private fun minutes(n: Long) = t0.plus(n, ChronoUnit.MINUTES)

    private fun monitored(
        priority: Priority, status: IncidentStatus = IncidentStatus.ASSIGNED, service: Long? = null,
        number: String = "INC-900001", from: Instant = t0,
    ): Long {
        val tech = users.getOrPut("tech1") { data.user("tech1", Role.TECHNICIAN) }
        val reporter = users.getOrPut("emp1") { data.user("emp1", Role.EMPLOYEE) }
        val incident = data.incident(number, "Monitored", reporter, status = status, priority = priority, assignee = tech, createdAt = t0)
        val deadlines = sla.computeDeadlines(service, priority, from)
        incident.applySla(deadlines.responseDueAt, deadlines.resolutionDueAt, from)
        return requireNotNull(incidents.saveAndFlush(incident).id)
    }

    private fun types(incidentId: Long) =
        jdbc.queryForList("select type from sla_events where incident_id = ? order by id", String::class.java, incidentId)

    @Test
    fun `default policy deadlines`() {
        val d = sla.computeDeadlines(null, Priority.P1, t0)
        assertThat(d.responseDueAt).isEqualTo(minutes(5))
        assertThat(d.resolutionDueAt).isEqualTo(minutes(30))
        assertThat(sla.computeDeadlines(null, Priority.P2, t0).resolutionDueAt).isEqualTo(minutes(120))
        assertThat(sla.computeDeadlines(null, Priority.P3, t0).resolutionDueAt).isEqualTo(minutes(480))
        assertThat(sla.computeDeadlines(null, Priority.P4, t0).responseDueAt).isEqualTo(minutes(240))
    }

    @Test
    fun `a service specific policy overrides the default`() {
        val id = jdbc.queryForObject("insert into it_services (code, name) values ('TEST_SLA', 'Test SLA svc') returning id", Long::class.java)!!
        serviceId = id
        val policy = policies.save(SlaPolicy(id, Priority.P1, 2, 10))

        val d = sla.computeDeadlines(id, Priority.P1, t0)
        assertThat(d.responseDueAt).isEqualTo(minutes(2))
        assertThat(d.resolutionDueAt).isEqualTo(minutes(10))
        assertThat(d.policyId).isEqualTo(policy.id)
        // another priority of the same service falls back to the default
        assertThat(sla.computeDeadlines(id, Priority.P2, t0).resolutionDueAt).isEqualTo(minutes(120))
    }

    @Test
    fun `warning then breach are each recorded once and published`() {
        val id = monitored(Priority.P3) // response 60 min, resolution 480 min

        assertThat(monitor.runOnce(minutes(30))).isZero() // 50 %
        assertThat(monitor.runOnce(minutes(50))).isEqualTo(1) // 83 % of the response window
        assertThat(monitor.runOnce(minutes(55))).isZero()
        assertThat(types(id)).containsExactly("WARNING")
        assertThat(events.stream(SlaWarningRaised::class.java).toList()).hasSize(1)
        assertThat(events.stream(SlaWarningRaised::class.java).findFirst().get().percentElapsed).isEqualTo(83)

        assertThat(monitor.runOnce(minutes(61))).isEqualTo(1)
        assertThat(monitor.runOnce(minutes(62))).isZero()
        assertThat(types(id)).containsExactly("WARNING", "RESPONSE_BREACH")
        assertThat(events.stream(SlaBreached::class.java).map { it.breachType }.toList()).containsExactly("RESPONSE_BREACH")
        assertThat(events.stream(IncidentEscalated::class.java).map { it.level }.toList()).containsExactly("ADMIN") // no team lead exists, so the chain falls through to the administrators
        assertThat(jdbc.queryForObject("select count(*) from audit_log where incident_id = ? and action = 'SLA_BREACHED'", Int::class.java, id)).isEqualTo(1)
    }

    @Test
    fun `an acknowledged incident only counts down the resolution deadline and P1 resolution breach escalates to admin`() {
        val id = monitored(Priority.P1, IncidentStatus.ACKNOWLEDGED) // response 5 min (acknowledged), resolution 30 min

        assertThat(monitor.runOnce(minutes(10))).isZero() // response deadline passed but acknowledged; resolution at 33 %
        assertThat(monitor.runOnce(minutes(24))).isEqualTo(1) // 80 % of 30 min
        assertThat(monitor.runOnce(minutes(31))).isEqualTo(1)
        assertThat(types(id)).containsExactly("WARNING", "RESOLUTION_BREACH")
        assertThat(events.stream(IncidentEscalated::class.java).map { it.level }.toList()).containsExactly("ADMIN")
    }

    @Test
    fun `a resolved incident is not monitored`() {
        val id = monitored(Priority.P1, IncidentStatus.RESOLVED)
        assertThat(monitor.runOnce(minutes(600))).isZero()
        assertThat(types(id)).isEmpty()
    }

    @Test
    fun `an incident without a policy does not stop another incident's breach from being recorded`() {
        val broken = monitored(Priority.P4, number = "INC-900002") // no default P4 policy once deleted below
        val healthy = monitored(Priority.P3, number = "INC-900003")
        val p4 = jdbc.queryForMap("select response_minutes, resolution_minutes, warning_threshold_pct from sla_policies where service_id is null and priority = 'P4'")
        jdbc.update("delete from sla_policies where service_id is null and priority = 'P4'")
        try {
            assertThat(monitor.runOnce(minutes(61))).isEqualTo(1) // only the P3 response breach
            assertThat(types(healthy)).containsExactly("RESPONSE_BREACH")
            assertThat(types(broken)).isEmpty()
        } finally {
            jdbc.update(
                "insert into sla_policies (service_id, priority, response_minutes, resolution_minutes, warning_threshold_pct) values (null, 'P4', ?, ?, ?)",
                p4["response_minutes"], p4["resolution_minutes"], p4["warning_threshold_pct"],
            )
        }
    }

    @Test
    fun `deadlines counted from a later start give no early warning`() {
        val id = monitored(Priority.P3, from = minutes(180)) // response due at +240, window starts at +180
        assertThat(monitor.runOnce(minutes(200))).isZero() // 33 % of the window, though 83 % of the time since creation
        assertThat(types(id)).isEmpty()
        assertThat(monitor.runOnce(minutes(230))).isEqualTo(1) // 83 % of the window
        assertThat(types(id)).containsExactly("WARNING")
    }
}
