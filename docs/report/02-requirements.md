# 2. Requirements Specification

Conventions: **FR-xx** functional requirement, **NFR-xx** non-functional requirement, **BR-xx** business rule.
"Where" names the main class, endpoint or page that implements the requirement on the `main` branch
(backend package `com.itsm.*` under `backend/src/main/kotlin`, pages under `frontend/src/pages`).
A test for each requirement is listed in [09-test-cases.md](09-test-cases.md).

## 2.1 Traceability to the brief's MVP list

| Brief (MVP) | Status | Requirements | Where implemented |
|---|---|---|---|
| User management: Employee, Technician, Administrator | ✅ | FR-01 to FR-03, FR-30, FR-31 | `user` package (`User` hierarchy, `UserFactory`, `AdminUserController`), `auth` package; pages `/register`, `/login`, `/account`, `/admin/users` |
| Incident management: create | ✅ | FR-10 | `IncidentService.report`, `POST /api/incidents`, page `/incidents/new` |
| Incident management: view | ✅ | FR-11, FR-12 | `IncidentService.get/mine/assigned`, pages `/incidents`, `/incidents/[id]` |
| Incident management: update | ✅ (status, notes, classification, assignment, editing) | FR-13 to FR-19, FR-22 to FR-26, FR-36, FR-59 | lifecycle endpoints of `IncidentController`, `PATCH /api/incidents/{id}` (`Incident.edit`), edit form on `/incidents/[id]` |
| Incident management: assign | ✅ | FR-36, FR-55 | `AssignmentService`, `AdminIncidentController.assign` |
| Incident management: resolve | ✅ | FR-25 | `Incident.resolve`, `POST /api/incidents/{id}/resolve` |
| Incident management: close | ✅ | FR-16, FR-17 | `POST /api/incidents/{id}/verify` then `/close` |
| Priority: impact, urgency, business criticality | ✅ | FR-51 | `Impact`, `Urgency`, `ItService.criticality`, `PriorityService`, `WeightedScoreStrategy`, `MatrixStrategy` |
| Technician assignment: at least two strategies | ✅ | FR-55 | `SkillLeastLoadedStrategy`, `RoundRobinStrategy` |
| SLA: calculation | ✅ | FR-33, FR-52 | `DefaultSlaService`, `SlaPolicyResolver`, table `sla_policies` |
| SLA: deadline tracking | ✅ | FR-52, FR-53 | `Incident.responseDueAt/resolutionDueAt`, `SlaMonitor` |
| SLA: violation detection | ✅ | FR-53, FR-54, FR-40 | `SlaMonitor`, `sla_events`, `EscalationChain`, `SlaRiskDetector`, report `sla-compliance`, search filter `slaStatus`, page `/admin/sla-monitor` |
| Notification: at least two mechanisms | ✅ | FR-57, FR-58 | `InAppChannel` (stored + SSE push) and `EmailChannel` (SMTP or logging gateway) |
| Audit: complete incident history | ✅ | FR-56, FR-39 | `AuditListener`, table `audit_log`, `GET /api/incidents/{id}/history`, `GET /api/admin/audit` |
| Search and reporting: at least five criteria | ✅ (ten) | FR-37, FR-38 | `IncidentSpecifications`, `GET /api/incidents/search`, `ReportService` (six reports) |
| Intelligent features: at least two | ✅ (five) | FR-04, FR-05, FR-55, FR-60, FR-62 | `KeywordCategorySuggester`, `TokenSimilarityDuplicateDetector`, skill/load-aware assignment, `SlaRiskDetector`, `IncidentCorrelator` |
| Persistence | ✅ | NFR-12 | PostgreSQL 17, Flyway migrations, [07-database-design.md](07-database-design.md) |

## 2.2 Functional requirements

### Employee

