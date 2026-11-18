# 6. Technology Stack and Selection Report

This chapter lists every technology, framework and library used, and answers the nine questions of the brief's
"Technology Selection Report".

## 6.1 Stack at a glance

| Layer | Technology | Version (from the build files) |
|---|---|---|
| Language (backend) | Kotlin on the JVM | Kotlin 2.2.21, JDK 21 (toolchain) |
| Backend framework | Spring Boot (Web MVC, Data JPA, Validation, Actuator, Mail) | 4.0.0 |
| Security | Spring Security, OAuth2 resource server (Nimbus JOSE), HS256 JWT | managed by Spring Boot 4.0.0 |
| Persistence | Hibernate / Jakarta Persistence (Spring Data JPA), JSON column mapping | managed by Spring Boot |
| Migrations | Flyway (`flyway-database-postgresql`) | managed by Spring Boot |
| Database | PostgreSQL | 17 (`postgres:17-alpine`) |
| API docs | springdoc-openapi (Swagger UI) | 3.0.3 |
| JSON | Jackson 3 with the Kotlin module (`tools.jackson.module:jackson-module-kotlin`) | managed |
| Language (frontend) | TypeScript | ^6.0.3 |
| Frontend framework | Astro, server-rendered, `@astrojs/node` standalone adapter | astro ^7.4.4, adapter ^11.1.9 |
| Charts | Chart.js | ^4.6.1 |
| Frontend tests | Vitest | 4.1.11 |
| Type checking | `@astrojs/check` with `astro check` | ^0.9.12 |
| Backend tests | JUnit 5, Spring Boot Test, MockMvc, Testcontainers (PostgreSQL), AssertJ, Mockito | managed |
| Package managers / build | Gradle wrapper (Kotlin DSL), pnpm | pnpm 12.9.0, Node 24 |
| Containers | Docker, Docker Compose, multi-stage Dockerfiles (`eclipse-temurin:21`, `node:24-alpine`) | |
| CI | GitHub Actions (`.github/workflows/ci.yml`) | actions/checkout v4, setup-java v4, setup-gradle v4, pnpm/action-setup v4, setup-node v4 |
| Optional services | SMTP server, for example Mailpit | not required |

## 6.2 The nine questions

### 1. Why this programming language?

**Backend: Kotlin.** The course is object-oriented, and Kotlin is a fully object-oriented JVM language with features
that make the design of this project shorter and safer than plain Java:

* `sealed class` gives the State pattern and the event hierarchy compiler-checked exhaustive `when` expressions
  (`IncidentState.of`, `NotificationFactory.create`).
* `data class` for value objects (`Actor`, `PriorityInput`, `AssignmentRequest`, DTOs) with equality and copy for free.
* Null-safety in the type system (`Long?` for `assigneeId`, `String?` for `categoryCode`), so "no value" is explicit.
* `private set` properties and `object` singletons express encapsulation and the singleton state objects directly.
* Full Java interoperability: Spring, Hibernate and Flyway work unchanged.

*Alternatives:* **Java** (verbose, more boilerplate for the same patterns; nullability not in the types),
**Scala** (more powerful but a steeper learning curve and weaker Spring ecosystem), **TypeScript on Node** (one
language everywhere, but weaker support for the OOP concepts the course evaluates and no sealed hierarchies with
compile-time exhaustiveness).
*Trade-offs:* a slightly smaller pool of tutorials than Java; the build plugins `kotlin-spring` and `kotlin-jpa`
(`allOpen`) are needed so classes can be proxied and entities have a no-arg constructor.

**Frontend: TypeScript.** The frontend handles API payloads and forms where typos in field names are the common bug;
interfaces such as `UserResponse`, `IncidentDetail` and `ApiError` catch them at build time (`pnpm check`).

### 2. Why this frontend technology?

**Astro in server-rendered mode.**

* Each page is rendered on the server with the user's session, so role-specific pages are simple (`Astro.locals.user`)
  and the **JWT never reaches JavaScript** (it stays in an httpOnly cookie that the Astro server converts to a bearer
  header).
