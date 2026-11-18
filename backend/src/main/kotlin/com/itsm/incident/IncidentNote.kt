package com.itsm.incident

import com.itsm.common.exception.domainRequire
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class NoteType { INVESTIGATION, INFO_REQUEST, INFO_RESPONSE, COMMENT }

/** An append-only entry on an incident's thread; never edited after it is written. */
@Entity
@Table(name = "incident_notes")
class IncidentNote(
    incidentId: Long,
    authorId: Long,
    type: NoteType,
    body: String,
    createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "incident_id", nullable = false, updatable = false)
    val incidentId: Long = incidentId

    @Column(name = "author_id", nullable = false, updatable = false)
    val authorId: Long = authorId

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    val type: NoteType = type

    @Column(name = "body", nullable = false, updatable = false)
    val body: String = body.trim().also {
        domainRequire(it.isNotEmpty()) { "Note must not be blank" }
        domainRequire(it.length <= Incident.MAX_TEXT) { "Note must be at most ${Incident.MAX_TEXT} characters" }
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = createdAt.truncatedTo(ChronoUnit.MICROS)
}