| ID | Requirement | Where |
|---|---|---|
| FR-01 | An employee can register with email, name, password (8 to 72 characters, at most 72 bytes) and optional department. Registration always creates an Employee, whatever role the body claims. | `AuthController.register`, `AuthService.register`, page `/register` |
| FR-02 | A user can log in and out, see and edit their profile (name, department for employees), change their password and log out of all devices. | `AuthController` (`/api/auth/login`, `/me`, `/change-password`, `/logout-all`), page `/account` |
| FR-03 | A logged-in user sees when they last logged in (previous login). | `UserResponse.previousLoginAt`, page `/account` |
| FR-04 | While reporting, the form suggests a category from the text. | `GET /api/intelligence/suggest-category`, `public/scripts/incident-suggest.js` |
| FR-05 | While reporting, the form lists similar open incidents the user may see (duplicate hint). | `GET /api/intelligence/similar` |
| FR-10 | An employee reports an incident: title (max 200), description (max 4000), impact, urgency, affected users (at least 1), optional service and category. The incident gets a number `INC-nnnnnn`. | `IncidentService.report`, `POST /api/incidents`, page `/incidents/new` |
| FR-11 | An employee lists the incidents they reported (paged, newest first). | `GET /api/incidents/mine`, page `/incidents` |
| FR-12 | An employee opens an incident they reported and sees status, priority with explanation, assignee, SLA deadlines, notes and the history timeline. | `GET /api/incidents/{id}`, `GET /api/incidents/{id}/history`, page `/incidents/[id]`, `IncidentTimeline.astro` |
| FR-13 | The reporter adds comments (additional information). | `POST /api/incidents/{id}/notes` (type COMMENT) |
| FR-14 | The reporter answers an information request, which returns the incident to IN_PROGRESS. | `POST /api/incidents/{id}/resume` |
| FR-15 | The reporter cancels an incident that is still NEW or CLASSIFIED, with an optional reason. | `POST /api/incidents/{id}/cancel` |
| FR-16 | The reporter verifies a resolution (RESOLVED to VERIFIED). | `POST /api/incidents/{id}/verify` |
| FR-17 | The reporter closes a verified incident (VERIFIED to CLOSED). | `POST /api/incidents/{id}/close` |
| FR-18 | The reporter reopens a resolved incident because the fix did not work; resolution and feedback are cleared and the SLA is re-armed. | `POST /api/incidents/{id}/reopen` |
| FR-19 | The reporter gives one rating (1 to 5) and comment after resolution. | `POST /api/incidents/{id}/feedback` |
| FR-20 | Every user sees their notifications (list, unread count, mark one or all as read) and receives new ones live. | `/api/notifications/**`, bell in `Nav.astro`, page `/notifications` |

### Technician

| ID | Requirement | Where |
|---|---|---|
| FR-21 | A technician lists active incidents assigned to them, most urgent first (priority, then resolution deadline). | `GET /api/incidents/assigned`, page `/incidents?tab=assigned` |
| FR-22 | A technician acknowledges an assigned incident. | `POST /api/incidents/{id}/acknowledge` |
| FR-23 | A technician starts work (ACKNOWLEDGED to IN_PROGRESS). | `POST /api/incidents/{id}/start` |
| FR-24 | A technician adds internal investigation notes, visible only to the assignee and administrators. | `POST /api/incidents/{id}/notes` (type INVESTIGATION) |
| FR-25 | A technician requests additional information (IN_PROGRESS to PENDING_INFO) and later resumes work. | `POST /api/incidents/{id}/request-info`, `/resume` |
| FR-26 | A technician resolves an incident with a summary (required) and root cause (optional). | `POST /api/incidents/{id}/resolve` |
| FR-27 | A technician sees their own profile (level, capacity, availability, skills). | page `/dashboard` (technician card) |
| FR-61 | A technician sees their workload: active incidents against capacity, counts per status, each active incident marked OVERDUE, AT_RISK or ON_TRACK, and can switch their availability for automatic assignment (existing assignments are kept). | `GET /api/incidents/workload`, `PATCH /api/me/availability` (`TechnicianWorkloadController`), page `/incidents?tab=assigned` |
| FR-28 | A technician searches the incidents they reported or are assigned to. | `GET /api/incidents/search`, page `/search` |

### Administrator

