package com.itsm.auth

import com.itsm.common.exception.ItsmException
import org.springframework.http.HttpStatus

class InvalidCredentialsException : ItsmException("Invalid email or password", HttpStatus.UNAUTHORIZED)

class AccountDisabledException : ItsmException("Account is disabled", HttpStatus.FORBIDDEN)

class UnauthenticatedException : ItsmException("Authentication required", HttpStatus.UNAUTHORIZED)

/** For ownership checks in other modules (e.g. an employee reading someone else's incident). */
class UnauthorizedOperationException(message: String = "You are not allowed to perform this operation") :
    ItsmException(message, HttpStatus.FORBIDDEN)
