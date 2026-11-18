package com.itsm.sla

import com.itsm.incident.Priority
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class SlaServiceTest {
    private val repository = mock(SlaPolicyRepository::class.java)
    private val service = DefaultSlaService(SlaPolicyResolver(repository))
    private val from = Instant.parse("2026-11-18T10:00:00Z")

    @Test
    fun `uses the default policy when the service has none`() {
        `when`(repository.findByServiceIdAndPriority(7L, Priority.P1)).thenReturn(null)
        `when`(repository.findByServiceIdIsNullAndPriority(Priority.P1)).thenReturn(SlaPolicy(null, Priority.P1, 5, 30))

        val deadlines = service.computeDeadlines(7L, Priority.P1, from)

        assertThat(deadlines.responseDueAt).isEqualTo(from.plusSeconds(5 * 60))
        assertThat(deadlines.resolutionDueAt).isEqualTo(from.plusSeconds(30 * 60))
    }

    @Test
    fun `prefers the service specific policy`() {
        `when`(repository.findByServiceIdAndPriority(7L, Priority.P2)).thenReturn(SlaPolicy(7L, Priority.P2, 10, 60))
        `when`(repository.findByServiceIdIsNullAndPriority(Priority.P2)).thenReturn(SlaPolicy(null, Priority.P2, 15, 120))

        val deadlines = service.computeDeadlines(7L, Priority.P2, from)

        assertThat(deadlines.responseDueAt).isEqualTo(from.plusSeconds(10 * 60))
        assertThat(deadlines.resolutionDueAt).isEqualTo(from.plusSeconds(60 * 60))
    }

    @Test
    fun `a null service uses the default and a missing default is an error`() {
        `when`(repository.findByServiceIdIsNullAndPriority(Priority.P3)).thenReturn(SlaPolicy(null, Priority.P3, 60, 480))
        assertThat(service.computeDeadlines(null, Priority.P3, from).resolutionDueAt).isEqualTo(from.plusSeconds(480 * 60))
        assertThatThrownBy { service.computeDeadlines(null, Priority.P4, from) }.isInstanceOf(SlaNotConfiguredException::class.java)
    }
}
