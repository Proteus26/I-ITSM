package com.itsm.auth

import com.itsm.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Records User.lastLoginAt (and moves the old value to previousLoginAt) when a [UserLoggedIn] event is published.
 * The repository update runs in its own transaction. A failure is logged and swallowed: bookkeeping must never
 * turn a successful login into an error.
 */
@Component
class LastLoginRecorder(private val users: UserRepository) {

    @EventListener
    fun onUserLoggedIn(event: UserLoggedIn) {
        try {
            users.recordLogin(event.userId, event.at)
        } catch (e: Exception) {
            log.warn("Could not record the last login of user {}", event.userId, e)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(LastLoginRecorder::class.java)
    }
}
