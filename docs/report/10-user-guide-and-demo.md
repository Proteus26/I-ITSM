# 10. User Guide and Demonstration

## 10.1 How to run

### Option 1: Docker (recommended for the demo)

Only Docker is required.

```sh
cp .env.example .env            # optional; the defaults work
DEMO_DATA=true docker compose up --build
```

| URL | What |
|---|---|
| http://localhost:4321 | the application (frontend) |
| http://localhost:8080/swagger-ui.html | interactive API documentation (use **Authorize** and paste a token) |
| http://localhost:8080/actuator/health | backend health |
| `localhost:5432` (user, password, database all `itsm`) | PostgreSQL |

`DEMO_DATA=true` creates the demo users and incidents below at start-up (development only). Without it only the
administrator exists. Stop with `docker compose down`; add `-v` to delete the database.

To see emails during the demo, run Mailpit and switch the gateway on:

```sh
docker run -p 1025:1025 -p 8025:8025 axllent/mailpit      # inbox at http://localhost:8025
ITSM_MAIL_GATEWAY=smtp MAIL_HOST=host.docker.internal MAIL_PORT=1025 DEMO_DATA=true docker compose up --build
```

(Without this, emails are written to the backend log as `EMAIL to=... subject=...`, which is also enough to demonstrate the
second notification mechanism.)

### Option 2: local development

```sh
docker compose up -d db                       # PostgreSQL only
cd backend
export JWT_SECRET=$(openssl rand -base64 48)  # required, at least 32 bytes
export ADMIN_EMAIL=admin@itsm.local ADMIN_PASSWORD='ChangeMe123!' DEMO_DATA=true
./gradlew bootRun
# second terminal
cd frontend && pnpm install && pnpm dev       # http://localhost:4321
```

Requirements: JDK 21, Node 24 with pnpm 12 (`corepack enable`), Docker. Configuration reference: root `README.md`.

## 10.2 Demo accounts

All passwords are `ChangeMe123!`.

| Role | Email | Notes |
|---|---|---|
| Administrator | `admin@itsm.local` | created from `ADMIN_EMAIL` / `ADMIN_PASSWORD` |
| Employee | `employee@itsm.local` (Erin Employee) | `DEMO_DATA=true`; reporter of the five demo incidents |
| Technician L2 | `tech1@itsm.local` (Tariq Network) | skills NETWORK, AUTHENTICATION, SECURITY |
| Technician L1 | `tech2@itsm.local` (Sofia Software) | skills SOFTWARE, APPLICATION, DATABASE |
| Technician TEAM_LEAD | `tech3@itsm.local` (Hugo Hardware) | skills HARDWARE, CLOUD, STORAGE |

New employees can register themselves at `/register`. The demo incidents are listed in
[07-database-design.md](07-database-design.md), section 7.4.
<!-- TODO(verify after merge): richer demo data is being added by a parallel track; update this table and the demo
script below to match the final seeded accounts and incidents. -->

## 10.3 Screens by role

| Screen | URL | Who | What it does |
|---|---|---|---|
| Home | `/` | everyone | backend reachability |
| Register, log in | `/register`, `/login` | guests | self-service employee accounts; login with throttling message |
| Dashboard | `/dashboard` | all | role-specific cards: report, my incidents; technicians also "Assigned to me" and a profile card; administrators an administration card |
| Report an incident | `/incidents/new` | all | form with title, description, impact, urgency, affected users, service, category; live category suggestion ("Use it") and similar open incidents |
| My / assigned incidents | `/incidents`, `/incidents?tab=assigned` | all / technicians | paged lists with status and priority badges; technicians default to the assigned tab |
| Incident detail | `/incidents/[id]` | reporter, assignee, admin | all fields, SLA due times (overdue highlighted), notes thread, history timeline, and only the action forms the caller may use (acknowledge, start, request info, resume, resolve, verify, reopen, close, cancel, feedback; admins also classify and assign with ranked recommendations) |
| Search | `/search` | all (own scope), admin (everything) | ten filters, sorting, paging |
| Notifications | `/notifications` | all | list, unread filter, mark read, mark all read; bell in the header shows the unread count and updates live |
| Account | `/account` | all | edit profile, change password, log out of all devices, previous login |
| Users | `/admin/users`, `/admin/users/new`, `/admin/users/[id]` | admin | search, filter, create employee / technician / admin, edit, deactivate, reset password, technician level, capacity, skills |
| Categories | `/admin/categories` | admin | create, rename, describe, deactivate |
| Settings | `/admin/settings` | admin | priority strategy, assignment strategy (see the note in 5.3), IT service list with criticality |
| Reports | `/admin/reports` | admin | overview tiles, SLA compliance, charts: incidents per day, by service, workload per technician, mean time to resolve |
| Audit log | `/admin/audit` | admin | filter by actor, action, incident, dates |

