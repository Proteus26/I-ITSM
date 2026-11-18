package com.itsm.reports

import com.itsm.incident.Priority
import com.itsm.support.TestData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import com.itsm.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ReportServiceTest @Autowired constructor(
    private val data: TestData,
    private val reports: ReportService,
) {
    @AfterEach
    fun cleanUp() = data.reset()

    @BeforeEach
    fun seed() {
        data.reset()
        data.seed()
    }

    @Test
    fun `overview counts are correct`() {
        val o = reports.overview()
        assertThat(o.total).isEqualTo(4L)
        assertThat(o.open).isEqualTo(3L)          // in progress, assigned, new
        assertThat(o.critical).isEqualTo(1L)      // the open P1
        assertThat(o.resolved).isEqualTo(1L)
        assertThat(o.slaViolations).isEqualTo(1L) // only INC-000001 has a breach event
    }

    @Test
    fun `workload counts active incidents per technician and includes idle technicians`() {
        val rows = reports.technicianWorkload().associate { it.technicianName to it.activeIncidents }
        assertThat(rows).containsEntry("Tech1", 1L)   // INC-000001 active, INC-000003 is resolved
        assertThat(rows).containsEntry("Tech2", 1L)
        assertThat(rows).containsEntry("Tech3", 0L)
    }

    @Test
    fun `incidents are counted per service and SLA compliance is a percentage`() {
        val byService = reports.byService().associate { it.serviceName to it.incidents }
        assertThat(byService).containsEntry("Payment", 1L).containsEntry("VPN", 1L)
            .containsEntry("Email", 1L).containsEntry("Unspecified", 1L)

        val sla = reports.slaCompliance()
        assertThat(sla.total).isEqualTo(4L)
        assertThat(sla.breached).isEqualTo(1L)
        assertThat(sla.percentage).isEqualTo(75.0)
    }

    @Test
    fun `mean time to resolve and the daily trend`() {
        val mttr = reports.mttr().associateBy { it.priority }
        assertThat(mttr.getValue(Priority.P3).meanMinutes).isEqualTo(120.0)
        assertThat(mttr.getValue(Priority.P3).resolvedCount).isEqualTo(1L)
        assertThat(mttr.getValue(Priority.P1).meanMinutes).isNull()   // nothing resolved at P1

        val trend = reports.trend(7)
        assertThat(trend).hasSize(7)
        assertThat(trend.sumOf { it.count }).isEqualTo(4L)
    }
}
