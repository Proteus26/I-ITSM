package com.itsm.incident

/** Stored as a plain string column; behaviour lives in [IncidentState]. */
enum class IncidentStatus {
    NEW,
    CLASSIFIED,
    ASSIGNED,
    ACKNOWLEDGED,
    IN_PROGRESS,
    PENDING_INFO,
    RESOLVED,
    VERIFIED,
    CLOSED,
    CANCELLED,
    ;

    /** Still waiting for a fix: used for SLA monitoring and the "open incidents" report. */
    val isOpen: Boolean get() = this in OPEN

    /** Counts toward a technician's workload. */
    val countsTowardWorkload: Boolean get() = this in ACTIVE

    companion object {
        val OPEN: Set<IncidentStatus> = setOf(NEW, CLASSIFIED, ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO)
        val ACTIVE: Set<IncidentStatus> = setOf(ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO)
    }
}