<!-- TODO(verify after merge): new screens from the parallel tracks (admin SLA policy and services pages, technician
workload view, admin SLA monitor, role dashboards, incident editing, correlation) belong in this table. -->

## 10.4 Walkthrough: the brief's 14-step scenario

Scenario: *"The organization's payment application is unavailable and users are unable to complete transactions."*
Start with `DEMO_DATA=true`. Use two browser windows (or one normal and one private window) so you can be the employee
and a technician at the same time.

### Script

1. Log in as `employee@itsm.local`. Open **Report incident** (`/incidents/new`).
2. Title `Payment application is unavailable`, description `The organization's payment application is unavailable and
   users are unable to complete transactions.`, impact **ORGANISATION**, urgency **CRITICAL**, affected users `500`,
   service **Payment**. Watch the category suggestion (APPLICATION, from the word "application") and similar-incident panel
   appear while typing; click "Use it". Submit.
3. You land on the incident page. Show: number `INC-0000nn`, status **ASSIGNED**, priority **P1** with the explanation
   (`Impact ORGANISATION (4) × urgency CRITICAL (4) × service criticality 4, 500 affected users → score 9.3 → P1`),
   response due in 5 minutes and resolution due in 30, assignee Sofia Software, and the history timeline.
   (With `ASSIGNMENT_STRATEGY=skill-least-loaded` Sofia is the only technician with the APPLICATION skill.)
4. In the other window log in as `tech2@itsm.local`: the bell shows a notification "INC-... assigned to you" (live, no reload).
   Open **Assigned to me**: the P1 incident is first. **Acknowledge**, **Start**, add an **investigation note**, then
   **Request information** ("Which payment method fails?").
5. As the employee: the bell lights up; open the incident, **Respond** (resume) with an answer. Status returns to IN_PROGRESS.
6. As the technician: **Resolve** with a summary and root cause. The employee is notified, **Verifies**, gives
   **feedback**, and **Closes** the incident (VERIFIED then CLOSED).
7. Log in as `admin@itsm.local`: **Search** (filter by priority P1 or SLA status), **Reports** (the incident is in the
   counts), **Audit log** (every step with actor and before/after state).

### Mapping to the 14 steps

| # | Brief step | In the system | Screen / endpoint | Code |
|---|---|---|---|---|
| 1 | Create an incident | The employee submits the form; the incident gets number `INC-nnnnnn` and status NEW | `/incidents/new`, `POST /api/incidents` | `IncidentService.report` |
| 2 | Classify the incident | Category suggested while typing and, if none chosen, by the pipeline (confidence at least 0.5); NEW to CLASSIFIED; administrators can classify manually | suggestion panel, `GET /api/intelligence/suggest-category`; admin form on the incident page, `POST /api/admin/incidents/{id}/classify` | `KeywordCategorySuggester`, `StandardIncidentPipeline.classify` |
| 3 | Identify the affected service | Service chosen on the form (catalog of eight seeded services) | `GET /api/services` | `ServiceCatalog`, `Incident.serviceId` |
| 4 | Determine business impact | Impact (1 to 4), urgency, affected users and the service's criticality (1 to 4) | form fields; `it_services.criticality` | `Impact`, `Urgency`, `ItService.criticality` |
| 5 | Calculate its priority | P1 to P4 with a score and an explanation; strategy `weighted` or `matrix` | incident detail ("Priority" with explanation), `PUT /api/admin/settings/priority.strategy` | `PriorityService`, `WeightedScoreStrategy`, `MatrixStrategy` |
| 6 | Determine the applicable SLA | Response and resolution deadlines from the default or service-specific policy (P1: 5 / 30 minutes) | due times on the incident page | `DefaultSlaService.computeDeadlines`, table `sla_policies` |
| 7 | Identify suitable technicians | Candidates: active, available, below capacity, not the reporter; ranked by skill, level (P1/P2) and load | admin incident page shows ranked recommendations, `GET /api/admin/assignment/recommendations` | `AssignmentService.recommend`, `SkillLeastLoadedStrategy` |
| 8 | Assign the incident | Chosen automatically (CLASSIFIED to ASSIGNED) with a stored reason, or manually by an administrator | automatic in the pipeline; `POST /api/admin/incidents/{id}/assign` | `AssignmentService.assign`, `Incident.assign` |
| 9 | Notify relevant personnel | In-app notification (live over SSE) and email to assignee, reporter and, for SLA events, administrators | bell and `/notifications`; Mailpit or backend log | `NotificationDispatcher`, `InAppChannel`, `EmailChannel` |
| 10 | Track investigation and resolution | acknowledge, start, notes, request information, resume, resolve; verify and close by the reporter | action forms on `/incidents/[id]`, lifecycle endpoints | `IncidentService`, `IncidentState` |
| 11 | Monitor SLA compliance | Every minute the monitor records a warning at 75 % and a breach at the deadline, then escalates; shown on reports and in search | `/admin/reports` (SLA compliance), `/search?slaStatus=BREACHED`, notifications to assignee and admins | `SlaMonitor`, `EscalationChain` |
| 12 | Record all important activities | Each action publishes an event that writes an audit row in the same transaction | timeline on the incident page, `GET /api/incidents/{id}/history`; `/admin/audit` | `AuditListener`, `audit_log` |
| 13 | Close the incident after resolution | RESOLVED, VERIFIED (reporter), CLOSED; feedback rating | `POST /api/incidents/{id}/verify`, `/close`, `/feedback` | `ResolvedState`, `VerifiedState` |
| 14 | Include it in reports and analytics | Overview, workload, by service, SLA compliance, MTTR, daily trend | `/admin/reports`, `GET /api/reports/*` | `ReportService` |

