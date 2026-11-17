package com.itsm.common

import com.itsm.incident.IncidentStatus
import com.itsm.incident.IncidentStatus.*
import com.itsm.user.User
import com.itsm.user.UserRepository
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** Which statuses count as "open", "active" (being worked on) and "resolved" in search and reports. */
object StatusGroups {
    val OPEN: List<IncidentStatus> = listOf(NEW, CLASSIFIED, ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO)
    val ACTIVE: List<IncidentStatus> = listOf(ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO)
    val RESOLVED_OR_CLOSED: List<IncidentStatus> = listOf(RESOLVED, VERIFIED, CLOSED)
}

/** Our own page shape, so the JSON stays stable (Spring warns about serialising PageImpl directly). */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <T> of(p: Page<T>) = PageResponse(p.content, p.number, p.size, p.totalElements, p.totalPages)
    }
}

/**
 * Who is making the request. This reads the Spring Security context and looks the user up by username.
 * ADAPT to P1: if P1's login puts a custom principal (or a user id) in the security context, change this one class.
 */
interface CurrentUserProvider {
    fun current(): User
}

@Component
class SecurityContextCurrentUserProvider(private val users: UserRepository) : CurrentUserProvider {
    override fun current(): User {
        val name = SecurityContextHolder.getContext().authentication?.name
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in")
        return users.findByUsername(name)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user")
    }
}
