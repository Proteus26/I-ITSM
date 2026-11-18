# 1. Problem Analysis

Course project CS F213 / MAC F212 (Object Oriented Programming), Semester I 2026-27.
Project: **I-ITSM, an Intelligent IT Service Management and Incident Resolution System**.

## 1.1 The problem

An organisation with hundreds of employees depends on many IT services (payments, email, VPN, databases, single
sign-on, storage, HR systems, the public website). When one of them breaks, the person who notices it usually
reports it informally (chat, phone call, email). The brief's example is typical: "the payment application is
unavailable and users are unable to complete transactions".

The IT department then has to answer the same questions for every report, and every one of them is a place where
work is lost:

| Question | What goes wrong without a system |
|---|---|
| What is broken and who is affected? | Reports are vague and nobody records which service or how many users are affected. |
| How urgent is it? | Priority is a guess by whoever reads the message first; loud users win over critical services. |
| Who should handle it? | Work goes to the person who happens to answer, not the person with the right skill or free capacity. |
| By when must it be answered and fixed? | There is no agreed deadline, so late work is noticed only when someone complains. |
| What happened to it? | Nobody can reconstruct who did what and when (no audit trail). |
| Is the same problem already being worked on? | Duplicate reports waste technician time. |
| How is IT performing overall? | No numbers on workload, resolution time or SLA compliance. |

## 1.2 Existing challenges

1. **Unstructured intake.** Free-text reports lack category, service, impact and urgency, so nothing downstream
   can be automated.
2. **Inconsistent prioritisation.** The same outage gets different priority depending on who triages it.
3. **Manual, unfair assignment.** Skilled people are overloaded while others are idle; there is no capacity limit.
4. **Hidden SLA risk.** Deadlines exist on paper only; breaches are discovered after the fact.
5. **No accountability.** Without a complete history it is impossible to audit a decision or learn from a failure.
6. **Poor communication.** The reporter does not know what is happening; technicians do not know what is new.
7. **No management view.** Administrators cannot see workload, trends or compliance.

## 1.3 Proposed solution

I-ITSM is a web application that manages the complete incident lifecycle
**Report, Classify, Prioritise, Assign, Investigate, Resolve, Verify, Close** (the brief's primary objective). The
system does the routine thinking automatically and keeps people in control of the exceptions:

* **Structured reporting.** An employee gives title, description, impact, urgency, affected users, the affected IT
  service and (optionally) a category. If no category is given the system suggests one from the text
  (`KeywordCategorySuggester`), and the form shows similar open incidents while typing
  (`TokenSimilarityDuplicateDetector`).
* **Automatic processing pipeline.** `IncidentProcessingPipeline` (Template Method) classifies the incident,
  calculates its priority from impact, urgency, service criticality, number of affected users and category
  (`PriorityStrategy`), derives SLA deadlines from the SLA policy (`SlaService`), and picks a technician
  (`AssignmentStrategy`).
* **Enforced lifecycle.** The incident's status is governed by the State pattern (`IncidentState`); impossible
  moves are refused with 409 and moves by the wrong person with 403.
* **Monitoring.** `SlaMonitor` runs on a schedule, records warnings and breaches and starts escalation
  (`EscalationChain`).
* **Communication and history.** Domain events are published for every important action. `AuditListener` writes the
  audit trail; `NotificationDispatcher` sends in-app (live over SSE) and email notifications.
* **Management view.** Search with ten filters, six reports with charts, an audit log viewer, and administration of
  users, categories, services (catalog) and strategies.

The brief's 14-step scenario (create, classify, identify service, determine impact, calculate priority,
determine SLA, identify technicians, assign, notify, track, monitor SLA, record activities, close, report) maps
one-to-one onto the implementation; the mapping is in [10-user-guide-and-demo.md](10-user-guide-and-demo.md).

## 1.4 Stakeholders

| Stakeholder | Interest | How the system serves them |
|---|---|---|
| **Employee / customer** (reporter) | Get the problem fixed quickly and know its status | Simple report form, category and duplicate hints, status tracking, notifications, feedback after resolution |
| **Technician / support engineer** | A fair, relevant workload and the information needed to fix things | "Assigned to me" list sorted by priority and deadline, investigation notes, information requests, SLA deadlines on screen |
| **Administrator / IT manager** | Control, compliance and visibility | User, technician, category and service management, strategy settings, search, reports, audit log |
| **The organisation / business owners of services** | Minimal downtime of critical services | Service criticality drives priority; P1/P2 get short SLAs and escalation |
| **The system itself** (scheduler) | n/a | Acts as a fourth, non-human actor: `Actor.SYSTEM` classifies, assigns and monitors SLAs |
| **Course evaluators** | Evidence of sound OOP design | Patterns, tests and documentation in this report |

