# I-ITSM — Intelligent IT Service Management & Incident Resolution System

CS F213 / MAC F212 OOP course project (Semester I, 2026–27). The brief is in
[`OOP_S1_2026-2027_Project.md`](OOP_S1_2026-2027_Project.md).

## Stack

| Layer     | Technology                                                       |
| --------- | ---------------------------------------------------------------- |
| Backend   | Kotlin 2.2, Spring Boot 4 (Web MVC, Data JPA, Security, Validation, Actuator), JDK 21 |
| Database  | PostgreSQL 17, schema migrations with Flyway                     |
| Frontend  | Astro 7 (server-rendered, `@astrojs/node` adapter), TypeScript   |
| API docs  | springdoc-openapi (Swagger UI)                                   |
| Testing   | JUnit 5, Spring Boot Test, Testcontainers                        |
| Tooling   | Docker Compose, GitHub Actions CI                                |

## Repository layout

```
.
├── backend/                 Spring Boot (Kotlin) REST API
│   ├── src/main/kotlin/com/itsm/
│   │   ├── config/          Security, CORS, other Spring config
│   │   ├── common/exception Base exception hierarchy (ItsmException)
│   │   └── web/             REST controllers + global exception handler
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/    Flyway migrations (V1__*.sql, V2__*.sql, ...)
│   └── Dockerfile
├── frontend/                Astro app
│   ├── src/pages/           Routes; src/pages/api/[...path].ts proxies /api/** to the backend
│   ├── src/layouts/
│   ├── src/lib/api.ts       Server-side API client
│   └── Dockerfile
├── docs/                    Report, UML, ER diagrams, test cases
├── docker-compose.yml       db + backend + frontend
└── .github/workflows/ci.yml
```

## How requests flow

```
Browser ──► Astro (:4321) ──► /api/** proxy ──► Spring Boot (:8080) ──► PostgreSQL (:5432)
```

The browser only talks to the Astro server. Astro pages call the backend server-side
(`apiFetch` in `src/lib/api.ts`), and client-side code calls same-origin `/api/...`,
which the catch-all route forwards to the backend. One origin means no CORS issues
and auth cookies just work.

---

## Option 1: Run everything with Docker

Only Docker is needed.

```sh
cp .env.example .env        # optional; the defaults work
docker compose up --build
```

| URL                                      | What                     |
| ---------------------------------------- | ------------------------ |
| http://localhost:4321                    | Frontend                 |
| http://localhost:8080/api/ping           | Backend ping             |
| http://localhost:8080/swagger-ui.html    | API docs (Swagger UI)    |
| http://localhost:8080/actuator/health    | Backend health           |
| `localhost:5432` (user/pass/db: `itsm`)  | PostgreSQL               |

Stop with `docker compose down`. Add `-v` to also delete the database volume.

## Option 2: Local development (hot reload)

### 1. Prerequisites

- JDK 21. Gradle doesn't need to be installed; use `./gradlew`.
- Node.js 24 and pnpm 12. The version is pinned in `frontend/package.json` (`packageManager`),
  and `corepack enable` picks it up automatically.
- Docker, for PostgreSQL and the Testcontainers-based tests.

Check with `java -version`, `node -v` and `pnpm -v`.

### 2. Start the database

```sh
docker compose up -d db
```

### 3. Start the backend (terminal 1)

```sh
cd backend
./gradlew bootRun
```

It connects to `jdbc:postgresql://localhost:5432/itsm` by default. Override this with
`DB_URL`, `DB_USER` and `DB_PASSWORD`. Flyway applies migrations on startup.

### 4. Start the frontend (terminal 2)

```sh
cd frontend
pnpm install
pnpm dev
```

Open http://localhost:4321. The home page shows whether the backend is reachable.
The frontend finds the backend through `API_URL` (default `http://localhost:8080`).

## Tests and checks

```sh
cd backend  && ./gradlew test     # needs Docker running (Testcontainers starts a throwaway PostgreSQL)
cd frontend && pnpm check         # Astro/TypeScript type checking
cd frontend && pnpm build
```

CI (`.github/workflows/ci.yml`) runs the same steps on every push and pull request.

## Configuration

| Variable               | Used by  | Default                                  |
| ---------------------- | -------- | ---------------------------------------- |
| `DB_URL`               | backend  | `jdbc:postgresql://localhost:5432/itsm`  |
| `DB_USER` / `DB_PASSWORD` | backend, db | `itsm` / `itsm`                     |
| `CORS_ALLOWED_ORIGINS` | backend  | `http://localhost:4321`                  |
| `PORT`                 | backend  | `8080`                                   |
| `API_URL`              | frontend | `http://localhost:8080`                  |
| `DB_PORT`, `BACKEND_PORT`, `FRONTEND_PORT` | docker compose | `5432`, `8080`, `4321` |

## Conventions

- **Schema changes** go in a new Flyway file `backend/src/main/resources/db/migration/V<n>__<description>.sql`.
  Never edit a migration after it has been merged. Hibernate runs with `ddl-auto: validate`,
  so entities must match the migrations.
- **Errors**: domain exceptions extend `ItsmException` (with an HTTP status).
  `GlobalExceptionHandler` turns them into RFC 9457 `application/problem+json` responses.
- **Security**: `SecurityConfig` currently allows all `/api/**` requests. Authentication
  (e.g. JWT) and role rules for Employee / Technician / Admin still need to be added.

## Troubleshooting

- **Gradle wrapper download times out**: `services.gradle.org` can be slow. The wrapper
  timeout is set to 120 s with 3 retries in `backend/gradle/wrapper/gradle-wrapper.properties`.
- **pnpm wants to delete and reinstall `node_modules`**: an older pnpm (< 12) is first on your
  `PATH`. Check with `which -a pnpm` and remove or reorder the old one.
- **`ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION`**: pnpm 12 refuses packages published less
  than a day ago. Wait a day, or pin the dependency to an older version.
- **Docker builds can't reach npm/Maven**: check that containers have outbound internet,
  e.g. `docker run --rm alpine wget -qO- https://registry.npmjs.org > /dev/null`.
