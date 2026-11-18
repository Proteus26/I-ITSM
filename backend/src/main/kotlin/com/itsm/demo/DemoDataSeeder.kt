package com.itsm.demo

import com.itsm.auth.AdminBootstrap
import com.itsm.auth.AuthenticatedUser
import com.itsm.incident.AssignRequest
import com.itsm.incident.FeedbackRequest
import com.itsm.incident.Impact
import com.itsm.incident.IncidentDetail
import com.itsm.incident.IncidentRepository
import com.itsm.incident.IncidentService
import com.itsm.incident.ReportIncidentRequest
import com.itsm.incident.RequestInfoRequest
import com.itsm.incident.ResolveRequest
import com.itsm.incident.Urgency
import com.itsm.sla.SlaMonitor
import com.itsm.user.Role
import com.itsm.user.TechnicianLevel
import com.itsm.user.User
import com.itsm.user.UserFactory
import com.itsm.user.UserRepository
import com.itsm.user.normalizeEmail
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Development helper: with `itsm.demo-data=true` (env `DEMO_DATA`) it creates three employees (different departments),
 * five technicians (L1, L2 and a team lead, their skills cover every category) and about twenty incidents spread over the
 * services, priorities and lifecycle states, so every dashboard has something to show. Everything goes through the real
 * services (assignment, events, audit rows and notifications are produced as usual); only the SLA deadlines of a few
 * incidents are moved back with JDBC (the creation time is immutable in the domain) and the SLA monitor is run once, so
 * that "at risk" and "breached" incidents exist from the first minute.
 *
 * Idempotent per item: a user that exists is reused and an incident is created only if its reporter has none with that
 * exact title, so a run that failed halfway is completed by the next one. Failures are logged and the next item still
 * runs. It refuses to run under the prod profile (the passwords are public).
 */
