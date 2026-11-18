package com.itsm.incident

import com.itsm.auth.AuthenticatedUser
import com.itsm.user.Role

/** Who is performing an action on an incident: a logged-in user, or the system itself (automation, schedulers). */
data class Actor(val userId: Long?, val role: Role?) {
    val isSystem: Boolean get() = role == null
    val isAdmin: Boolean get() = role == Role.ADMIN

    companion object {
        val SYSTEM = Actor(null, null)

        fun of(user: AuthenticatedUser) = Actor(user.id, user.role)
    }
}
