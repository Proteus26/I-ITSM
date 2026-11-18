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
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Component

/**
 * Development helper: with `itsm.demo-data=true` (env `DEMO_DATA`) it creates one employee, three technicians with
 * different skills and a handful of incidents in different states, all through the real services so assignment,
 * events, audit rows and notifications are produced as usual. Idempotent: users that exist are reused, and nothing is
 * added once the demo employee has reported incidents. Never enable it in production (the passwords are public).
 */
@Component
@ConditionalOnProperty(name = ["itsm.demo-data"], havingValue = "true")
class DemoDataSeeder(
    private val users: UserRepository,
    private val factory: UserFactory,
    private val incidentService: IncidentService,
    private val incidents: IncidentRepository,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        try {
            seed()
        } catch (ex: RuntimeException) {
            log.warn("Demo data could not be created completely: {}", ex.message)
        }
    }

    private fun seed() {
        val employee = ensure(EMPLOYEE_EMAIL, Role.EMPLOYEE, "Erin Employee")
        val tech1 = ensure("tech1@itsm.local", Role.TECHNICIAN, "Tariq Network", TechnicianLevel.L2, setOf("NETWORK", "AUTHENTICATION", "SECURITY"))
        val tech2 = ensure("tech2@itsm.local", Role.TECHNICIAN, "Sofia Software", TechnicianLevel.L1, setOf("SOFTWARE", "APPLICATION", "DATABASE"))
        ensure("tech3@itsm.local", Role.TECHNICIAN, "Hugo Hardware", TechnicianLevel.TEAM_LEAD, setOf("HARDWARE", "CLOUD", "STORAGE"))
        if (incidents.findByReporterId(requireNotNull(employee.id), Pageable.ofSize(1)).hasContent()) return

        report(employee, "VPN drops every few minutes", "Working from home, the VPN disconnects and latency is very high.", Impact.TEAM, Urgency.HIGH)
            .also { work(it, tech1, resolve = false) }
        report(employee, "Locked out after password reset", "My account is locked out and login fails with the new password.", Impact.INDIVIDUAL, Urgency.HIGH, category = "AUTHENTICATION")
        report(employee, "Laptop battery is swollen", "The laptop battery bulges and the trackpad is lifting.", Impact.INDIVIDUAL, Urgency.CRITICAL)
        report(employee, "Excel crashes on large files", "Excel closes without a message when I open the quarterly workbook.", Impact.TEAM, Urgency.MEDIUM)
            .also { work(it, tech2, resolve = true) }
        report(employee, "Something odd on my screen", "It started this morning and I cannot describe it better.", Impact.INDIVIDUAL, Urgency.LOW)
        log.info("Demo data created: log in as {} or tech1..3@itsm.local with {}", EMPLOYEE_EMAIL, PASSWORD)
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

    private fun ensure(
        email: String, role: Role, name: String, level: TechnicianLevel? = null, skills: Set<String>? = null,
    ): User = users.findByEmail(normalizeEmail(email))
        ?: users.save(factory.create(role, email, name, PASSWORD, level = level, skills = skills))

    private fun User.asAuthenticated() = AuthenticatedUser(requireNotNull(id), email, role)

    private companion object {
        val log = LoggerFactory.getLogger(DemoDataSeeder::class.java)
        const val EMPLOYEE_EMAIL = "employee@itsm.local"
        const val PASSWORD = "ChangeMe123!"
    }
}
