# 5. Design Patterns and Object-Oriented Design

The brief asks for at least two design patterns and sound use of the OOP concepts. I-ITSM uses **eight patterns
(nine uses, because Strategy appears twice)** plus a few supporting idioms. For each pattern this chapter says where it
lives, why it was chosen and what it buys.

| # | Pattern | Where | Replaces |
|---|---|---|---|
| 1 | State | `IncidentState` and ten state objects (`incident/IncidentState.kt`) | a `when (status)` ladder in every method |
| 2 | Strategy (priority) | `PriorityStrategy`, `WeightedScoreStrategy`, `MatrixStrategy`, `PriorityService` (`priority/`) | one hard-coded formula |
| 3 | Strategy (assignment) | `AssignmentStrategy`, `SkillLeastLoadedStrategy`, `RoundRobinStrategy`, `AssignmentStrategySelector` (`assignment/`) | one hard-coded assignment rule |
| 4 | Template Method | `IncidentProcessingPipeline` / `StandardIncidentPipeline` (`incident/IncidentPipeline.kt`) | copy-pasted "report" and "classify" sequences |
| 5 | Chain of Responsibility | `EscalationHandler`, `TeamLeadEscalationHandler`, `AdminEscalationHandler`, `EscalationChain` (`sla/escalation/Escalation.kt`) | an if/else escalation block in the monitor |
| 6 | Observer | Spring events: `IncidentEvent`, `AuditListener`, `NotificationDispatcher` (`events/`, `audit/`, `notification/`) | direct calls from incident code to audit and notification code |
| 7 | Adapter | `EmailGateway`, `SmtpEmailGateway`, `LoggingEmailGateway` (`notification/channel/EmailGateway.kt`) | code that depends on one mail library |
| 8 | Factory | `UserFactory` (`user/UserFactory.kt`), `NotificationFactory` (`notification/NotificationFactory.kt`) | scattered `if (role == ...)` construction and message building |
| 9 | Repository | Spring Data interfaces: `IncidentRepository`, `UserRepository`, `AuditLogRepository`, ... | SQL in business code |

Additional idioms: **Specification** (`IncidentSpecifications` builds each search filter as a small composable
query object), **Value objects** (`Actor`, `StatusChange`, `PriorityInput`, `AssignmentRequest`, DTOs), **Singleton**
(Spring beans and the Kotlin `object` states) and **Composition over inheritance** (the pipeline and services are
assembled from collaborators passed to the constructor).

---

## 5.1 State pattern: incident lifecycle

**Where.** `sealed class IncidentState(val status: IncidentStatus)` and the objects `NewState`, `ClassifiedState`,
`AssignedState`, `AcknowledgedState`, `InProgressState`, `PendingInfoState`, `ResolvedState`, `VerifiedState`,
`ClosedState`, `CancelledState`. `Incident.state` returns `IncidentState.of(status)`.

**How it works.** Each state declares `transitions: Map<IncidentStatus, Set<Party>>`, the statuses that can follow and
who may trigger each move. `Incident.move()` calls `state.checkTransition(incident, target, actor)`, which throws
`InvalidStateTransitionException` (409) for a missing edge and `UnauthorizedOperationException` (403) for a wrong
actor. `availableTargets` powers the UI: `IncidentDetail.actions` lists exactly the buttons the caller may press.

**Why.** The brief requires that "the system must prevent inappropriate state transitions" and names the State
pattern. The lifecycle rules sit in one file, one object per status; adding a status means adding one object and
edges, not touching `Incident`. `sealed` lets the compiler check that `IncidentState.of` covers every status.
Because transitions carry **who** may move, the same table drives authorisation, so role checks are not duplicated
in controllers.

**Why the state is not stored.** Only `status` (an enum string) is persisted. The behaviour object is derived, so
there is no second source of truth and no JPA mapping of polymorphic state objects.

## 5.2 Strategy pattern (1): priority

**Where.** `interface PriorityStrategy { val key: String; fun calculate(input: PriorityInput): PriorityResult }`,
implemented by `WeightedScoreStrategy` (`weighted`) and `MatrixStrategy` (`matrix`). `PriorityService` collects all
`PriorityStrategy` beans and picks the one named by the admin setting `priority.strategy` (default and fallback
`weighted`; an unknown name logs a warning once).

