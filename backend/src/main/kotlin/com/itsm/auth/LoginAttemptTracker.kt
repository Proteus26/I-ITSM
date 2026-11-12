package com.itsm.auth

import com.itsm.user.normalizeEmail
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Per-email login throttle. [AuthService.login] calls [reserveAttempt] first and [clear] after a successful login.
 *
 * Rules: every login attempt reserves a slot up front (atomically), so parallel requests cannot all slip past the
 * check before any failure is recorded. The attempts of one email are counted from the first one; when the count
 * reaches `maxAttempts` the email is locked until `now + window` (measured from that moment, not from the first
 * attempt) and every further attempt is rejected with [TooManyLoginAttemptsException] whose retry time follows the
 * lock. If the window ends before the limit is reached, counting restarts. A successful login clears the entry.
 * Unknown emails are counted exactly like known ones, so the 429 never reveals whether an account exists.
 * Keys are normalized emails (the login request limits them to 255 characters).
 *
 * Memory is bounded by [maxTrackedEmails] and never fails open: when the map is full and a new email arrives,
 * expired entries are swept (at most once per second, so a flood cannot cause an O(n) scan per request), and if
 * the map is still full the least recently used entry is evicted to make room. The new email is always tracked.
 * Trade-off: an attacker who submits more than [maxTrackedEmails] distinct emails within the window can push
 * older entries out and reset their counters.
 *
 * All state is guarded by one lock; every operation is O(1) apart from the rate-limited sweep. It is in memory and
 * per instance (not shared between instances, lost on restart), and anyone can lock a known email out for one
 * window by failing logins on purpose.
 */
@Component
class LoginAttemptTracker(
    private val properties: AuthProperties,
    private val clock: Clock,
    private val maxTrackedEmails: Int = DEFAULT_MAX_TRACKED_EMAILS,
) {
    /** [lockedUntil] is set once the count reaches the limit; until then the entry expires at start + window. */
    private data class Entry(val start: Instant, val attempts: Int, val lockedUntil: Instant?)

    // accessOrder = true makes the first entry the least recently used one.
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val lock = Any()
    private var lastSweep: Instant = Instant.MIN

    private val maxAttempts get() = properties.loginThrottle.maxAttempts
    private val windowLength: Duration get() = properties.loginThrottle.window

    /**
     * Counts this attempt, or rejects it when the email is locked.
     * @throws TooManyLoginAttemptsException when this email is currently locked out.
     */
    fun reserveAttempt(email: String) {
        val key = normalizeEmail(email)
        val now = clock.instant()
        synchronized(lock) {
            val current = entries[key]?.takeUnless { isExpired(it, now) }
            if (current?.lockedUntil != null) {
                throw TooManyLoginAttemptsException(Duration.between(now, current.lockedUntil))
            }
            if (current == null) makeRoom(key, now)
            val start = current?.start ?: now
            val attempts = (current?.attempts ?: 0) + 1
            entries[key] = Entry(start, attempts, if (attempts >= maxAttempts) now.plus(windowLength) else null)
        }
    }

    /** Forgets the attempts of this email (call after a successful login). */
    fun clear(email: String) {
        synchronized(lock) { entries.remove(normalizeEmail(email)) }
    }

    private fun isExpired(entry: Entry, now: Instant): Boolean =
        !now.isBefore(entry.lockedUntil ?: entry.start.plus(windowLength))

    /** Called under the lock before adding a new key. */
    private fun makeRoom(key: String, now: Instant) {
        entries.remove(key) // an expired entry for this key
        if (entries.size < maxTrackedEmails) return
        if (Duration.between(lastSweep, now) >= SWEEP_INTERVAL) {
            lastSweep = now
            entries.values.removeIf { isExpired(it, now) }
        }
        while (entries.size >= maxTrackedEmails) {
            val eldest = entries.keys.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_TRACKED_EMAILS = 10_000
        private val SWEEP_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}
