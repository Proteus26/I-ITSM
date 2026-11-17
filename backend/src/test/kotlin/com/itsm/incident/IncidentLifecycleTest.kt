package com.itsm.incident

import com.itsm.auth.UnauthorizedOperationException
import com.itsm.common.exception.DomainValidationException
import com.itsm.incident.IncidentStatus.ACKNOWLEDGED
import com.itsm.incident.IncidentStatus.ASSIGNED
import com.itsm.incident.IncidentStatus.CANCELLED
import com.itsm.incident.IncidentStatus.CLASSIFIED
import com.itsm.incident.IncidentStatus.CLOSED
import com.itsm.incident.IncidentStatus.IN_PROGRESS
import com.itsm.incident.IncidentStatus.NEW
import com.itsm.incident.IncidentStatus.PENDING_INFO
import com.itsm.incident.IncidentStatus.RESOLVED
import com.itsm.incident.IncidentStatus.VERIFIED
import com.itsm.user.Role
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure domain tests: no Spring, no database. */
class IncidentLifecycleTest {

    private val t0 = Instant.parse("2026-11-15T10:00:00Z")
    private val reporter = Actor(1, Role.EMPLOYEE)
    private val otherEmployee = Actor(5, Role.EMPLOYEE)
    private val tech = Actor(2, Role.TECHNICIAN)
    private val otherTech = Actor(3, Role.TECHNICIAN)
    private val admin = Actor(9, Role.ADMIN)

    private fun newIncident() = Incident(
        number = "INC-000001",
        title = "Payment application unavailable",
        description = "Users cannot complete transactions",
        reporterId = 1,
        impact = Impact.ORGANISATION,
        urgency = Urgency.CRITICAL,
        affectedUsers = 120,
        createdAt = t0,
    )

    private fun assigned(): Incident = newIncident().apply {
        classify("APPLICATION", Actor.SYSTEM, t0)
        assign(2, Actor.SYSTEM, t0)
    }

    private fun inProgress(): Incident = assigned().apply {
        transitionTo(ACKNOWLEDGED, tech, t0)
        transitionTo(IN_PROGRESS, tech, t0)
    }

    private fun resolved(): Incident = inProgress().apply { resolve("Restarted the gateway", "Memory leak", tech, t0) }

    @Test
    fun `incident walks the whole lifecycle with valid transitions`() {
        val incident = newIncident()
        assertEquals(NEW, incident.status)

        incident.classify("application", Actor.SYSTEM, t0.plusSeconds(1))
        assertEquals(CLASSIFIED, incident.status)
        assertEquals("APPLICATION", incident.categoryCode)

        incident.assign(2, Actor.SYSTEM, t0.plusSeconds(2))
        assertEquals(ASSIGNED, incident.status)
        assertEquals(2L, incident.assigneeId)

        incident.transitionTo(ACKNOWLEDGED, tech, t0.plusSeconds(60))
        assertEquals(t0.plusSeconds(60), incident.acknowledgedAt)

        incident.transitionTo(IN_PROGRESS, tech, t0.plusSeconds(120))
        incident.resolve("Restarted the gateway", "Memory leak", tech, t0.plusSeconds(600))
        assertEquals(RESOLVED, incident.status)
        assertEquals(t0.plusSeconds(600), incident.resolvedAt)

        incident.transitionTo(VERIFIED, reporter, t0.plusSeconds(700))
        incident.transitionTo(CLOSED, admin, t0.plusSeconds(800))
        assertEquals(CLOSED, incident.status)
        assertEquals(t0.plusSeconds(800), incident.closedAt)
        assertEquals(t0.plusSeconds(800), incident.updatedAt)
    }

    @Test
    fun `NEW cannot go straight to CLOSED`() {
        val incident = newIncident()
        val ex = assertFailsWith<InvalidStateTransitionException> { incident.transitionTo(CLOSED, admin, t0) }
        assertEquals(NEW, ex.from)
        assertEquals(CLOSED, ex.to)
        assertEquals(NEW, incident.status)
    }