### To show an SLA breach quickly

The default P1 SLA is 5 minutes to respond. Report a P1 incident as above and wait: after 75 % (about 3.75 minutes) the
monitor records a warning, after 5 minutes without acknowledgement a response breach, which publishes
`SlaBreached` and `IncidentEscalated(REASSIGN_REQUESTED)`; the assignee and administrators get notifications and the
audit log shows `SLA_WARNING` and `SLA_BREACHED`. For a faster demo start the backend with `SLA_MONITOR_INTERVAL=5000`
(check every 5 seconds).

## 10.5 The three demonstration workflows required by the brief

The brief asks for at least three complete workflows in the recorded video.

**Workflow 1: employee reports, system prioritises, technician assigned, notification generated.**
Steps 1 to 3 and 4 (first part) above: report the payment incident; show the P1 priority and its explanation, the SLA
due times, the automatic assignment with the reason, the live notification for the technician and the email in Mailpit
or the log. Change the priority strategy to `matrix` in `/admin/settings`, report another incident and show that the
explanation changes.

**Workflow 2: technician investigates, SLA monitored, incident resolved, user notified.**
Acknowledge, start, investigation note, request information, reporter's answer, resolve, verify, close (steps 4 to 6),
and the SLA warning and breach demonstration. Show the history timeline and the reporter's notifications.

**Workflow 3: duplicates, related incidents, escalation.**
While reporting a second incident ("Payment page error 500", similar words) show the **similar incidents** panel listing
the open payment incident (`TokenSimilarityDuplicateDetector`, threshold 0.4). Then let an unacknowledged incident breach
its response SLA and show the escalation event and notifications (`REASSIGN_REQUESTED`; `ADMIN` for a resolution breach of
a P1 or P2). As administrator use **Assign** to reassign the incident to another technician from the ranked
recommendations: the incident returns to ASSIGNED and must be acknowledged again.
<!-- TODO(verify after merge): incident correlation (linking related incidents) and the Technician -> Team Lead ->
Admin escalation are in progress on parallel tracks. Today duplicates are hints only and escalation only publishes
events; update this workflow with the final behaviour and screens after the merge. -->

## 10.6 API quick reference

Interactive documentation is at `/swagger-ui.html`. Example with curl:

```sh
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"employee@itsm.local","password":"ChangeMe123!"}' | jq -r .token)

curl -s localhost:8080/api/incidents -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"Payment application is unavailable","description":"Users cannot complete transactions","impact":"ORGANISATION","urgency":"CRITICAL","affectedUsers":500}' | jq .
```

Endpoint groups: `/api/auth/**`, `/api/admin/users/**`, `/api/incidents/**` (lifecycle, notes, search, history),
`/api/admin/incidents/**`, `/api/categories`, `/api/admin/categories`, `/api/services`, `/api/admin/services`,
`/api/admin/settings`, `/api/admin/assignment/recommendations`, `/api/intelligence/**`, `/api/notifications/**` (including the
SSE stream), `/api/reports/**`, `/api/admin/audit`, `/api/ping`.

## 10.7 Troubleshooting

| Symptom | Fix |
|---|---|
| Backend fails with "JWT secret is missing or too short" | set `JWT_SECRET` to at least 32 bytes (compose sets a development value) |
| Cannot log in as the demo users | start with `DEMO_DATA=true`; the seeder refuses to run under the `prod` profile |
| 429 on login | five failed attempts lock that email for 15 minutes; wait, or restart the backend (the throttle is in memory) |
| No technician is assigned | the technicians are at capacity or unavailable, or have no matching skill pool; the incident waits as CLASSIFIED for an admin to assign |
| Emails do not arrive | by default they are only logged; start Mailpit and set `ITSM_MAIL_GATEWAY=smtp` |
| Docker build cannot reach npm or Maven | check the container's outbound internet access |