## 1.5 Goals

| # | Goal | Measured by |
|---|---|---|
| G1 | Cover every MVP item of the brief | Traceability table in [02-requirements.md](02-requirements.md) |
| G2 | Demonstrate object-oriented design (encapsulation, inheritance, polymorphism, abstraction, interfaces, composition) and at least two design patterns | [05-design-patterns-and-oop.md](05-design-patterns-and-oop.md) (eight patterns, nine uses) |
| G3 | Make priority, assignment and SLA logic replaceable | Strategy interfaces, selectable by administrator setting (priority) or configuration (assignment) |
| G4 | Never lose history | Every status change publishes an event that is audited in the same transaction |
| G5 | Basic enterprise security | JWT with revocation, BCrypt, role checks, CSRF defences, input validation ([08-exceptions-and-security.md](08-exceptions-and-security.md)) |
| G6 | Be testable and tested | 283 backend and 357 frontend automated tests ([09-test-cases.md](09-test-cases.md)) |
| G7 | Run anywhere with one command | `docker compose up --build` |

## 1.6 Scope

**In scope**

* Three roles (employee, technician, administrator) with different screens.
* Incident lifecycle with ten statuses, notes, information requests, feedback, reopen and cancel.
* Priority calculation (two strategies), SLA policies and monitoring, escalation events.
* Two assignment strategies, category suggestion and duplicate hints (the two "intelligent features").
* Notifications (in-app with live push, email through an adapter), audit trail, search, reports.
* Persistence in PostgreSQL with versioned migrations.

**Out of scope or not done** (documented honestly so evaluators are not surprised)

* Editing an incident after it was reported, and attachments.
* Pausing the SLA clock while an incident waits for information from the reporter.
* Automatic closing of VERIFIED incidents (the state machine allows `SYSTEM` to close, but no scheduler does it).
* Shared (multi-node) login throttle and round-robin pointer: both are in memory per instance.
* Native mobile client, SSO/OAuth with an external provider, real payment or cloud integrations.

<!-- TODO(verify after merge): parallel tracks are adding admin SLA policy/services pages, an escalation chain
Technician -> Team Lead -> Admin, incident editing, a technician workload view, an admin SLA monitor with risk
detection, incident correlation, role dashboards and richer demo data. When they are merged, move the matching
bullets from "Out of scope" above into "In scope" and re-check the wording. -->

## 1.7 Constraints

| Constraint | Effect |
|---|---|
| Course deadline 22.11.2026 (report PDF and recorded demo) | Features were prioritised by the MVP list first, extras second |
| Team of five, one semester | Work was split into five modules (P1 to P5, see [03-architecture.md](03-architecture.md)) that communicate through narrow contracts |
| Brief requires OOP quality over feature count | Domain logic lives in classes with enforced invariants, not in controllers |
| Free technology choice, but must be justified | [06-technology-stack.md](06-technology-stack.md) |
| Must run on a student laptop and in CI | Docker Compose, Testcontainers, no paid services; email defaults to a logging gateway |

## 1.8 Assumptions

1. Employees register themselves (`POST /api/auth/register` always creates an **Employee**); technicians and
   administrators are created by an administrator. The first administrator is bootstrapped from environment
   variables.
2. An organisation has a small catalog of IT services, each with a business criticality from 1 (low) to 4 (critical)
   maintained by administrators. Eight services are seeded.
3. The service catalog and SLA table are the same for all customers (single tenant). A service can override the default SLA per priority.
4. SLA time is wall-clock time (no business-hours calendar).
5. A technician's capacity is a number of simultaneously active incidents (`maxActiveIncidents`, default 5). Capacity
   is a soft limit under concurrency (documented in `AssignmentService`).
6. A technician may also report incidents; the system never assigns an incident to its own reporter.
7. Category skills are the codes of the nine seeded categories (HARDWARE, SOFTWARE, NETWORK, DATABASE, CLOUD,
   SECURITY, AUTHENTICATION, APPLICATION, STORAGE).
8. The backend runs as a single instance (in-memory SSE hub, login throttle and round-robin pointer). Scaling out
   needs shared state for those three; see "evolution" in [06-technology-stack.md](06-technology-stack.md).
9. Email is simulated by default (`ITSM_MAIL_GATEWAY=logging`); real SMTP is a configuration switch.
10. Times are stored in UTC (`timestamptz`) and shown in the browser's locale.