    @Test
    fun `terminal states allow no further transition`() {
        assertTrue(ClosedState.allowedTransitions.isEmpty())
        assertTrue(CancelledState.allowedTransitions.isEmpty())
        val cancelled = newIncident().apply { transitionTo(CANCELLED, reporter, t0) }
        assertFailsWith<InvalidStateTransitionException> { cancelled.transitionTo(CLASSIFIED, admin, t0) }
    }

    @Test
    fun `only the assigned technician can acknowledge`() {
        val incident = assigned()
        assertFailsWith<UnauthorizedOperationException> { incident.transitionTo(ACKNOWLEDGED, otherTech, t0) }
        assertFailsWith<UnauthorizedOperationException> { incident.transitionTo(ACKNOWLEDGED, reporter, t0) }
        assertFailsWith<UnauthorizedOperationException> { incident.transitionTo(ACKNOWLEDGED, admin, t0) }
        assertEquals(ASSIGNED, incident.status)
        incident.transitionTo(ACKNOWLEDGED, tech, t0)
        assertEquals(ACKNOWLEDGED, incident.status)
    }

    @Test
    fun `only the reporter or an admin can verify`() {
        val incident = resolved()
        assertFailsWith<UnauthorizedOperationException> { incident.transitionTo(VERIFIED, tech, t0) }
        assertFailsWith<UnauthorizedOperationException> { incident.transitionTo(VERIFIED, otherEmployee, t0) }
        incident.transitionTo(VERIFIED, admin, t0)
        assertEquals(VERIFIED, incident.status)
    }

    @Test
    fun `only the reporter or an admin can cancel, and only early`() {
        assertFailsWith<UnauthorizedOperationException> { newIncident().transitionTo(CANCELLED, otherEmployee, t0) }
        newIncident().transitionTo(CANCELLED, reporter, t0)
        assertFailsWith<InvalidStateTransitionException> { assigned().transitionTo(CANCELLED, reporter, t0) }
    }

    @Test
    fun `reopening a resolved incident clears the resolved time`() {
        val incident = resolved()
        assertTrue(incident.resolvedAt != null)
        incident.transitionTo(IN_PROGRESS, reporter, t0.plusSeconds(5))
        assertEquals(IN_PROGRESS, incident.status)
        assertNull(incident.resolvedAt)
    }

    @Test
    fun `info request cycle moves between IN_PROGRESS and PENDING_INFO`() {
        val incident = inProgress()
        incident.transitionTo(PENDING_INFO, tech, t0)
        assertFailsWith<UnauthorizedOperationException> { incident.transitionTo(IN_PROGRESS, otherEmployee, t0) }
        incident.transitionTo(IN_PROGRESS, reporter, t0)
        assertEquals(IN_PROGRESS, incident.status)
    }

    @Test
    fun `resolving needs a summary and leaves the incident unchanged when it fails`() {
        val incident = inProgress()
        assertFailsWith<DomainValidationException> { incident.resolve("  ", null, tech, t0) }
        assertEquals(IN_PROGRESS, incident.status)
        assertNull(incident.resolutionSummary)
        assertFailsWith<DomainValidationException> { incident.transitionTo(RESOLVED, tech, t0) }
        assertEquals(IN_PROGRESS, incident.status)
    }

    @Test
    fun `assign only works from CLASSIFIED or ASSIGNED, and only for system or admin`() {
        assertFailsWith<InvalidStateTransitionException> { newIncident().assign(2, Actor.SYSTEM, t0) }

        val classified = newIncident().apply { classify("NETWORK", Actor.SYSTEM, t0) }
        assertFailsWith<UnauthorizedOperationException> { classified.assign(2, tech, t0) }

        val incident = assigned()
        assertNull(incident.assign(3, admin, t0)) // reassignment: no status change
        assertEquals(3L, incident.assigneeId)
        assertEquals(ASSIGNED, incident.status)
        assertFailsWith<UnauthorizedOperationException> { incident.assign(2, reporter, t0) }
        assertFailsWith<InvalidStateTransitionException> { inProgress().assign(3, admin, t0) }
    }

