package com.itsm.notification.channel

import com.itsm.notification.ChannelType
import com.itsm.notification.Notification
import com.itsm.notification.NotificationDto
import com.itsm.notification.NotificationEntity
import com.itsm.notification.NotificationRepository
import com.itsm.notification.stream.NotificationStream
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.annotation.Async
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
 * Hands the email to a small background executor, so a slow or unreachable mail server never delays the request
 * that caused the notification. If the queue is full the email is dropped with a warning (the in-app
 * notification still exists).
 */
@Component
class EmailChannel(
    private val sender: AsyncEmailSender,
    @Value("\${itsm.frontend-base-url:http://localhost:4321}") private val baseUrl: String,
) : NotificationChannel {
    override val type = ChannelType.EMAIL

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(notification: Notification) {
        val to = notification.recipientEmail?.takeIf { it.isNotBlank() } ?: return
        val subject = "[ITSM] ${notification.title}"
        val text = buildString {
            append(notification.body)
            notification.link?.let { append("\n\nOpen: ").append(baseUrl.trimEnd('/')).append(it) }
        }
        try {
            sender.send(to, subject, text)
        } catch (ex: TaskRejectedException) {
            log.warn("Email queue is full, email to {} was not sent", to)
        }
    }
}

/**
 * Runs on the "emailExecutor" thread pool (see EmailGatewayConfig). If the gateway fails (SMTP down, say) the email
 * is logged instead, so the failure is visible but harmless.
 */
@Component
class AsyncEmailSender(private val gateway: EmailGateway) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val fallback = LoggingEmailGateway()

    @Async("emailExecutor")
    fun send(to: String, subject: String, text: String) {
        try {
            gateway.send(to, subject, text)
        } catch (ex: Exception) {
            log.warn("Email gateway failed ({}), logging the email instead", ex.message)
            fallback.send(to, subject, text)
        }
    }
}
