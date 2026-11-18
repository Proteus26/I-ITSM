# 4. UML Diagrams

All diagrams are Mermaid and render on GitHub. They are derived from the code on `main`; class and method names are
the real ones. Diagrams show the members that matter for the design, not every getter.

## 4.1 Use case diagram

Four actors: the three human roles and the **System** (the scheduler and the automatic pipeline). Use cases inside
the boundary are rounded boxes; the lines show who can start them. `<<include>>` and `<<extend>>` are drawn as dotted
labelled arrows.

```mermaid
flowchart LR
    EMP(["Employee"])
    TEC(["Technician"])
    ADM(["Administrator"])
    SYS(["System<br/>(pipeline, scheduler)"])

    subgraph SYSTEM["I-ITSM"]
        direction TB
        subgraph Account["Account"]
            UC1(["Register"])
            UC2(["Log in / log out"])
            UC3(["Edit profile, change password,<br/>log out everywhere"])
        end
        subgraph Reporting["Reporting and tracking"]
            UC4(["Report incident"])
            UC5(["Get category suggestion"])
            UC6(["See similar incidents"])
            UC7(["View my incidents and status"])
            UC8(["View incident history"])
            UC9(["Add comment / answer information request"])
            UC10(["Verify, reopen or close resolution"])
            UC11(["Cancel incident"])
            UC12(["Give feedback"])
            UC34(["Edit incident"])
            UC37(["See related incidents"])
        end
        subgraph Work["Investigation"]
            UC13(["View assigned incidents"])
            UC14(["Acknowledge and start work"])
            UC15(["Add investigation note"])
            UC16(["Request additional information"])
            UC17(["Resolve incident"])
            UC35(["View my workload,<br/>set availability"])
        end
        subgraph Admin["Administration"]
            UC18(["Manage users and technicians"])
            UC19(["Manage categories"])
            UC20(["Manage IT services"])
            UC21(["Configure strategies"])
            UC22(["Classify incident"])
            UC23(["Assign / reassign incident"])
            UC24(["Search incidents"])
            UC25(["View reports"])
            UC26(["View audit log"])
            UC27(["Monitor SLA violations"])
            UC36(["Manage SLA policies"])
            UC38(["View SLA risk and<br/>possible major incidents"])
        end
        subgraph Auto["Automatic"]
            UC28(["Classify, prioritise,<br/>apply SLA, assign"])
            UC29(["Monitor SLA deadlines"])
            UC30(["Escalate breach"])
            UC31(["Record audit entry"])
            UC32(["Send notification<br/>(in-app, email)"])
        end
        UC33(["Receive notifications"])
    end

    EMP --- UC1 & UC2 & UC3 & UC4 & UC7 & UC8 & UC9 & UC10 & UC11 & UC12 & UC24 & UC33 & UC34 & UC37
    TEC --- UC2 & UC3 & UC4 & UC8 & UC13 & UC14 & UC15 & UC16 & UC17 & UC9 & UC24 & UC33 & UC34 & UC35 & UC37
    ADM --- UC2 & UC3 & UC18 & UC19 & UC20 & UC21 & UC22 & UC23 & UC24 & UC25 & UC26 & UC27 & UC8 & UC33 & UC34 & UC36 & UC37 & UC38
    SYS --- UC28 & UC29 & UC30 & UC31 & UC32

    UC4 -.->|include| UC28
    UC4 -.->|extend| UC5
    UC4 -.->|extend| UC6
    UC22 -.->|include| UC28
    UC34 -.->|include| UC28
    UC29 -.->|include| UC30
    UC28 -.->|include| UC31
    UC17 -.->|include| UC31
    UC31 -.->|triggers| UC32
    UC32 -.->|include| UC33
```

Notes:

* Technicians use the reporting use cases too when they report an incident themselves (UC4 and the reporter actions).
* UC27 (monitor SLA violations) is served by the overview and SLA-compliance reports, the `slaStatus` search filter and
  the admin SLA monitor page (UC38), which also lists clusters of similar open incidents.
