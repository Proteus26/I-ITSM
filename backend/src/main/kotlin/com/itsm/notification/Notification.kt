package com.itsm.notification

import jakarta.persistence.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

enum class ChannelType { IN_APP, EMAIL }

/** The message itself, independent of how it is delivered. Built by NotificationFactory. */
data class Notification(
    val recipientId: Long,
    val recipientEmail: String?,
    val title: String,
    val body: String,
    /** App-relative link, e.g. /incidents/12 */
    val link: String?,
    val incidentId: Long?,
)

/** A row in the `notifications` table (what the in-app channel stores). */
@Entity
@Table(name = "notifications")
class NotificationEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "recipient_id", nullable = false)
    var recipientId: Long = 0,

    @Column(name = "incident_id")
    var incidentId: Long? = null,

    @Column(nullable = false, length = 200)
    var title: String = "",

    @Column(nullable = false, length = 1000)
    var body: String = "",

    @Column(length = 300)
    var link: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "read_at")
    var readAt: Instant? = null,
)

data class NotificationDto(
    val id: Long?,
    val title: String,
    val body: String,
    val link: String?,
    val incidentId: Long?,
    val createdAt: Instant,
    val readAt: Instant?,
    val read: Boolean,
) {
    companion object {
        fun from(n: NotificationEntity) =
            NotificationDto(n.id, n.title, n.body, n.link, n.incidentId, n.createdAt, n.readAt, n.readAt != null)
    }
}

interface NotificationRepository : JpaRepository<NotificationEntity, Long> {
    fun findByRecipientIdOrderByCreatedAtDesc(recipientId: Long, pageable: Pageable): Page<NotificationEntity>
    fun findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(recipientId: Long, pageable: Pageable): Page<NotificationEntity>
    fun countByRecipientIdAndReadAtIsNull(recipientId: Long): Long
    fun findByIdAndRecipientId(id: Long, recipientId: Long): NotificationEntity?

    @Modifying
    @Query("update NotificationEntity n set n.readAt = :now where n.recipientId = :uid and n.readAt is null")
    fun markAllRead(@Param("uid") uid: Long, @Param("now") now: Instant): Int
}
