# ITSM: P4 (events, notifications, audit, search, reports)

Kotlin + Spring Boot 3 (JPA, PostgreSQL, Flyway). This module records and announces every important action,
and lets admins search and analyse incidents.

## What is in here

| Area | Where | Pattern / notes |
| --- | --- | --- |
| Events (the contract) | `events/IncidentEvent.kt` | Sealed classes. Everyone else publishes these. |
| Audit | `audit/` | `@EventListener`, same transaction. JSONB `details`. |
| Notifications | `notification/` | Observer (`@TransactionalEventListener(AFTER_COMMIT)`), Adapter (`EmailGateway`), Factory (`NotificationFactory`) |
| Live updates | `notification/stream/` | Server-Sent Events at `/api/notifications/stream` |
| Search | `search/` | JPA Specifications, 10 filters, pagination, sorting |
| Reports | `reports/` | 6 endpoints behind the admin dashboard |
| Pages | `src/main/resources/static/p4/` | Plain HTML/JS: bell, notifications, search, dashboard (Chart.js), audit viewer |
| Tests | `src/test/` | 5 required scenarios plus extras |

## Run it

```bash
docker compose up -d          # PostgreSQL + Mailpit (fake mail server)
gradle wrapper                # once, if the repo has no ./gradlew yet
./gradlew test                # tests use in-memory H2, no Docker needed
./gradlew bootRun
```

* Mailpit inbox (see the emails arrive during the demo): http://localhost:8025
* Pages: http://localhost:8080/p4/notifications.html, `search.html`, `dashboard.html`, `audit.html`
* To send emails to the log instead of Mailpit: set `itsm.mail.gateway: logging`.

## How the other parts use P4

Publish an event from inside the `@Transactional` method that changes the data:

```kotlin
@Service
class IncidentService(private val publisher: ApplicationEventPublisher /* ... */) {
    @Transactional
    fun acknowledge(id: Long, actor: User) {
        val incident = /* load, check, transitionTo(...) */
        publisher.publishEvent(StatusChanged(incident.id, actor.id, from, to))
    }
}
```

Rules for publishers:

1. Publish `StatusChanged` on **every** status transition. The audit timeline is built from it.
2. Also publish the specific event where one exists (`IncidentAssigned`, `IncidentResolved`, `IncidentClosed`,
   `IncidentClassified`, `InfoRequested`...). `RecipientResolver` skips `StatusChanged` for CLASSIFIED / ASSIGNED /
   RESOLVED / CLOSED so people are not told twice, which means those specific events are what notify people.
3. `actorId` is the user who did it, or `null` for the system (scheduler, auto-assignment).
4. Publish inside the transaction. Audit then commits or rolls back with the action, and notifications are only
   sent after a successful commit.
5. Login events: `UserLoggedIn(userId, username, ip)` and `LoginFailed(null, username, ip)` from P1.

## API

| Endpoint | Who |
| --- | --- |
| `GET /api/incidents/{id}/history` | Reporter, assignee, admin, manager, team lead |
| `GET /api/admin/audit?actor=&action=&incident=&from=&to=&page=&size=` | Admin, IT manager |
| `GET /api/notifications?unread=&page=&size=` | Logged-in user (own notifications) |
| `GET /api/notifications/unread-count`, `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all` | Logged-in user |
| `GET /api/notifications/stream` (SSE) | Logged-in user |
| `GET /api/incidents/search?number=&from=&to=&status=&priority=&category=&service=&assignee=&reporter=&slaStatus=&q=&page=&size=&sort=` | Employee (own), technician (assigned or own), admin/manager/lead (all) |
| `GET /api/reports/overview`, `/technician-workload`, `/by-service`, `/sla-compliance`, `/mttr`, `/trend?days=30` | Admin, IT manager |

## Definitions used by the reports and search

* **Open**: NEW, CLASSIFIED, ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO. **Active** (counts toward a
  technician's workload): ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO. **Resolved**: RESOLVED, VERIFIED, CLOSED.
* **Critical**: open incidents with priority P1.
* **SLA violation / breached**: the incident has a `RESPONSE_BREACH` or `RESOLUTION_BREACH` row in P3's `sla_events`.
* **SLA at risk**: still open, has a `WARNING` event, no breach. **On track**: everything else.
* **SLA compliance**: incidents without a breach divided by all non-cancelled incidents.
* **MTTR**: average minutes from `created_at` to `resolved_at`, per priority.
* Dates in search, audit filters and the trend chart are UTC days; `to` is inclusive.

## Merging with P1, P2 and P3

This repo compiles and tests on its own because it includes **stand-ins** for the types it needs from others.
When the real ones are merged, delete the stand-in and fix imports:

| Stand-in | Owner | Delete when |
| --- | --- | --- |
| `incident/Incident.kt`, `IncidentStatus.kt`, `IncidentRepository.kt` | P2 | P2's entity is merged (must expose the fields listed in the file header, and the repository must extend `JpaSpecificationExecutor`) |
| `user/User.kt`, `UserRepository.kt` | P1 | P1's user is merged (needs `employeeCode`, `fullName`, `email`, `role`, `active`, and the two finders) |
| `sla/Priority.kt` | P3 | P3's enum is merged |
| `ItsmApplication.kt` | P1 | The team's main class exists |

Things to adapt (each is in one place):

* **Current user**: `common/Common.kt` → `SecurityContextCurrentUserProvider` looks the user up by `authentication.name`.
* **Roles**: `@PreAuthorize("hasRole('ADMIN')")` expects authorities named `ROLE_ADMIN`, `ROLE_IT_MANAGER`.
* **Incident link in notifications**: `itsm.notifications.incident-link` in `application.yml`.
* **Frontend token**: `static/p4/p4.js` → `ITSM.headers()` reads `localStorage["token"]` as a Bearer token.
* **Migration number**: rename `V40__p4_audit_and_notifications.sql` so it comes after the other migrations.
* **Dependencies**: merge the `build.gradle.kts` dependencies (web, data-jpa, security, mail, flyway, postgres) into the shared build file.
* **P3's `sla_events`**: P4 reads it through a small read-only mapping (`reference/ReferenceEntities.kt`) and assumes
  columns `incident_id`, `type` (string), `occurred_at`. Same for `it_services(id, name)`.
* If P2 maps `reporter`, `assignee` or `service` as relations instead of plain id columns, update
  `IncidentSpecifications` and `ReportService` (search for `reporterId`, `assigneeId`, `serviceId`).

## Design decisions worth mentioning in the report

* **Audit is in-transaction, notifications are after-commit.** Audit can never be missing for a saved action;
  nobody is emailed about something that rolled back. Failures while notifying are logged and never undo the action.
* **Login events are not incident events.** The brief puts `UserLoggedIn` / `LoginFailed` in the same family, but they
  have no incident, so they extend a shared `AppEvent` root instead of `IncidentEvent`. Audit listens to the root.
* **Email falls back to logging** if the SMTP server is unreachable (`EmailChannel` + `LoggingEmailGateway`).
* **Search respects visibility**: employees only find their own incidents, technicians find assigned or own ones.
* **SSE** uses `fetch()` streaming rather than `EventSource` so the Authorization header can be sent.
  State is in memory, so it assumes a single server instance.