| ID | Requirement | Where |
|---|---|---|
| FR-30 | Manage users: list with search, role and status filters; create an employee, technician or administrator; edit details; activate or deactivate; reset a password. An administrator cannot deactivate themselves. | `AdminUserController`, `AdminUserService`, pages `/admin/users`, `/admin/users/new`, `/admin/users/[id]` |
| FR-31 | Manage technicians: level (L1, L2, TEAM_LEAD), maximum active incidents, availability and skills (category codes; unknown or inactive categories are refused). | same endpoints; `Technician` entity |
| FR-32 | Manage IT services: list, create, edit, deactivate (never deleted); each has a criticality from 1 to 4. | `AdminServiceController` (`/api/admin/services`), page `/admin/services` |
| FR-33 | Manage SLA policies: the four defaults (one per priority, edit only) plus optional per-service overrides (create, edit, delete); response and resolution minutes and warning threshold are validated. | `AdminSlaPolicyController` (`/api/admin/sla-policies`), `SlaPolicyAdminService`, `SlaPolicyResolver`, page `/admin/sla` |
| FR-34 | Manage incident categories: list, create, rename, describe, deactivate. | `AdminCategoryController`, page `/admin/categories` |
| FR-35 | Configure the priority strategy (`weighted` or `matrix`) and the assignment strategy setting. | `PUT /api/admin/settings/{key}`, page `/admin/settings` (the assignment setting is validated and stored but not yet read by the selector, see the note in [05](05-design-patterns-and-oop.md)) |
| FR-36 | Classify a NEW incident (then the rest of the pipeline runs) and assign or reassign an incident to an active technician, with ranked recommendations. | `POST /api/admin/incidents/{id}/classify`, `/assign`, `GET /api/admin/assignment/recommendations` |
| FR-37 | Search incidents with ten filters and sorting. Administrators see everything; everyone else sees only incidents they reported or are assigned to. | `GET /api/incidents/search`, page `/search` |
| FR-38 | View six reports with charts: overview, technician workload, incidents by service, SLA compliance, mean time to resolve per priority, daily trend. | `ReportController` (`/api/reports/**`), page `/admin/reports` |
| FR-39 | View and filter the audit log by actor, action, incident and date. | `GET /api/admin/audit`, page `/admin/audit` |
| FR-40 | Monitor SLA violations: the overview report counts incidents with a breach, the compliance report gives the percentage, and search can filter by SLA status (ON_TRACK, AT_RISK, BREACHED). | `ReportService.overview/slaCompliance`, `IncidentSpecifications.withSlaStatus` |

### System (automatic)

| ID | Requirement | Where |
|---|---|---|
| FR-50 | Process every new incident through a fixed pipeline: suggest category, classify, prioritise, apply SLA, assign. | `IncidentProcessingPipeline`, `StandardIncidentPipeline` |
| FR-51 | Calculate priority P1 to P4 and a 0 to 10 score from impact, urgency, service criticality, affected users and category, with a stored human-readable explanation. | `PriorityService`, `WeightedScoreStrategy`, `MatrixStrategy` |
| FR-52 | Compute response and resolution deadlines from the applicable SLA policy. | `DefaultSlaService.computeDeadlines` |
| FR-53 | Check open incidents every minute (configurable) and record one warning at the policy's threshold (default 75 %) and a breach when a deadline passes, each at most once per incident. | `SlaMonitor`, `SlaMonitorScheduler` |
| FR-54 | On a breach, run the escalation chain Technician, Team Lead, Administrator: a response breach on an unacknowledged incident reassigns it to the best available team lead; a resolution breach of a P1/P2, or a response breach no team lead can take, notifies the administrators. | `EscalationChain`, `TeamLeadEscalationHandler`, `AdminEscalationHandler`, `JpaTeamLeadSource` |
| FR-55 | Choose a technician with the configured strategy among active, available technicians below their capacity, never the reporter. | `AssignmentService`, `SkillLeastLoadedStrategy`, `RoundRobinStrategy` |
| FR-56 | Publish a domain event for every important action and record each one in the audit log in the same transaction. | `IncidentEvent`, `AuditListener` |
| FR-57 | Notify the relevant people (reporter, assignee, administrators) after the transaction commits, by in-app notification and by email. | `NotificationDispatcher`, `RecipientResolver`, `NotificationFactory`, channels |
| FR-58 | Push new in-app notifications to open browser tabs over Server-Sent Events. | `NotificationStream`, `GET /api/notifications/stream`, `pages/api/notifications/stream.ts` |

