package com.itsm.sla.risk

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure tests of the SLA risk rules; the window is 60 minutes for response and 480 for resolution unless stated. */
class SlaRiskDetectorTest {

    private val detector = SlaRiskDetector()
    private val start = Instant.parse("2026-11-18T08:00:00Z")
    private fun at(minutes: Long) = start.plus(Duration.ofMinutes(minutes))

    private fun input(
        acknowledged: Boolean = false, assigned: Boolean = true, over: Boolean = false, threshold: Int = 75,
        responseMin: Int = 60, resolutionMin: Int = 480,
        responseDue: Instant? = start.plus(Duration.ofMinutes(60)), resolutionDue: Instant? = start.plus(Duration.ofMinutes(480)),
    ) = SlaRiskInput(responseDue, resolutionDue, acknowledged, assigned, responseMin, resolutionMin, threshold, over)

    @Test
    fun `a fresh incident is fine`() {
        assertNull(detector.assess(input(), at(5)))
    }

    @Test
    fun `an incident without deadlines has nothing to miss`() {
        assertNull(detector.assess(input(responseDue = null, resolutionDue = null), at(10_000)))
    }

    @Test
    fun `a passed response deadline breaches while nobody acknowledged`() {
        val r = assertNotNull(detector.assess(input(), at(75)))
        assertEquals(SlaRiskState.BREACHED, r.state)
        assertEquals(SlaWindowKind.RESPONSE, r.dueKind)
        assertEquals(-15, r.minutesRemaining)
        assertTrue(r.riskReason.startsWith("response deadline passed 15 min ago"), r.riskReason)
    }

    @Test
    fun `an acknowledged incident no longer breaches the response deadline`() {
        assertNull(detector.assess(input(acknowledged = true), at(75)))
    }

    @Test
    fun `a passed resolution deadline breaches and the most overdue window is the main one`() {
        val r = assertNotNull(detector.assess(input(), at(500)))
        assertEquals(SlaRiskState.BREACHED, r.state)
        assertEquals(2, r.reasons.size)
        assertEquals(SlaWindowKind.RESPONSE, r.dueKind) // 440 minutes late against 20
        assertEquals(-440, r.minutesRemaining)
        val onlyResolution = assertNotNull(detector.assess(input(acknowledged = true), at(500)))
        assertEquals(SlaWindowKind.RESOLUTION, onlyResolution.dueKind)
        assertEquals(-20, onlyResolution.minutesRemaining)
    }

    @Test
    fun `reaching the warning threshold of the resolution window is at risk`() {
        assertNull(detector.assess(input(acknowledged = true), at(359)))               // 74%
        val r = assertNotNull(detector.assess(input(acknowledged = true), at(360)))    // 75%
        assertEquals(SlaRiskState.AT_RISK, r.state)
        assertEquals(SlaWindowKind.RESOLUTION, r.dueKind)
        assertEquals(120, r.minutesRemaining)
        assertEquals(75, r.percentElapsed)
        assertEquals("75% of the resolution time used (warning at 75%)", r.riskReason)
    }

    @Test
    fun `an unacknowledged incident with under a quarter of the response time left is at risk even with a high threshold`() {
        assertNull(detector.assess(input(threshold = 95), at(44)))   // 16 of 60 minutes left, 26.7%
        val r = assertNotNull(detector.assess(input(threshold = 95), at(46)))
        assertEquals(SlaRiskState.AT_RISK, r.state)
        assertEquals("Not acknowledged and only 24% of the response time is left", r.riskReason)
    }

    @Test
    fun `an unassigned incident half way through its response window is at risk`() {
        assertNull(detector.assess(input(assigned = false, threshold = 95), at(29)))
        val r = assertNotNull(detector.assess(input(assigned = false, threshold = 95), at(30)))
        assertEquals(SlaRiskState.AT_RISK, r.state)
        assertTrue(r.riskReason.startsWith("Nobody is assigned"), r.riskReason)
    }

    @Test
    fun `an overloaded assignee only matters once half the resolution window is gone`() {
        assertNull(detector.assess(input(acknowledged = true, over = true), at(100)))
        val r = assertNotNull(detector.assess(input(acknowledged = true, over = true), at(240)))
        assertEquals(SlaRiskState.AT_RISK, r.state)
        assertTrue(r.riskReason.contains("over capacity"), r.riskReason)
        assertNull(detector.assess(input(acknowledged = true, over = false), at(240)))
    }

    @Test
    fun `the most pressing deadline is reported when several rules fire`() {
        val r = assertNotNull(detector.assess(input(), at(50))) // response 83%, resolution 10%
        assertEquals(SlaWindowKind.RESPONSE, r.dueKind)
        assertEquals(10, r.minutesRemaining)
        assertEquals(83, r.percentElapsed)
    }

    @Test
    fun `a window of length zero counts as fully used`() {
        val r = assertNotNull(detector.assess(input(acknowledged = true, resolutionMin = 0, resolutionDue = start), start))
        assertEquals(SlaRiskState.AT_RISK, r.state)
    }

    @Test
    fun `the exact deadline instant is not yet a breach`() {
        val r = assertNotNull(detector.assess(input(acknowledged = true), at(480)))
        assertEquals(SlaRiskState.AT_RISK, r.state)
        assertEquals(0, r.minutesRemaining)
    }
}
