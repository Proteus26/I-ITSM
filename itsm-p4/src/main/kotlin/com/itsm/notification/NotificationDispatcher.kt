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
 * AFTER_COMMIT means this only runs if the publisher's transaction really committed, so nothing is sent for
 * an action that rolled back. By then the original transaction is finished, so the work happens in a fresh
 * one (REQUIRES_NEW). fallbackExecution = true means events published with no transaction at all (for
 * example from a scheduler that is not @Transactional) are still delivered.
 *
 * A failure here is logged and swallowed: it must never undo or fail the action that was already saved.
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
        try {
            tx.executeWithoutResult { deliver(event) }
        } catch (ex: Exception) {
            log.error("Could not deliver notifications for {}", event, ex)
        }
    }

    private fun deliver(event: IncidentEvent) {
        val incident = incidents.findById(event.incidentId).orElse(null) ?: return
        for (recipient in resolver.resolve(event, incident)) {
            val notification = factory.create(event, incident, recipient)
            channels.forEach { it.send(notification) }
        }
    }
}
