package com.itsm.notification

import com.itsm.audit.AuditLogRepository
import com.itsm.events.IncidentAssigned
import com.itsm.incident.IncidentStatus
import com.itsm.support.RecordingEmailGateway
import com.itsm.support.TestData
import com.itsm.support.uid
import com.itsm.user.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import com.itsm.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(properties = ["itsm.mail.async=false"])   // send email on the test thread
@Import(TestcontainersConfiguration::class)
class NotificationFlowTest @Autowired constructor(
    private val data: TestData,
    private val publisher: ApplicationEventPublisher,
    private val txManager: PlatformTransactionManager,
    private val notifications: NotificationRepository,
    private val audit: AuditLogRepository,
    private val emails: RecordingEmailGateway,
) {
    @AfterEach
    fun cleanUp() = data.reset()

    @BeforeEach
    fun reset() {
        data.reset()
        emails.sent.clear()
    }

    @Test
    fun `IncidentAssigned notifies the technician and the reporter on both channels`() {
        val admin = data.user("admin", Role.ADMIN)
        val reporter = data.user("emp1", Role.EMPLOYEE)
        val tech = data.user("tech1", Role.TECHNICIAN)
        val bystander = data.user("emp2", Role.EMPLOYEE)
        val incident = data.incident("INC-000020", "VPN down", reporter, status = IncidentStatus.ASSIGNED, assignee = tech)

        TransactionTemplate(txManager).executeWithoutResult {
            publisher.publishEvent(IncidentAssigned(incident.id!!, admin.uid, tech.uid, "SKILL_BASED"))
        }

        // In-app channel: exactly the technician and the reporter. Not the admin who did it, not a bystander.
        val stored = notifications.findAll().filter { it.incidentId == incident.id }
        assertThat(stored.map { it.recipientId }).containsExactlyInAnyOrder(reporter.uid, tech.uid)
        assertThat(stored.first { it.recipientId == tech.uid }.title).contains("assigned to you")
        assertThat(stored.map { it.recipientId }).doesNotContain(admin.uid, bystander.uid)

        // Email channel: the same two people.
        assertThat(emails.sent.map { it.to }).containsExactlyInAnyOrder(reporter.email, tech.email)
    }

    @Test
    fun `nothing is sent when the transaction rolls back`() {
        val admin = data.user("admin", Role.ADMIN)
        val reporter = data.user("emp1", Role.EMPLOYEE)
        val tech = data.user("tech1", Role.TECHNICIAN)
        val incident = data.incident("INC-000021", "VPN down", reporter, status = IncidentStatus.ASSIGNED, assignee = tech)

        assertThrows<IllegalStateException> {
            TransactionTemplate(txManager).executeWithoutResult {
                publisher.publishEvent(IncidentAssigned(incident.id!!, admin.uid, tech.uid))
                throw IllegalStateException("something failed after the event was published")
            }
        }

        assertThat(notifications.findAll().filter { it.incidentId == incident.id }).isEmpty()
        assertThat(emails.sent).isEmpty()
        assertThat(audit.findByIncidentIdOrderByOccurredAtAscIdAsc(incident.id!!)).isEmpty()   // the audit row rolled back together with the action
    }
}
