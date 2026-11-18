# 12. Deployment

I-ITSM is delivered as three containers started by one Docker Compose file. The architecture view of the same material is
in [section 3.6](03-architecture.md); this chapter is the practical guide.

## 12.1 Components

| Service | Image / build | Port | Purpose |
|---|---|---|---|
| `db` | `postgres:17-alpine`, volume `db-data` | 5432 | PostgreSQL, health check `pg_isready` |
| `backend` | `backend/Dockerfile` (Gradle build on JDK 21, runs on a JRE 21 image as a non-root user) | 8080 | Spring Boot REST API, Flyway migrations on start, health check `/actuator/health` |
| `frontend` | `frontend/Dockerfile` (Astro, Node standalone server) | 4321 | pages and the `/api/**` proxy to the backend (`API_URL=http://backend:8080`) |

Start order is enforced with `depends_on` and health checks: database, then backend, then frontend.

## 12.2 Run it

```sh
cp .env.example .env              # optional, the defaults work
DEMO_DATA=true docker compose up --build
```

Open http://localhost:4321 (application) and http://localhost:8080/swagger-ui.html (API documentation). Stop with
`docker compose down`; `-v` also deletes the database volume.

## 12.3 Configuration

Everything is configured by environment variables (see `.env.example` and the root `README.md`).

| Variable | Default | Meaning |
|---|---|---|
| `JWT_SECRET` | development value in compose | HS256 secret, at least 32 bytes; the backend refuses to start without it. Change it outside development |
| `JWT_TTL` | `8h` | token lifetime |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | `admin@itsm.local`, `ChangeMe123!` | first administrator, created only if none exists |
| `DEMO_DATA` | `false` | seed demo users and incidents (never under the `prod` profile) |
| `ITSM_MAIL_GATEWAY` | `logging` | `logging` writes emails to the log, `smtp` sends through `MAIL_HOST` and `MAIL_PORT` |
| `ASSIGNMENT_STRATEGY` | `skill-least-loaded` | or `round-robin` |
| `SLA_MONITOR_INTERVAL`, `SLA_MONITOR_ENABLED` | `60000`, `true` | SLA check period in milliseconds, and an on/off switch |
| `COOKIE_SECURE` | `false` | set `true` behind HTTPS so the session cookie is `Secure` |
| `DB_*`, `BACKEND_PORT`, `FRONTEND_PORT`, `CORS_ALLOWED_ORIGINS` | see `.env.example` | database and ports |

## 12.4 Continuous integration

`.github/workflows/ci.yml` runs on every push to `main` and every pull request: the backend job runs `./gradlew build`
(JDK 21, Testcontainers PostgreSQL on the runner); the frontend job runs `pnpm install --frozen-lockfile`, `pnpm check`,
`pnpm test` and `pnpm build` (Node 24).

## 12.5 Production notes

* Serve the frontend behind HTTPS and set `COOKIE_SECURE=true`; set a strong `JWT_SECRET` and administrator password.
* Back up the `db-data` volume. Migrations run automatically; Hibernate only validates the schema.
* Run a single backend instance: the SLA monitor, login throttle, round-robin pointer and SSE emitters are per instance
  (section 3.7 lists what would change for several nodes).
* Health: `GET /actuator/health`; logs go to standard output.