* UC34 (edit incident): which fields a person may change depends on role and status (section 2.4, BR-08); an edit that
  touches impact, urgency, affected users or service re-runs the priority and SLA steps (so it includes UC28).
* "Manage IT services" (UC20) and "Manage SLA policies" (UC36) have API endpoints and admin pages (`/admin/services`, `/admin/sla`).
* UC10 for an administrator means verifying, reopening or closing on behalf of the reporter (`REPORTER_OR_ADMIN`).
* UC35 is for technicians only; UC37 shows incidents of the same cluster that the viewer is allowed to see.

## 4.2 Class diagram: users

Single-table inheritance: one `users` table, discriminator column `role` (`EMPLOYEE`, `TECHNICIAN`, `ADMIN`). The
password hash is private; callers can only verify or replace it through methods.

```mermaid
classDiagram
    class User {
        <<abstract>>
        +Long id
        +String email
        +String fullName
        +Boolean active
        +Instant createdAt
        +Int tokenVersion
        +Instant lastLoginAt
        +Instant previousLoginAt
        -String passwordHash
        +Role role*
        +passwordMatches(encoder, raw) Boolean
        +changePassword(encoder, current, new)
        +resetPassword(encoder, new)
        +activate()
        +deactivate()
        +rename(fullName)
        +changeEmail(email)
    }
    class Employee {
        +String department
        +changeDepartment(department)
    }
    class Technician {
        +TechnicianLevel level
        +Int maxActiveIncidents
        +Boolean available
        +Set~String~ skills
        +addSkill(code) Boolean
        +removeSkill(code) Boolean
        +replaceSkills(codes)
        +hasSkill(code) Boolean
        +markAvailable()
        +markUnavailable()
        +changeLevel(level)
        +changeCapacity(max)
    }
    class Administrator
    class Role {
        <<enumeration>>
        EMPLOYEE
        TECHNICIAN
        ADMIN
    }
    class TechnicianLevel {
        <<enumeration>>
        L1
        L2
        TEAM_LEAD
    }
    class UserFactory {
        <<Factory>>
        +createEmployee(email, fullName, rawPassword, department) Employee
        +createTechnician(...) Technician
        +createAdministrator(...) Administrator
        +create(role, email, fullName, rawPassword, ...) User
    }
    class PasswordPolicy {
        <<object>>
        +MIN_LENGTH = 8
        +MAX_BYTES = 72
        +violation(raw) String
        +requireUsable(raw)
    }
    class AuthenticatedUser {
        <<value>>
        +Long id
        +String email
        +Role role
    }

    User <|-- Employee
    User <|-- Technician
    User <|-- Administrator
    User ..> Role : role
    Technician --> TechnicianLevel
    UserFactory ..> User : creates
    UserFactory ..> PasswordPolicy : validates
    Technician "1" --> "*" Category : skills by code
```

`AuthenticatedUser` is the per-request projection built from the validated token (`UserJwtAuthenticationConverter`);
services receive it instead of the entity.

## 4.3 Class diagram: incident domain

`Incident` is the aggregate root. It does not know which status-specific rules apply: it delegates to the
`IncidentState` object for its current status (State pattern). People are referenced by id. `Actor` says who is acting
and `Party` says which role that actor plays on this incident.

