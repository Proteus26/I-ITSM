package com.itsm.sla

import com.itsm.events.IncidentAssigned
import com.itsm.events.IncidentEscalated
import com.itsm.events.StatusChanged
import com.itsm.incident.Actor
import com.itsm.incident.Impact
import com.itsm.incident.Incident
import com.itsm.incident.IncidentStatus
import com.itsm.incident.Priority
import com.itsm.incident.Urgency
import com.itsm.category.Category
import com.itsm.sla.escalation.AdminEscalationHandler
import com.itsm.sla.escalation.EscalationChain
import com.itsm.sla.escalation.EscalationContext
import com.itsm.sla.escalation.TeamLeadEscalationHandler
import com.itsm.sla.escalation.TeamLeadSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class EscalationChainTest {
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published += it }
    private var lead: Long? = null
    private val source = TeamLeadSource { lead }
    private val chain = EscalationChain(listOf(TeamLeadEscalationHandler(source, publisher), AdminEscalationHandler(publisher)))
    private val now = Instant.now()

    private fun incident(priority: Priority, assigned: Boolean = true): Incident {
        val incident = Incident("INC-1", "t", "d", 1L, Impact.TEAM, Urgency.LOW, createdAt = now)
        ReflectionTestUtils.setField(incident, "id", 1L) // normally set by the database
        incident.applyPriority(priority, 1.0, now)
        incident.classify(Category("SOFTWARE", "Software", null), Actor.SYSTEM, now)
        if (assigned) incident.assign(5L, Actor.SYSTEM, now)
        return incident
    }

    private fun levels() = published.filterIsInstance<IncidentEscalated>().map { it.level }

    @Test
    fun `resolution breach of P1 and P2 escalates to ADMIN, even when a team lead exists`() {
        lead = 9L
        assertThat(chain.escalate(EscalationContext(incident(Priority.P1), SlaEventType.RESOLUTION_BREACH))).isTrue()
        assertThat(chain.escalate(EscalationContext(incident(Priority.P2), SlaEventType.RESOLUTION_BREACH))).isTrue()
        assertThat(levels()).containsExactly("ADMIN", "ADMIN")
    }

    @Test
    fun `resolution breach of P3 is not escalated`() {
        assertThat(chain.escalate(EscalationContext(incident(Priority.P3), SlaEventType.RESOLUTION_BREACH))).isFalse()
        assertThat(published).isEmpty()
    }

    @Test
    fun `unacknowledged response breach reassigns to the team lead`() {
        lead = 9L
        val incident = incident(Priority.P4)
        assertThat(chain.escalate(EscalationContext(incident, SlaEventType.RESPONSE_BREACH, now))).isTrue()

        assertThat(incident.assigneeId).isEqualTo(9L)
        assertThat(incident.status).isEqualTo(IncidentStatus.ASSIGNED)
        val escalated = published.filterIsInstance<IncidentEscalated>().single()
        assertThat(escalated.level).isEqualTo("TEAM_LEAD")
        assertThat(escalated.escalatedToId).isEqualTo(9L)
        assertThat(published.filterIsInstance<IncidentAssigned>().single().strategy).isEqualTo("escalation")
        assertThat(published.filterIsInstance<StatusChanged>()).isEmpty() // ASSIGNED -> ASSIGNED
    }

    @Test
    fun `a classified incident reassigned by escalation also publishes the status change`() {
        lead = 9L
        val incident = incident(Priority.P3, assigned = false)
        chain.escalate(EscalationContext(incident, SlaEventType.RESPONSE_BREACH, now))
        assertThat(incident.status).isEqualTo(IncidentStatus.ASSIGNED)
        assertThat(published.filterIsInstance<StatusChanged>().single().to).isEqualTo(IncidentStatus.ASSIGNED)
    }

    @Test
    fun `without a team lead the response breach goes to the administrators and nothing is reassigned`() {
        lead = null
        val incident = incident(Priority.P4)
        assertThat(chain.escalate(EscalationContext(incident, SlaEventType.RESPONSE_BREACH, now))).isTrue()
        assertThat(levels()).containsExactly("ADMIN")
        assertThat(incident.assigneeId).isEqualTo(5L)
        assertThat(published.filterIsInstance<IncidentAssigned>()).isEmpty()
    }

    @Test
    fun `an acknowledged incident is not escalated on response breach`() {
        lead = 9L
        val incident = incident(Priority.P4)
        incident.transitionTo(IncidentStatus.ACKNOWLEDGED, Actor(5L, com.itsm.user.Role.TECHNICIAN), now)
        assertThat(chain.escalate(EscalationContext(incident, SlaEventType.RESPONSE_BREACH, now))).isFalse()
        assertThat(published).isEmpty()
    }
}
