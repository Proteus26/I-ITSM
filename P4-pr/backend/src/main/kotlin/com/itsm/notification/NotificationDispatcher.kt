package com.itsm.notification

import com.itsm.events.IncidentEvent
import com.itsm.incident.IncidentRepository
import com.itsm.notification.channel.NotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate

/**
 * Observer pattern: listens for events and fans them out to every channel.
 *
 * AFTER_COMMIT means this only runs if the publisher's transaction really committed, so nothing is sent for an
 * action that rolled back. By then the original transaction is finished, so the work uses fresh ones
 * (REQUIRES_NEW). fallbackExecution = true means events published with no transaction at all (for example from
 * a scheduler that is not @Transactional) are still delivered.
 *
 * Failures are isolated: each recipient and each channel runs in its own transaction and its own try/catch, so
 * one failing save never rolls back or blocks the others. Anything that fails is logged and swallowed; it must
 * never undo or fail the action that was already saved. (Email itself is sent in the background, see EmailChannel.)
 */
@Component
class NotificationDispatcher(
    private val incidents: IncidentRepository,
    private val resolver: RecipientResolver,
    private val factory: NotificationFactory,
    channelBeans: List<NotificationChannel>,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val channels = channelBeans.sortedBy { it.type }   // in-app first, then email
    private val tx = TransactionTemplate(txManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onEvent(event: IncidentEvent) {
        val notifications = try {
            tx.execute { prepare(event) } ?: return
        } catch (ex: Exception) {
            log.error("Could not work out who to notify for {}", event, ex)
            return
        }

        for (notification in notifications) {
            for (channel in channels) {
                try {
                    tx.executeWithoutResult { channel.send(notification) }
                } catch (ex: Exception) {
                    log.error(
                        "Could not deliver notification to user {} via {} for {}",
                        notification.recipientId, channel.type, event, ex,
                    )
                }
            }
        }
    }

    private fun prepare(event: IncidentEvent): List<Notification> {
        val incident = incidents.findById(event.incidentId).orElse(null) ?: return emptyList()
        return resolver.resolve(event, incident).map { factory.create(event, incident, it) }
    }
}
