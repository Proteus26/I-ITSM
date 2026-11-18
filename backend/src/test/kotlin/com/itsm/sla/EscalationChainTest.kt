package com.itsm.sla

import com.itsm.events.IncidentEscalated
import com.itsm.incident.Impact
import com.itsm.incident.Incident
import com.itsm.incident.Priority
import com.itsm.incident.Urgency
import com.itsm.sla.escalation.AdminEscalationHandler
import com.itsm.sla.escalation.EscalationChain
import com.itsm.sla.escalation.EscalationContext
import com.itsm.sla.escalation.ReassignEscalationHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

class EscalationChainTest {
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published += it }
    private val chain = EscalationChain(listOf(AdminEscalationHandler(publisher), ReassignEscalationHandler(publisher)))

    private fun incident(priority: Priority): Incident {
        val incident = Incident("INC-1", "t", "d", 1L, Impact.TEAM, Urgency.LOW, createdAt = Instant.now())
        org.springframework.test.util.ReflectionTestUtils.setField(incident, "id", 1L) // normally set by the database
        incident.applyPriority(priority, 1.0, Instant.now())
        return incident
    }

    private fun levels() = published.filterIsInstance<IncidentEscalated>().map { it.level }

    @Test
    fun `resolution breach of P1 and P2 escalates to ADMIN`() {
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
    fun `unacknowledged response breach asks for a reassignment`() {
        assertThat(chain.escalate(EscalationContext(incident(Priority.P4), SlaEventType.RESPONSE_BREACH))).isTrue()
        assertThat(levels()).containsExactly("REASSIGN_REQUESTED")
    }
}