**Why.** Different organisations prioritise differently. The brief lists "Priority calculation" as a Strategy use
case. Both implementations take the same input (impact, urgency, service criticality, affected users, category) and
return priority, score and a readable explanation. A new strategy is one `@Component`; the setting validator
(`SettingsAdminService`) accepts any registered key automatically.

### The priority algorithm and its rationale

Inputs, all integers: **impact** 1 to 4 (INDIVIDUAL, TEAM, DEPARTMENT, ORGANISATION), **urgency** 1 to 4 (LOW,
MEDIUM, HIGH, CRITICAL), **service criticality** 1 to 4 (from the IT service catalog, default 2 when the incident has
no service), **affected users** at least 1, and the category code.

**`weighted` (default).**

```
base        = 8 * ln(impact * urgency * criticality) / ln(64)       (1*1*1 gives 0, 4*4*4 gives 8)
usersBoost  = min(1.5, 0.5 * log10(affectedUsers))                  (1 user: 0, 100 users: 1.0, 1000 or more: 1.5)
securityBoost = +1.0 when category is SECURITY
score       = min(10, base + usersBoost + securityBoost), rounded to one decimal
priority    = P1 if score >= 8.0, P2 if >= 6.0, P3 if >= 4.0, else P4
```

Rationale:

* **Multiplication, not addition.** An outage needs to be big on several axes at once to reach P1: a huge impact on
  a trivial question (urgency 1) must not become critical. The product of three 1-to-4 factors ranges 1 to 64.
* **Logarithm.** The product grows steeply; taking `ln` and dividing by `ln(64)` maps it evenly to 0 to 8, so each
  factor-of-two step is worth the same number of points and thresholds are easy to explain.
* **Business criticality.** A broken Payment service (criticality 4) outranks a broken HR portal (1) with the same
  impact and urgency, which is what "business criticality" in the brief asks for.
* **Affected users** add up to 1.5 points on a log scale: the first users matter most, and 1000 users is not ten times
  worse than 100.
* **Security** incidents get one point because delay is costly even when few users are affected.
* The cap at 10 keeps the score a stable 0 to 10 measure; the thresholds 8, 6 and 4 give a P1 only when all three
  factors are near the top (4*4*4 is exactly 8.0).

Worked values (users = 1, no security; all verified in `PriorityStrategyTest`):

| impact x urgency x criticality | score | priority |
|---|---|---|
| 1 x 1 x 1 | 0.0 | P4 |
| 2 x 2 x 1 | 2.7 | P4 |
| 2 x 2 x 2 | 4.0 | P3 (boundary) |
| 3 x 3 x 2 | 5.6 | P3 |
| 3 x 3 x 3 | 6.3 | P2 |
| 4 x 4 x 3 | 7.4 | P2 |
| 4 x 4 x 4 | 8.0 | P1 (boundary) |
| 4 x 4 x 3 with 100 users | 8.4 (7.4 + 1.0) | P1 |
| 3 x 3 x 2 with SECURITY | 6.6 (5.6 + 1.0) | P2 |
| 4 x 4 x 4 with 120 users | 9.0 | P1 (explanation `Impact ORGANISATION (4) × urgency CRITICAL (4) × service criticality 4, 120 affected users → score 9.0 → P1`) |

The brief's example (payment application down for everyone): impact ORGANISATION 4, urgency CRITICAL 4, Payment
criticality 4 gives 8.0 or more, so **P1**, SLA 5 minutes to respond and 30 to resolve.

**`matrix`.** The classic ITIL impact by urgency table; rows impact 1 to 4, columns urgency 1 to 4:

|  | urgency 1 | urgency 2 | urgency 3 | urgency 4 |
|---|---|---|---|---|
| impact 1 | P4 | P4 | P3 | P3 |
| impact 2 | P4 | P3 | P3 | P2 |
| impact 3 | P3 | P3 | P2 | P1 |
| impact 4 | P3 | P2 | P1 | P1 |

A service criticality of 4 raises the result one level (P3 to P2; P1 stays P1). Affected users and category are
ignored. The score is 10, 7.5, 5 or 2.5 for P1 to P4 so scores remain comparable across strategies. The matrix is
transparent and auditable (people can read it off a table); the weighted formula is smoother and uses more
information. Offering both shows the Strategy pattern working and lets the administrator choose.

Every calculation stores a sentence explaining itself (`PriorityResult.explanation`), saved on the incident as
`priority_explanation` and in the `PriorityChanged` audit event, so a priority is never a black box.