* Pages are HTML first: forms are plain `<form method="post">` handled in the page frontmatter (post/redirect/get).
  They work without JavaScript, which keeps the UI accessible and the code small. JavaScript is added only where it
  helps: live suggestions (`incident-suggest.js`), the confirm dialog (`confirm.js`), the admin menu, the notification bell
  with `EventSource`, and Chart.js on the reports page.
* One origin: the Astro server proxies `/api/**` to the backend, so no CORS or cookie-domain problems, and the proxy is
  a single place for CSRF defences, header allow-lists, body limits and timeouts (`lib/proxy.ts`, `lib/csrf.ts`).

*Alternatives:* **React/Vite single-page app** (the brief's example; needs the token in the browser or a separate
backend-for-frontend, more client state), **Next.js** (similar SSR benefits but a heavier framework and runtime),
**Angular, Vue** (full SPA frameworks, more tooling), **Thymeleaf inside Spring** (would remove the second server but
tie the UI to the backend and make it harder to show separation of tiers).
*Trade-offs:* a second runtime (Node) to deploy; fewer ready-made component libraries than React; page navigation is
full page loads (acceptable for this workflow-style application).

### 3. Why this backend technology?

**Spring Boot with Spring Web MVC, Spring Data JPA and Spring Security.**

* Mature, widely used in enterprise ITSM-like systems, and a natural fit for the brief's "React + Spring Boot +
  PostgreSQL" suggested combination.
* **Dependency injection** is the mechanism behind several patterns here: Spring collects all `PriorityStrategy`,
  `AssignmentStrategy`, `NotificationChannel` and `EscalationHandler` beans into lists, so a new implementation is just
  a new `@Component`.
* **Application events** (`ApplicationEventPublisher`, `@EventListener`, `@TransactionalEventListener`) are the Observer
  implementation, with transaction-phase control that audit and notifications rely on.
* **Declarative transactions** (`@Transactional`) give "one transaction per use case", and `TransactionTemplate` with
  `REQUIRES_NEW` isolates notification delivery and per-incident SLA checks.
* **Spring Security's resource-server support** validates JWTs with little code, and `@EnableMethodSecurity` gives
  `@PreAuthorize`.
* `@Scheduled` and `@Async` cover the SLA monitor and background email without extra infrastructure.
* Spring Boot 4 (Jakarta EE 11 era) is current, with Jackson 3 and the new modular starters.

*Alternatives:* **Node/Express or NestJS** (fast to start, but weaker typing for domain rules and no built-in
transaction/event model), **.NET (ASP.NET Core)** (comparable, but the team's JVM experience and course examples
favour Spring), **FastAPI/Django** (productive, less suited to showing OOP structure and design patterns), **Quarkus/
Micronaut** (lighter, but a smaller ecosystem for the security features needed).
*Trade-offs:* higher memory use and slower start than Node or Go (the container uses `-XX:MaxRAMPercentage=75`); a lot
of annotation "magic" that must be understood (proxies, transaction boundaries); major-version upgrades require care
(Boot 4 changed packages and starters).

### 4. Why this database?

**PostgreSQL 17.**

* Relational data with real relationships (users, incidents, notes, services, policies) and strong integrity features
  that the schema uses: foreign keys, check constraints that mirror enums, partial unique indexes (the default SLA
  policy), composite and partial indexes, `timestamptz`, and identity columns.
* **JSONB** for the audit log's event-specific `details`, without needing a second database.
* Reliable transactions, which the event-driven design depends on (audit rows commit with the action).
* Free, runs in Docker and in CI via Testcontainers, so tests run against the same engine as production, not an
  in-memory imitation.
* Aggregates (`avg`, `group by`, `count(distinct)`) let the reports run in the database.

