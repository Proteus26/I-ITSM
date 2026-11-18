package com.itsm.incident

import com.itsm.common.exception.ItsmException
import org.springframework.http.HttpStatus

class IncidentNotFoundException(message: String) : ItsmException(message, HttpStatus.NOT_FOUND) {
    constructor(id: Long) : this("Incident $id not found")
}

/** The lifecycle does not allow moving from [from] to [to], whoever asks. */
class InvalidStateTransitionException(val from: IncidentStatus, val to: IncidentStatus) :
    ItsmException("Cannot move an incident from $from to $to", HttpStatus.CONFLICT)

/** The action is not possible in the incident's current situation (for example feedback before resolution). */
class InvalidIncidentOperationException(message: String) : ItsmException(message, HttpStatus.CONFLICT)