## 5.3 Strategy pattern (2): technician assignment

**Where.** `interface AssignmentStrategy { val key: String; fun pick(request, candidates): AssignmentResult? }`,
implemented by `SkillLeastLoadedStrategy` (`skill-least-loaded`, default) and `RoundRobinStrategy` (`round-robin`).
`AssignmentStrategySelector` resolves the configured key (via `StrategyKeySource`, today the property
`itsm.assignment.strategy` / env `ASSIGNMENT_STRATEGY`) to a bean and falls back to the default for an unknown key.
`AssignmentService` builds the candidate list (active, available, below capacity, not the reporter, with workload
from one grouped query) and hands it to the strategy. Strategies are pure functions of their inputs, so they are
unit-tested without a database (`AssignmentStrategiesTest`).

**Why.** The brief requires at least two strategies and lists "technician assignment" as a Strategy use case.
Separating *who is eligible* (the service) from *who is best* (the strategy) means each rule can change alone.

### The assignment algorithms

**Eligibility (the service, for every strategy).** A technician is a candidate only if they are active, available,
are not excluded (the reporter) and have `activeIncidents < maxActiveIncidents` (active means ASSIGNED, ACKNOWLEDGED,
IN_PROGRESS or PENDING_INFO). If no candidate remains, `assign` returns `null`, the incident stays CLASSIFIED and an
administrator is shown ranked recommendations.

**`skill-least-loaded`.**

1. If the incident has a category, keep candidates whose skills contain it. If nobody has the skill, use all
   candidates and say so in the reason.
2. For **P1 and P2** only: among the pool, those with load ratio (`active / max`) **below 0.8** (`SENIOR_LOAD_LIMIT`)
   are ranked by **higher level first** (TEAM_LEAD > L2 > L1). If nobody is below 0.8, level is ignored so urgent
   work does not pile onto the most senior person.
3. Then by **lowest load ratio**, then **fewest active incidents**, then **lowest id** (deterministic).
4. The result carries a human-readable reason, e.g. `Ravi (L2) has NETWORK skill and 1/5 active incidents`
   (stored with the `IncidentAssigned` event and shown to administrators).

Why: skill matching puts the right person on the problem; load ratio (not raw count) is fair across people with
different capacities; senior preference for P1/P2 uses scarce expertise where it matters, with a guard against
burn-out.

**`round-robin`.** Candidates sorted by id; the pick is the first one with id greater than the last picked, wrapping
around. It ignores skills and priority. The pointer is an in-memory `lastId` in a `@Synchronized` method. Why: a
simple, predictable baseline; it is useful when skills are not maintained.

**Recommendations.** `AssignmentService.recommend` repeatedly applies `SkillLeastLoadedStrategy` and removes the
winner, giving up to three ranked suggestions without disturbing round-robin's pointer.

**Known limitation.** Capacity is a soft limit: workloads are read without locking, so two simultaneous assignments
can both pick a technician with one slot left (documented in `AssignmentService`).

**Known limitation (honest note).** The admin setting `assignment.strategy` is stored in `system_settings` and validated
by `SettingsAdminService`, but `AssignmentStrategySelector` still reads the key from `PropertyStrategyKeySource` (the
`ASSIGNMENT_STRATEGY` environment variable). The code carries a `TODO(P5/P3)` for reading the setting through another
`StrategyKeySource`. Changing the strategy on the settings page therefore does not yet change assignment; the
environment variable does. (The `priority.strategy` setting, in contrast, is read by `PriorityService` on every call
and takes effect immediately.)

## 5.4 Template Method: the report pipeline

**Where.** `abstract class IncidentProcessingPipeline` has a `final fun process(incident, context)` that calls, in
this order, the protected abstract steps `suggestCategory`, `classify`, `prioritise`, `applySla`, `assign`.
`StandardIncidentPipeline` implements them. `IncidentService.report` and `IncidentService.classify` call
`pipeline.process(...)`.

**Why.** The *order* of processing is a business rule (a priority needs the category and the service, an
assignment needs the priority). Putting it in a final method makes it impossible for a variant to reorder or skip
steps; variants only change what each step does. Each step is written to be a **no-op** when it does not apply
(already classified, nobody to assign), so the same pipeline serves a new report and a manual classification by an
administrator. `IncidentPipelineTest` proves the order with a recording subclass.

## 5.5 Chain of Responsibility: SLA escalation

