# 9. Testing

The brief asks for at least 15 documented test cases covering normal operations, boundary conditions, invalid
inputs, exceptions, authorisation and business rules. This chapter documents **77 test cases** (TC-01 to TC-77). Every
case names the automated test that implements it; each test class and method name below exists in the repository
(`backend/src/test/kotlin/com/itsm/...` and `frontend/test/*.test.ts`). The requirement IDs refer to
[02-requirements.md](02-requirements.md).

Type codes: **N** normal operation, **B** boundary, **I** invalid input, **E** exception or error response, **A**
authorisation, **R** business rule.

## 9.1 Test strategy

| Level | Tool | What it covers | Where |
|---|---|---|---|
| Domain unit tests | JUnit 5, AssertJ, no Spring | `Incident` and `IncidentState`, user entities and factory, strategies, text similarity, throttle, escalation chain, pipeline order | `IncidentLifecycleTest`, `UserDomainTest`, `PriorityStrategyTest`, `AssignmentStrategiesTest`, `TextSimilarityTest`, `LoginAttemptTrackerTest`, `EscalationChainTest`, `IncidentPipelineTest`, `SlaServiceTest`, `JwtTokenServiceTest` |
| Repository and database tests | `@DataJpaTest`-style with Testcontainers PostgreSQL | mapping, constraints, polymorphic loading | `UserRepositoryTest` |
| Integration (API) tests | Spring Boot Test, MockMvc, real PostgreSQL (Testcontainers), real Flyway migrations | endpoints, security rules, events, audit rows, notifications, SLA monitor | `P2ApiTest`, `P4ApiTest`, `P5ApiTest`, `AuthApiTest`, `AdminUserApiTest`, `SessionRevocationTest`, `LoginThrottleApiTest`, `NotificationFlowTest`, `AuditListenerTest`, `SlaMonitorTest`, `IncidentSearchTest`, `ReportServiceTest`, `CatalogAndSettingsApiTest` |
| Frontend unit tests | Vitest | pure logic: route rules, middleware gate, proxy (paths, headers, limits), CSRF check, cookies, error parsing, form helpers, typed client | `frontend/test/*.test.ts` |
| Static checks | `astro check` (TypeScript) | types across pages and components | CI step `pnpm check` |
| Build and CI | GitHub Actions | everything above on every push and pull request | `.github/workflows/ci.yml` |

Principles used: tests act as real callers (HTTP with real tokens) wherever the behaviour crosses layers; time is
injected (`Clock`, `now` parameters), so SLA tests move a clock instead of sleeping; the SLA monitor scheduler is off in tests
(`itsm.sla.monitor-enabled=false`) and tests call `SlaMonitor.runOnce(now)`; email goes to `RecordingEmailGateway`.

## 9.2 Documented test cases

### Incident lifecycle and reporting