| FR-59 | Edit an incident after reporting. Reporter: all editable fields (title, description, impact, urgency, affected users, service) while NEW, CLASSIFIED or ASSIGNED. Assignee: impact, urgency, affected users and service while the incident is active. Administrator: everything until RESOLVED. A change to impact, urgency, users or service recalculates priority and SLA deadlines; the editor's change is audited and the other party notified. | `PATCH /api/incidents/{id}`, `Incident.edit`, `Incident.editableFieldsFor`, `IncidentUpdated`, form on `/incidents/[id]` |
| FR-60 | Predict SLA risk: classify open incidents as BREACHED or AT_RISK with a readable reason; administrators see the list. | `SlaRiskDetector`, `SlaRiskService`, `GET /api/admin/sla/at-risk`, page `/admin/sla-monitor` |
| FR-62 | Correlate incidents: report clusters of at least three open incidents for one service (or category) created within two hours; show them to administrators and as related incidents to people who can see them. | `IncidentCorrelator`, `IncidentCorrelationService`, `GET /api/admin/correlations`, `GET /api/incidents/{id}/related` |
| FR-63 | Optionally seed demo users and about 25 incidents through the real services (never under the `prod` profile). | `DemoDataSeeder`, `DEMO_DATA=true` |
## 2.3 Non-functional requirements

| ID | Category | Requirement | How it is met |
|---|---|---|---|
| NFR-01 | Security | Passwords are never stored in clear text and not readable from the API. | Spring's `DelegatingPasswordEncoder` (BCrypt by default); `User.passwordHash` is private; `toString` hides it |
| NFR-02 | Security | Authentication on every endpoint except register, login, ping, health and API docs. | `SecurityConfig` (`anyRequest().authenticated()`) |
| NFR-03 | Security | Role-based authorisation: `/api/admin/**` is admin only; finer rules per endpoint and per incident. | `SecurityConfig`, `@PreAuthorize("hasRole('ADMIN')")`, `Incident.isVisibleTo`, `IncidentState` |
| NFR-04 | Security | Sessions can be revoked: password change, admin reset, deactivation, log out everywhere. | `users.token_version`, `SessionRevoker`, `UserJwtAuthenticationConverter` |
| NFR-05 | Security | Brute-force protection: 5 failed logins per email lock further attempts for 15 minutes (429 with `Retry-After`). | `LoginAttemptTracker` |
| NFR-06 | Security | The browser never holds the token in JavaScript-readable storage; cross-site request forgery is blocked. | httpOnly SameSite=Lax cookie, `isCrossOriginWrite`, `security.checkOrigin` |
| NFR-07 | Security | All input is validated at the API boundary and again in the domain. | Bean Validation annotations on DTOs, `domainRequire` in entities |
| NFR-08 | Performance | List endpoints are paged and capped (page size at most 100); reports aggregate in the database; names are loaded per page, not per row. | `PageResponse`, `IncidentAssembler`, `ReportService` JPQL, `default_batch_fetch_size: 50`, indexes in the migrations |
| NFR-09 | Performance | A slow mail server must not slow a request. | `AsyncEmailSender` on a bounded pool, SMTP timeouts of 3 to 5 s |
| NFR-10 | Reliability | Concurrent edits to an incident never silently overwrite each other. | `@Version` on `Incident`, 409 `ConcurrentUpdate` |
| NFR-11 | Reliability | A failing listener, channel, email or one bad incident in the SLA run must not undo the user's action or stop others. | `NotificationDispatcher` (per recipient and channel try/catch, `REQUIRES_NEW`), `SlaMonitor` (per incident transaction) |
| NFR-12 | Reliability | Data is durable and consistent. | PostgreSQL, Flyway, check constraints, foreign keys, one transaction per use case |
| NFR-13 | Reliability | The frontend degrades gracefully when the backend is down, slow or returns junk. | 5 s backend timeout, 15 s proxy timeout, 502/503/504 pages, `ApiError` |
| NFR-14 | Usability | Different screens per role; the UI only offers actions the user may perform. | `actionsFor` / `noteTypesFor` computed by the backend (`IncidentDetail.actions`), `Nav.astro` |
| NFR-15 | Usability | Pages work without JavaScript; forms are plain HTML posts; labels, errors and focus are accessible. | Post/redirect/get pages, `FormField`, `Alert`, `ChartCanvas` with "Show as table" |
| NFR-16 | Usability | Errors are understandable and never leak internals. | `ProblemDetail` responses, `ApiError.userMessage`, `server.error.include-message: never` |
| NFR-17 | Maintainability | Modules are separated by narrow contracts; new strategies, channels, escalation steps and settings are added without changing callers. | Interfaces plus Spring beans, event contract `IncidentEvent` |
| NFR-18 | Maintainability | Database schema changes are versioned and Hibernate validates the schema. | Flyway, `ddl-auto: validate` |
| NFR-19 | Maintainability | Automated tests run in CI on every push and pull request. | `.github/workflows/ci.yml` (backend `./gradlew build`; frontend `pnpm check`, `pnpm test`, `pnpm build`) |
| NFR-20 | Portability | One-command start. | `docker-compose.yml` (db, backend, frontend) |
| NFR-21 | Observability | Health and API documentation endpoints. | Actuator `/actuator/health`, springdoc `/swagger-ui.html` |