**Where.** `abstract class EscalationHandler` has a `next` link, a final `handle(context)` (`tryHandle(context) || next?.handle(context)`)
and the protected abstract `tryHandle`. Two handlers, ordered with `@Order`:

1. `TeamLeadEscalationHandler` (`@Order(1)`). For a **response breach** of an incident nobody acknowledged, it asks a
   `TeamLeadSource` (`JpaTeamLeadSource`: active, available team lead, not the reporter or current assignee, category
   skill first, then lowest load ratio) and **reassigns** the incident to that team lead as `Actor.SYSTEM`. It publishes
   `IncidentEscalated` (level `TEAM_LEAD`, with `escalatedToId`), `IncidentAssigned` (strategy `escalation`) and, if the status
   changed, `StatusChanged`. With no team lead it passes the breach on.
2. `AdminEscalationHandler` (`@Order(2)`, the last link). It publishes `IncidentEscalated` (level `ADMIN`) for a P1 or P2
   **resolution breach**, and for an unacknowledged response breach that no team lead could take. P3 and P4 resolution
   breaches are not escalated.

`EscalationChain` receives the handler list from Spring (already sorted by `@Order`) and links it with
`reduceRightOrNull`. `SlaMonitor` calls `escalation.escalate(EscalationContext(...))` once for each breach it records, so the chain
is not repeated for the same incident and breach type.

**Why.** Each escalation rule is independent and optional; the monitor should not know them. A handler either deals
with the breach or passes it on, so the order "Technician, then Team Lead, then Administrator" is data (the `@Order`
values), and a new level is a new `@Component`. Reassignment goes through `Incident.assign`, so the state rules and the
event stream stay the same as for any other assignment, and audit and notifications follow without extra code.
`EscalationChainTest` checks the rules without a database and `EscalationIntegrationTest` checks that an
unacknowledged breach is reassigned and recorded once.

## 5.6 Observer: events, audit and notifications

**Where.** The publishers call `ApplicationEventPublisher.publishEvent(...)` with subclasses of the sealed
`IncidentEvent`. Two observers: `AuditListener` (`@EventListener`, same transaction) and `NotificationDispatcher`
(`@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`). The SSE push in `InAppChannel` is
registered as an `afterCommit` synchronisation, so the browser is told only after the row is visible.

**Why.** The brief lists Notifications, Audit events and status changes as Observer use cases. The incident code
does not know that audit or notifications exist: P2 and P3 only publish. This is what lets five people work in
parallel. The two listeners deliberately differ in transaction phase: audit must commit or roll back with the action
(complete history), notifications must never be sent for an action that rolled back and must never undo one that
succeeded (each recipient and channel runs in its own `REQUIRES_NEW` transaction inside its own try/catch).

## 5.7 Adapter: email gateway

**Where.** `interface EmailGateway { fun send(to, subject, body) }` is the interface the application wants.
`SmtpEmailGateway` adapts Spring's `JavaMailSender` (a different interface) to it; `LoggingEmailGateway` is a
stand-in that writes the email to the log. `EmailGatewayConfig` selects one from `itsm.mail.gateway`.
`AsyncEmailSender` depends only on `EmailGateway` and falls back to logging when the gateway throws.

**Why.** The brief lists "external notification/payment/cloud services" for Adapter. The channel code does not care
whether mail goes to an SMTP server, Mailpit, a cloud mail API or the log, and tests use `RecordingEmailGateway`.

## 5.8 Factory

* **`UserFactory`** builds the right `User` subclass (`createEmployee`, `createTechnician`, `createAdministrator`, or
  `create(role, ...)`), validates the raw password (`PasswordPolicy`) and hashes it, and rejects fields that do not
  apply to the role (department only for employees; level, capacity and skills only for technicians). Used by
  registration, the admin API, the bootstrap admin and the demo seeder, so there is a single place that decides how a
  user is created.
* **`NotificationFactory`** turns an `(IncidentEvent, Incident, recipient)` into the `Notification` that recipient
  should see: the title and text depend on the event type and on whether the recipient is the assignee. The `when`
  over the sealed event class is exhaustive, so adding an event without a message does not compile.

## 5.9 Repository

