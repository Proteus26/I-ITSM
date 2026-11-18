package com.itsm.web

import com.itsm.auth.TooManyLoginAttemptsException
import org.junit.jupiter.api.Test
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Duration
import kotlin.test.assertEquals

class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    private fun retryAfter(remaining: Duration): String? =
        handler.handleTooManyLoginAttempts(TooManyLoginAttemptsException(remaining)).headers.getFirst("Retry-After")

    @Test
    fun `Retry-After rounds up to whole seconds`() {
        assertEquals("2", retryAfter(Duration.ofMillis(1200)))
        assertEquals("1", retryAfter(Duration.ofMillis(1000)))
        assertEquals("1", retryAfter(Duration.ofMillis(1)))
        assertEquals("900", retryAfter(Duration.ofMinutes(15)))
    }

    @Test
    fun `Retry-After is never zero`() {
        assertEquals("1", retryAfter(Duration.ZERO))
        assertEquals("1", retryAfter(Duration.ofMillis(-5)))
    }

    @Test
    fun `the response is a 429`() {
        val response = handler.handleTooManyLoginAttempts(TooManyLoginAttemptsException(Duration.ofSeconds(3)))
        assertEquals(429, response.statusCode.value())
    }

    @Test
    fun `an optimistic lock failure is a 409 ConcurrentUpdate problem`() {
        val problem = handler.handleOptimisticLock(ObjectOptimisticLockingFailureException("Incident", 7L))
        assertEquals(409, problem.status)
        assertEquals("ConcurrentUpdate", problem.title)
        assertEquals("The record was changed by someone else. Reload and try again.", problem.detail)
    }
}
