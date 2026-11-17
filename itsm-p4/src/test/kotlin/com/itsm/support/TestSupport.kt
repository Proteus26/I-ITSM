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
import com.itsm.user.Role
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

/** A small fixed set of users, services and incidents that the search and report tests share. */
data class Seed(
    val admin: User, val e1: User, val e2: User, val t1: User, val t2: User, val t3: User,
    val s1: Long, val s2: Long, val s3: Long,
    val i1: Incident, val i2: Incident, val i3: Incident, val i4: Incident,
    val base: Instant,
)

@Component
class TestData(
    private val users: UserRepository,
    private val incidents: IncidentRepository,
    private val services: ItServiceRefRepository,
    private val slaEvents: SlaEventRefRepository,
    private val jdbc: JdbcTemplate,
) {
    fun reset() {
        listOf("notifications", "audit_log", "sla_events", "incidents", "it_services", "users")
            .forEach { jdbc.update("delete from $it") }
    }

    fun user(username: String, role: Role, active: Boolean = true): User = users.save(
        User(
            username = username, fullName = username.replaceFirstChar { it.uppercase() },
            email = "$username@example.test", employeeCode = null, role = role, active = active,
        ),
    )

    fun incident(
        number: String, title: String, reporter: User,
        description: String = "", status: IncidentStatus = IncidentStatus.NEW, priority: Priority? = null,
        category: String? = null, serviceId: Long? = null, assignee: User? = null,
        createdAt: Instant = Instant.now(), resolvedAt: Instant? = null,
    ): Incident = incidents.save(
        Incident(
            number = number, title = title, description = description, reporterId = reporter.id,
            categoryCode = category, serviceId = serviceId, assigneeId = assignee?.id,
            priority = priority, status = status, createdAt = createdAt, resolvedAt = resolvedAt,
        ),
    )

    fun slaEvent(incidentId: Long, type: String) {
        slaEvents.save(SlaEventRef(incidentId = incidentId, type = type))
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
        services.save(ItServiceRef(1, "Payment")); services.save(ItServiceRef(2, "VPN")); services.save(ItServiceRef(3, "Email"))

        val i1 = incident(
            "INC-000001", "Payment app down", e1, "Checkout fails for every customer",
            IncidentStatus.IN_PROGRESS, Priority.P1, "APPLICATION", 1, t1, base.minus(3, ChronoUnit.DAYS),
        )
        val i2 = incident(
            "INC-000002", "VPN cannot connect", e2, "Timeout when dialling in from home",
            IncidentStatus.ASSIGNED, Priority.P2, "NETWORK", 2, t2, base.minus(2, ChronoUnit.DAYS),
        )
        val created3 = base.minus(1, ChronoUnit.DAYS)
        val i3 = incident(
            "INC-000003", "Email delayed", e1, "Messages arrive an hour late",
            IncidentStatus.RESOLVED, Priority.P3, "EMAIL", 3, t1, created3, created3.plus(120, ChronoUnit.MINUTES),
        )
        val i4 = incident("INC-000004", "Laptop is slow", e2, "Takes ten minutes to boot", IncidentStatus.NEW, Priority.P4, "HARDWARE", null, null, base)

        slaEvent(i1.id, "RESOLUTION_BREACH")
        slaEvent(i2.id, "WARNING")
        return Seed(admin, e1, e2, t1, t2, t3, 1, 2, 3, i1, i2, i3, i4, base)
    }
}
