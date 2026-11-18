# 11. Implementation Notes

This chapter records how the design of chapters 3 to 5 was turned into code, the conventions the team followed, and
the points that were hard or that behave in a way a reader might not expect.

## 11.1 Project layout

```
backend/src/main/kotlin/com/itsm/
  auth/  user/  config/  web/  common/exception/     accounts, JWT, security, error mapping
  incident/  category/  demo/                        incident aggregate, state, pipeline, REST API, demo data
  priority/  service/  sla/ (escalation, risk)  settings/    priority strategies, catalog, SLA, escalation chain
  assignment/  intelligence/                         assignment strategies, suggestion, duplicates, correlation
  events/  audit/  notification/ (channel, stream)  search/  reports/  reference/
backend/src/main/resources/db/migration/             seven Flyway migrations (chapter 7)
frontend/src/ (pages, components, layouts, lib, middleware.ts)   Astro pages and the typed backend client
frontend/test/                                        Vitest tests of the logic in src/lib
```

Each backend package was owned by one team member (modules P1 to P5 in section 3.2). They meet only through small
contracts: interfaces, DTOs and the sealed `IncidentEvent` class.

## 11.2 Conventions

* **One transaction per use case.** Controllers parse and validate, `@Service` methods are `@Transactional`, and the
  domain classes hold the rules.
* **Rich domain, private setters.** `Incident` changes only through methods such as `classify`, `assign`, `resolve`,
  `edit` and `applyPriority`, which validate input and delegate lifecycle checks to `IncidentState`.
* **People by id.** `Incident` stores `reporterId` and `assigneeId`, not entities, so packages stay decoupled.
* **Time is injected.** Services take a `Clock`; the domain takes `now` as a parameter. Tests move the clock instead of
  sleeping.
* **Events for side effects.** Code that changes an incident publishes an `IncidentEvent` and knows nothing about
  audit or notifications.
* **Errors are typed.** Every failure is an `ItsmException` subclass carrying its HTTP status; `GlobalExceptionHandler`
  turns it into `application/problem+json` (chapter 8).
* **Migrations are append-only**, named `V<yyyymmdd>_<hhmm>__description.sql` so parallel branches do not collide.
* **Frontend.** Pages call the backend on the server through one typed client (`lib/backend.ts`); browser code calls the
  same-origin `/api/**` proxy (`pages/api/[...path].ts`). Logic that can be pure lives in `src/lib` and is unit tested.

## 11.3 Notes on selected features

**Report pipeline.** `IncidentService.report` saves the incident, then calls `StandardIncidentPipeline` (suggest category,
classify, prioritise, apply SLA, assign) in the same transaction. A manual classification by an administrator reuses the
same pipeline; each step does nothing when it does not apply.

**Editing.** `PATCH /api/incidents/{id}` maps to `IncidentService.update`, which asks `Incident.edit` to apply the change.
The domain decides which fields the actor may touch (`editableFieldsFor`) and reports only fields that really changed
(`EditResult`). If a priority-relevant field changed, the service re-runs the priority and SLA steps; the deadlines keep
their original start time. One `IncidentUpdated` event follows.

**Events, audit and notifications.** `AuditListener` is a plain `@EventListener`, so the audit row commits or rolls back
with the action. `NotificationDispatcher` uses `@TransactionalEventListener(AFTER_COMMIT)`; each recipient and channel runs in
its own `REQUIRES_NEW` transaction inside a try/catch, so a failing email never undoes the action. The SSE push is registered as an
`afterCommit` callback, so the browser is told only after the row is visible.

**SLA monitor.** `SlaMonitorScheduler.tick` calls `SlaMonitor.runOnce(now)` every minute. Each incident is checked in its own
`REQUIRES_NEW` transaction; the unique index on `sla_events (incident_id, type)` makes warnings and breaches idempotent, so a
second run (or a second node) cannot record them twice. One failing incident is logged and does not stop the others.
`SlaRiskDetector` reuses the same percent-of-window arithmetic to predict risk without storing anything.

**Escalation.** `EscalationChain` links the handlers Spring injects in `@Order`. `TeamLeadEscalationHandler` reassigns through
`Incident.assign` as `Actor.SYSTEM`, so audit and notifications follow the normal event path.

**Authentication.** Passwords are hashed with BCrypt (72-byte limit enforced up front). JWTs carry a `ver` claim compared with
`users.token_version`, so "log out of all devices", password change and deactivation revoke tokens immediately. A failed-login
throttle (five attempts per 15 minutes per email) is kept in memory.

**Demo data.** `DemoDataSeeder` (enabled by `DEMO_DATA=true`) creates users and incidents through the real services, then moves a few SLA
deadlines into the past so the monitor produces breaches and warnings. It is idempotent and refuses to run under the `prod` profile.

## 11.4 Problems met and how they were solved

| Problem | Solution |
|---|---|
| Five people adding migrations at the same time | timestamped version names; the SLA tables were first created minimal and later extended with `ALTER TABLE` |
| Audit and notifications must behave differently on failure | two listeners with different transaction phases (section 5.6) |
| A scheduler that runs twice must not duplicate SLA events | unique constraint on `sla_events` plus a per-incident `REQUIRES_NEW` transaction |
| Two users saving the same incident | optimistic locking with `@Version` (`incidents.version`), reported as a 409 |
| Tests must not wait for the SLA clock | injected `Clock`, scheduler disabled in tests (`itsm.sla.monitor-enabled=false`), tests call `runOnce(now)` |
| BCrypt silently truncates passwords at 72 bytes | the limit is validated and tested (`BcryptLimitTest`) |

## 11.5 Known limitations

* Login throttle, round-robin pointer and SSE emitters are per instance (section 3.7).
* The SLA clock is not paused while waiting for information; VERIFIED incidents are not closed automatically.
* Correlated incidents are shown, not linked or merged. Assignment capacity is a soft limit (no locking).
