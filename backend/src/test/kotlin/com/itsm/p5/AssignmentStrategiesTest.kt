package com.itsm.p5

import com.itsm.assignment.AssignmentRequest
import com.itsm.assignment.AssignmentStrategySelector
import com.itsm.assignment.RoundRobinStrategy
import com.itsm.assignment.SkillLeastLoadedStrategy
import com.itsm.assignment.StrategyKeySource
import com.itsm.assignment.TechnicianCandidate
import com.itsm.incident.Priority
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssignmentStrategiesTest {
    private fun tech(id: Long, name: String, level: String = "L1", skills: Set<String> = emptySet(), active: Long = 0, max: Int = 5) =
        TechnicianCandidate(id, name, level, skills, active, max)

    private val skill = SkillLeastLoadedStrategy()

    @Test
    fun `skill match beats a less loaded technician without the skill`() {
        val result = skill.pick(
            AssignmentRequest(null, "NETWORK", Priority.P3),
            listOf(tech(1, "Idle", active = 0), tech(2, "Ravi", "L2", setOf("NETWORK"), active = 1)),
        )!!
        assertEquals(2, result.technicianId)
        assertEquals("Ravi (L2) has NETWORK skill and 1/5 active incidents", result.reason)
    }

    @Test
    fun `least loaded ratio wins among skilled technicians`() {
        val result = skill.pick(
            AssignmentRequest(null, "network", Priority.P3),
            listOf(tech(1, "A", skills = setOf("NETWORK"), active = 3, max = 5), tech(2, "B", skills = setOf("NETWORK"), active = 3, max = 10)),
        )!!
        assertEquals(2, result.technicianId)
    }

    @Test
    fun `falls back to anyone and says so when nobody has the skill`() {
        val result = skill.pick(AssignmentRequest(null, "CLOUD", null), listOf(tech(1, "A", active = 2), tech(2, "B", active = 1)))!!
        assertEquals(2, result.technicianId)
        assertTrue(result.reason.startsWith("No available technician has CLOUD skill"), result.reason)
    }

    @Test
    fun `P1 prefers the higher level even if more loaded, P3 does not`() {
        val candidates = listOf(
            tech(1, "Junior", "L1", setOf("NETWORK"), active = 0),
            tech(2, "Lead", "TEAM_LEAD", setOf("NETWORK"), active = 3),
        )
        assertEquals(2, skill.pick(AssignmentRequest(null, "NETWORK", Priority.P1), candidates)!!.technicianId)
        assertEquals(1, skill.pick(AssignmentRequest(null, "NETWORK", Priority.P3), candidates)!!.technicianId)
    }

    @Test
    fun `no candidates gives null`() {
        assertNull(skill.pick(AssignmentRequest(null, "NETWORK", Priority.P1), emptyList()))
        assertNull(RoundRobinStrategy().pick(AssignmentRequest(null, null, null), emptyList()))
    }

    @Test
    fun `round robin rotates by id and wraps around`() {
        val rr = RoundRobinStrategy()
        val candidates = listOf(tech(30, "C"), tech(10, "A"), tech(20, "B"))
        val order = (1..4).map { rr.pick(AssignmentRequest(null, null, null), candidates)!!.technicianId }
        assertEquals(listOf(10L, 20L, 30L, 10L), order)
    }

    @Test
    fun `round robin keeps going when a technician drops out`() {
        val rr = RoundRobinStrategy()
        val req = AssignmentRequest(null, null, null)
        assertEquals(10, rr.pick(req, listOf(tech(10, "A"), tech(20, "B")))!!.technicianId)
        assertEquals(30, rr.pick(req, listOf(tech(10, "A"), tech(30, "C")))!!.technicianId)
    }

    @Test
    fun `selector falls back to the default for an unknown key`() {
        val strategies = listOf(skill, RoundRobinStrategy())
        assertEquals("round-robin", AssignmentStrategySelector(strategies, StrategyKeySource { "Round-Robin" }).select().key)
        assertEquals("skill-least-loaded", AssignmentStrategySelector(strategies, StrategyKeySource { "nonsense" }).select().key)
        assertEquals("skill-least-loaded", AssignmentStrategySelector(strategies, StrategyKeySource { null }).select().key)
    }
}
