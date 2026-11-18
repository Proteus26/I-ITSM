package com.itsm.demo

import com.itsm.auth.AuthenticatedUser
import com.itsm.incident.Impact
import com.itsm.incident.IncidentDetail
import com.itsm.incident.IncidentRepository
import com.itsm.incident.IncidentService
import com.itsm.incident.ReportIncidentRequest
import com.itsm.incident.ResolveRequest
import com.itsm.incident.Urgency
import com.itsm.user.Role
import com.itsm.user.TechnicianLevel
import com.itsm.user.User
import com.itsm.user.UserFactory
import com.itsm.user.UserRepository
import com.itsm.user.normalizeEmail
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Development helper: with `itsm.demo-data=true` (env `DEMO_DATA`) it creates one employee, three technicians with
 * different skills and a handful of incidents in different states, all through the real services so assignment,
 * events, audit rows and notifications are produced as usual. Idempotent per item: a user that exists is reused and an incident is created only
 * if the demo employee has none with that exact title, so a run that failed halfway is completed by the next one. Failures are
 * logged and the next item still runs. It refuses to run under the prod profile (the passwords are public).
 */
@Component
@ConditionalOnProperty(name = ["itsm.demo-data"], havingValue = "true")
class DemoDataSeeder(
    private val users: UserRepository,
    private val factory: UserFactory,
    private val incidentService: IncidentService,
    private val incidents: IncidentRepository,
    private val environment: Environment,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        if (environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }) {
            log.warn("Demo data is enabled but the prod profile is active; refusing to create users with public passwords")
            return
        }
        val employee = ensureUser(EMPLOYEE_EMAIL, Role.EMPLOYEE, "Erin Employee")
        val tech1 = ensureUser("tech1@itsm.local", Role.TECHNICIAN, "Tariq Network", TechnicianLevel.L2, setOf("NETWORK", "AUTHENTICATION", "SECURITY"))
        val tech2 = ensureUser("tech2@itsm.local", Role.TECHNICIAN, "Sofia Software", TechnicianLevel.L1, setOf("SOFTWARE", "APPLICATION", "DATABASE"))
        ensureUser("tech3@itsm.local", Role.TECHNICIAN, "Hugo Hardware", TechnicianLevel.TEAM_LEAD, setOf("HARDWARE", "CLOUD", "STORAGE"))
        if (employee == null) return

        ensureIncident(employee, "VPN drops every few minutes", "Working from home, the VPN disconnects and latency is very high.", Impact.TEAM, Urgency.HIGH) {
            tech1?.let { t -> work(it, t, resolve = false) }
        }
        ensureIncident(employee, "Locked out after password reset", "My account is locked out and login fails with the new password.", Impact.INDIVIDUAL, Urgency.HIGH, category = "AUTHENTICATION")
        ensureIncident(employee, "Laptop battery is swollen", "The laptop battery bulges and the trackpad is lifting.", Impact.INDIVIDUAL, Urgency.CRITICAL)
        ensureIncident(employee, "Excel crashes on large files", "Excel closes without a message when I open the quarterly workbook.", Impact.TEAM, Urgency.MEDIUM) {
            tech2?.let { t -> work(it, t, resolve = true) }
        }
        ensureIncident(employee, "Something odd on my screen", "It started this morning and I cannot describe it better.", Impact.INDIVIDUAL, Urgency.LOW)
        log.info("Demo data ready: log in as {} or tech1..3@itsm.local with {}", EMPLOYEE_EMAIL, PASSWORD)
    }

    /** Creates the incident unless the employee already has one with this exact title; a failure is logged and the next item still runs. */
    private fun ensureIncident(
        reporter: User, title: String, description: String, impact: Impact, urgency: Urgency, category: String? = null,
        afterReport: (IncidentDetail) -> Unit = {},
    ) {
        try {
            if (incidents.existsByReporterIdAndTitle(requireNotNull(reporter.id), title)) return
            afterReport(report(reporter, title, description, impact, urgency, category))
        } catch (ex: Exception) {
            log.warn("Demo incident '{}' could not be created completely", title, ex)
        }
    }

    private fun report(
        reporter: User, title: String, description: String, impact: Impact, urgency: Urgency, category: String? = null,
    ): IncidentDetail = incidentService.report(
        reporter.asAuthenticated(),
        ReportIncidentRequest(title, description, impact, urgency, categoryCode = category),
    )

    /** Lets [technician] pick the incident up, if the automatic assignment gave it to them. */
    private fun work(incident: IncidentDetail, technician: User, resolve: Boolean) {
        if (incident.assignee?.id != technician.id) return
        val actor = technician.asAuthenticated()
        incidentService.acknowledge(incident.id, actor)
        incidentService.start(incident.id, actor)
        if (resolve) incidentService.resolve(incident.id, actor, ResolveRequest("Repaired the Office installation", "Corrupt add-in"))
    }

    /** The demo user with this email, created when missing; null (logged) when it could not be created. */
    private fun ensureUser(
        email: String, role: Role, name: String, level: TechnicianLevel? = null, skills: Set<String>? = null,
    ): User? = try {
        users.findByEmail(normalizeEmail(email))
            ?: users.save(factory.create(role, email, name, PASSWORD, level = level, skills = skills))
    } catch (ex: Exception) {
        log.warn("Demo user {} could not be created", email, ex)
        null
    }

    private fun User.asAuthenticated() = AuthenticatedUser(requireNotNull(id), email, role)

    private companion object {
        val log = LoggerFactory.getLogger(DemoDataSeeder::class.java)
        const val EMPLOYEE_EMAIL = "employee@itsm.local"
        const val PASSWORD = "ChangeMe123!"
    }
}