*Alternatives:* **MySQL** (acceptable, but weaker check-constraint and partial-index support historically),
**MongoDB** (flexible documents, but the domain is relational and the lifecycle needs transactions and constraints),
**SQLite / H2** (zero setup, but not a production engine and would hide dialect issues), **Firebase / cloud databases**
(adds an external dependency and cost for no benefit at this scale).
*Trade-offs:* needs a running server (solved with Compose); schema evolution requires migrations (Flyway, which we
want anyway).

### 5. Why this architecture?

A **layered architecture** (the brief's Choice 1) with a server-rendered frontend, a REST API, a domain layer and a
repository layer, plus an **event-driven** core:

* Layers keep rules in the domain (`Incident`, states, strategies) and make controllers thin.
* **Modular monolith**: five modules in one deployable. Microservices were considered and rejected: for a team of five
  and one semester they add network failure modes, distributed transactions (audit must commit with the action) and
  deployment cost without benefit. The module boundaries (ids not entities, events, interfaces) are drawn so a module
  could be extracted later.
* **Stateless backend with JWT** keeps the API simple and horizontally scalable (except the three in-memory pieces
  noted in 3.7).
* **Event-driven notifications and audit** keep the incident code ignorant of its consumers.

### 6. Alternatives considered (summary)

| Decision | Chosen | Considered | Why not the others |
|---|---|---|---|
| Backend language | Kotlin | Java, TypeScript, Python | see question 1 |
| UI | Astro SSR | React SPA, Next.js, Thymeleaf | token exposure, client state, tier separation |
| Backend | Spring Boot | NestJS, ASP.NET, FastAPI | see question 3 |
| Database | PostgreSQL | MySQL, MongoDB, SQLite | see question 4 |
| Auth | JWT (HS256) in httpOnly cookie, plus `token_version` | server sessions, OAuth2 login with an IdP, opaque tokens | stateless API, no external IdP available; revocation solved with the version claim. An IdP (SSO) is the production upgrade path |
| Migrations | Flyway | Liquibase, Hibernate `ddl-auto` | plain SQL is readable and reviewable; `ddl-auto: validate` guards drift |
| Real-time | Server-Sent Events | WebSocket, polling | one-way push is all that is needed; SSE is plain HTTP and works through the proxy; polling every 30 s is the fallback |
| Search | JPA Specifications | Elasticsearch, hand-written SQL | ten filters over one table; no extra infrastructure |
| Charts | Chart.js | D3, server-rendered images | small, accessible fallback table |
| Email | `EmailGateway` adapter with logging default | direct `JavaMailSender` | testability, no mail server needed to demo |
| Frontend tests | Vitest | Jest | native ESM and TypeScript, same config as the Vite toolchain Astro uses |
| Package manager | pnpm | npm, yarn | fast, strict, lockfile with minimum-release-age protection |

### 7. Advantages and limitations of the stack

| Advantages | Limitations |
|---|---|
| Strong typing end to end (Kotlin, TypeScript) catches contract errors early | Two runtimes (JVM and Node) to run and monitor |
| Real PostgreSQL in tests (Testcontainers) gives high confidence | Tests need Docker; the backend suite is slower than pure unit tests |
| Patterns map naturally onto Spring features (beans, events, transactions) | Spring's proxy-based features (`@Transactional`, `@Async`) are easy to misuse (self-invocation); documented in code comments |
| Pages work without JavaScript; httpOnly cookie hides the token | Full page loads rather than a client-side app; live updates only for notifications |
| One command start-up with Docker Compose | Single backend instance assumed (SSE hub, throttle, round-robin pointer are in memory) |
| Flyway migrations are versioned, reviewable SQL | Never edit a merged migration; fixes need new migrations |
| HS256 JWT is simple | One shared secret signs and verifies; an asymmetric key (RS256) would suit multi-service setups |

### 8. How does the technology support object-oriented design?

| Concept | Technology feature used |
|---|---|
| Encapsulation | Kotlin `private set`, `private` properties, `final var`, `val`; JPA field access with controlled mutators |
| Inheritance | Kotlin `abstract class`/`open`; JPA `@Inheritance(SINGLE_TABLE)` and `@DiscriminatorColumn` for `User` |
| Polymorphism | Kotlin `sealed class` and `interface`; Spring injecting `List<Strategy>`; JPA polymorphic loading |
| Abstraction / interfaces | Kotlin `interface`, `fun interface`; Spring Data repository interfaces generated at run time |
| Composition | Constructor injection of collaborators |
| Exceptions | One `ItsmException` hierarchy mapped to HTTP by `@RestControllerAdvice` |
| Patterns | Spring events (Observer), `@Component` lists (Strategy, Chain), `@Configuration` + `@ConditionalOnProperty` (Adapter selection) |
| Value objects | Kotlin `data class` |

### 9. How would the system evolve with many more users?

1. **Stateless horizontal scale.** Run several backend instances behind a load balancer; the API is already stateless.
2. **Move in-memory state out.** Login throttle and round-robin pointer to PostgreSQL or Redis; the SSE hub gets a
   pub/sub bridge (Redis, PostgreSQL `LISTEN/NOTIFY`) so any instance can push to any user.
3. **SLA monitor on many nodes.** Add row locking or a leader election (for example `SELECT ... FOR UPDATE SKIP LOCKED`)
   and the unique constraint on `sla_events` already protects idempotency.
4. **Database.** Read replicas for reports and search; the indexes already cover the main filters; partition
   `audit_log` by time; add full-text search (`tsvector`) for the `q` filter or an external search engine.
5. **Reports.** Pre-aggregate (materialised views) when incident counts become large.
6. **Messaging.** Replace in-process Spring events with a broker (Kafka, RabbitMQ) for notifications and audit if they
   must scale or survive restarts; the sealed `IncidentEvent` contract is already the message schema.
7. **Email.** The bounded executor becomes a queue consumer; the `EmailGateway` adapter points to a transactional email service.
8. **Security.** Single sign-on through an identity provider (OIDC); asymmetric JWT signing; shared rate limiting.
9. **Observability.** Actuator metrics to Prometheus, tracing, centralised logs.
10. **Extraction.** The modules can become services (notification service first) because they already communicate by events
    and ids.

## 6.3 Libraries and services used (complete list)

| Where | Library | Purpose |
|---|---|---|
| backend | `spring-boot-starter-webmvc` | REST controllers, Server-Sent Events (`SseEmitter`) |
| backend | `spring-boot-starter-data-jpa` | repositories, Hibernate, specifications |
| backend | `spring-boot-starter-security`, `...-oauth2-resource-server` | filter chain, JWT validation (`NimbusJwtDecoder`, `NimbusJwtEncoder`), method security |
| backend | `spring-boot-starter-validation` | Bean Validation on DTOs |
| backend | `spring-boot-starter-flyway`, `flyway-database-postgresql` | migrations |
| backend | `spring-boot-starter-mail` | `JavaMailSender` behind `SmtpEmailGateway` |
| backend | `spring-boot-starter-actuator` | health and info endpoints |
| backend | `springdoc-openapi-starter-webmvc-ui` | OpenAPI and Swagger UI |
| backend | `jackson-module-kotlin`, `kotlin-reflect` | JSON and Kotlin support |
| backend | `postgresql` (runtime) | JDBC driver |
| backend (test) | `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter` | real PostgreSQL in tests |
| backend (test) | `spring-boot-starter-*-test`, `kotlin-test-junit5`, `junit-platform-launcher`, security test | MockMvc, `@DataJpaTest`, JWT test support |
| frontend | `astro`, `@astrojs/node` | SSR framework and Node adapter |
| frontend | `chart.js` | charts on the reports page |
| frontend (dev) | `typescript`, `@astrojs/check`, `vitest`, `@types/node` | typing, checks, unit tests |
| infrastructure | Docker, Docker Compose, GitHub Actions | build, run, CI |
| optional | SMTP (Mailpit for demos) | real email |

No third-party SaaS API is required to run or demo the system.
