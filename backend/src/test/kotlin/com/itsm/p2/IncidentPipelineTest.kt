package com.itsm.p2

import com.itsm.incident.Actor
import com.itsm.incident.Impact
import com.itsm.incident.Incident
import com.itsm.incident.IncidentAction
import com.itsm.incident.IncidentProcessingPipeline
import com.itsm.incident.IncidentStatus
import com.itsm.incident.NoteType
import com.itsm.incident.ProcessingContext
import com.itsm.incident.Urgency
import com.itsm.incident.actionsFor
import com.itsm.incident.noteTypesFor
import com.itsm.user.Role
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure unit tests: the template's step order, and the viewer-specific actions and note types. */
class IncidentPipelineTest {

    private val now = Instant.parse("2026-11-18T10:00:00Z")

    private fun incident() = Incident("INC-000001", "Title", "Text", reporterId = 1, impact = Impact.TEAM, urgency = Urgency.HIGH, createdAt = now)

    @Test
    fun `the template runs the steps in the fixed order whatever the subclass does`() {
        val calls = mutableListOf<String>()
        val pipeline = object : IncidentProcessingPipeline() {
            override fun suggestCategory(incident: Incident, context: ProcessingContext) { calls += "suggest" }
            override fun classify(incident: Incident, context: ProcessingContext) { calls += "classify" }
            override fun prioritise(incident: Incident, context: ProcessingContext) { calls += "prioritise" }
            override fun applySla(incident: Incident, context: ProcessingContext) { calls += "sla" }
            override fun assign(incident: Incident, context: ProcessingContext) { calls += "assign" }
        }
        pipeline.process(incident(), ProcessingContext(Actor.SYSTEM, now))
        assertEquals(listOf("suggest", "classify", "prioritise", "sla", "assign"), calls)
    }

    @Test
    fun `a new incident offers the reporter cancel and the admin classify`() {
        val i = incident()
        assertEquals(listOf(IncidentAction.CANCEL), i.actionsFor(Actor(1, Role.EMPLOYEE)))
        assertEquals(setOf(IncidentAction.CLASSIFY, IncidentAction.CANCEL), i.actionsFor(Actor(9, Role.ADMIN)).toSet())
        assertEquals(emptyList(), i.actionsFor(Actor(2, Role.EMPLOYEE)))
        assertEquals(listOf(NoteType.COMMENT), i.noteTypesFor(Actor(1, Role.EMPLOYEE)))
        assertEquals(listOf(NoteType.INVESTIGATION, NoteType.COMMENT), i.noteTypesFor(Actor(9, Role.ADMIN)))
        assertEquals(IncidentStatus.NEW, i.status)
    }
}
