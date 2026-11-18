package com.itsm.support

import com.itsm.category.CategoryRepository
import com.itsm.incident.Actor
import com.itsm.incident.Impact
import com.itsm.incident.Incident
import com.itsm.incident.IncidentRepository
import com.itsm.incident.IncidentStatus
import com.itsm.incident.Priority
import com.itsm.incident.Urgency
import com.itsm.notification.channel.EmailGateway
import com.itsm.user.Administrator
import com.itsm.user.Employee
import com.itsm.user.Role
import com.itsm.user.Technician
import com.itsm.user.User
import com.itsm.user.UserRepository
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

/** Replaces the real email gateway in tests and remembers what was "sent". */
@Component
@Primary
class RecordingEmailGateway : EmailGateway {
    data class Sent(val to: String, val subject: String, val body: String)

    val sent = CopyOnWriteArrayList<Sent>()

    override fun send(to: String, subject: String, body: String) {
        sent += Sent(to, subject, body)
    }
}

val User.uid: Long get() = requireNotNull(id)

/** A small fixed set of users, services and incidents that the search and report tests share. */
data class Seed(
    val admin: User, val e1: User, val e2: User, val t1: User, val t2: User, val t3: User,
    val s1: Long, val s2: Long, val s3: Long,
    val i1: Incident, val i2: Incident, val i3: Incident, val i4: Incident,
    val base: Instant,
)

/**
 * Test fixtures. Only rows created here (or caused by them: notifications, audit rows and SLA events of these
 * incidents and users) are deleted again, so the helper is safe to use next to other tests' data.
 */
