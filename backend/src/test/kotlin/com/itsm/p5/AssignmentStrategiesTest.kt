package com.itsm.p5

import com.itsm.assignment.AssignmentRequest
import com.itsm.assignment.AssignmentStrategySelector
import com.itsm.assignment.RoundRobinStrategy
import com.itsm.assignment.SkillLeastLoadedStrategy
import com.itsm.assignment.StrategyKeySource
import com.itsm.assignment.TechnicianCandidate
import com.itsm.incident.Priority
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
    fun `P1 prefers the higher level while it is below 80 percent load, P3 does not`() {
        val candidates = listOf(
            tech(1, "Junior", "L1", setOf("NETWORK"), active = 0),
            tech(2, "Lead", "TEAM_LEAD", setOf("NETWORK"), active = 3), // 60%
        )
        assertEquals(2, skill.pick(AssignmentRequest(null, "NETWORK", Priority.P1), candidates)!!.technicianId)
        assertEquals(1, skill.pick(AssignmentRequest(null, "NETWORK", Priority.P3), candidates)!!.technicianId)
    }

    @Test
    fun `P1 senior at 80 percent or more loses to an idle L2`() {
        val candidates = listOf(
            tech(1, "Lead", "TEAM_LEAD", setOf("NETWORK"), active = 4), // 80%
            tech(2, "Idle", "L2", setOf("NETWORK"), active = 0),
        )
        assertEquals(2, skill.pick(AssignmentRequest(null, "NETWORK", Priority.P1), candidates)!!.technicianId)
    }

    @Test
    fun `P1 ranks purely by load when everybody is at 80 percent or more`() {
        val candidates = listOf(
            tech(1, "Lead", "TEAM_LEAD", setOf("NETWORK"), active = 9, max = 10),
            tech(2, "Junior", "L1", setOf("NETWORK"), active = 4, max = 5),
        )
        assertEquals(2, skill.pick(AssignmentRequest(null, "NETWORK", Priority.P1), candidates)!!.technicianId)
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
    fun `selector warns once per distinct unknown key`() {
        val logger = LoggerFactory.getLogger(AssignmentStrategySelector::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            var key = "nonsense"
            val selector = AssignmentStrategySelector(listOf(skill, RoundRobinStrategy()), StrategyKeySource { key })
            repeat(3) { selector.select() }
            key = "other"
            repeat(2) { selector.select() }
            assertEquals(2, appender.list.count { it.level == Level.WARN })
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `selector falls back to the default for an unknown key`() {
        val strategies = listOf(skill, RoundRobinStrategy())
        assertEquals("round-robin", AssignmentStrategySelector(strategies, StrategyKeySource { "Round-Robin" }).select().key)
        assertEquals("skill-least-loaded", AssignmentStrategySelector(strategies, StrategyKeySource { "nonsense" }).select().key)
        assertEquals("skill-least-loaded", AssignmentStrategySelector(strategies, StrategyKeySource { null }).select().key)
    }
}