All persistence goes through Spring Data repository interfaces that hide SQL and JPA: `IncidentRepository`,
`IncidentNoteRepository`, `UserRepository` (with `EmployeeRepository`, `TechnicianRepository`), `CategoryRepository`,
`ItServiceRepository`, `SlaPolicyRepository`, `SlaEventRepository`, `SystemSettingRepository`, `AuditLogRepository`,
`NotificationRepository`. Domain classes never see a connection; services depend on interfaces, so tests can use mocks
(for example `PriorityStrategyTest` mocks `SystemSettingRepository`). Searching uses `JpaSpecificationExecutor` with
small `Specification` objects (`IncidentSpecifications`).

---

## 5.10 OOP principles, with examples

### Encapsulation

* **State is changed only through methods that check the rules.** `Incident` properties have `private set`; there is no
  `incident.status = CLOSED` from outside. `classify`, `assign`, `resolve`, `transitionTo`, `applyPriority`, `applySla`
  and `submitFeedback` validate input (`domainRequire`) and the lifecycle. `transitionTo` even refuses CLASSIFIED,
  ASSIGNED and RESOLVED because those must record data and go through `classify`, `assign` and `resolve`.
* **`User.passwordHash` is `private`.** The only ways in are `passwordMatches`, `changePassword` and `resetPassword`;
  `toString` prints neither the password nor the hash.
* **`Technician.skills` returns a snapshot** (`skillCodes.toSet()`); the mutable set is private and normalised on
  every write (`addSkill`, `replaceSkills`).
* `token_version` and the last-login columns are read-only on the entity and written by dedicated atomic SQL
  (`SessionRevoker`), so the entity cannot overwrite them.
* **`IncidentNote` is append-only**: all columns are `updatable = false` and the class has no mutators.
* Time is never read inside the domain: `now` is passed in, so behaviour is deterministic and testable.

### Abstraction

Complex operations are named use cases behind small interfaces: `PriorityStrategy`, `AssignmentStrategy`,
`SlaService`, `CategorySuggester`, `DuplicateDetector`, `NotificationChannel`, `EmailGateway`, `TokenService`,
`StrategyKeySource`. Callers (`StandardIncidentPipeline`, `NotificationDispatcher`) depend on the abstraction only.
`IncidentService` exposes `report`, `acknowledge`, `resolve`, `verify`, `close`, one transaction each.

### Inheritance (only where "is-a" holds)

`Employee`, `Technician` and `Administrator` extend the abstract `User` (single-table inheritance). They share
identity, credentials and activation; they differ in role-specific data. `ItsmException` is the root of the error
hierarchy (section 8.1), and `EscalationHandler` is a base class for the chain. Notably, incidents, notes and states
do *not* inherit from each other: relationships that are "has-a" or "plays-a-role" use composition (`Party`, `Actor`).

### Polymorphism

* `IncidentState.checkTransition` behaves differently per state object, selected at run time from the status.
* `PriorityStrategy.calculate` and `AssignmentStrategy.pick` are interchangeable implementations behind one call site.
* `User.role` is abstract and answered by each subclass; JPA loads the right subclass polymorphically
  (`UserRepositoryTest.employee technician and administrator load polymorphically with fields intact`).
* `NotificationChannel.send` is called on every registered channel in a loop; `EmailGateway.send` hides SMTP vs log.
* The `when` over the sealed `IncidentEvent` in `NotificationFactory`, `RecipientResolver` and `AuditDescriber` is
  exhaustive.

### Interfaces

See the abstraction list above; each interface is a small contract with one reason to exist. Kotlin `fun interface`
is used for the single-method `StrategyKeySource`.

### Composition, association, aggregation

* `StandardIncidentPipeline` is **composed** of a suggester, repositories, `PriorityService`, `ServiceCatalog`,
  `SlaService`, `AssignmentService` and an event publisher; none is inherited.
* `Incident` is **associated** with its reporter, assignee, category and service by id; it **aggregates** a thread of
  `IncidentNote`s (notes are deleted with the incident: `on delete cascade`).
* `Technician` **owns** a set of skill codes (element collection in `technician_skills`).
* `NotificationDispatcher` aggregates the list of `NotificationChannel`s Spring injects.

### Exception handling and collections

Custom exceptions carry their HTTP status (chapter 8). Data structures follow their use: `Map<IncidentStatus,
Set<Party>>` for transition tables, `LinkedHashMap` with access order as an LRU map in `LoginAttemptTracker`,
`ConcurrentHashMap` for SSE emitters and warned-key sets, `CopyOnWriteArrayList` for emitters per user, sets for
skills and candidate exclusion, and a list of beans sorted by `@Order` for the escalation chain.

