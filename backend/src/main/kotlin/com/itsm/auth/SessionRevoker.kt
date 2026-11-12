package com.itsm.auth

import com.itsm.user.UserNotFoundException
import com.itsm.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Invalidates every token issued so far for a user by incrementing users.token_version in the database.
 * The increment is a single atomic SQL statement, so concurrent revocations cannot lose an update, and it
 * cannot be overwritten by a stale entity save (the entity never writes that column).
 */
@Service
class SessionRevoker(private val users: UserRepository) {

    /** @return the new token version; tokens issued from now on must carry it. */
    @Transactional
    fun revoke(userId: Long): Int = users.incrementTokenVersion(userId) ?: throw UserNotFoundException(userId)
}
