package com.itsm.common.exception

import org.springframework.http.HttpStatus

class InvalidSearchException(message: String) : ItsmException(message, HttpStatus.BAD_REQUEST)

class NotificationNotFoundException(id: Long) : ItsmException("Notification $id not found", HttpStatus.NOT_FOUND)
