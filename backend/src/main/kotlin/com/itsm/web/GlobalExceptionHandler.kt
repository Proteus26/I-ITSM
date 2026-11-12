package com.itsm.web

import com.itsm.auth.TooManyLoginAttemptsException
import com.itsm.common.exception.ItsmException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Translates exceptions into RFC 9457 problem-detail responses. */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ItsmException::class)
    fun handleItsm(ex: ItsmException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(ex.status, ex.message ?: ex.status.reasonPhrase).apply {
            title = ex::class.simpleName
        }

    /** 429 with a Retry-After header (whole seconds, at least 1). */
    @ExceptionHandler(TooManyLoginAttemptsException::class)
    fun handleTooManyLoginAttempts(ex: TooManyLoginAttemptsException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(ex.status, ex.message ?: ex.status.reasonPhrase).apply {
            title = ex::class.simpleName
        }
        val seconds = ((ex.retryAfter.toMillis() + 999) / 1000).coerceAtLeast(1)
        return ResponseEntity.status(ex.status).header(HttpHeaders.RETRY_AFTER, seconds.toString()).body(problem)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed").apply {
            title = "ValidationError"
            setProperty("errors", ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") })
        }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body").apply {
            title = "Bad Request"
        }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid value for parameter '${ex.name}'").apply {
            title = "Bad Request"
        }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParameter(ex: MissingServletRequestParameterException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Missing required parameter '${ex.parameterName}'").apply {
            title = "Bad Request"
        }

    /** Covers @PreAuthorize denials (AuthorizationDeniedException is a subclass). */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied").apply { title = "Forbidden" }
}
