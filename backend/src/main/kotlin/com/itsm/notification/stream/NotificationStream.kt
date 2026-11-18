package com.itsm.notification.stream

import com.itsm.notification.NotificationDto
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Server-Sent Events hub: keeps one open connection per browser tab and pushes new notifications to it.
 * State is in memory, so this works for a single server instance (fine for the project).
 */
@Component
class NotificationStream {
    private val log = LoggerFactory.getLogger(javaClass)
    private val emitters = ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>()

    fun subscribe(userId: Long): SseEmitter {
        val emitter = SseEmitter(TIMEOUT_MS)
        val mine = emitters.computeIfAbsent(userId) { CopyOnWriteArrayList() }
        mine.add(emitter)

        emitter.onCompletion { mine.remove(emitter) }
        emitter.onTimeout { mine.remove(emitter) }
        emitter.onError { mine.remove(emitter) }

        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"))
        } catch (ex: Exception) {
            mine.remove(emitter)
        }
        return emitter
    }

    fun push(userId: Long, payload: NotificationDto) {
        val mine = emitters[userId] ?: return
        for (emitter in mine) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(payload, MediaType.APPLICATION_JSON))
            } catch (ex: Exception) {
                mine.remove(emitter)
                log.debug("Dropped a closed notification stream for user {}", userId)
            }
        }
    }

    /** Keeps idle connections alive through proxies; also removes dead ones. */
    @Scheduled(fixedDelay = 25_000)
    fun heartbeat() {
        for (mine in emitters.values) {
            for (emitter in mine) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"))
                } catch (ex: Exception) {
                    mine.remove(emitter)
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30 * 60 * 1000L   // the browser reconnects automatically after this
    }
}
