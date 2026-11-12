package com.itsm.auth

import java.time.Instant

/**
 * Login events are the Observer hook of the auth module: [AuthService] publishes them and does not know who listens.
 * Today [LastLoginRecorder] is the only listener (it consumes [UserLoggedIn]); [LoginFailed] has none yet. They exist
 * for future audit and notification modules, which can subscribe without touching the login code.
 * Login throttling is deliberately not a listener, see [AuthService.login].
 */

/**
 * Published after a successful login.
 *
 * Login runs outside a transaction, so consume this (and [LoginFailed]) with a plain `@EventListener`;
 * `@TransactionalEventListener` would never fire. A listener that writes should open its own transaction.
 */
data class UserLoggedIn(val userId: Long, val at: Instant)

/**
 * Published after a failed login; [reason] is one of UNKNOWN_EMAIL, BAD_PASSWORD, ACCOUNT_DISABLED.
 * Consume with a plain `@EventListener`, see [UserLoggedIn].
 */
data class LoginFailed(val email: String, val reason: String, val at: Instant)