```mermaid
classDiagram
    class Incident {
        <<aggregate root>>
        +Long id
        +String number
        +String title
        +String description
        +Long reporterId
        +Long assigneeId
        +String categoryCode
        +Long serviceId
        +Impact impact
        +Urgency urgency
        +Int affectedUsers
        +Priority priority
        +Double priorityScore
        +String priorityExplanation
        +IncidentStatus status
        +Instant responseDueAt
        +Instant resolutionDueAt
        +Instant acknowledgedAt
        +Instant resolvedAt
        +Instant closedAt
        +String resolutionSummary
        +String rootCause
        +Int feedbackRating
        +Long version
        +state IncidentState
        +isActing(actor, party) Boolean
        +isVisibleTo(actor) Boolean
        +availableTransitions(actor) Set
        +transitionTo(target, actor, now) StatusChange
        +classify(category, actor, now) StatusChange
        +assign(technicianId, actor, now) StatusChange
        +resolve(summary, rootCause, actor, now) StatusChange
        +applyPriority(priority, score, now, explanation) Priority
        +applySla(responseDueAt, resolutionDueAt, now)
        +submitFeedback(rating, comment, actor, now)
        +canGiveFeedback(actor) Boolean
        +editableFieldsFor(actor) Set
        +edit(edit, actor, now) EditResult
    }
    class IncidentEdit {
        <<data>>
        +String title
        +String description
        +Impact impact
        +Urgency urgency
        +Int affectedUsers
        +Long serviceId
        +Boolean clearService
    }
    class EditResult {
        <<data>>
        +List changed
        +affectsPriority Boolean
    }
    class EditField {
        <<enumeration>>
        TITLE
        DESCRIPTION
        IMPACT
        URGENCY
        AFFECTED_USERS
        SERVICE
    }
    Incident ..> IncidentEdit : edit
    Incident ..> EditResult : returns
    EditResult --> EditField
    class IncidentState {
        <<sealed>>
        +IncidentStatus status
        #transitions Map
        +allowedTransitions Set
        +canTransitionTo(target) Boolean
        +partiesFor(target) Set
        +availableTargets(incident, actor) Set
        +checkTransition(incident, target, actor)
        +of(status)$ IncidentState
    }
    class NewState
    class ClassifiedState
    class AssignedState
    class AcknowledgedState
    class InProgressState
    class PendingInfoState
    class ResolvedState
    class VerifiedState
    class ClosedState
    class CancelledState
    class IncidentStatus {
        <<enumeration>>
        NEW
        CLASSIFIED
        ASSIGNED
        ACKNOWLEDGED
        IN_PROGRESS
        PENDING_INFO
        RESOLVED
        VERIFIED
        CLOSED
        CANCELLED
        +isOpen Boolean
        +countsTowardWorkload Boolean
    }
    class Party {
        <<enumeration>>
        REPORTER
        ASSIGNEE
        ADMIN
        SYSTEM
    }
    class Actor {
        <<value>>
        +Long userId
        +Role role
        +isSystem Boolean
        +isAdmin Boolean
        +of(user)$ Actor
        +SYSTEM Actor
    }
    class IncidentNote {
        +Long id
        +Long incidentId
        +Long authorId
        +NoteType type
        +String body
        +Instant createdAt
    }
    class NoteType {
        <<enumeration>>
        INVESTIGATION
        INFO_REQUEST
        INFO_RESPONSE
        COMMENT
    }
    class Impact {
        <<enumeration>>
        INDIVIDUAL = 1
        TEAM = 2
        DEPARTMENT = 3
        ORGANISATION = 4
    }
    class Urgency {
        <<enumeration>>
        LOW = 1
        MEDIUM = 2
        HIGH = 3
        CRITICAL = 4
    }
    class Priority {
        <<enumeration>>
        P1
        P2
        P3
        P4
    }
    class StatusChange {
        <<value>>
        +IncidentStatus from
        +IncidentStatus to
    }
    class Category {
        +String code
        +String name
        +Boolean active
    }
    class ItService {
        +Long id
        +String code
        +String name
        +Int criticality
        +Boolean active
    }

    IncidentState <|-- NewState
    IncidentState <|-- ClassifiedState
    IncidentState <|-- AssignedState
    IncidentState <|-- AcknowledgedState
    IncidentState <|-- InProgressState
    IncidentState <|-- PendingInfoState
    IncidentState <|-- ResolvedState
    IncidentState <|-- VerifiedState
    IncidentState <|-- ClosedState
    IncidentState <|-- CancelledState
    Incident --> IncidentState : state, derived from status
    Incident --> IncidentStatus : status
    Incident ..> Actor : acts for
    IncidentState ..> Party : who may move
    Incident ..> Party : isActing
    Incident ..> StatusChange : returns
    Incident --> Impact
    Incident --> Urgency
    Incident --> Priority
    Incident "1" o-- "*" IncidentNote : thread, by incident_id
    IncidentNote --> NoteType
    Incident "*" --> "0..1" Category : categoryCode
    Incident "*" --> "0..1" ItService : serviceId
    Incident "*" --> "1" User : reporterId
    Incident "*" --> "0..1" Technician : assigneeId
```