@Component
@ConditionalOnProperty(name = ["itsm.demo-data"], havingValue = "true")
class DemoDataSeeder(
    private val users: UserRepository,
    private val factory: UserFactory,
    private val incidentService: IncidentService,
    private val incidents: IncidentRepository,
    private val environment: Environment,
    private val jdbc: JdbcTemplate,
    private val slaMonitor: ObjectProvider<SlaMonitor>,
    private val clock: Clock,
    private val adminBootstrap: AdminBootstrap,
) : ApplicationRunner {

    /** How far a scenario ends in the lifecycle. */
    private enum class Stage { REPORTED, ASSIGNED, IN_PROGRESS, PENDING_INFO, RESOLVED, CLOSED }

    /** How much of its SLA window an incident has used: [AT_RISK] past the warning threshold, [BREACHED] past the deadline. */
    private enum class SlaClock { FRESH, AT_RISK, BREACHED }

    private class Scenario(
        val reporter: User?, val title: String, val description: String, val impact: Impact, val urgency: Urgency,
        val service: String? = null, val category: String? = null, val technician: User? = null,
        val stage: Stage = Stage.REPORTED, val clock: SlaClock = SlaClock.FRESH, val rating: Int? = null, val comment: String? = null,
        /** Finished scenarios only: report the incident this many days back, resolved [resolveHours] after it was reported (for the trend and MTTR reports). */
        val ageDays: Int = 0, val resolveHours: Int = 4,
    )

    override fun run(args: ApplicationArguments) {
        if (environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }) {
            log.warn("Demo data is enabled but the prod profile is active; refusing to create users with public passwords")
            return
        }
        // Runners have no guaranteed order: make sure the administrator exists, the seeding assigns incidents on their behalf (idempotent).
        adminBootstrap.run(args)
        val erin = ensureUser(EMPLOYEE_EMAIL, Role.EMPLOYEE, "Erin Employee", department = "Finance")
        val marco = ensureUser("employee2@itsm.local", Role.EMPLOYEE, "Marco Marketing", department = "Marketing")
        val priya = ensureUser("employee3@itsm.local", Role.EMPLOYEE, "Priya People", department = "Human Resources")
        val tariq = ensureUser("tech1@itsm.local", Role.TECHNICIAN, "Tariq Network", level = TechnicianLevel.L2, skills = setOf("NETWORK", "AUTHENTICATION", "SECURITY"))
        val sofia = ensureUser("tech2@itsm.local", Role.TECHNICIAN, "Sofia Software", level = TechnicianLevel.L1, skills = setOf("SOFTWARE", "APPLICATION", "DATABASE"))
        val hugo = ensureUser("tech3@itsm.local", Role.TECHNICIAN, "Hugo Hardware", level = TechnicianLevel.TEAM_LEAD, skills = setOf("HARDWARE", "CLOUD", "STORAGE"))
        val dana = ensureUser("tech4@itsm.local", Role.TECHNICIAN, "Dana Database", level = TechnicianLevel.L2, skills = setOf("DATABASE", "APPLICATION", "CLOUD", "SECURITY"))
        val leo = ensureUser("tech5@itsm.local", Role.TECHNICIAN, "Leo Helpdesk", level = TechnicianLevel.L1, skills = setOf("HARDWARE", "SOFTWARE", "AUTHENTICATION", "NETWORK", "STORAGE"))

        val scenarios = listOf(
            // Erin: the account the employee demo uses. One of each interesting state.
            Scenario(erin, "VPN drops every few minutes", "Working from home, the VPN disconnects and latency is very high.", Impact.TEAM, Urgency.HIGH,
                service = "VPN", category = "NETWORK", technician = tariq, stage = Stage.IN_PROGRESS, clock = SlaClock.AT_RISK),
            Scenario(erin, "Locked out after password reset", "My account is locked out and login fails with the new password.", Impact.INDIVIDUAL, Urgency.HIGH,
                service = "AUTH", category = "AUTHENTICATION", technician = tariq, stage = Stage.ASSIGNED),
            Scenario(erin, "Laptop battery is swollen", "The laptop battery bulges and the trackpad is lifting.", Impact.INDIVIDUAL, Urgency.CRITICAL,
                category = "HARDWARE", technician = hugo, stage = Stage.ASSIGNED, clock = SlaClock.BREACHED),
            Scenario(erin, "Excel crashes on large files", "Excel closes without a message when I open the quarterly workbook.", Impact.TEAM, Urgency.MEDIUM,
                category = "SOFTWARE", technician = sofia, stage = Stage.RESOLVED),
            Scenario(erin, "Shared finance drive is missing after the restart", "The F: drive no longer appears on my laptop since this morning.", Impact.TEAM, Urgency.MEDIUM,
                service = "STORAGE", category = "STORAGE", technician = hugo, stage = Stage.PENDING_INFO),
            Scenario(erin, "Something odd on my screen", "It started this morning and I cannot describe it better.", Impact.INDIVIDUAL, Urgency.LOW),
            Scenario(erin, "Docking station does not charge the laptop", "The new docking station connects the monitors but the laptop never charges.", Impact.INDIVIDUAL, Urgency.LOW,
                category = "HARDWARE", technician = leo, stage = Stage.CLOSED, rating = 5, comment = "Replaced within the hour, thank you!", ageDays = 9, resolveHours = 3),
            // Marco (Marketing) and Priya (HR)
            Scenario(marco, "Cannot send email to external clients", "Messages to addresses outside the company bounce with a 550 error.", Impact.DEPARTMENT, Urgency.HIGH,
                service = "EMAIL", category = "SOFTWARE", technician = sofia, stage = Stage.PENDING_INFO, clock = SlaClock.AT_RISK),
            Scenario(marco, "Website contact form returns an error", "Visitors report a 500 page after submitting the contact form.", Impact.ORGANISATION, Urgency.HIGH,
                service = "WEBSITE", category = "APPLICATION", technician = dana, stage = Stage.IN_PROGRESS, clock = SlaClock.BREACHED),
            Scenario(marco, "Printer on floor 3 is offline", "Nobody on floor 3 can print since lunch.", Impact.DEPARTMENT, Urgency.MEDIUM,
                category = "HARDWARE", technician = leo, stage = Stage.CLOSED, rating = 4, comment = "Fixed quickly.", ageDays = 5, resolveHours = 2),
            Scenario(priya, "HR portal shows leave balance of zero", "My leave balance reads 0 days but I have 12 left.", Impact.INDIVIDUAL, Urgency.MEDIUM,
                service = "HR", category = "APPLICATION", technician = sofia, stage = Stage.RESOLVED),
            Scenario(priya, "Payroll report query times out", "The monthly payroll report fails after about a minute.", Impact.DEPARTMENT, Urgency.HIGH,
                service = "DATABASE", category = "DATABASE", technician = dana, stage = Stage.IN_PROGRESS, clock = SlaClock.AT_RISK),
            Scenario(priya, "Suspicious sign-in alert on my account", "I received three sign-in alerts from a country I have never visited.", Impact.INDIVIDUAL, Urgency.CRITICAL,
                service = "AUTH", category = "SECURITY", technician = tariq, stage = Stage.ASSIGNED),
            Scenario(priya, "Wi-Fi keeps dropping in meeting room 4", "Video calls freeze every few minutes in meeting room 4.", Impact.TEAM, Urgency.MEDIUM,
                category = "NETWORK", technician = leo, stage = Stage.CLOSED, rating = 3, ageDays = 12, resolveHours = 20),
            Scenario(priya, "New starter needs a laptop and accounts", "A new colleague starts Monday and has no equipment or accounts yet.", Impact.INDIVIDUAL, Urgency.LOW),
            // Older, finished work so the trend and mean-time-to-resolve reports have a history.
            Scenario(marco, "Mailbox is full and cannot receive mail", "Outlook says my mailbox quota is exceeded.", Impact.INDIVIDUAL, Urgency.MEDIUM,
                service = "EMAIL", category = "SOFTWARE", technician = sofia, stage = Stage.CLOSED, rating = 5, ageDays = 3, resolveHours = 1),
            Scenario(erin, "VPN certificate expired", "The VPN client refuses to connect: certificate expired.", Impact.TEAM, Urgency.HIGH,
                service = "VPN", category = "NETWORK", technician = tariq, stage = Stage.CLOSED, rating = 4, comment = "Quick fix, thanks.", ageDays = 7, resolveHours = 6),
            Scenario(priya, "Second monitor flickers", "The second monitor flickers every few seconds.", Impact.INDIVIDUAL, Urgency.LOW,
                category = "HARDWARE", technician = hugo, stage = Stage.CLOSED, rating = 5, ageDays = 2, resolveHours = 26),
            Scenario(marco, "Backup of the campaign files failed", "The nightly backup of the campaign share reports an error.", Impact.TEAM, Urgency.MEDIUM,
                service = "STORAGE", category = "STORAGE", technician = hugo, stage = Stage.CLOSED, rating = 4, ageDays = 10, resolveHours = 9),
            Scenario(erin, "SSO login loops back to the sign-in page", "After entering my password I land on the sign-in page again.", Impact.INDIVIDUAL, Urgency.HIGH,
                service = "AUTH", category = "AUTHENTICATION", technician = leo, stage = Stage.RESOLVED, ageDays = 1, resolveHours = 5),
            // The brief's correlation example: several people hit the Payment service within the hour.
            Scenario(marco, "Payment application unavailable", "The payment application shows a blank page for everybody in my team.", Impact.DEPARTMENT, Urgency.CRITICAL,
                service = "PAYMENT", category = "APPLICATION"),
            Scenario(priya, "Payment transaction failing", "Every card payment fails with a timeout after the confirmation step.", Impact.DEPARTMENT, Urgency.CRITICAL,
                service = "PAYMENT", category = "APPLICATION"),
            Scenario(erin, "Unable to make payment", "I cannot pay supplier invoices: the payment page keeps loading.", Impact.DEPARTMENT, Urgency.CRITICAL,
                service = "PAYMENT", category = "APPLICATION"),
            Scenario(erin, "Payment gateway error 503 on checkout", "Customers see a 503 on checkout and no payment goes through.", Impact.ORGANISATION, Urgency.CRITICAL,
                service = "PAYMENT", category = "APPLICATION"),
        )
        var created = 0
        for (s in scenarios) if (ensureIncident(s)) created++
        if (created > 0) slaMonitor.ifAvailable?.let { runCatching { it.runOnce(clock.instant()) }.onFailure { e -> log.warn("SLA monitor run failed", e) } }
        log.info("Demo data ready ({} new incidents). Log in with employee@itsm.local, employee2..3@itsm.local, tech1..5@itsm.local or the admin, password {}", created, PASSWORD)
    }

    /** Creates the incident unless its reporter already has one with this exact title. Returns whether it was created; a failure is logged and the next item still runs. */
    private fun ensureIncident(s: Scenario): Boolean {
        val reporter = s.reporter ?: return false
        return try {
            if (incidents.existsByReporterIdAndTitle(requireNotNull(reporter.id), s.title)) return false
            play(s, reporter)
            true
        } catch (ex: Exception) {
            log.warn("Demo incident '{}' could not be created completely", s.title, ex)
            false
        }
    }

    private fun play(s: Scenario, reporter: User) {
        val serviceId = s.service?.let { code -> jdbc.queryForList("select id from it_services where code = ?", Long::class.javaObjectType, code).firstOrNull() }
        val detail = incidentService.report(
            reporter.asAuthenticated(),
            ReportIncidentRequest(s.title, s.description, s.impact, s.urgency, affectedUsers = affected(s.impact), serviceId = serviceId, categoryCode = s.category),
        )
        // The incident exists from here on: a failure below leaves it as far as it got, and the next run does not repeat it.
        val tech = s.technician
        if (tech != null && s.stage != Stage.REPORTED) {
            val actor = tech.asAuthenticated()
            val id = detail.id
            if (detail.assignee?.id != tech.id) assignByAdmin(id, tech)
            if (s.stage != Stage.ASSIGNED) {
                incidentService.acknowledge(id, actor)
                incidentService.start(id, actor)
            }
            if (s.stage == Stage.PENDING_INFO) {
                incidentService.requestInfo(id, actor, RequestInfoRequest("Could you tell us when it started and whether it still happens right now?"))
            }
            if (s.stage == Stage.RESOLVED || s.stage == Stage.CLOSED) {
                incidentService.resolve(id, actor, ResolveRequest("Fixed and tested together with the user", "Configuration drift after the last update"))
            }
            if (s.stage == Stage.CLOSED) {
                val me = reporter.asAuthenticated()
                incidentService.verify(id, me)
                s.rating?.let { incidentService.feedback(id, me, FeedbackRequest(it, s.comment)) }
                incidentService.close(id, me)
            }
        }
        shiftClock(detail.id, s.clock)
        if (s.ageDays > 0) age(detail.id, s.ageDays, s.resolveHours)
    }

    /** Admin-assigns the technician (the automatic strategy may have picked somebody else or nobody). Skipped when there is no administrator. */
    private fun assignByAdmin(incidentId: Long, tech: User) {
        val admin = users.findAll().firstOrNull { it.role == Role.ADMIN && it.active } ?: return
        incidentService.assign(incidentId, admin.asAuthenticated(), AssignRequest(requireNotNull(tech.id)))
    }

    /**
     * Moves creation time and both deadlines back by a share of the resolution window, as if the incident had been
     * reported that long ago: 85% leaves it past the warning threshold, 130% past its deadline. The SLA monitor
     * (run once at the end of the seeding, then every minute) records the warning or breach.
     */
    private fun shiftClock(incidentId: Long, state: SlaClock) {
        val share = when (state) {
            SlaClock.FRESH -> return
            SlaClock.AT_RISK -> 0.85
            SlaClock.BREACHED -> 1.3
        }
        jdbc.update(
            """update incidents set created_at = created_at - (resolution_due_at - created_at) * ?,
               response_due_at = response_due_at - (resolution_due_at - created_at) * ?,
               resolution_due_at = resolution_due_at - (resolution_due_at - created_at) * ?
               where id = ? and resolution_due_at is not null""",
            share, share, share, incidentId,
        )
    }

    /** Makes a finished incident look [days] old: every timestamp moves back, then resolution (and closing) are set relative to the creation time. */
    private fun age(incidentId: Long, days: Int, resolveHours: Int) {
        jdbc.update(
            """update incidents set created_at = created_at - make_interval(days => ?),
               response_due_at = response_due_at - make_interval(days => ?),
               resolution_due_at = resolution_due_at - make_interval(days => ?),
               acknowledged_at = created_at - make_interval(days => ?) + interval '10 minutes',
               resolved_at = created_at - make_interval(days => ?) + make_interval(hours => ?),
               closed_at = case when closed_at is null then null else created_at - make_interval(days => ?) + make_interval(hours => ? + 1) end,
               updated_at = created_at - make_interval(days => ?) + make_interval(hours => ? + 1)
               where id = ?""",
            days, days, days, days, days, resolveHours, days, resolveHours, days, resolveHours, incidentId,
        )
    }

    private fun affected(impact: Impact): Int = when (impact) {
        Impact.INDIVIDUAL -> 1
        Impact.TEAM -> 8
        Impact.DEPARTMENT -> 40
        Impact.ORGANISATION -> 250
    }

    /** The demo user with this email, created when missing; null (logged) when it could not be created. */
    private fun ensureUser(
        email: String, role: Role, name: String, department: String? = null, level: TechnicianLevel? = null, skills: Set<String>? = null,
    ): User? = try {
        users.findByEmail(normalizeEmail(email))
            ?: users.save(factory.create(role, email, name, PASSWORD, department = department, level = level, skills = skills))
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
