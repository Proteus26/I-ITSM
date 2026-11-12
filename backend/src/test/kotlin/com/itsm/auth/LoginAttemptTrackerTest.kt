package com.itsm.auth

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoginAttemptTrackerTest {

    private class MutableClock(var now: Instant = Instant.parse("2026-11-12T10:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advance(d: Duration) { now = now.plus(d) }
    }

    private val clock = MutableClock()
    private val properties = AuthProperties() // 5 attempts per 15 minutes
    private val tracker = LoginAttemptTracker(properties, clock, maxTrackedEmails = 3)

    private fun attempt(email: String, times: Int = 1) = repeat(times) { tracker.reserveAttempt(email) }

    private fun assertLocked(email: String): TooManyLoginAttemptsException =
        assertFailsWith<TooManyLoginAttemptsException> { tracker.reserveAttempt(email) }

    @Test
    fun `allows max attempts then locks for the whole window from the last one`() {
        attempt("a@x.com", 4)
        clock.advance(Duration.ofMinutes(5))
        attempt("a@x.com") // the 5th reaches the limit and starts the lock

        clock.advance(Duration.ofMinutes(5))
        assertEquals(Duration.ofMinutes(10), assertLocked("a@x.com").retryAfter)
    }

    @Test
    fun `the lock lasts a full window from the moment the limit was reached, not from the first attempt`() {
        attempt("a@x.com")
        clock.advance(Duration.ofMinutes(14))
        attempt("a@x.com", 4) // limit reached at minute 14

        clock.advance(Duration.ofMinutes(14)) // minute 28: the first attempt's window is long over
        assertEquals(Duration.ofMinutes(1), assertLocked("a@x.com").retryAfter)

        clock.advance(Duration.ofMinutes(1))
        tracker.reserveAttempt("a@x.com")
    }

    @Test
    fun `rejected attempts do not extend the lock`() {
        attempt("a@x.com", 5)
        clock.advance(Duration.ofMinutes(10))
        assertLocked("a@x.com")
        clock.advance(Duration.ofMinutes(5))

        tracker.reserveAttempt("a@x.com")
    }

    @Test
    fun `emails are normalized and tracked independently`() {
        attempt("  A@X.com ", 5)

        assertLocked("a@x.com")
        tracker.reserveAttempt("b@x.com")
    }

    @Test
    fun `counting restarts when the window ends before the limit`() {
        attempt("a@x.com", 4)
        clock.advance(Duration.ofMinutes(15))

        attempt("a@x.com", 5)
        assertLocked("a@x.com")
    }

    @Test
    fun `a successful login clears the counter`() {
        attempt("a@x.com", 4)
        tracker.clear("a@x.com")

        attempt("a@x.com", 5)
        assertLocked("a@x.com")
    }

    @Test
    fun `limits come from the properties`() {
        properties.loginThrottle.maxAttempts = 2
        properties.loginThrottle.window = Duration.ofSeconds(30)
        attempt("a@x.com", 2)

        assertEquals(Duration.ofSeconds(30), assertLocked("a@x.com").retryAfter)
    }

    @Test
    fun `parallel attempts are counted atomically, at most max are let through`() {
        val threads = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val allowed = AtomicInteger()
        val rejected = AtomicInteger()
        repeat(threads) {
            pool.submit {
                start.await()
                try {
                    tracker.reserveAttempt("race@x.com")
                    allowed.incrementAndGet()
                } catch (_: TooManyLoginAttemptsException) {
                    rejected.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(properties.loginThrottle.maxAttempts, allowed.get())
        assertEquals(threads - properties.loginThrottle.maxAttempts, rejected.get())
    }

    @Test
    fun `with the map full a new email is still tracked and throttled, evicting the least recently used`() {
        attempt("a@x.com")
        attempt("b@x.com")
        attempt("c@x.com") // full, all live

        attempt("d@x.com", 5) // evicts a
        assertLocked("d@x.com")

        // b and c survive; a was evicted and starts from zero again (and evicts b when it comes back)
        attempt("a@x.com", 5)
        assertLocked("a@x.com")
    }

    @Test
    fun `expired entries are swept first when the map is full`() {
        // LRU order ends up as: a (live, eldest), b (expired), c (live)
        attempt("b@x.com") // window ends at 10:15
        clock.advance(Duration.ofMinutes(10))
        attempt("a@x.com", 4) // window ends at 10:25
        attempt("b@x.com") // touched: b is now after a in LRU order, but its window is unchanged
        attempt("c@x.com")
        clock.advance(Duration.ofMinutes(6)) // 10:16: b expired, a and c live

        attempt("d@x.com") // full: the sweep removes b, so the eldest live entry (a) must not be evicted

        attempt("a@x.com") // a kept its 4 attempts, so this is the 5th
        assertLocked("a@x.com")
    }

    @Test
    fun `a second sweep within the sweep interval is skipped`() {
        properties.loginThrottle.window = Duration.ofSeconds(10)
        attempt("e1@x.com")
        attempt("e2@x.com") // both end at +10s
        clock.advance(Duration.ofSeconds(8))
        attempt("e3@x.com", 4) // ends at +18s; map full, LRU order: e1, e2, e3
        clock.advance(Duration.ofMillis(1500)) // +9.5s
        attempt("n1@x.com") // full, nothing expired: first sweep (removes nothing), then e1 is evicted
        clock.advance(Duration.ofMillis(100))
        attempt("e2@x.com") // touch: order is now e3, n1, e2
        clock.advance(Duration.ofMillis(600)) // +10.2s: e2 expired, 700 ms after the last sweep

        attempt("n2@x.com") // full: sweep skipped, so the LRU entry e3 (live) is evicted instead of expired e2

        // e3 was evicted and starts from zero again. Had the sweep run it would have kept its 4 attempts and be locked now.
        attempt("e3@x.com", 2)
    }

    @Test
    fun `the sweep runs again once the sweep interval has passed`() {
        properties.loginThrottle.window = Duration.ofSeconds(10)
        attempt("e1@x.com")
        attempt("e2@x.com")
        clock.advance(Duration.ofSeconds(8))
        attempt("e3@x.com", 4)
        clock.advance(Duration.ofMillis(1500))
        attempt("n1@x.com") // first sweep at +9.5s
        clock.advance(Duration.ofMillis(100))
        attempt("e2@x.com")
        clock.advance(Duration.ofMillis(1000)) // +10.6s: more than 1 s after the last sweep

        attempt("n2@x.com") // sweep removes e2, e3 survives

        attempt("e3@x.com")
        assertLocked("e3@x.com")
    }
}
