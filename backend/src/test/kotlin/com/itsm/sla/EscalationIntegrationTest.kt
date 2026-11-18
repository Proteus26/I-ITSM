package com.itsm.sla

import com.itsm.TestcontainersConfiguration
import com.itsm.events.IncidentAssigned
import com.itsm.events.IncidentEscalated
import com.itsm.incident.IncidentRepository
import com.itsm.incident.IncidentStatus
import com.itsm.incident.Priority
import com.itsm.support.TestData
import com.itsm.support.uid
import com.itsm.user.Role
import com.itsm.user.Technician
import com.itsm.user.TechnicianLevel
import com.itsm.user.UserRepository
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

/** Escalation to a team lead through the real monitor and database. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class EscalationIntegrationTest @Autowired constructor(
    private val monitor: SlaMonitor,
    private val sla: SlaService,
    private val data: TestData,
    private val incidents: IncidentRepository,
    private val users: UserRepository,
    private val jdbc: JdbcTemplate,
) {
    @Autowired
    private lateinit var events: ApplicationEvents

    private val t0: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val extraUserIds = mutableListOf<Long>()

    @BeforeEach
    fun setUp() = data.reset()

    @AfterEach
    fun cleanUp() {
        data.reset()
        extraUserIds.forEach {
            jdbc.update("delete from notifications where recipient_id = ?", it)
            jdbc.update("delete from audit_log where actor_id = ?", it)
            jdbc.update("delete from technician_skills where technician_id = ?", it)
            jdbc.update("delete from users where id = ?", it)
        }
        extraUserIds.clear()
    }

    private fun teamLead(name: String, skills: Set<String> = emptySet(), available: Boolean = true): Technician {
        val lead = Technician("$name@example.test", name, "dummy-hash", TechnicianLevel.TEAM_LEAD, skills = skills)
        if (!available) lead.markUnavailable()
        return users.save(lead).also { extraUserIds += it.uid }
    }

    private fun unacknowledged(): Long {
        val tech = data.user("tech1", Role.TECHNICIAN)
        val reporter = data.user("emp1", Role.EMPLOYEE)
        val incident = data.incident("INC-900101", "Stuck", reporter, status = IncidentStatus.ASSIGNED, priority = Priority.P3, category = "NETWORK", assignee = tech, createdAt = t0)
        val d = sla.computeDeadlines(null, Priority.P3, t0)
        incident.applySla(d.responseDueAt, d.resolutionDueAt, t0)
        return requireNotNull(incidents.saveAndFlush(incident).id)
    }

    @Test
    fun `an unacknowledged breach is reassigned to the team lead with the matching skill and recorded once`() {
        val id = unacknowledged()
        val other = teamLead("leadother")
        val skilled = teamLead("leadskilled", setOf("NETWORK"))
        teamLead("leadaway", setOf("NETWORK"), available = false)

        assertThat(monitor.runOnce(t0.plus(61, ChronoUnit.MINUTES))).isEqualTo(1)
        assertThat(monitor.runOnce(t0.plus(70, ChronoUnit.MINUTES))).isZero() // idempotent

        val incident = incidents.findById(id).orElseThrow()
        assertThat(incident.assigneeId).isEqualTo(skilled.uid)
        assertThat(incident.assigneeId).isNotEqualTo(other.uid)
        assertThat(incident.status).isEqualTo(IncidentStatus.ASSIGNED)
        val escalated = events.stream(IncidentEscalated::class.java).toList().single { it.incidentId == id }
        assertThat(escalated.level).isEqualTo("TEAM_LEAD")
        assertThat(escalated.escalatedToId).isEqualTo(skilled.uid)
        assertThat(events.stream(IncidentAssigned::class.java).toList().single { it.incidentId == id }.strategy).isEqualTo("escalation")
        assertThat(jdbc.queryForObject("select count(*) from audit_log where incident_id = ? and action = 'INCIDENT_ESCALATED'", Int::class.java, id)).isEqualTo(1)
    }

    @Test
    fun `without an available team lead the administrators are told and the assignee stays`() {
        val id = unacknowledged()
        teamLead("leadaway", available = false)
        val before = incidents.findById(id).orElseThrow().assigneeId

        assertThat(monitor.runOnce(t0.plus(61, ChronoUnit.MINUTES))).isEqualTo(1)

        assertThat(incidents.findById(id).orElseThrow().assigneeId).isEqualTo(before)
        assertThat(events.stream(IncidentEscalated::class.java).toList().filter { it.incidentId == id }.map { it.level }).containsExactly("ADMIN")
    }
}