### SOLID, with concrete examples

| Principle | Example in the code |
|---|---|
| **S**ingle responsibility | `AssignmentService` decides *who is eligible*, a strategy decides *who is best*; `RecipientResolver` decides *who*, `NotificationFactory` decides *what text*, channels decide *how*; `AuditDescriber` is a pure function from event to text |
| **O**pen/closed | New priority strategy, assignment strategy, notification channel, escalation handler, setting key: add a class or a line; no existing caller changes (`docs/p3.md`, "How to extend") |
| **L**iskov substitution | Every `PriorityStrategy` accepts any valid `PriorityInput` and returns a score 0 to 10; every `AssignmentStrategy` returns `null` for no candidates; `SmtpEmailGateway` and `LoggingEmailGateway` are interchangeable |
| **I**nterface segregation | `EmailGateway` has one method; `StrategyKeySource` has one method; `NotificationChannel` has two members; strategies do not know about persistence |
| **D**ependency inversion | Services receive interfaces through constructors (`SlaService`, `CategorySuggester`, `EmailGateway`, repositories); Spring wires implementations; the domain depends on nothing outside the JDK and `common.exception` |

### Loose coupling

* **Events** instead of calls: incident code never imports audit or notification classes.
* **Small input objects** (`PriorityInput`, `AssignmentRequest`, `ProcessingContext`, `EscalationContext`) make the
  contracts explicit and keep method signatures stable when fields are added.
* **Ids instead of entities** across modules (`reporterId`, `assigneeId`, `serviceId`, `categoryCode`).
* **Read-only mirrors** of foreign tables (`reference/ReferenceEntities.kt`) instead of cross-module entity imports.
* **Caller passed in** (`AuthenticatedUser`) instead of reading the security context inside services, so the demo
  seeder and tests can act as any user.

## 5.11 Intelligent features (the brief's "at least two")

| Feature | Class | Algorithm |
|---|---|---|
| Category suggestion | `KeywordCategorySuggester` | Keyword lists per category; whole-word, case-insensitive matches in title and description matched separately; overlapping matches count once (longest wins); winner = category with most distinct keywords. `confidence = min(0.95, n/(n+1)) * n / total` where n is the winner's matches and total all matches, so a text that fits two categories equally gets a low value. The pipeline uses a suggestion only at confidence 0.5 or more. |
| Duplicate / similar incident hints | `TokenSimilarityDuplicateDetector`, `TextSimilarity` | Tokens (lower-case words, stopwords and words shorter than 3 characters removed); Jaccard similarity; `score = 0.7 * title + 0.3 * description` (title alone if a description is missing); threshold 0.4; only OPEN incidents of the last 30 days, at most the newest 500 the viewer may see. "Printer broken" vs "Printer jammed" scores 0.33 and is not flagged. |
| Skill- and load-aware assignment | `SkillLeastLoadedStrategy` | Section 5.3 |
| Explainable priority | `PriorityResult.explanation` | Section 5.2 |
| Automated SLA monitoring and escalation | `SlaMonitor`, `EscalationChain` | Section 3.5 and 5.5 |

Two more features were added after the first version:

| Feature | Class | Algorithm |
|---|---|---|
| Incident correlation (possible major incidents) | `IncidentCorrelator`, `IncidentCorrelationService` | Open incidents created inside a sliding window (default 2 hours) are grouped by service, or by category when they have no service (service wins; one incident is in at most one cluster). A group of at least 3 (default) is reported as a cluster, largest first, then newest. Pure and deterministic, so `IncidentCorrelatorTest` needs no database. Shown to administrators (`GET /api/admin/correlations`) and as related incidents on the incident page (`GET /api/incidents/{id}/related`, only incidents the viewer may see). Duplicates stay hints; nothing is linked or merged. |
| SLA risk detection (prediction) | `SlaRiskDetector`, `SlaRiskService` | An open incident is `BREACHED` when a still-active deadline has passed, and `AT_RISK` when none has but a rule fires: a window has used the policy's warning percentage; not acknowledged with less than 25 percent of the response time left; nobody assigned and 50 percent of the response time gone; or the assignee is over capacity and 50 percent of the resolution time is gone. Each result carries a readable `riskReason`. `GET /api/admin/sla/at-risk` feeds the admin SLA monitor page, and the technician workload view marks each incident OVERDUE, AT_RISK or ON_TRACK with the same detector. |