@Component
class TestData(
    private val users: UserRepository,
    private val incidents: IncidentRepository,
    private val categories: CategoryRepository,
    private val jdbc: JdbcTemplate,
) {
    private val userIds = mutableListOf<Long>()
    private val incidentIds = mutableListOf<Long>()
    private val serviceIds = mutableListOf<Long>()

    fun reset() {
        incidentIds.forEach {
            jdbc.update("delete from notifications where incident_id = ?", it)
            jdbc.update("delete from audit_log where incident_id = ?", it)
            jdbc.update("delete from incidents where id = ?", it)   // sla_events cascade
        }
        userIds.forEach {
            jdbc.update("delete from notifications where recipient_id = ?", it)
            jdbc.update("delete from audit_log where actor_id = ?", it)
            jdbc.update("delete from technician_skills where technician_id = ?", it)
            jdbc.update("delete from users where id = ?", it)
        }
        serviceIds.forEach { jdbc.update("delete from it_services where id = ?", it) }
        incidentIds.clear(); serviceIds.clear(); userIds.clear()
    }

    /** Builds the right User subclass. The password hash is a dummy: these users never log in. */
    fun user(name: String, role: Role, active: Boolean = true, passwordHash: String = "dummy-hash"): User {
        val email = "$name@example.test"
        val fullName = name.replaceFirstChar { it.uppercase() }
        val user: User = when (role) {
            Role.EMPLOYEE -> Employee(email, fullName, passwordHash)
            Role.TECHNICIAN -> Technician(email, fullName, passwordHash)
            Role.ADMIN -> Administrator(email, fullName, passwordHash)
        }
        if (!active) user.deactivate()
        return users.save(user).also { userIds += it.uid }
    }

    /**
     * Builds a real incident and walks it through the lifecycle with the domain methods until it reaches [status]
     * (NEW, CLASSIFIED, ASSIGNED, ACKNOWLEDGED, IN_PROGRESS or RESOLVED). Every step happens at [createdAt]
     * except the resolution, which is stamped [resolvedAt] (default: [createdAt]). Any status past NEW needs a
     * category; "SOFTWARE" is used when none is given. [assignee] is needed from ASSIGNED on.
     */
    fun incident(
        number: String, title: String, reporter: User,
        description: String = "Something is wrong", status: IncidentStatus = IncidentStatus.NEW, priority: Priority? = null,
        category: String? = null, serviceId: Long? = null, assignee: User? = null,
        createdAt: Instant = Instant.now(), resolvedAt: Instant? = null,
    ): Incident {
        val incident = Incident(
            number = number, title = title, description = description, reporterId = reporter.uid,
            impact = Impact.TEAM, urgency = Urgency.MEDIUM, affectedUsers = 1,
            serviceId = serviceId, categoryCode = category, createdAt = createdAt,
        )
        val order = listOf(
            IncidentStatus.NEW, IncidentStatus.CLASSIFIED, IncidentStatus.ASSIGNED,
            IncidentStatus.ACKNOWLEDGED, IncidentStatus.IN_PROGRESS, IncidentStatus.RESOLVED,
        )
        val target = order.indexOf(status)
        require(target >= 0) { "TestData.incident cannot build status $status" }
        val techActor = assignee?.let { Actor(it.uid, Role.TECHNICIAN) }

        if (target >= 1) {
            val code = category ?: "SOFTWARE"
            incident.classify(categories.findById(code.uppercase()).orElseThrow(), Actor.SYSTEM, createdAt)
        }
        if (target >= 2) incident.assign(requireNotNull(assignee) { "an assignee is needed from ASSIGNED on" }.uid, Actor.SYSTEM, createdAt)
        if (target >= 3) incident.transitionTo(IncidentStatus.ACKNOWLEDGED, techActor!!, createdAt)
        if (target >= 4) incident.transitionTo(IncidentStatus.IN_PROGRESS, techActor!!, createdAt)
        if (target >= 5) incident.resolve("Fixed it", null, techActor!!, resolvedAt ?: createdAt)
        if (priority != null) incident.applyPriority(priority, priority.ordinal.toDouble(), createdAt)
        return incidents.saveAndFlush(incident).also { incidentIds += requireNotNull(it.id) }
    }

    fun slaEvent(incidentId: Long, type: String) {
        jdbc.update("insert into sla_events (incident_id, type) values (?, ?)", incidentId, type)
    }

    private fun service(name: String): Long {
        val id = jdbc.queryForObject("insert into it_services (name) values (?) returning id", Long::class.javaObjectType, name)!!
        serviceIds += id
        return id
    }

    /**
     *  i1 INC-000001 "Payment app down"   IN_PROGRESS P1 APPLICATION svc1 T1 / E1  3 days ago  (resolution breach)
     *  i2 INC-000002 "VPN cannot connect" ASSIGNED    P2 NETWORK     svc2 T2 / E2  2 days ago  (warning only)
     *  i3 INC-000003 "Email delayed"      RESOLVED    P3 SOFTWARE      svc3 T1 / E1  1 day ago   (resolved after 120 min)
     *  i4 INC-000004 "Laptop is slow"     NEW         P4 HARDWARE    none -  / E2  now
     */
    fun seed(passwordHash: String = "dummy-hash"): Seed {
        val base = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val admin = user("admin", Role.ADMIN, passwordHash = passwordHash)
        val e1 = user("emp1", Role.EMPLOYEE, passwordHash = passwordHash)
        val e2 = user("emp2", Role.EMPLOYEE, passwordHash = passwordHash)
        val t1 = user("tech1", Role.TECHNICIAN, passwordHash = passwordHash)
        val t2 = user("tech2", Role.TECHNICIAN, passwordHash = passwordHash)
        val t3 = user("tech3", Role.TECHNICIAN, passwordHash = passwordHash)
        val s1 = service("Payment")
        val s2 = service("VPN")
        val s3 = service("Email")

        val i1 = incident(
            "INC-000001", "Payment app down", e1, "Checkout fails for every customer",
            IncidentStatus.IN_PROGRESS, Priority.P1, "APPLICATION", s1, t1, base.minus(3, ChronoUnit.DAYS),
        )
        val i2 = incident(
            "INC-000002", "VPN cannot connect", e2, "Timeout when dialling in from home",
            IncidentStatus.ASSIGNED, Priority.P2, "NETWORK", s2, t2, base.minus(2, ChronoUnit.DAYS),
        )
        val created3 = base.minus(1, ChronoUnit.DAYS)
        val i3 = incident(
            "INC-000003", "Email delayed", e1, "Messages arrive an hour late",
            IncidentStatus.RESOLVED, Priority.P3, "SOFTWARE", s3, t1, created3, created3.plus(120, ChronoUnit.MINUTES),
        )
        val i4 = incident(
            "INC-000004", "Laptop is slow", e2, "Takes ten minutes to boot",
            IncidentStatus.NEW, Priority.P4, "HARDWARE", null, null, base,
        )

        slaEvent(requireNotNull(i1.id), "RESOLUTION_BREACH")
        slaEvent(requireNotNull(i2.id), "WARNING")
        return Seed(admin, e1, e2, t1, t2, t3, s1, s2, s3, i1, i2, i3, i4, base)
    }
}