The ten state classes are Kotlin `object`s (singletons): they hold no per-incident data, only the transition table.

## 4.4 Class diagram: strategies

Two Strategy families, each chosen at run time by a key. Both are open for extension: add a `@Component` that
implements the interface and it is picked up by the selector service.

```mermaid
classDiagram
    class PriorityStrategy {
        <<interface>>
        +String key
        +calculate(input PriorityInput) PriorityResult
    }
    class WeightedScoreStrategy {
        key = weighted
        +P1_THRESHOLD = 8.0
        +P2_THRESHOLD = 6.0
        +P3_THRESHOLD = 4.0
    }
    class MatrixStrategy {
        key = matrix
        -MATRIX 4x4
    }
    class PriorityService {
        +availableStrategies Set
        +calculate(input) PriorityResult
    }
    class PriorityInput {
        <<data>>
        +Int impact
        +Int urgency
        +Int serviceCriticality
        +Int affectedUsers
        +String categoryCode
    }
    class PriorityResult {
        <<data>>
        +Priority priority
        +Double score
        +String explanation
    }
    class SettingsService {
        +get(key, default) String
        +set(key, value)
    }
    PriorityStrategy <|.. WeightedScoreStrategy
    PriorityStrategy <|.. MatrixStrategy
    PriorityService o-- "*" PriorityStrategy : by key
    PriorityService --> SettingsService : priority.strategy
    PriorityStrategy ..> PriorityInput
    PriorityStrategy ..> PriorityResult

    class AssignmentStrategy {
        <<interface>>
        +String key
        +pick(request, candidates) AssignmentResult
    }
    class SkillLeastLoadedStrategy {
        key = skill-least-loaded
        +SENIOR_LOAD_LIMIT = 0.8
    }
    class RoundRobinStrategy {
        key = round-robin
        -Long lastId
    }
    class AssignmentStrategySelector {
        +keys Set
        +select() AssignmentStrategy
    }
    class StrategyKeySource {
        <<interface>>
        +currentKey() String
    }
    class PropertyStrategyKeySource {
        itsm.assignment.strategy
    }
    class SettingsStrategyKeySource {
        system_settings assignment.strategy
    }
    class AssignmentService {
        +availableStrategies Set
        +assign(request) AssignmentResult
        +recommend(request, limit) List
    }
    class AssignmentRequest {
        <<data>>
        +Long incidentId
        +String categoryCode
        +Priority priority
        +Set excludeTechnicianIds
    }
    class TechnicianCandidate {
        <<data>>
        +Long id
        +String level
        +Set skills
        +Long activeIncidents
        +Int maxActiveIncidents
        +load Double
    }
    class AssignmentResult {
        <<data>>
        +Long technicianId
        +String strategy
        +String reason
    }
    AssignmentStrategy <|.. SkillLeastLoadedStrategy
    AssignmentStrategy <|.. RoundRobinStrategy
    StrategyKeySource <|.. PropertyStrategyKeySource
    StrategyKeySource <|.. SettingsStrategyKeySource
    SettingsStrategyKeySource --> PropertyStrategyKeySource : fallback
    AssignmentStrategySelector o-- "*" AssignmentStrategy
    AssignmentStrategySelector --> StrategyKeySource
    AssignmentService --> AssignmentStrategySelector
    AssignmentService --> SkillLeastLoadedStrategy : recommend
    AssignmentStrategy ..> AssignmentRequest
    AssignmentStrategy ..> TechnicianCandidate
    AssignmentStrategy ..> AssignmentResult

    class CategorySuggester {
        <<interface>>
        +suggest(title, description) CategorySuggestion
    }
    class KeywordCategorySuggester
    class DuplicateDetector {
        <<interface>>
        +findSimilar(title, description, viewer, excludeId, limit) List
    }
    class TokenSimilarityDuplicateDetector {
        +THRESHOLD = 0.4
    }
    class TextSimilarity {
        <<object>>
        +tokens(text) Set
        +jaccard(a, b) Double
        +score(...) Double
    }
    CategorySuggester <|.. KeywordCategorySuggester
    DuplicateDetector <|.. TokenSimilarityDuplicateDetector
    TokenSimilarityDuplicateDetector ..> TextSimilarity
```

