package com.itsm.user

import com.itsm.common.exception.domainRequire

/** Single source of truth for email normalization (trim + lowercase). */
fun normalizeEmail(email: String): String {
    val normalized = email.trim().lowercase()
    domainRequire(normalized.isNotEmpty()) { "Email must not be blank" }
    return normalized
}
