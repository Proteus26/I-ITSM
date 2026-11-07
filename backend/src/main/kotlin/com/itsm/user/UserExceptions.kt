package com.itsm.user

import com.itsm.common.exception.ItsmException
import org.springframework.http.HttpStatus

class IncorrectPasswordException : ItsmException("Current password is incorrect", HttpStatus.BAD_REQUEST)

class UserNotFoundException(message: String) : ItsmException(message, HttpStatus.NOT_FOUND) {
    constructor(id: Long) : this("User $id not found")
}

class EmailAlreadyRegisteredException(email: String) :
    ItsmException("Email '$email' is already registered", HttpStatus.CONFLICT)
