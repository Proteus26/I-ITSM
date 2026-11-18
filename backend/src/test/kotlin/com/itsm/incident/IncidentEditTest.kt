package com.itsm.incident

import com.itsm.auth.UnauthorizedOperationException
import com.itsm.category.Category
import com.itsm.common.exception.DomainValidationException
import com.itsm.user.Role
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The edit rules of the [Incident] aggregate: who may change what, when; no Spring, no database. */
class IncidentEditTest {

    private val t0 = Instant.parse("2026-11-15T10:00:00Z")
    private val t1 = Instant.parse("2026-11-15T11:00:00Z")
    private val reporter = Actor(1, Role.EMPLOYEE)
    private val other = Actor(5, Role.EMPLOYEE)
    private val tech = Actor(2, Role.TECHNICIAN)
    private val otherTech = Actor(3, Role.TECHNICIAN)
    private val admin = Actor(9, Role.ADMIN)

    private fun incident() = Incident(
        number = "INC-000001", title = "VPN down", description = "Cannot connect", reporterId = 1,
        impact = Impact.INDIVIDUAL, urgency = Urgency.LOW, affectedUsers = 1, createdAt = t0,
    )

    private fun classified() = incident().apply { classify(Category("NETWORK", "Network"), Actor.SYSTEM, t0) }
    private fun assigned() = classified().apply { assign(2, Actor.SYSTEM, t0) }
    private fun inProgress() = assigned().apply {
        transitionTo(IncidentStatus.ACKNOWLEDGED, tech, t0)
        transitionTo(IncidentStatus.IN_PROGRESS, tech, t0)
    }
    private fun resolved() = inProgress().apply { resolve("Fixed", null, tech, t0) }

    @Test
    fun `the reporter edits every field before work starts and only real changes are reported`() {
        val i = assigned()
        val result = i.edit(
            IncidentEdit(title = "  VPN is down  ", description = "Cannot connect", impact = Impact.TEAM, urgency = Urgency.LOW, affectedUsers = 30, serviceId = 4),
            reporter, t1,
        )
        assertEquals(listOf("title", "impact", "affectedUsers", "serviceId"), result.names)
        assertTrue(result.affectsPriority)
        assertEquals("VPN is down", i.title)
        assertEquals(Impact.TEAM, i.impact)
        assertEquals(30, i.affectedUsers)
        assertEquals(4L, i.serviceId)
        assertEquals(t1, i.updatedAt)
    }

    @Test
    fun `an edit that changes nothing is empty and does not touch the incident`() {
        val i = assigned()
        val result = i.edit(IncidentEdit(title = "VPN down", urgency = Urgency.LOW), reporter, t1)
        assertTrue(result.isEmpty)
        assertEquals(t0, i.updatedAt)
    }

    @Test
    fun `text-only edits do not affect priority`() {
        val result = assigned().edit(IncidentEdit(description = "Cannot connect since Monday"), reporter, t1)
        assertEquals(listOf("description"), result.names)
        assertEquals(false, result.affectsPriority)
    }

    @Test
    fun `the service can be cleared`() {
        val i = incident().apply { edit(IncidentEdit(serviceId = 3), reporter, t0) }
        val result = i.edit(IncidentEdit(clearService = true), reporter, t1)
        assertEquals(listOf("serviceId"), result.names)
        assertEquals(null, i.serviceId)
    }

    @Test
    fun `the reporter may no longer edit once the technician has started`() {
        val i = inProgress()
        assertTrue(i.editableFieldsFor(reporter).isEmpty())
        assertFailsWith<UnauthorizedOperationException> { i.edit(IncidentEdit(title = "x"), reporter, t1) }
    }

    @Test
    fun `the assignee may correct impact urgency users and service but not the text`() {
        val i = inProgress()
        assertEquals(setOf(EditField.IMPACT, EditField.URGENCY, EditField.AFFECTED_USERS, EditField.SERVICE), i.editableFieldsFor(tech))
        assertEquals(listOf("urgency"), i.edit(IncidentEdit(urgency = Urgency.HIGH), tech, t1).names)
        assertFailsWith<UnauthorizedOperationException> { i.edit(IncidentEdit(title = "x"), tech, t1) }
        assertFailsWith<UnauthorizedOperationException> { i.edit(IncidentEdit(description = "x"), tech, t1) }
    }

    @Test
    fun `an administrator may edit everything until the incident is resolved`() {
        assertEquals(EditField.entries.toSet(), inProgress().editableFieldsFor(admin))
        assertEquals(listOf("title"), inProgress().edit(IncidentEdit(title = "Renamed"), admin, t1).names)
        assertEquals(EditField.entries.toSet(), incident().editableFieldsFor(admin))
    }

    @Test
    fun `strangers and other technicians cannot edit`() {
        val i = assigned()
        assertFailsWith<UnauthorizedOperationException> { i.edit(IncidentEdit(title = "x"), other, t1) }
        assertFailsWith<UnauthorizedOperationException> { i.edit(IncidentEdit(impact = Impact.TEAM), otherTech, t1) }
        assertFailsWith<UnauthorizedOperationException> { i.edit(IncidentEdit(title = "x"), Actor.SYSTEM, t1) }
        assertTrue(i.editableFieldsFor(other).isEmpty())
    }

    @Test
    fun `nothing can be edited once the incident is resolved`() {
        val i = resolved()
        assertTrue(i.editableFieldsFor(admin).isEmpty())
        assertFailsWith<InvalidIncidentOperationException> { i.edit(IncidentEdit(title = "x"), admin, t1) }
        assertFailsWith<InvalidIncidentOperationException> { i.edit(IncidentEdit(impact = Impact.TEAM), reporter, t1) }
    }

    @Test
    fun `actions list EDIT exactly when something is editable`() {
        assertTrue(IncidentAction.EDIT in assigned().actionsFor(reporter))
        assertTrue(IncidentAction.EDIT in inProgress().actionsFor(tech))
        assertTrue(IncidentAction.EDIT !in inProgress().actionsFor(reporter))
        assertTrue(IncidentAction.EDIT !in resolved().actionsFor(admin))
        assertTrue(IncidentAction.EDIT !in assigned().actionsFor(other))
    }

    @Test
    fun `invalid values are rejected and leave the incident unchanged`() {
        val i = assigned()
        assertFailsWith<DomainValidationException> { i.edit(IncidentEdit(), reporter, t1) }
        assertFailsWith<DomainValidationException> { i.edit(IncidentEdit(title = "   "), reporter, t1) }
        assertFailsWith<DomainValidationException> { i.edit(IncidentEdit(title = "a".repeat(Incident.MAX_TITLE + 1)), reporter, t1) }
        assertFailsWith<DomainValidationException> { i.edit(IncidentEdit(description = "a".repeat(Incident.MAX_TEXT + 1)), reporter, t1) }
        assertFailsWith<DomainValidationException> { i.edit(IncidentEdit(affectedUsers = 0), reporter, t1) }
        assertFailsWith<DomainValidationException> { i.edit(IncidentEdit(serviceId = 0), reporter, t1) }
        assertFailsWith<DomainValidationException> { i.edit(IncidentEdit(serviceId = 2, clearService = true), reporter, t1) }
        // a valid field next to an invalid one: nothing is applied
        assertFailsWith<DomainValidationException> { i.edit(IncidentEdit(impact = Impact.TEAM, affectedUsers = -3), reporter, t1) }
        assertEquals(Impact.INDIVIDUAL, i.impact)
        assertEquals(t0, i.updatedAt)
    }
}
