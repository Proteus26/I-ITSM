package com.itsm.notification.channel

import com.itsm.notification.ChannelType
import com.itsm.notification.Notification
import com.itsm.notification.NotificationDto
import com.itsm.notification.NotificationEntity
import com.itsm.notification.NotificationRepository
import com.itsm.notification.stream.NotificationStream
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** One way of delivering a notification. Add a new channel by writing a new @Component implementing this. */
interface NotificationChannel {
    val type: ChannelType
    fun send(notification: Notification)
}

/** Stores the notification (the bell and notifications page read from this) and pushes it live over SSE. */
@Component
class InAppChannel(
    private val repo: NotificationRepository,
    private val stream: NotificationStream,
) : NotificationChannel {
    override val type = ChannelType.IN_APP

    override fun send(notification: Notification) {
        val saved = repo.save(
            NotificationEntity(
                recipientId = notification.recipientId,
                incidentId = notification.incidentId,
                title = notification.title.take(200),
                body = notification.body.take(1000),
                link = notification.link?.take(300),
            ),
        )
        val dto = NotificationDto.from(saved)
        // Push only once the row is committed, otherwise the browser could ask for it before it exists.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = stream.push(notification.recipientId, dto)
            })
        } else {
            stream.push(notification.recipientId, dto)
        }
    }
}

/**
 * Sends email through whichever EmailGateway is configured. If sending fails (SMTP down, say), it falls back to
 * logging the email so the failure never breaks the action that caused it.
 */
@Component
class EmailChannel(
    private val gateway: EmailGateway,
    @Value("\${itsm.frontend-base-url:http://localhost:8080}") private val baseUrl: String,
) : NotificationChannel {
    override val type = ChannelType.EMAIL

    private val log = LoggerFactory.getLogger(javaClass)
    private val fallback = LoggingEmailGateway()

    override fun send(notification: Notification) {
        val to = notification.recipientEmail?.takeIf { it.isNotBlank() } ?: return
        val subject = "[ITSM] ${notification.title}"
        val text = buildString {
            append(notification.body)
            notification.link?.let { append("\n\nOpen: ").append(baseUrl.trimEnd('/')).append(it) }
        }
        try {
            gateway.send(to, subject, text)
        } catch (ex: Exception) {
            log.warn("Email gateway failed ({}), logging the email instead", ex.message)
            fallback.send(to, subject, text)
        }
    }
}