    @Test
    fun `visibility is limited to reporter, assignee and admin`() {
        val incident = assigned()
        assertTrue(incident.isVisibleTo(reporter))
        assertTrue(incident.isVisibleTo(tech))
        assertTrue(incident.isVisibleTo(admin))
        assertFalse(incident.isVisibleTo(otherEmployee))
        assertFalse(incident.isVisibleTo(otherTech))
    }

    @Test
    fun `available transitions depend on the actor`() {
        val incident = assigned()
        assertEquals(setOf(ACKNOWLEDGED), incident.availableTransitions(tech))
        assertTrue(incident.availableTransitions(reporter).isEmpty())
        assertTrue(incident.availableTransitions(otherTech).isEmpty())
    }

    @Test
    fun `feedback is reporter only, after resolution, once, and rated 1 to 5`() {
        val early = inProgress()
        assertFailsWith<InvalidIncidentOperationException> { early.submitFeedback(5, null, reporter, t0) }

        val incident = resolved()
        assertFailsWith<UnauthorizedOperationException> { incident.submitFeedback(5, null, otherEmployee, t0) }
        assertFailsWith<DomainValidationException> { incident.submitFeedback(6, null, reporter, t0) }
        assertFailsWith<DomainValidationException> { incident.submitFeedback(0, null, reporter, t0) }

        incident.submitFeedback(4, "  Quick fix  ", reporter, t0)
        assertEquals(4, incident.feedbackRating)
        assertEquals("Quick fix", incident.feedbackComment)
        assertFailsWith<InvalidIncidentOperationException> { incident.submitFeedback(5, null, reporter, t0) }
    }

    @Test
    fun `every state has a lookup and the status order matches the design`() {
        IncidentStatus.entries.forEach { assertEquals(it, IncidentState.of(it).status) }
        assertEquals(setOf(CLASSIFIED, CANCELLED), NewState.allowedTransitions)
        assertEquals(setOf(VERIFIED, IN_PROGRESS), ResolvedState.allowedTransitions)
        assertEquals(setOf(CLOSED), VerifiedState.allowedTransitions)
    }

    @Test
    fun `invalid input is rejected when creating an incident`() {
        assertFailsWith<DomainValidationException> {
            Incident("INC-000002", " ", "d", 1, Impact.TEAM, Urgency.LOW, createdAt = t0)
        }
        assertFailsWith<DomainValidationException> {
            Incident("INC-000002", "t", "d", 1, Impact.TEAM, Urgency.LOW, affectedUsers = 0, createdAt = t0)
        }
    }

    @Test
    fun `priority and SLA are recorded and the previous priority is reported`() {
        val incident = newIncident()
        assertNull(incident.applyPriority(Priority.P1, 64.0, t0))
        assertEquals(Priority.P1, incident.applyPriority(Priority.P2, 30.0, t0))
        assertEquals(Priority.P2, incident.priority)
        assertFailsWith<DomainValidationException> { incident.applySla(t0.plusSeconds(60), t0.plusSeconds(30), t0) }
        incident.applySla(t0.plusSeconds(300), t0.plusSeconds(1800), t0)
        assertEquals(t0.plusSeconds(1800), incident.resolutionDueAt)
    }

    @Test
    fun `impact and urgency weights run from 1 to 4`() {
        assertEquals(listOf(1, 2, 3, 4), Impact.entries.map { it.weight })
        assertEquals(listOf(1, 2, 3, 4), Urgency.entries.map { it.weight })
    }

    @Test
    fun `incident numbers are zero padded`() {
        assertEquals("INC-000001", IncidentNumberGenerator.format(1))
        assertEquals("INC-000123", IncidentNumberGenerator.format(123))
        assertEquals("INC-1234567", IncidentNumberGenerator.format(1234567))
    }
}