`AssignmentStrategySelector` reads the key through `SettingsStrategyKeySource`, which uses the `assignment.strategy` setting
in `system_settings` and falls back to `PropertyStrategyKeySource` (`itsm.assignment.strategy`) (see section 5.3).

Incident correlation (`IncidentCorrelator`, a pure class grouping `CorrelationCandidate`s into `IncidentCluster`s) and
`IncidentCorrelationService` (loads open incidents, filters by what the viewer may see) are described in section 5.11.

## 4.5 Class diagram: notifications (Observer, Factory, Adapter)

```mermaid
classDiagram
    class IncidentEvent {
        <<sealed>>
        +Long incidentId
        +Long actorId
        +Instant occurredAt
    }
    class IncidentReported
    class StatusChanged
    class IncidentAssigned
    class SlaBreached
    class IncidentEscalated
    IncidentEvent <|-- IncidentReported
    IncidentEvent <|-- StatusChanged
    IncidentEvent <|-- IncidentAssigned
    IncidentEvent <|-- SlaBreached
    IncidentEvent <|-- IncidentEscalated

    class NotificationDispatcher {
        <<Observer>>
        +onEvent(event IncidentEvent)
    }
    class AuditListener {
        <<Observer>>
        +onIncidentEvent(event)
        +onLogin(event)
        +onLoginFailed(event)
    }
    class RecipientResolver {
        +resolve(event, incident) List~User~
    }
    class NotificationFactory {
        <<Factory>>
        +create(event, incident, recipient) Notification
    }
    class Notification {
        <<data>>
        +Long recipientId
        +String recipientEmail
        +String title
        +String body
        +String link
        +Long incidentId
    }
    class NotificationChannel {
        <<interface>>
        +ChannelType type
        +send(notification)
    }
    class InAppChannel {
        type = IN_APP
    }
    class EmailChannel {
        type = EMAIL
    }
    class AsyncEmailSender {
        +send(to, subject, text)
    }
    class EmailGateway {
        <<interface>>
        +send(to, subject, body)
    }
    class SmtpEmailGateway
    class LoggingEmailGateway
    class NotificationStream {
        +subscribe(userId) SseEmitter
        +push(userId, dto)
        +heartbeat()
    }
    class NotificationEntity {
        <<entity>>
    }

    NotificationDispatcher ..> IncidentEvent : observes after commit
    AuditListener ..> IncidentEvent : observes in transaction
    NotificationDispatcher --> RecipientResolver
    NotificationDispatcher --> NotificationFactory
    NotificationDispatcher o-- "*" NotificationChannel
    NotificationFactory ..> Notification : creates
    NotificationChannel <|.. InAppChannel
    NotificationChannel <|.. EmailChannel
    InAppChannel --> NotificationEntity : saves
    InAppChannel --> NotificationStream : push
    EmailChannel --> AsyncEmailSender
    AsyncEmailSender --> EmailGateway
    EmailGateway <|.. SmtpEmailGateway
    EmailGateway <|.. LoggingEmailGateway
    SmtpEmailGateway ..> JavaMailSender : adapts
```

