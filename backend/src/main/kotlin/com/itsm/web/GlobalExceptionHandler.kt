package com.itsm.web

import com.itsm.common.exception.ItsmException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Translates exceptions into RFC 9457 problem-detail responses. */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ItsmException::class)
    fun handleItsm(ex: ItsmException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(ex.status, ex.message ?: ex.status.reasonPhrase).apply {
            title = ex::class.simpleName
        }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed").apply {
            title = "ValidationError"
            setProperty("errors", ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") })
        }
}
