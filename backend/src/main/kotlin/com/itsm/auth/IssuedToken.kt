package com.itsm.auth

import java.time.Instant

data class IssuedToken(val value: String, val expiresAt: Instant)