`EmailGateway` is the target interface of the Adapter; `SmtpEmailGateway` adapts Spring's `JavaMailSender` to it.
`EmailGatewayConfig` selects the implementation from `itsm.mail.gateway` (`smtp` or `logging`).

## 4.6 Class diagram: SLA and escalation chain

```mermaid
classDiagram
    class SlaPolicy {
        +Long serviceId
        +Priority priority
        +Int responseMinutes
        +Int resolutionMinutes
        +Int warningThresholdPct
    }
    class SlaPolicyResolver {
        +resolve(serviceId, priority) SlaPolicy
    }
    class SlaService {
        <<interface>>
        +computeDeadlines(serviceId, priority, from) SlaDeadlines
    }
    class DefaultSlaService
    class SlaDeadlines {
        <<data>>
        +Instant responseDueAt
        +Instant resolutionDueAt
        +Long policyId
    }
    class SlaMonitor {
        +runOnce(now Instant) Int
    }
    class SlaMonitorScheduler {
        +tick()
    }
    class SlaEvent {
        +Long incidentId
        +SlaEventType type
        +Instant occurredAt
    }
    class SlaEventType {
        <<enumeration>>
        WARNING
        RESPONSE_BREACH
        RESOLUTION_BREACH
    }
    class EscalationContext {
        <<data>>
        +Incident incident
        +SlaEventType breach
    }
    class EscalationHandler {
        <<abstract>>
        +EscalationHandler next
        +handle(context) Boolean
        #tryHandle(context) Boolean*
    }
    class TeamLeadEscalationHandler {
        order 1
        level = TEAM_LEAD
    }
    class AdminEscalationHandler {
        order 2
        level = ADMIN
    }
    class TeamLeadSource {
        <<interface>>
        +find(incident) Long
    }
    class JpaTeamLeadSource
    class EscalationChain {
        +escalate(context) Boolean
    }

    SlaService <|.. DefaultSlaService
    DefaultSlaService --> SlaPolicyResolver
    SlaPolicyResolver --> SlaPolicy
    DefaultSlaService ..> SlaDeadlines
    SlaMonitorScheduler --> SlaMonitor
    SlaMonitor --> SlaEvent : records once per type
    SlaEvent --> SlaEventType
    SlaMonitor --> EscalationChain
    SlaMonitor ..> SlaPolicy : reads
    EscalationHandler <|-- TeamLeadEscalationHandler
    EscalationHandler <|-- AdminEscalationHandler
    TeamLeadEscalationHandler --> TeamLeadSource
    TeamLeadSource <|.. JpaTeamLeadSource
    EscalationHandler --> EscalationHandler : next
    EscalationChain --> EscalationHandler : first
    EscalationHandler ..> EscalationContext
```

The chain is Technician (the current assignee) to Team Lead to Administrator. `TeamLeadEscalationHandler` (order 1)
handles the response breach of an incident nobody acknowledged: `JpaTeamLeadSource` picks an active, available team
lead (preferring the category skill, then the lowest load) and the incident is **reassigned** to that person with
`Actor.SYSTEM`; it publishes `IncidentEscalated(level TEAM_LEAD)`, `IncidentAssigned(strategy "escalation")` and, when
the status changed, `StatusChanged`. `AdminEscalationHandler` (order 2) is the last link: it publishes
`IncidentEscalated(level ADMIN)` for a P1/P2 resolution breach, or for an unacknowledged response breach for which no
team lead could take over. `SlaMonitor` calls the chain once per incident and breach type.

Beside the monitor, `SlaRiskDetector` is a pure class that classifies an open incident as `BREACHED` or `AT_RISK`
(`SlaRiskState`), used by `SlaRiskService` for `GET /api/admin/sla/at-risk` and by `TechnicianWorkloadService`.

## 4.7 Class diagram: report pipeline (Template Method)

