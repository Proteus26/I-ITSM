package com.itsm.p5

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.itsm.TestcontainersConfiguration
import com.itsm.assignment.AssignmentRequest
import com.itsm.assignment.AssignmentService
import com.itsm.assignment.PropertyStrategyKeySource
import com.itsm.assignment.SettingsStrategyKeySource
import com.itsm.incident.Priority
import com.itsm.settings.SettingKeys
import com.itsm.settings.SettingsService
import com.itsm.support.TestData
import com.itsm.user.Role
import com.itsm.user.Technician
import com.itsm.user.TechnicianLevel
import com.itsm.user.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The admin setting assignment.strategy drives the strategy used by a real assignment, without a restart. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AssignmentStrategySettingTest @Autowired constructor(
    private val settings: SettingsService,
    private val assignment: AssignmentService,
    private val data: TestData,
    private val users: UserRepository,
    private val jdbc: JdbcTemplate,
) {
    private var originalSetting: String? = null
    private var parkedTechnicians = emptyList<Long>()

    @BeforeEach
    fun setUp() {
        originalSetting = jdbc.queryForList("select value from system_settings where key = ?", String::class.java, SettingKeys.ASSIGNMENT_STRATEGY).firstOrNull()
        data.reset()
        parkedTechnicians = jdbc.queryForList("select id from users where role = 'TECHNICIAN' and available", Long::class.javaObjectType)
        parkedTechnicians.forEach { jdbc.update("update users set available = false where id = ?", it) }
    }

    @AfterEach
    fun cleanUp() {
        data.reset()
        parkedTechnicians.forEach { jdbc.update("update users set available = true where id = ?", it) }
        jdbc.update("delete from system_settings where key = ?", SettingKeys.ASSIGNMENT_STRATEGY)
        originalSetting?.let { settings.set(SettingKeys.ASSIGNMENT_STRATEGY, it) }
    }

    private fun clearSetting() {
        jdbc.update("delete from system_settings where key = ?", SettingKeys.ASSIGNMENT_STRATEGY)
    }

    private fun tech(name: String, skills: Set<String>): Technician {
        val t = data.user(name, Role.TECHNICIAN) as Technician
        t.changeLevel(TechnicianLevel.L1)
        t.replaceSkills(skills)
        return users.save(t)
    }

    @Test
    fun `the setting overrides the property`() {
        settings.set(SettingKeys.ASSIGNMENT_STRATEGY, "round-robin")
        assertEquals("round-robin", SettingsStrategyKeySource(settings, PropertyStrategyKeySource("skill-least-loaded")).currentKey())
    }

    @Test
    fun `a missing setting falls back to the property`() {
        clearSetting()
        assertEquals("round-robin", SettingsStrategyKeySource(settings, PropertyStrategyKeySource("round-robin")).currentKey())
    }

    @Test
    fun `an unknown setting falls back to the property with a warning`() {
        val logger = LoggerFactory.getLogger(SettingsStrategyKeySource::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            settings.set(SettingKeys.ASSIGNMENT_STRATEGY, "nonsense")
            val source = SettingsStrategyKeySource(settings, PropertyStrategyKeySource("round-robin"))
            repeat(2) { assertEquals("round-robin", source.currentKey()) }
            assertEquals(1, appender.list.count { it.level == Level.WARN })
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `changing the setting switches the strategy of a real assignment`() {
        val other = tech("stratother", emptySet())
        val skilled = tech("stratskilled", setOf("NETWORK"))
        val req = AssignmentRequest(null, "NETWORK", Priority.P3)

        settings.set(SettingKeys.ASSIGNMENT_STRATEGY, "skill-least-loaded")
        repeat(3) {
            val result = assignment.assign(req)!!
            assertEquals("skill-least-loaded", result.strategy)
            assertEquals(requireNotNull(skilled.id), result.technicianId)
        }

        settings.set(SettingKeys.ASSIGNMENT_STRATEGY, "round-robin")
        val picks = (1..2).map { assignment.assign(req)!! }
        assertTrue(picks.all { it.strategy == "round-robin" }, picks.toString())
        assertEquals(setOf(requireNotNull(other.id), requireNotNull(skilled.id)), picks.map { it.technicianId }.toSet())

        settings.set(SettingKeys.ASSIGNMENT_STRATEGY, "skill-least-loaded")
        assertEquals("skill-least-loaded", assignment.assign(req)!!.strategy)
    }
}