## 2.4 Business rules

### BR-01 Incident lifecycle (State pattern)

Defined once, in `IncidentState.kt`. A move that is not in the table is refused with **409**
(`InvalidStateTransitionException`); a move in the table by someone who may not make it is refused with **403**
(`UnauthorizedOperationException`).

| From | To | Who (party) | API action |
|---|---|---|---|
| NEW | CLASSIFIED | system, admin | pipeline, `classify` |
| NEW | CANCELLED | reporter, admin | `cancel` |
| CLASSIFIED | ASSIGNED | system, admin | pipeline, `assign` |
| CLASSIFIED | CANCELLED | reporter, admin | `cancel` |
| ASSIGNED | ACKNOWLEDGED | assignee | `acknowledge` |
| ACKNOWLEDGED | IN_PROGRESS | assignee | `start` |
| IN_PROGRESS | PENDING_INFO | assignee | `request-info` |
| IN_PROGRESS | RESOLVED | assignee | `resolve` |
| PENDING_INFO | IN_PROGRESS | assignee, reporter | `resume` |
| RESOLVED | VERIFIED | reporter, admin | `verify` |
| RESOLVED | IN_PROGRESS (reopen) | reporter, admin | `reopen` |
| VERIFIED | CLOSED | reporter, admin, system | `close` |
| CLOSED, CANCELLED | none (final) | | |

Extra rules:

* **Reassignment** is not a transition: from ASSIGNED, ACKNOWLEDGED, IN_PROGRESS or PENDING_INFO an administrator
  (or the system) may assign another technician; the incident goes back to ASSIGNED and `acknowledgedAt` is cleared
  so the new technician must acknowledge and the response SLA is measured again.
* `NEW` to `CLOSED` is impossible (no such edge); the brief's own example.
* Reopening clears `resolvedAt`, summary, root cause and feedback and recomputes SLA deadlines from the reopen time.
* Feedback: reporter only, status RESOLVED, VERIFIED or CLOSED, once, rating 1 to 5, comment at most 1000 characters.
* Notes: INVESTIGATION by assignee or admin (hidden from everybody else, even a technician who is the reporter);
  COMMENT by reporter, assignee or admin; INFO_REQUEST only through `request-info`; INFO_RESPONSE only through `resume`;
  nothing can be added to a CLOSED or CANCELLED incident.

### BR-02 Priority

* Impact (INDIVIDUAL 1, TEAM 2, DEPARTMENT 3, ORGANISATION 4), urgency (LOW 1, MEDIUM 2, HIGH 3, CRITICAL 4) and
  service criticality (1 to 4, `it_services.criticality`, default 2; 2 also when the incident has no service) feed a
  `PriorityStrategy`.
