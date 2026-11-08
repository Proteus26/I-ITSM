package com.itsm.common.exception

import org.springframework.http.HttpStatus

/** A domain invariant was violated by user-supplied input; maps to 400. */
class DomainValidationException(message: String) : ItsmException(message, HttpStatus.BAD_REQUEST)

/** Like require(), but throws [DomainValidationException] so it is mapped to 400 without catching every IllegalArgumentException. */
inline fun domainRequire(condition: Boolean, message: () -> String) {
    if (!condition) throw DomainValidationException(message())
}
