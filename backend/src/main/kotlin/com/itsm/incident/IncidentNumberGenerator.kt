package com.itsm.incident

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Produces INC-000123 style numbers from the incident_number_seq database sequence (safe across instances). */
@Component
class IncidentNumberGenerator(private val jdbc: JdbcTemplate) {

    fun next(): String {
        val n = jdbc.queryForObject("select nextval('incident_number_seq')", Long::class.javaObjectType)
            ?: error("incident_number_seq returned no value")
        return format(n)
    }

    companion object {
        fun format(n: Long): String = "INC-%06d".format(n)
    }
}