* The administrator chooses the strategy with setting `priority.strategy` (`weighted` default, `matrix`). An unknown
  stored value falls back to `weighted` and logs a warning once.
* Priority is calculated when the incident is reported (even if it stays NEW), again after manual classification,
  and a `PriorityChanged` event (with the explanation) is published when it changes.
* Formulas and thresholds: [05-design-patterns-and-oop.md](05-design-patterns-and-oop.md).

### BR-03 SLA

Default policy (migration `V20261118_1300__service_catalog_and_sla.sql`; a service may have its own row per priority, which then wins):

| Priority | Response within | Resolution within | Warning at |
|---|---|---|---|
| P1 | 5 min | 30 min | 75 % |
| P2 | 15 min | 2 h (120 min) | 75 % |
| P3 | 60 min | 8 h (480 min) | 75 % |
| P4 | 240 min | 24 h (1440 min) | 75 % |

* Deadlines are counted from the incident's creation time (`createdAt`); after a reopen from the reopen time.
* The response deadline stops counting when the incident is acknowledged; the resolution deadline when it is resolved.
* Only OPEN incidents (NEW to PENDING_INFO) with a priority are monitored.
* One WARNING per incident (for whichever window reaches the threshold first), then a breach event per type
  (`RESPONSE_BREACH`, `RESOLUTION_BREACH`) at most once per incident (unique constraint on `sla_events`).
* A breach starts the escalation chain (FR-54). If no policy exists for a priority the check for that incident fails
  with `SlaNotConfiguredException` (logged; the other incidents are still checked).
* The SLA clock is not paused while waiting for information (known limitation).

### BR-04 Assignment

* Candidates are active, available technicians whose active incident count (ASSIGNED, ACKNOWLEDGED, IN_PROGRESS,
  PENDING_INFO) is below `maxActiveIncidents`, excluding the reporter.
* `skill-least-loaded` (default): prefer technicians with the incident's category as a skill; then, for P1 and P2,
  higher level while load is below 80 %; then lowest load ratio, fewest active incidents, lowest id.
* `round-robin`: next candidate by id, ignoring skills and priority.
* If nobody qualifies the incident stays CLASSIFIED and an administrator assigns it (recommendations are offered).
* An administrator may only assign to an active technician who is not the reporter.

### BR-05 Visibility

An incident, its notes and its history can be read by its reporter, its assigned technician and administrators
(`Incident.isVisibleTo`); anybody else gets 403 (404 if the incident does not exist). Search, duplicate hints and
history apply the same rule. INVESTIGATION notes are further limited to assignee and administrators.

### BR-06 Notification recipients (`RecipientResolver`)

The person who performed the action is never notified; inactive users are skipped; `StatusChanged` to CLASSIFIED,
ASSIGNED, RESOLVED or CLOSED is not notified because the specific event covers it.

| Event | Recipients |
|---|---|
| IncidentReported, IncidentClassified, InfoRequested, IncidentResolved | reporter |
| IncidentAssigned | assignee and reporter |
| PriorityChanged | assignee |
| StatusChanged (other statuses), IncidentClosed | reporter and assignee |
| NoteAdded | COMMENT: reporter and assignee; INVESTIGATION: assignee only; INFO_REQUEST: nobody (InfoRequested covers it) |
| SlaWarningRaised | assignee, or administrators when nobody is assigned |
| SlaBreached | assignee and administrators |
| IncidentEscalated | assignee, the team lead escalated to (if any) and administrators |
| IncidentUpdated | reporter and assignee (the editor is never notified) |

### BR-07 Users

* Email is trimmed and lower-cased and unique. Roles: EMPLOYEE, TECHNICIAN, ADMIN. Only technicians have level,
  capacity, availability and skills; only employees have a department.
* Passwords are 8 to 72 characters and at most 72 UTF-8 bytes (BCrypt limit).
* A deactivated user cannot log in and their existing tokens stop working immediately.

### BR-08 Editing an incident