```mermaid
classDiagram
    class IncidentProcessingPipeline {
        <<abstract>>
        +process(incident, context) final
        #suggestCategory(incident, context)*
        #classify(incident, context)*
        #prioritise(incident, context)*
        #applySla(incident, context)*
        #assign(incident, context)*
    }
    class StandardIncidentPipeline {
        +MIN_SUGGESTION_CONFIDENCE = 0.5
    }
    class ProcessingContext {
        +Actor actor
        +Instant now
        +Category category
    }
    class IncidentService {
        +report(reporter, request) IncidentDetail
        +classify(id, admin, request) IncidentDetail
        +assign(id, admin, request) IncidentDetail
        +acknowledge(id, viewer) IncidentDetail
        +resolve(id, viewer, request) IncidentDetail
    }
    IncidentProcessingPipeline <|-- StandardIncidentPipeline
    IncidentProcessingPipeline ..> ProcessingContext
    IncidentService --> IncidentProcessingPipeline
    StandardIncidentPipeline --> CategorySuggester
    StandardIncidentPipeline --> PriorityService
    StandardIncidentPipeline --> ServiceCatalog
    StandardIncidentPipeline --> SlaService
    StandardIncidentPipeline --> AssignmentService
    StandardIncidentPipeline --> CategoryRepository
    StandardIncidentPipeline ..> IncidentEvent : publishes
```

## 4.8 State diagram: incident lifecycle

Derived directly from the transition tables in `IncidentState.kt`; edge labels are the parties allowed to make the move
(`SYSTEM_OR_ADMIN`, `REPORTER_OR_ADMIN`, and so on). Reassignment (an administrator or the system giving an active
incident to another technician) is drawn separately because it is not a transition of its own (`Incident.assign`).

```mermaid
stateDiagram-v2
    [*] --> NEW : report (reporter)

    NEW --> CLASSIFIED : classify (system, admin)
    NEW --> CANCELLED : cancel (reporter, admin)

    CLASSIFIED --> ASSIGNED : assign (system, admin)
    CLASSIFIED --> CANCELLED : cancel (reporter, admin)

    ASSIGNED --> ACKNOWLEDGED : acknowledge (assignee)
    ACKNOWLEDGED --> IN_PROGRESS : start (assignee)

    IN_PROGRESS --> PENDING_INFO : request-info (assignee)
    PENDING_INFO --> IN_PROGRESS : resume (assignee, reporter)

    IN_PROGRESS --> RESOLVED : resolve (assignee)
    RESOLVED --> VERIFIED : verify (reporter, admin)
    RESOLVED --> IN_PROGRESS : reopen (reporter, admin)

    VERIFIED --> CLOSED : close (reporter, admin, system)

    CLOSED --> [*]
    CANCELLED --> [*]

    note right of ASSIGNED
        Reassignment: from ASSIGNED, ACKNOWLEDGED,
        IN_PROGRESS or PENDING_INFO an admin or the
        system can pick another technician.
        The incident goes back to ASSIGNED and
        acknowledgedAt is cleared.
    end note
    note left of NEW
        NEW to CLOSED has no edge:
        409 InvalidStateTransitionException.
        Wrong person on a valid edge: 403.
    end note
```

## 4.9 Sequence diagram: lifecycle after assignment

```mermaid
sequenceDiagram
    autonumber
    actor T as Technician
    actor R as Reporter
    participant S as IncidentService
    participant I as Incident
    participant ST as IncidentState
    participant EV as Events
    T->>S: acknowledge(id)
    S->>I: transitionTo(ACKNOWLEDGED, actor, now)
    I->>ST: state.checkTransition(incident, target, actor)
    ST-->>I: ok (assignee) or 409 / 403
    I-->>S: StatusChange(ASSIGNED, ACKNOWLEDGED)
    S->>EV: StatusChanged
    T->>S: start(id)
    S->>EV: StatusChanged (IN_PROGRESS)
    T->>S: resolve(id, summary, rootCause)
    S->>I: resolve(...)
    S->>EV: StatusChanged, IncidentResolved
    R->>S: verify(id)
    S->>EV: StatusChanged (VERIFIED)
    R->>S: close(id)
    S->>EV: StatusChanged, IncidentClosed
```
