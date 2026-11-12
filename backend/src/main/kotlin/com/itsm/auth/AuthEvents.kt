package com.itsm.auth

import java.time.Instant

/**
 * Published after a successful login.
 *
 * Login runs outside a transaction, so consume this (and [LoginFailed]) with a plain `@EventListener`;
 * `@TransactionalEventListener` would never fire. A listener that writes should open its own transaction.
 */
data class UserLoggedIn(val userId: Long, val email: String, val at: Instant)

/**
 * Published after a failed login; [reason] is one of UNKNOWN_EMAIL, BAD_PASSWORD, ACCOUNT_DISABLED.
 * Consume with a plain `@EventListener`, see [UserLoggedIn].
 */
data class LoginFailed(val email: String, val reason: String, val at: Instant)
