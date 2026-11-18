package com.itsm.priority

import com.itsm.common.exception.DomainValidationException
import com.itsm.incident.Priority
import com.itsm.settings.SettingsService
import com.itsm.settings.SystemSetting
import com.itsm.settings.SystemSettingRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

class PriorityStrategyTest {
    private val weighted = WeightedScoreStrategy()
    private val matrix = MatrixStrategy()

    private fun input(i: Int, u: Int, c: Int, users: Int = 1, category: String? = null) = PriorityInput(i, u, c, users, category)

    @Test
    fun `weighted gives the maximum input P1 with a readable explanation`() {
        val result = weighted.calculate(input(4, 4, 4, users = 120))
        assertThat(result.priority).isEqualTo(Priority.P1)
        assertThat(result.score).isEqualTo(9.0)
        assertThat(result.explanation).isEqualTo(
            "Impact ORGANISATION (4) × urgency CRITICAL (4) × service criticality 4, 120 affected users → score 9.0 → P1",
        )
    }

    @Test
    fun `weighted thresholds at the extremes and at the P3 boundary`() {
        assertThat(weighted.calculate(input(1, 1, 1)).let { it.priority to it.score }).isEqualTo(Priority.P4 to 0.0)
        assertThat(weighted.calculate(input(2, 2, 1)).priority).isEqualTo(Priority.P4) // 2.7
        assertThat(weighted.calculate(input(2, 2, 2)).let { it.priority to it.score }).isEqualTo(Priority.P3 to 4.0)
        assertThat(weighted.calculate(input(4, 4, 4)).let { it.priority to it.score }).isEqualTo(Priority.P1 to 8.0)
        assertThat(weighted.calculate(input(4, 4, 3)).priority).isEqualTo(Priority.P2) // 7.4
        assertThat(weighted.calculate(input(3, 3, 3)).priority).isEqualTo(Priority.P2) // 6.3
        assertThat(weighted.calculate(input(3, 3, 2)).priority).isEqualTo(Priority.P3) // 5.6
    }

    @Test
    fun `many affected users and a security category raise the score`() {
        assertThat(weighted.calculate(input(4, 4, 3, users = 100)).priority).isEqualTo(Priority.P1) // 7.4 + 1.0
        assertThat(weighted.calculate(input(3, 3, 2, category = "security")).priority).isEqualTo(Priority.P2) // 5.6 + 1.0
        assertThat(weighted.calculate(input(3, 3, 2, category = "security")).explanation).contains("security incident")
        assertThat(weighted.calculate(input(4, 4, 4, users = 1_000_000, category = "SECURITY")).score).isEqualTo(10.0)
    }

    @Test
    fun `matrix follows the ITIL table`() {
        assertThat(matrix.calculate(input(1, 1, 1)).priority).isEqualTo(Priority.P4)
        assertThat(matrix.calculate(input(2, 2, 1)).priority).isEqualTo(Priority.P3)
        assertThat(matrix.calculate(input(2, 4, 1)).priority).isEqualTo(Priority.P2)
        assertThat(matrix.calculate(input(4, 4, 1)).let { it.priority to it.score }).isEqualTo(Priority.P1 to 10.0)
        assertThat(matrix.calculate(input(1, 4, 1)).priority).isEqualTo(Priority.P3)
    }

    @Test
    fun `matrix raises one level for criticality 4 and never above P1`() {
        assertThat(matrix.calculate(input(1, 1, 4)).priority).isEqualTo(Priority.P3)
        assertThat(matrix.calculate(input(3, 3, 4)).priority).isEqualTo(Priority.P1)
        assertThat(matrix.calculate(input(4, 4, 4)).priority).isEqualTo(Priority.P1)
        assertThat(matrix.calculate(input(1, 1, 3)).priority).isEqualTo(Priority.P4)
        assertThat(matrix.calculate(input(3, 3, 4)).explanation).contains("raised one level")
    }

    @Test
    fun `invalid input is rejected`() {
        assertThatThrownBy { input(0, 1, 1) }.isInstanceOf(DomainValidationException::class.java)
        assertThatThrownBy { input(1, 5, 1) }.isInstanceOf(DomainValidationException::class.java)
        assertThatThrownBy { input(1, 1, 0) }.isInstanceOf(DomainValidationException::class.java)
        assertThatThrownBy { input(1, 1, 1, users = 0) }.isInstanceOf(DomainValidationException::class.java)
    }

    @Test
    fun `priority service uses the strategy named by the setting and falls back to weighted`() {
        val repository = mock(SystemSettingRepository::class.java)
        val service = PriorityService(listOf(weighted, matrix), SettingsService(repository))
        val sample = input(3, 3, 2) // weighted P3 (5.6), matrix P3 -> use (2,4,1): weighted 5.3 P3, matrix P2
        val probe = input(2, 4, 1)

        `when`(repository.findById("priority.strategy")).thenReturn(Optional.empty())
        assertThat(service.calculate(probe).priority).isEqualTo(Priority.P3)

        `when`(repository.findById("priority.strategy")).thenReturn(Optional.of(SystemSetting("priority.strategy", "matrix")))
        assertThat(service.calculate(probe).priority).isEqualTo(Priority.P2)

        `when`(repository.findById("priority.strategy")).thenReturn(Optional.of(SystemSetting("priority.strategy", "nonsense")))
        assertThat(service.calculate(probe).priority).isEqualTo(Priority.P3)
        assertThat(service.calculate(sample).explanation).contains("score")
        assertThat(service.availableStrategies).containsExactlyInAnyOrder("weighted", "matrix")
    }
}
