package com.itsm.intelligence

import com.itsm.incident.IncidentStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure tests of the correlation rules: 3 or more open incidents on one service (or category) in a 2 hour window. */
class IncidentCorrelatorTest {

    private val correlator = IncidentCorrelator()
    private val now = Instant.parse("2026-11-18T12:00:00Z")
    private fun ago(minutes: Long) = now.minus(Duration.ofMinutes(minutes))

    private var next = 1L
    private fun c(
        minutesAgo: Long, service: Long? = 7, category: String? = "NETWORK", status: IncidentStatus = IncidentStatus.NEW,
    ): CorrelationCandidate {
        val id = next++
        return CorrelationCandidate(id, "INC-$id", "t$id", status, service, category, ago(minutesAgo))
    }

    @Test
    fun `three open incidents on one service in the window form a cluster`() {
        val clusters = correlator.correlate(listOf(c(100), c(50), c(5)), now)
        assertEquals(1, clusters.size)
        assertEquals(CorrelationKind.SERVICE, clusters[0].kind)
        assertEquals(3, clusters[0].count)
        assertEquals("service:7", clusters[0].key)
        assertEquals(clusters[0].members.map { it.createdAt }.sorted(), clusters[0].members.map { it.createdAt })
    }

    @Test
    fun `two incidents are not enough`() {
        assertTrue(correlator.correlate(listOf(c(10), c(5)), now).isEmpty())
    }

    @Test
    fun `incidents older than the window do not count`() {
        assertTrue(correlator.correlate(listOf(c(121), c(30), c(5)), now).isEmpty())
        assertEquals(1, correlator.correlate(listOf(c(120), c(30), c(5)), now).size)
    }

    @Test
    fun `resolved and closed incidents do not count`() {
        val list = listOf(c(30), c(20), c(10, status = IncidentStatus.RESOLVED), c(5, status = IncidentStatus.CLOSED))
        assertTrue(correlator.correlate(list, now).isEmpty())
    }

    @Test
    fun `incidents without a service are grouped by category and a service wins over the category`() {
        val noService = listOf(c(30, service = null), c(20, service = null), c(10, service = null))
        val clusters = correlator.correlate(noService, now)
        assertEquals(CorrelationKind.CATEGORY, clusters.single().kind)
        assertEquals("category:NETWORK", clusters.single().key)

        // same category but two different services: nobody forms a cluster
        val split = listOf(c(30, service = 1), c(20, service = 2), c(10, service = 3))
        assertTrue(correlator.correlate(split, now).isEmpty())
    }

    @Test
    fun `incidents with neither service nor category are ignored and the biggest cluster comes first`() {
        val list = listOf(
            c(30, service = null, category = null), c(20, service = null, category = null), c(10, service = null, category = null),
            c(40, service = 1), c(30, service = 1), c(20, service = 1),
            c(40, service = 2), c(30, service = 2), c(20, service = 2), c(10, service = 2),
        )
        val clusters = correlator.correlate(list, now)
        assertEquals(listOf("service:2", "service:1"), clusters.map { it.key })
    }

    @Test
    fun `an incident from the future is not in the window and the settings are validated`() {
        assertTrue(correlator.correlate(listOf(c(-5), c(10), c(5)), now).isEmpty())
        assertThrows<IllegalArgumentException> { IncidentCorrelator(Duration.ZERO) }
        assertThrows<IllegalArgumentException> { IncidentCorrelator(minClusterSize = 1) }
    }
}
