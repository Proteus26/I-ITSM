package com.itsm.auth

import java.time.Instant

/** Published after a successful login. */
data class UserLoggedIn(val userId: Long, val at: Instant)

/** Published after a failed login; [reason] is one of UNKNOWN_EMAIL, BAD_PASSWORD, ACCOUNT_DISABLED. */
data class LoginFailed(val email: String, val reason: String, val at: Instant)
