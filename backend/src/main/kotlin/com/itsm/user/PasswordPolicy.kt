package com.itsm.user

import com.itsm.common.exception.domainRequire

/** Limits for new passwords. BCrypt only handles 72 bytes of input, so the limit is in bytes, not characters. */
object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 72
    const val MAX_BYTES = 72
    const val MESSAGE = "Password must be 8 to 72 characters and at most 72 bytes in UTF-8"

    fun exceedsByteLimit(raw: String): Boolean = raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES

    /** Domain guard: blank and over-long (BCrypt) passwords are rejected; the minimum length is enforced at the API boundary. */
    fun requireUsable(raw: String) {
        domainRequire(raw.isNotBlank()) { "Password must not be blank" }
        domainRequire(!exceedsByteLimit(raw)) { MESSAGE }
    }

    /** Null when [raw] is acceptable as a new password, otherwise a description of the problem. */
    fun violation(raw: String): String? =
        if (raw.length < MIN_LENGTH || raw.length > MAX_LENGTH || exceedsByteLimit(raw)) MESSAGE else null
}
