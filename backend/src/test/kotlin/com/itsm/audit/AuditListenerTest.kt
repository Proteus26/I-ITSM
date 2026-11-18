package com.itsm.audit

import com.itsm.events.StatusChanged
import com.itsm.incident.IncidentStatus
import com.itsm.support.TestData
import com.itsm.support.uid
import com.itsm.user.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import com.itsm.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AuditListenerTest @Autowired constructor(
    private val data: TestData,
    private val publisher: ApplicationEventPublisher,
    private val txManager: PlatformTransactionManager,
    private val audit: AuditLogRepository,
) {
    @AfterEach
    fun cleanUp() = data.reset()

    @BeforeEach
    fun reset() = data.reset()

    @Test
    fun `a status change creates an audit row with the right before and after states`() {
        val tech = data.user("tech1", Role.TECHNICIAN)
        val reporter = data.user("emp1", Role.EMPLOYEE)
        val incident = data.incident("INC-000010", "Printer jammed", reporter, assignee = tech, status = IncidentStatus.ASSIGNED)

        TransactionTemplate(txManager).executeWithoutResult {
            publisher.publishEvent(StatusChanged(incident.id!!, tech.uid, IncidentStatus.ASSIGNED, IncidentStatus.ACKNOWLEDGED))
        }

        val rows = audit.findByIncidentIdOrderByOccurredAtAscIdAsc(incident.id!!)
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.action).isEqualTo("STATUS_CHANGED")
        assertThat(row.fromState).isEqualTo("ASSIGNED")
        assertThat(row.toState).isEqualTo("ACKNOWLEDGED")
        assertThat(row.actorId).isEqualTo(tech.uid)
        assertThat(row.description).contains("INC-000010").contains("ASSIGNED → ACKNOWLEDGED")
    }
}