| Who | When | Fields |
|---|---|---|
| Reporter | NEW, CLASSIFIED, ASSIGNED | title, description, impact, urgency, affected users, service |
| Assignee | active (ASSIGNED to PENDING_INFO) | impact, urgency, affected users, service |
| Administrator | until RESOLVED | all |

Nothing can be edited from RESOLVED onwards (409). An edit that changes no value is a no-op (no event, no audit entry); a request naming no field is a 400. A change to impact, urgency, affected users or service re-runs priority and SLA calculation; deadlines keep
their original start time.

## 2.5 Roles and permissions matrix

| Capability | Employee | Technician | Administrator | Enforced by |
|---|---|---|---|---|
| Register, log in, edit own profile, change password | yes | yes | yes | `SecurityConfig` (public or authenticated) |
| Report an incident | yes | yes | yes | `IncidentService.report` (any role) |
| View an incident | own | own or assigned | all | `Incident.isVisibleTo` |
| List "mine" | yes | yes | yes | `IncidentService.mine` |
| List "assigned" | no (403) | yes | no (403) | `IncidentService.assigned` |
| Acknowledge, start, request info, resolve | no | assignee | no | `IncidentState` parties |
| Resume from PENDING_INFO | reporter | assignee | no | `PendingInfoState` |
| Verify, reopen, close | reporter | reporter (if reporter) | yes | `ResolvedState`, `VerifiedState` |
| Cancel (NEW or CLASSIFIED) | reporter | reporter (if reporter) | yes | `NewState`, `ClassifiedState` |
| Feedback | reporter | reporter | no (not reporter) | `Incident.submitFeedback` |
| Investigation note | no | assignee | yes | `noteTypesFor` |
| Comment | reporter | reporter or assignee | yes | `noteTypesFor` |
| Edit incident fields (BR-08) | reporter, early | assignee, limited | yes | `Incident.editableFieldsFor` |
| My workload, availability | no | yes | no | `TechnicianWorkloadService` |
| SLA monitor, possible major incidents, SLA policies | no | no | yes | `/api/admin/sla`, `/api/admin/correlations`, `/api/admin/sla-policies` |
| Classify, assign, reassign | no | no | yes | `requireAdmin`, `/api/admin/**` |
| Assignment recommendations | no | no | yes | `AssignmentController` `@PreAuthorize` |
| Category suggestion, similar incidents | yes | yes | yes | any logged-in user |
| List categories and active services | yes | yes | yes | any logged-in user |
| Create or edit categories, services, users | no | no | yes | `/api/admin/**` |
| Settings (strategies) | no | no | yes | `/api/admin/settings` |
| Search | own scope | own scope | all | `IncidentSpecifications.visibleTo` |
| Reports | no | no | yes | `ReportController` `@PreAuthorize` |
| Audit log (global) | no | no | yes | `/api/admin/audit` |
| Incident history timeline | visible incidents | visible incidents | yes | `AuditQueryService.history` |
| Notifications (own) | yes | yes | yes | `NotificationController` |

## 2.6 Exceptional situations the brief lists, and where each is handled

| Brief item | Handling | HTTP |
|---|---|---|
| Invalid login | `InvalidCredentialsException`, same message for unknown email and wrong password | 401 |
| Unauthorised operation | `UnauthorizedOperationException`, `AccessDeniedException` | 403 |
| Invalid incident | `IncidentNotFoundException`; validation errors | 404, 400 |
| Invalid state transition | `InvalidStateTransitionException` | 409 |
| Technician unavailable / workload exceeded | not an error: unavailable and full technicians are filtered out of the candidates; with none left the incident waits as CLASSIFIED for an administrator | none |
| Service not found | `DomainValidationException` ("Service n does not exist or is not active") | 400 |
| Missing SLA configuration | `SlaNotConfiguredException` | 500 |
| Duplicate incident | hint only (`/api/intelligence/similar`); a duplicate is not blocked | n/a |
| Database failure | unhandled `DataAccessException` becomes a generic 500 from the container; concurrent update is 409 | 500, 409 |
| Network failure | frontend: 502 / 503 / 504 pages and `ApiError` | 502, 503, 504 |
| Invalid user input | `MethodArgumentNotValidException` with per-field `errors` | 400 |
