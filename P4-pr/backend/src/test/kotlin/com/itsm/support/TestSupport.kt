package com.itsm.support

import com.itsm.incident.Incident
import com.itsm.incident.IncidentRepository
import com.itsm.incident.IncidentStatus
import com.itsm.notification.channel.EmailGateway
import com.itsm.reference.ItServiceRef
import com.itsm.reference.ItServiceRefRepository
import com.itsm.reference.SlaEventRef
import com.itsm.reference.SlaEventRefRepository
import com.itsm.sla.Priority
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
 * Test fixtures. Only the rows created here are deleted again, so the helper is safe to use next to other tests'
 * data. The audit_log, notifications and sla_events tables belong to P4 and are emptied completely.
 */
@Component
class TestData(
    private val users: UserRepository,
    private val incidents: IncidentRepository,
    private val services: ItServiceRefRepository,
    private val slaEvents: SlaEventRefRepository,
    private val jdbc: JdbcTemplate,
) {
    private val userIds = mutableListOf<Long>()
    private val incidentIds = mutableListOf<Long>()
    private val serviceIds = mutableListOf<Long>()

    fun reset() {
        listOf("notifications", "audit_log", "sla_events").forEach { jdbc.update("delete from $it") }
        incidentIds.forEach { jdbc.update("delete from incidents where id = ?", it) }
        serviceIds.forEach { jdbc.update("delete from it_services where id = ?", it) }
        userIds.forEach {
            jdbc.update("delete from technician_skills where technician_id = ?", it)
            jdbc.update("delete from users where id = ?", it)
        }
        incidentIds.clear(); serviceIds.clear(); userIds.clear()
    }

    /** Builds the right User subclass. The password hash is a dummy: these users never log in. */
    fun user(name: String, role: Role, active: Boolean = true): User {
        val email = "$name@example.test"
        val fullName = name.replaceFirstChar { it.uppercase() }
        val user: User = when (role) {
            Role.EMPLOYEE -> Employee(email, fullName, "dummy-hash")
            Role.TECHNICIAN -> Technician(email, fullName, "dummy-hash")
            Role.ADMIN -> Administrator(email, fullName, "dummy-hash")
        }
        if (!active) user.deactivate()
        return users.save(user).also { userIds += it.uid }
    }

    /**
     * ADAPT THIS ONE FUNCTION to P2's real Incident (constructor or factory, required columns such as impact and
     * urgency). Everything else in the tests goes through it. The properties the tests rely on are number, title,
     * description, reporterId, categoryCode, serviceId, assigneeId, priority, status, createdAt, resolvedAt.
     */
    fun incident(
        number: String, title: String, reporter: User,
        description: String = "", status: IncidentStatus = IncidentStatus.NEW, priority: Priority? = null,
        category: String? = null, serviceId: Long? = null, assignee: User? = null,
        createdAt: Instant = Instant.now(), resolvedAt: Instant? = null,
    ): Incident = incidents.save(
        Incident(
            number = number, title = title, description = description, reporterId = reporter.uid,
            categoryCode = category, serviceId = serviceId, assigneeId = assignee?.uid,
            priority = priority, status = status, createdAt = createdAt, resolvedAt = resolvedAt,
        ),
    ).also { incidentIds += requireNotNull(it.id) }

    fun slaEvent(incidentId: Long, type: String) {
        slaEvents.save(SlaEventRef(incidentId = incidentId, type = type))
    }

    private fun service(id: Long, name: String): Long {
        services.save(ItServiceRef(id, name))
        serviceIds += id
        return id
    }

    /**
     *  i1 INC-000001 "Payment app down"   IN_PROGRESS P1 APPLICATION svc1 T1 / E1  3 days ago  (resolution breach)
     *  i2 INC-000002 "VPN cannot connect" ASSIGNED    P2 NETWORK     svc2 T2 / E2  2 days ago  (warning only)
     *  i3 INC-000003 "Email delayed"      RESOLVED    P3 EMAIL       svc3 T1 / E1  1 day ago   (resolved after 120 min)
     *  i4 INC-000004 "Laptop is slow"     NEW         P4 HARDWARE    none -  / E2  now
     */
    fun seed(): Seed {
        val base = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val admin = user("admin", Role.ADMIN)
        val e1 = user("emp1", Role.EMPLOYEE)
        val e2 = user("emp2", Role.EMPLOYEE)
        val t1 = user("tech1", Role.TECHNICIAN)
        val t2 = user("tech2", Role.TECHNICIAN)
        val t3 = user("tech3", Role.TECHNICIAN)
        val s1 = service(9001, "Payment")
        val s2 = service(9002, "VPN")
        val s3 = service(9003, "Email")

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
            IncidentStatus.RESOLVED, Priority.P3, "EMAIL", s3, t1, created3, created3.plus(120, ChronoUnit.MINUTES),
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
