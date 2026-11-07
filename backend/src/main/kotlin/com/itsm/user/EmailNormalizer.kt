package com.itsm.user

/** Single source of truth for email normalization (trim + lowercase). */
fun normalizeEmail(email: String): String {
    val normalized = email.trim().lowercase()
    require(normalized.isNotEmpty()) { "Email must not be blank" }
    return normalized
}
