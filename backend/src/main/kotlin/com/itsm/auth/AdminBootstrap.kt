package com.itsm.auth

import com.itsm.user.PasswordPolicy
import com.itsm.user.UserFactory
import com.itsm.user.UserRepository
import com.itsm.user.isEmailConflict
import com.itsm.user.normalizeEmail
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Creates the first administrator from app.auth.bootstrap-admin when none exists. Idempotent. */
@Component
class AdminBootstrap(
    private val users: UserRepository,
    private val userFactory: UserFactory,
    private val properties: AuthProperties,
) : ApplicationRunner {

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (users.existsAdministrator()) return
        val cfg = properties.bootstrapAdmin
        if (cfg.email.isBlank() || cfg.password.isBlank()) {
            log.warn("No administrator exists. Set ADMIN_EMAIL and ADMIN_PASSWORD to create one at startup.")
            return
        }
        // Fail fast rather than create an admin whose password could never be used to log in.
        check(PasswordPolicy.violation(cfg.password) == null) {
            "ADMIN_PASSWORD (app.auth.bootstrap-admin.password) is invalid: ${PasswordPolicy.MESSAGE}."
        }
        val email = normalizeEmail(cfg.email)
        if (users.existsByEmail(email)) {
            log.warn("Bootstrap admin email {} belongs to a non-admin user; no administrator created", email)
            return
        }
        try {
            users.saveAndFlush(userFactory.createAdministrator(email, cfg.fullName, cfg.password))
            log.info("Created bootstrap admin {}", email)
        } catch (ex: DataIntegrityViolationException) {
            if (!ex.isEmailConflict()) throw ex
            log.info("Bootstrap admin {} was created concurrently", email)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(AdminBootstrap::class.java)
    }
}
