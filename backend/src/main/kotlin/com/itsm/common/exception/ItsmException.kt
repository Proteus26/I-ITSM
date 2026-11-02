package com.itsm.common.exception

import org.springframework.http.HttpStatus

/**
 * Root of the application's exception hierarchy. Domain-specific errors
 * (InvalidStateTransitionException, TechnicianUnavailableException, SlaNotConfiguredException, ...)
 * should extend this so [com.itsm.web.GlobalExceptionHandler] can map them to HTTP responses.
 */
abstract class ItsmException(
    message: String,
    val status: HttpStatus = HttpStatus.BAD_REQUEST,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class NotFoundException(message: String) : ItsmException(message, HttpStatus.NOT_FOUND)
