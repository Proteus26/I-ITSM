package com.itsm.search

import com.itsm.incident.IncidentStatus
import com.itsm.sla.Priority
import com.itsm.support.Seed
import com.itsm.support.TestData
import com.itsm.user.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import java.time.ZoneOffset

@SpringBootTest
class IncidentSearchTest @Autowired constructor(
    private val data: TestData,
    private val search: IncidentSearchService,
) {
    private lateinit var s: Seed

    @BeforeEach
    fun seed() {
        data.reset()
        s = data.seed()
    }

    private fun numbers(criteria: IncidentSearchCriteria, viewer: User = s.admin): List<String> =
        search.search(criteria, PageRequest.of(0, 50), viewer).content.map { it.number }

    @Test
    fun `each filter works on its own`() {
        fun day(daysAgo: Long) = s.base.minusSeconds(daysAgo * 86_400).atZone(ZoneOffset.UTC).toLocalDate()

        assertThat(numbers(IncidentSearchCriteria(number = "000002"))).containsExactly("INC-000002")
        assertThat(numbers(IncidentSearchCriteria(from = day(2), to = day(1))))
            .containsExactlyInAnyOrder("INC-000002", "INC-000003")
        assertThat(numbers(IncidentSearchCriteria(status = IncidentStatus.NEW))).containsExactly("INC-000004")
        assertThat(numbers(IncidentSearchCriteria(priority = Priority.P1))).containsExactly("INC-000001")
        assertThat(numbers(IncidentSearchCriteria(category = "network"))).containsExactly("INC-000002")
        assertThat(numbers(IncidentSearchCriteria(service = s.s1))).containsExactly("INC-000001")
        assertThat(numbers(IncidentSearchCriteria(assignee = s.t1.id))).containsExactlyInAnyOrder("INC-000001", "INC-000003")
        assertThat(numbers(IncidentSearchCriteria(reporter = s.e2.id))).containsExactlyInAnyOrder("INC-000002", "INC-000004")
        assertThat(numbers(IncidentSearchCriteria(q = "VPN"))).containsExactly("INC-000002")
        assertThat(numbers(IncidentSearchCriteria(q = "checkout"))).containsExactly("INC-000001")   // matches the description
    }

    @Test
    fun `SLA status filter separates breached, at risk and on track`() {
        assertThat(numbers(IncidentSearchCriteria(slaStatus = SlaStatus.BREACHED))).containsExactly("INC-000001")
        assertThat(numbers(IncidentSearchCriteria(slaStatus = SlaStatus.AT_RISK))).containsExactly("INC-000002")
        assertThat(numbers(IncidentSearchCriteria(slaStatus = SlaStatus.ON_TRACK)))
            .containsExactlyInAnyOrder("INC-000003", "INC-000004")
    }

    @Test
    fun `filters combine with AND`() {
        assertThat(numbers(IncidentSearchCriteria(assignee = s.t1.id, status = IncidentStatus.RESOLVED))).containsExactly("INC-000003")
        assertThat(numbers(IncidentSearchCriteria(reporter = s.e1.id, priority = Priority.P1))).containsExactly("INC-000001")
        assertThat(numbers(IncidentSearchCriteria(reporter = s.e2.id, status = IncidentStatus.NEW, q = "laptop")))
            .containsExactly("INC-000004")
        assertThat(numbers(IncidentSearchCriteria(assignee = s.t1.id, priority = Priority.P2))).isEmpty()
    }

    @Test
    fun `people only find incidents they are allowed to see`() {
        assertThat(numbers(IncidentSearchCriteria(), viewer = s.e1)).containsExactlyInAnyOrder("INC-000001", "INC-000003")
        assertThat(numbers(IncidentSearchCriteria(), viewer = s.t2)).containsExactly("INC-000002")
        assertThat(numbers(IncidentSearchCriteria(), viewer = s.admin)).hasSize(4)
    }
}