| ID | Type | Req | Scenario | Steps / input | Expected result | Automated test | Status |
|---|---|---|---|---|---|---|---|
| TC-01 | N | FR-10, FR-50 | Report with a known category | Employee posts an incident with `category=network` | 201; status ASSIGNED; category NETWORK; priority, score and explanation set; both SLA deadlines set; `INC-` number; assignee is the skilled technician; employee has no actions, technician has ACKNOWLEDGE only | `P2ApiTest` `a reported incident with a category is classified, priced, given deadlines and assigned` | Automated |
| TC-02 | N | FR-22 to FR-26, FR-16, FR-17 | Full happy path in the domain | assign, acknowledge, start, resolve, verify, close with the right actors | every step succeeds in order; timestamps recorded | `IncidentLifecycleTest` `incident walks the whole lifecycle with valid transitions` | Automated |
| TC-03 | N | FR-10 to FR-26, FR-56, FR-57 | Full lifecycle over HTTP | report, acknowledge, start, request-info, resume (reporter), investigation note, resolve, feedback, verify, close through the API | each call returns the new state and the allowed actions; reporter sees INFO notes but not the investigation note; at least six `STATUS_CHANGED` audit rows; reporter and technician have notifications | `P2ApiTest` `the whole lifecycle works over HTTP and leaves audit rows and notifications` | Automated |
| TC-04 | R, E | BR-01 | Invalid transition (the brief's example) | try NEW to CLOSED | refused with an invalid-transition error (409 over HTTP); status unchanged | `IncidentLifecycleTest` `NEW cannot go straight to CLOSED` | Automated |
| TC-05 | R, E | BR-01 | Final states stay final | try any move from CLOSED and CANCELLED | no transition allowed | `IncidentLifecycleTest` `terminal states allow no further transition` | Automated |
| TC-06 | A, R | BR-01 | Only the right party moves an incident | someone other than the assignee acknowledges; someone other than reporter or admin verifies; late cancel | 403 (wrong actor) vs 409 (no such move) | `IncidentLifecycleTest` `only the assigned technician can acknowledge`, `only the reporter or an admin can verify`, `only the reporter or an admin can cancel, and only early` | Automated |
| TC-07 | A, E | FR-12, NFR-03 | Roles, conflicts and foreign incidents over HTTP | wrong role calls an action; impossible move; unknown id; incident of someone else | 403 / 409 / 404 / 403 respectively | `P2ApiTest` `wrong roles get 403, impossible moves get 409 and unknown or foreign incidents are refused` | Automated |
| TC-08 | N, R | FR-36, FR-50 | Unclear text stays NEW until an admin classifies it | report "Odd thing / zzz qqq xxx" | status NEW, priority still calculated, only CANCEL offered; admin classify gives ASSIGNED; classify again 409; employee classify 403; unknown category 400 | `P2ApiTest` `text without a clear category stays NEW and an admin can classify it and trigger assignment` | Automated |
| TC-09 | N, R | FR-36, FR-55 | Nobody available: incident waits for admin | the only technician is unavailable; report | status CLASSIFIED; admin may ASSIGN; assigning to the reporter or an unknown id is 400; technician cannot assign (403); assignment audited | `P2ApiTest` `a classified incident nobody can take waits for an admin who can assign it` | Automated |
| TC-10 | R | BR-04 | Never assigned to the reporter | a technician who is the only available technician reports a NETWORK incident; admin then tries to assign it to that technician | status CLASSIFIED with no assignee; admin assignment to the reporter is 400 "cannot assign an incident to its reporter"; assignment to another technician is 200 | `P2ApiTest` `an incident is never assigned to its own reporter, automatically or by an admin` | Automated |
| TC-11 | R | FR-18, BR-01, BR-03 | Reopen clears the resolution and re-arms the SLA | resolve, then reopen; run the SLA monitor later | resolution, root cause and feedback cleared; no false resolution breach | `IncidentLifecycleTest` `reopening clears the resolution and feedback so the next resolution starts fresh`; `P2ApiTest` `reopening re-arms the SLA so the monitor records no false resolution breach` | Automated |
| TC-12 | B, R | FR-19 | Feedback rules | feedback before resolution; by another employee; rating 0 and 6; rating 4 with a padded comment; a second time | only reporter, only after resolution, once, rating 1 to 5 | `IncidentLifecycleTest` `feedback is reporter only, after resolution, once, and rated 1 to 5` | Automated |
| TC-13 | I, B | FR-10 | Input validation when reporting | blank title, impact `HUGE`, affected users 0, unknown, malformed or inactive category, unknown service, no token | 400 for each input (401 without a token); the entity also rejects a blank title and 0 affected users | `P2ApiTest` `reporting validates the input`; `IncidentLifecycleTest` `invalid input is rejected when creating an incident` | Automated |
| TC-14 | A, R | FR-13, FR-24 | Note visibility and rules | INVESTIGATION by reporter, assignee, admin; INFO_REQUEST posted directly; note on a closed incident | investigation notes visible only to assignee and admin (even if a technician reported it); direct INFO notes 400; closed incident accepts nothing | `P2ApiTest` `note rules depend on role and status`, `internal notes are hidden from everyone but the assignee and admins, even from a technician reporter` | Automated |
| TC-15 | N, R | FR-25 | Information request cycle | IN_PROGRESS to PENDING_INFO, reporter answers, back to IN_PROGRESS | status moves both ways | `IncidentLifecycleTest` `info request cycle moves between IN_PROGRESS and PENDING_INFO` | Automated |
| TC-16 | R | BR-01 | Reassignment rules | reassign while ASSIGNED; from IN_PROGRESS; same technician again | assignee changes; status back to ASSIGNED (acknowledgement cleared) from active states; same assignee is a no-op | `IncidentLifecycleTest` `reassigning while ASSIGNED changes the assignee without a status change`, `reassigning from an active status sends the incident back to ASSIGNED`, `assigning the current assignee again is a no-op` | Automated |
| TC-17 | R | BR-01 | Bypass attempts | `transitionTo(CLASSIFIED / ASSIGNED / RESOLVED)` | refused; must use `classify`, `assign`, `resolve`; resolving without summary leaves the incident unchanged | `IncidentLifecycleTest` `transitionTo cannot bypass classify, assign or resolve`, `resolving needs a summary and leaves the incident unchanged when it fails` | Automated |
| TC-18 | A, R | BR-05 | Visibility | reporter, assignee, admin, stranger read an incident | only the first three may | `IncidentLifecycleTest` `visibility is limited to reporter, assignee and admin` | Automated |
| TC-19 | N | FR-04 | Automatic category from text | title "VPN down", text mentions vpn, wifi, network | category NETWORK, status ASSIGNED | `P2ApiTest` `keyword text is classified automatically` | Automated |

### Priority

| ID | Type | Req | Scenario | Steps / input | Expected result | Automated test | Status |
|---|---|---|---|---|---|---|---|
| TC-20 | B, R | FR-51 | Weighted strategy thresholds | (impact, urgency, criticality) = (1,1,1), (2,2,1), (2,2,2), (4,4,4), (4,4,3), (3,3,3), (3,3,2) | P4 0.0, P4, P3 4.0, P1 8.0, P2, P2, P3 | `PriorityStrategyTest` `weighted thresholds at the extremes and at the P3 boundary` | Automated |
| TC-21 | N | FR-51 | Maximum input with explanation | (4,4,4) with 120 users | P1, score 9.0, exact explanation text | `PriorityStrategyTest` `weighted gives the maximum input P1 with a readable explanation` | Automated |
| TC-22 | B, R | FR-51 | Affected users and security boost | (4,4,3) with 100 users; (3,3,2) with category security; 1,000,000 users + SECURITY | P1; P2 with explanation mentioning security; score capped at 10.0 | `PriorityStrategyTest` `many affected users and a security category raise the score` | Automated |
| TC-23 | R | FR-51 | Matrix strategy | (1,1,1), (2,2,1), (2,4,1), (4,4,1), (1,4,1) | P4, P3, P2, P1 (score 10.0), P3 | `PriorityStrategyTest` `matrix follows the ITIL table` | Automated |
| TC-24 | B, R | FR-51 | Business criticality in matrix | (1,1,4) raised; (3,3,4) and (4,4,4) stay P1; (1,1,3) not raised | P3, P1, P1, P4; explanation says "raised one level" | `PriorityStrategyTest` `matrix raises one level for criticality 4 and never above P1` | Automated |
| TC-25 | I | FR-51 | Invalid priority input | impact 0, urgency 5, criticality 0, users 0 | `DomainValidationException` for each | `PriorityStrategyTest` `invalid input is rejected` | Automated |
| TC-26 | R | FR-35, FR-51 | Strategy chosen by setting, fallback | setting `matrix`; unknown name | matrix used; unknown falls back to weighted | `PriorityStrategyTest` `priority service uses the strategy named by the setting and falls back to weighted` | Automated |
| TC-27 | A, N | FR-32, FR-35 | Catalog and settings | admin creates, edits and deactivates a service; employee calls admin endpoints; invalid setting values | works for admin; 403 for employee; 400 for unknown key or strategy | `CatalogAndSettingsApiTest` `admin creates, updates and deactivates a service`, `an employee cannot use the admin endpoints`, `settings accept known keys and valid strategies only` | Automated |

### SLA

| ID | Type | Req | Scenario | Steps / input | Expected result | Automated test | Status |
|---|---|---|---|---|---|---|---|
| TC-28 | N, R | FR-52 | SLA deadlines from the default policy | compute deadlines for P1 to P4 | response and resolution minutes as in BR-03 | `SlaMonitorTest` `default policy deadlines`; `SlaServiceTest` `uses the default policy when the service has none` | Automated |
| TC-29 | R | FR-33, FR-52 | Service-specific policy wins | service has its own P1 policy | its minutes are used instead of the default | `SlaServiceTest` `prefers the service specific policy`; `SlaMonitorTest` `a service specific policy overrides the default` | Automated |
| TC-30 | E | FR-52 | Missing SLA configuration | no default policy for a priority | `SlaNotConfiguredException`; null service uses the default | `SlaServiceTest` `a null service uses the default and a missing default is an error` | Automated |
| TC-31 | N, B | FR-53 | Warning then breach, each once | P3 incident (60 / 480 min); run monitor at 30, 50, 55, 61, 62 minutes | nothing at 50 %; one WARNING at 83 %; none at 55; one RESPONSE_BREACH at 61; none again at 62; events published once; escalation `REASSIGN_REQUESTED`; one `SLA_BREACHED` audit row | `SlaMonitorTest` `warning then breach are each recorded once and published` | Automated |
| TC-32 | R | FR-53, FR-54 | Acknowledged incident counts only the resolution deadline; P1 breach escalates | P1 acknowledged; run at 10, 24, 31 minutes | no event at 10; WARNING at 80 %; RESOLUTION_BREACH at 31; escalation level `ADMIN` | `SlaMonitorTest` `an acknowledged incident only counts down the resolution deadline and P1 resolution breach escalates to admin` | Automated |
| TC-33 | R | FR-53 | Resolved incidents are not monitored | resolved P1; run monitor far in the future | 0 events | `SlaMonitorTest` `a resolved incident is not monitored` | Automated |
| TC-34 | E | NFR-11 | One bad incident does not stop others | incident without a policy next to a breaching one | breach still recorded for the other incident | `SlaMonitorTest` `an incident without a policy does not stop another incident's breach from being recorded` | Automated |
| TC-35 | B | FR-53 | Late-started windows measured correctly | deadlines counted from a later start | no early warning | `SlaMonitorTest` `deadlines counted from a later start give no early warning` | Automated |
| TC-36 | R | FR-54 | Escalation chain rules | resolution breach of P1/P2; of P3; unacknowledged response breach | ADMIN escalation; none; REASSIGN_REQUESTED | `EscalationChainTest` `resolution breach of P1 and P2 escalates to ADMIN`, `resolution breach of P3 is not escalated`, `unacknowledged response breach asks for a reassignment` | Automated |

### Assignment and intelligence

| ID | Type | Req | Scenario | Steps / input | Expected result | Automated test | Status |
|---|---|---|---|---|---|---|---|
| TC-37 | R | FR-55 | Skill beats lower load | idle technician without skill vs L2 with NETWORK skill and 1/5 | L2 chosen; reason `Ravi (L2) has NETWORK skill and 1/5 active incidents` | `AssignmentStrategiesTest` `skill match beats a less loaded technician without the skill` | Automated |
| TC-38 | R, B | FR-55 | P1 seniority guard (80 %) | P1: TEAM_LEAD at 60 % vs idle L1; TEAM_LEAD at 80 % vs idle L2; everybody at 80 % or more | senior chosen at 60 %; not at 80 %; then purely by load | `AssignmentStrategiesTest` `P1 prefers the higher level while it is below 80 percent load, P3 does not`, `P1 senior at 80 percent or more loses to an idle L2`, `P1 ranks purely by load when everybody is at 80 percent or more` | Automated |
| TC-39 | R | FR-55 | Fallback when nobody has the skill, no candidates | category CLOUD, nobody skilled; empty list | least loaded chosen and reason says so; `null` for empty (both strategies) | `AssignmentStrategiesTest` `falls back to anyone and says so when nobody has the skill`, `no candidates gives null` | Automated |
| TC-40 | R | FR-55 | Round-robin | ids 10, 20, 30 called four times; a technician drops out | 10, 20, 30, 10; keeps going | `AssignmentStrategiesTest` `round robin rotates by id and wraps around`, `round robin keeps going when a technician drops out` | Automated |
| TC-41 | R, E | BR-04 | Capacity, availability and exclusion | full, unavailable and excluded technicians | skilled, least loaded, available one chosen; null when nobody has capacity; excluded never picked | `P5ApiTest` `assign picks the skilled, least loaded technician and skips full or unavailable ones`, `assign returns null when nobody has capacity`, `an excluded technician is never picked and excluding the only candidate gives null` | Automated |
| TC-42 | E | FR-35 | Unknown strategy key | selector configured with an unknown key | warns once per key and uses `skill-least-loaded` | `AssignmentStrategiesTest` `selector warns once per distinct unknown key`, `selector falls back to the default for an unknown key` | Automated |
| TC-43 | A, N | FR-36 | Recommendations are admin only | employee and admin call recommendations | 403 for employee; ranked technicians with reasons for admin | `P5ApiTest` `recommendations endpoint is admin only and names the technicians` | Automated |
| TC-44 | N, B, I | FR-04 | Category suggestion API | clear text; no match; title over 200; inactive category; overlapping keywords | suggestion; 204; 400; never suggested; counted once | `P5ApiTest` `suggest-category returns a suggestion, 204 without a match and 400 for a long title`, `an inactive category is never suggested`, `overlapping keywords count once and phrases do not span title and description` | Automated |
| TC-45 | N, B | FR-05 | Duplicate hints | "Printer broken" vs "Printer jammed" (0.33) vs real similarity (at least 0.4) | not flagged vs flagged; Jaccard and title weighting correct | `P5ApiTest` `short single word overlaps are not flagged as duplicates but real similarity is`; `TextSimilarityTest` `jaccard is the overlap over the union`, `title counts more than description` | Automated |
| TC-46 | A | FR-05, BR-05 | Hints respect visibility | technician and stranger ask for similar incidents | only incidents the caller reported or is assigned to | `P5ApiTest` `similar incidents respect visibility` | Automated |

### Notifications, audit, search and reports

| ID | Type | Req | Scenario | Steps / input | Expected result | Automated test | Status |
|---|---|---|---|---|---|---|---|
| TC-47 | N | FR-57 | Notification on assignment, both channels | publish `IncidentAssigned` in a transaction | in-app rows and emails for the technician and the reporter only; not for the admin who acted, not for a bystander | `NotificationFlowTest` `IncidentAssigned notifies the technician and the reporter on both channels` | Automated |
| TC-48 | E, R | FR-56, FR-57 | Rollback sends and records nothing | publish an event, then fail the transaction | no notification, no email, no audit row | `NotificationFlowTest` `nothing is sent when the transaction rolls back` | Automated |
| TC-49 | A, N | FR-20 | Own notifications only; live stream | list, mark read, other user marks; open the stream | recipient only (404 for others); `text/event-stream` content type | `P4ApiTest` `notifications can be listed and marked read, only by their recipient`, `the notification stream opens as event-stream for a logged-in user` | Automated |
| TC-50 | N | FR-56 | Audit row for a status change | publish `StatusChanged(ASSIGNED, ACKNOWLEDGED)` | one `STATUS_CHANGED` row with from, to, actor and description | `AuditListenerTest` `a status change creates an audit row with the right before and after states` | Automated |
| TC-51 | A | FR-12, FR-56 | History visibility | reporter and an unrelated employee request history | reporter 200; stranger 403 | `P4ApiTest` `incident history is visible to the reporter but not to an unrelated employee` | Automated |
| TC-52 | N, R | FR-37 | Search filters | each of the ten filters alone; combined; SLA status | each narrows correctly; combination is AND; BREACHED, AT_RISK, ON_TRACK separated | `IncidentSearchTest` `each filter works on its own`, `filters combine with AND`, `SLA status filter separates breached, at risk and on track` | Automated |
| TC-53 | A, I | FR-37 | Search visibility and bad input | employee vs admin search; no token; unknown sort field | employee sees own; admin all; 401; 400 | `IncidentSearchTest` `people only find incidents they are allowed to see`; `P4ApiTest` `search shows an employee only their own incidents and an admin everything`, `search requires authentication and rejects an unknown sort field` | Automated |
| TC-54 | N, A | FR-38 | Reports | overview, workload (idle technicians included), by service, SLA compliance %, MTTR, trend; reports as employee | numbers correct; non-admin 403 | `ReportServiceTest` `overview counts are correct`, `workload counts active incidents per technician and includes idle technicians`, `incidents are counted per service and SLA compliance is a percentage`, `mean time to resolve and the daily trend`; `P4ApiTest` `reports are admin only` | Automated |

### Authentication and security (backend)

| ID | Type | Req | Scenario | Steps / input | Expected result | Automated test | Status |
|---|---|---|---|---|---|---|---|
| TC-55 | N, A | FR-01 | Registration creates only Employees | register with `role` in the body | 201 Employee, no password fields returned | `AuthApiTest` `register valid user returns 201 as employee without any password fields`, `register ignores a role field in the body and still creates an employee` | Automated |
| TC-56 | E, I | FR-01 | Duplicate and invalid registration | same email in other case or with spaces; invalid fields | 409 problem; 400 with per-field errors | `AuthApiTest` `register duplicate email differing by case is rejected with 409 problem`, `register with invalid fields returns 400 with per-field errors` | Automated |
| TC-57 | E | FR-02 | Invalid login gives identical errors | wrong password; unknown email | same 401 title and detail (no account enumeration) | `AuthApiTest` `wrong password and unknown email produce identical 401 details` | Automated |
| TC-58 | E, B | NFR-05 | Login throttle | five wrong passwords, then the right one; unknown email; four failures then success; one email does not affect another | 429 with `Retry-After` from the 6th attempt, even with the right password; unknown emails throttled alike; success resets; independent per email | `LoginThrottleApiTest` `five failures then 429 with Retry-After even for the correct password`, `an unknown email is throttled exactly like a known one`, `four failures are still allowed and a success resets the counter`, `throttling one email does not affect another`; `LoginAttemptTrackerTest` `allows max attempts then locks for the whole window from the last one`, `parallel attempts are counted atomically, at most max are let through` | Automated |
| TC-59 | A, R | NFR-04 | Session revocation | change password; admin reset; deactivate then reactivate; logout-all | old tokens get 401; change-password returns a new working token; logout-all revokes all tokens including the current one | `SessionRevocationTest` `change password returns a fresh token, revokes the old one and swaps the password`, `admin reset password revokes the user's existing token`, `deactivating through the admin api then reactivating still leaves the old token revoked`, `logout-all revokes every token including the current one and a fresh login works`; `AuthApiTest` `an existing token stops working as soon as the user is deactivated and stays rejected after reactivation` | Automated |
| TC-60 | E | NFR-02 | Bad tokens | tampered signature; tampered payload; expired; wrong issuer; garbage; no token | 401 problem+json (`WWW-Authenticate: Bearer`) | `AuthApiTest` `token with a tampered signature is rejected`, `token with a tampered payload is rejected`, `me without a token returns 401 problem json with a WWW-Authenticate header`, `me with garbage token returns 401 problem json`; `JwtTokenServiceTest` `expired token is rejected`, `token with a different issuer is rejected`, `token signed with a different secret is rejected` | Automated |
| TC-61 | A | NFR-03 | Admin endpoints by role | employee, technician, no token call `/api/admin/users` | 403, 403, 401 (problem+json) | `AdminUserApiTest` `employee token on admin endpoint is forbidden with problem json`, `technician token on admin endpoint is forbidden`, `no token on admin endpoint is unauthorized` | Automated |
| TC-62 | B, I | NFR-01 | BCrypt 72-byte limit | password of 72 and 73 bytes; multi-byte characters | 72 accepted, 73 rejected with field error; byte (not character) count; over-long login is a plain 401 | `BcryptLimitTest` `encode accepts 72 bytes and rejects 73`, `byte limit counts utf8 bytes not characters`; `AuthApiTest` `register with a password over 72 bytes returns 400 with a field error`, `register with a 72 byte password works and the user can log in`, `login with an over long password is a plain 401 even when it extends the real one` | Automated |
| TC-63 | N, R, E | FR-30, FR-31 | Admin user management | create technician (skills upper-cased) who can log in; duplicate email; employee with technician-only fields; self-deactivation; unknown skills | 201 and login works; 409; 400; 400; 400 naming the skills and nothing created | `AdminUserApiTest` `admin creates a technician with uppercased skills who can then log in`, `admin create with a duplicate email returns 409`, `admin create employee with technician only fields returns 400`, `admin cannot deactivate themselves`, `unknown skills are rejected with 400 naming them and nothing is created or changed` | Automated |
| TC-64 | R | NFR-12 | Database enforces integrity | duplicate normalised email; technician row without technician columns; invalid role | constraint violations | `UserRepositoryTest` `duplicate normalized email violates the unique constraint`, `check constraint requires technician columns for technician rows`, `check constraint rejects an invalid role` | Automated |
| TC-65 | E | NFR-10 | Optimistic locking response | `ObjectOptimisticLockingFailureException` | 409 `ConcurrentUpdate` problem | `GlobalExceptionHandlerTest` `an optimistic lock failure is a 409 ConcurrentUpdate problem` | Automated |
| TC-66 | N | FR-30 | Encapsulation of credentials | change password with a wrong current password; `toString` | exception and unchanged password; neither password nor hash printed | `UserDomainTest` `changePassword with wrong current password throws and leaves password unchanged`, `toString exposes neither raw password nor hash` | Automated |
| TC-67 | N | NFR-18 | Demo data is idempotent | seed twice | no duplicates; missing items completed | `DemoDataSeederTest` `a second run completes missing items and creates no duplicates` | Automated |

### Frontend (Vitest)

| ID | Type | Req | Scenario | Steps / input | Expected result | Automated test | Status |
|---|---|---|---|---|---|---|---|
| TC-68 | A, E | NFR-06 | CSRF: cross-origin writes are rejected | write with foreign `Origin`; `Sec-Fetch-Site: cross-site` or `same-site`; foreign `Referer` | rejected; safe methods and header-less requests allowed | `csrf.test.ts` `rejects a foreign Origin on writes`, `rejects cross-site and same-site Sec-Fetch-Site`, `falls back to the Referer origin when Origin is absent`, `allows requests with neither header, and safe methods`; `apiRoute.test.ts` `answers 403 for a cross-origin write` | Automated |
| TC-69 | A, E | NFR-06 | Proxy path traversal | `/api/../admin`, `%2e%2e`, `\`, empty segment | 400 and the backend is never called | `apiRoute.test.ts` `answers 400 for path traversal without calling the backend`; `proxy.test.ts` `always stays on the backend origin under /api/` | Automated |
| TC-70 | R | NFR-06 | Header allow-lists, cookie never forwarded | request with cookie and extra headers; backend answers with `Set-Cookie` and `Server` | token injected as `Authorization`; browser cookie not forwarded; `Set-Cookie` and `Server` not returned | `apiRoute.test.ts` `injects the token from the cookie and does not forward cookies or browser-only headers`, `filters response headers (no Set-Cookie or Server)`; `proxy.test.ts` `forwards only the allow-listed headers` | Automated |
| TC-71 | B, E | NFR-13 | Body size cap | `Content-Length` above 1 MiB; body that grows past it without a length | 413 in both cases | `apiRoute.test.ts` `answers 413 when Content-Length is above 1 MiB`, `answers 413 when a body without Content-Length grows past 1 MiB` | Automated |
| TC-72 | E | NFR-13 | Backend unreachable or slow | connection refused; timeout | 502 and 504 with generic messages | `apiRoute.test.ts` `answers 502 when the backend is unreachable`, `answers 504 with a generic message on a timeout` | Automated |
| TC-73 | A | NFR-03 | Route rules | `/admin` as employee; case, trailing slash, encoding and `..` tricks | forbidden; no variant bypasses the rule | `access.test.ts` `allows only admins under /admin`, `is not fooled by case, trailing or repeated slashes, or encoding`, `resolves . and .. after decoding`; `gate.test.ts` `wrong role rewrites to /403` | Automated |
| TC-74 | N, E | NFR-13 | Session expiry in the middleware | cookie present, `/me` answers 401; backend down | cookie cleared and redirect to login with `next` and `expired`; 503 page and cookie kept when the backend is down | `gate.test.ts` `a 401 clears the cookie and sends a protected page to login`, `backend down on a protected path rewrites to /503 and keeps the cookie` | Automated |
| TC-75 | N | NFR-06 | Session cookie flags | build cookie options | `HttpOnly`, `SameSite=Lax`, path `/`, expires with the token; `Secure` only when configured | `session.test.ts` `is httpOnly, SameSite=Lax, path / and expires at the token expiry`, `is Secure only when COOKIE_SECURE is true` | Automated |
| TC-76 | E | NFR-16 | Error messages hide server details | parse problem+json, non-JSON, 5xx | detail for 4xx; generic message for 5xx and unreachable | `errors.test.ts` `parses problem+json`, `falls back when the body is not JSON`, `is generic for 5xx and an unreachable backend` | Automated |
| TC-77 | E | NFR-05 | Login throttle message | 429 with `Retry-After` | "try again in N minutes"; query text never reflected | `loginMessages.test.ts` `429 shows the wait, 400 a fixed hint, anything else the backend message`, `never reflects query text` | Automated |

<!-- TODO(verify after merge): add test cases for the parallel tracks (incident editing, escalation Technician ->
Team Lead -> Admin, SLA risk detection and admin SLA monitor, incident correlation, technician workload view) with
the real test class and method names once merged. -->

## 9.3 Coverage of the brief's test categories

| Category | Cases |
|---|---|
| Normal operations | TC-01, 02, 03, 08, 15, 19, 21, 27, 28, 47, 49, 50, 52, 54, 55, 63, 66, 74, 75 |
| Boundary conditions | TC-12, 13, 20, 22, 24, 31, 35, 38, 44, 45, 58, 62, 71 |
| Invalid inputs | TC-13, 25, 44, 53, 56, 62, 63 |
| Exceptions and error responses | TC-04, 05, 07, 30, 34, 42, 48, 57, 60, 65, 68, 69, 72, 74, 76 |
| Authorisation | TC-06, 07, 14, 18, 43, 46, 49, 51, 53, 54, 59, 61, 68, 73 |
| Business rules | TC-04 to 06, 08 to 12, 14 to 18, 20, 22 to 24, 26, 29, 32, 33, 36 to 42, 48, 52, 59, 64, 70 |

## 9.4 Test counts and how to run

Measured on this branch (`./gradlew test` and `pnpm test`, run on 2026-11-18, all green):

| Suite | Test files / classes | Tests executed | Failures |
|---|---|---|---|
| Backend (JUnit 5, Testcontainers PostgreSQL 17) | 33 classes | 283 | 0 |
| Frontend (Vitest) | 19 files | 357 (parameterised cases counted separately) | 0 |
| **Total** | 52 | **640** | **0** |

Backend tests per package: `auth` 88, `user` 79, `p5` 25 (assignment, intelligence), `incident` 24, `p2` 20, `sla` 13, `priority` 7, `p4` 6,
`service` 5, `reports` 4, `search` 4, `web` 4, `notification` 2, `audit` 1, plus the application context test (static
count of `@Test` methods per directory). The documented cases above are a curated, traceable selection of these.

<!-- TODO(verify after merge): re-run both suites after the parallel tracks are merged and update this table. -->

Run the same checks as CI:

```sh
# backend: needs Docker running (Testcontainers starts a throwaway PostgreSQL 17)
cd backend && ./gradlew test        # or ./gradlew build to also assemble the jar

# frontend
cd frontend && pnpm install
pnpm check      # Astro / TypeScript type checking
pnpm test       # Vitest unit tests
pnpm build

# one class or method
cd backend && ./gradlew test --tests 'com.itsm.sla.SlaMonitorTest'
cd frontend && pnpm vitest run test/csrf.test.ts
```

HTML test reports are written to `backend/build/reports/tests/test/index.html`.
