package com.itsm.user

import com.itsm.common.exception.ItsmException
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus

class IncorrectPasswordException : ItsmException("Current password is incorrect", HttpStatus.BAD_REQUEST)

class UserNotFoundException(message: String) : ItsmException(message, HttpStatus.NOT_FOUND) {
    constructor(id: Long) : this("User $id not found")
}

class EmailAlreadyRegisteredException(email: String) :
    ItsmException("Email '$email' is already registered", HttpStatus.CONFLICT)

class CannotDeactivateSelfException : ItsmException("Administrators cannot deactivate their own account", HttpStatus.BAD_REQUEST)

class InvalidUserUpdateException(message: String) : ItsmException(message, HttpStatus.BAD_REQUEST)

/** True when this violation was caused by the unique constraint on users.email (uq_users_email). */
fun DataIntegrityViolationException.isEmailConflict(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { t ->
        (t is ConstraintViolationException && t.constraintName?.contains(EMAIL_UNIQUE_CONSTRAINT, ignoreCase = true) == true) ||
            t.message?.contains(EMAIL_UNIQUE_CONSTRAINT, ignoreCase = true) == true
    }

/** Runs [block]; a unique-email violation becomes [EmailAlreadyRegisteredException], any other violation is rethrown. */
inline fun <T> translatingEmailConflict(email: () -> String, block: () -> T): T =
    try {
        block()
    } catch (ex: DataIntegrityViolationException) {
        if (ex.isEmailConflict()) throw EmailAlreadyRegisteredException(email()) else throw ex
    }

const val EMAIL_UNIQUE_CONSTRAINT = "uq_users_email"
