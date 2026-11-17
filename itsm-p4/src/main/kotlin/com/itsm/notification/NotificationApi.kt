package com.itsm.notification

import com.itsm.common.CurrentUserProvider
import com.itsm.common.PageResponse
import com.itsm.notification.stream.NotificationStream
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant

@Service
class NotificationService(private val repo: NotificationRepository) {

    @Transactional(readOnly = true)
    fun list(userId: Long, unreadOnly: Boolean, pageable: Pageable): PageResponse<NotificationDto> {
        val page = if (unreadOnly) {
            repo.findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
        } else {
            repo.findByRecipientIdOrderByCreatedAtDesc(userId, pageable)
        }
        return PageResponse.of(page.map(NotificationDto::from))
    }

    @Transactional(readOnly = true)
    fun unreadCount(userId: Long): Long = repo.countByRecipientIdAndReadAtIsNull(userId)

    /** Only the recipient can mark their own notification as read; anyone else gets a 404. */
    @Transactional
    fun markRead(id: Long, userId: Long): NotificationDto {
        val n = repo.findByIdAndRecipientId(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found")
        if (n.readAt == null) n.readAt = Instant.now()
        return NotificationDto.from(n)
    }

    @Transactional
    fun markAllRead(userId: Long): Int = repo.markAllRead(userId, Instant.now())
}

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val service: NotificationService,
    private val hub: NotificationStream,
    private val currentUser: CurrentUserProvider,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "false") unread: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<NotificationDto> =
        service.list(currentUser.current().id, unread, PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100)))

    @GetMapping("/unread-count")
    fun unreadCount(): Map<String, Long> = mapOf("unread" to service.unreadCount(currentUser.current().id))

    @PostMapping("/{id}/read")
    fun read(@PathVariable id: Long): NotificationDto = service.markRead(id, currentUser.current().id)

    @PostMapping("/read-all")
    fun readAll(): Map<String, Int> = mapOf("updated" to service.markAllRead(currentUser.current().id))

    /** Server-Sent Events: new notifications for the logged-in user arrive here as they happen. */
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter = hub.subscribe(currentUser.current().id)
}
