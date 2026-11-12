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
│   │   ├── auth/            JWT login/register, CurrentUser, bootstrap admin
│   │   ├── user/            User model (Employee/Technician/Administrator), admin user API
│   │   ├── config/          Security, CORS, other Spring config
│   │   ├── common/exception Base exception hierarchy (ItsmException)
│   │   └── web/             REST controllers + global exception handler
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/    Flyway migrations (V<yyyymmdd_hhmm>__*.sql)
│   └── Dockerfile
├── frontend/                Astro app
│   ├── src/middleware.ts    Session lookup (Astro.locals.user) and route protection
│   ├── src/pages/           Routes; src/pages/api/[...path].ts proxies /api/** to the backend
│   ├── src/layouts/
│   ├── src/components/      Nav, FormField, CheckboxField, Alert, RoleBadge, StatusBadge, ErrorPage
│   ├── src/lib/             api.ts (API_URL), backend.ts (typed client, timeouts, response checks), types.ts,
│   │                        errors.ts (ApiError, problem+json), session.ts (cookie), access.ts (route rules),
│   │                        gate.ts (middleware body), redirect.ts, csrf.ts, proxy.ts (/api proxy path, header and
│   │                        body rules), loadError.ts (handleLoadError), forms.ts (parsing, labels, formErrorState),
│   │                        userPatch.ts (admin edit form -> PATCH body)
│   ├── test/                Vitest unit tests for the pure logic in src/lib
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
(`backendFor(Astro.cookies)` from `src/lib/backend.ts`), and client-side code calls same-origin `/api/...`,
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

An admin account is created on first start: `admin@itsm.local` / `ChangeMe123!`
(change these with `ADMIN_EMAIL` / `ADMIN_PASSWORD` in `.env`).

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
export JWT_SECRET=$(openssl rand -base64 48)    # required: the backend refuses to start without it
export ADMIN_EMAIL=admin@itsm.local ADMIN_PASSWORD='ChangeMe123!'   # optional: creates the first admin
./gradlew bootRun
```

A new `JWT_SECRET` logs everyone out, so keep one value per terminal session (or put the
exports in a local, uncommitted script). It connects to `jdbc:postgresql://localhost:5432/itsm` by default. Override this with
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
cd frontend && pnpm test          # Vitest unit tests
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
| `JWT_SECRET`           | backend  | none for `bootRun` (required, ≥ 32 bytes); a dev value in docker compose |
| `JWT_TTL`              | backend  | `8h`                                     |
| `LOGIN_MAX_ATTEMPTS` / `LOGIN_WINDOW` | backend | `5` / `15m` (failed logins per email before 429) |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | backend | none for `bootRun`; `admin@itsm.local` / `ChangeMe123!` in docker compose |
| `API_URL`              | frontend | `http://localhost:8080`                  |
| `BACKEND_TIMEOUT_MS`   | frontend | `5000`; how long a page waits for the backend before treating it as unreachable |
| `COOKIE_SECURE`        | frontend | `false`; set `true` when served over HTTPS (adds `Secure` to the session cookie) |
| `DB_PORT`, `BACKEND_PORT`, `FRONTEND_PORT` | docker compose | `5432`, `8080`, `4321` |

## Authentication

Stateless JWT (HS256). Log in to get a token, then send it as `Authorization: Bearer <token>`.
In Swagger UI, click **Authorize** and paste the token.

| Endpoint | Access | Notes |
|---|---|---|
| `POST /api/auth/register` | public | Always creates an **Employee** |
| `POST /api/auth/login` | public | `email` is limited to 255 characters (400 otherwise). Returns `{ token, tokenType, expiresAt, user }`; 429 with `Retry-After` after too many failures (see below) |
| `GET /api/auth/me` | logged in | Current user's profile (includes `lastLoginAt` and `previousLoginAt`; both null before the first login, `previousLoginAt` null until the second) |
| `PATCH /api/auth/me` | logged in | `{ fullName?, department? }`, null or omitted = unchanged; `department` is for employees only (400 otherwise). Returns the user. Email and role cannot be changed here; other fields in the body are ignored, not rejected |
| `POST /api/auth/change-password` | logged in | `{ currentPassword, newPassword }`. Returns **200** with a new login response `{ token, tokenType, expiresAt, user }`; all older tokens stop working, so the client must switch to the new token |
| `POST /api/auth/logout-all` | logged in | Logs out everywhere: revokes every token of the caller, including this one (204) |
| `GET /api/admin/users?role=&active=&q=&page=&size=` | admin | Paged, filtered user list |
| `GET /api/admin/users/{id}` | admin | One user |
| `POST /api/admin/users` | admin | Create an Employee, Technician or Admin |
| `PATCH /api/admin/users/{id}` | admin | Partial update, activate or deactivate |
| `POST /api/admin/users/{id}/reset-password` | admin | `{ newPassword }` |

New passwords (register, change, admin create and reset, and `ADMIN_PASSWORD`) must be 8 to 72 characters
and at most 72 bytes in UTF-8 (a BCrypt limit; non-ASCII characters take more than one byte).

```sh
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@itsm.local","password":"ChangeMe123!"}' | jq -r .token)
curl -s localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
```

**In your own module**, inject `CurrentUser` to find out who is calling:

```kotlin
@Service
class IncidentService(private val currentUser: CurrentUser) {
    fun mine(): List<IncidentResponse> {
        val me = currentUser.get()          // AuthenticatedUser(id, email, role); 401 if not logged in
        if (me.role != Role.EMPLOYEE) throw UnauthorizedOperationException("Only employees have reported incidents")
        // ...
    }
}
```

- URL rules live in `SecurityConfig`: `/api/admin/**` is admin-only; everything else needs a token.
- For per-endpoint role rules, use `@PreAuthorize("hasRole('TECHNICIAN')")` (roles: `EMPLOYEE`, `TECHNICIAN`, `ADMIN`).
- Ownership checks (for example "only the reporter can view this incident") belong in your service code:
  throw `UnauthorizedOperationException` (403).
- Errors are `application/problem+json`: 401 when the token is missing, invalid or expired; 403 when the role is wrong.
- Every authenticated request loads the user from the database, so deactivating or deleting an account
  takes effect immediately (its existing tokens get 401), and `CurrentUser` always returns the current
  email and role, not the values stored in the token.
- Login publishes `LoginFailed` and `UserLoggedIn` events (package `com.itsm.auth`); consume them with a
  plain `@EventListener` (register publishes no event; login runs outside a transaction, so `@TransactionalEventListener` never fires).
- **Session revocation**: every user has a `token_version`, and each token carries it in the `ver` claim (a token
  without `ver` counts as version 0; a `ver` that is not a non-negative integer is rejected with 401). A token whose
  version differs from the database gets 401. The version is bumped by a password change, an admin password reset,
  deactivation (so reactivating does not bring old tokens back) and `POST /api/auth/logout-all`. The bump is one atomic
  SQL increment (`SessionRevoker.revoke(userId)`, which returns the new version), so concurrent revocations cannot lose
  an update and the entity never writes the column. Changing your own password keeps your current session alive by
  returning a token that carries the version produced by that same increment. Users deactivated before the migration
  start at version 1, so their old tokens stay dead after reactivation.
- **Login throttling**: every login attempt for an email is counted up front (atomically, so parallel requests cannot
  all slip through). After 5 attempts that did not end in a success (`LOGIN_MAX_ATTEMPTS`), further logins for that email get
  429 (`application/problem+json`) with a `Retry-After` header in seconds, even with the right password, until
  `LOGIN_WINDOW` (default 15 minutes) has passed since the limit was reached. If the limit is not reached, counting restarts
  after one window. The check runs before password hashing, and unknown emails are counted the same way, so it does not
  reveal whether an account exists. A successful login clears the counter. Attempts on a disabled account (403) count too and are never cleared, so reactivating the account within
  the window still gives 429 until the lock expires. `AuthService` calls `LoginAttemptTracker`
  directly; the `LoginFailed` and `UserLoggedIn` events remain for other observers.
  Limitations: the counters are in memory and per backend instance (not shared, reset on restart), and because the lock
  is per email, anyone can lock a known email out of logging in for one window by failing on purpose. Memory is bounded
  at 10,000 tracked emails and the throttle never fails open: when the map is full, expired entries are swept (at most
  once per second) and then the least recently used entry is evicted, so a new email is always tracked; an attacker
  sending more than 10,000 distinct emails within a window can push older counters out. Tokens that were already issued keep working.
- **Last login**: `UserResponse.lastLoginAt` and `previousLoginAt` are set after each successful login (a `UserLoggedIn`
  listener with its own transaction; the old last login becomes the previous one). Both are null until the first login
  and `previousLoginAt` is null until the second. The login response shows the values as they are after that update.
  A failure to record them is logged and never fails the login.

### Frontend authentication

- **Cookie**: after a successful login the Astro server stores the JWT in an `itsm_session` cookie:
  `HttpOnly`, `SameSite=Lax`, `Path=/`, expiring when the token does, and `Secure` when `COOKIE_SECURE=true`.
  The token is never readable by browser JS and never rendered into HTML (`src/lib/session.ts`).
- **Middleware** (`src/middleware.ts`): when the cookie is present, calls `GET /api/auth/me` once per page request
  and exposes the result as `Astro.locals.user` (a `UserResponse`, or `null`; typed in `src/env.d.ts`).
  A 401 deletes the cookie. If the backend is unreachable the visitor is treated as logged out and
  `Astro.locals.backendDown` is `true` (protected pages then show a 503 page instead of a redirect to login).
  `/api/**` requests skip this lookup; the proxy forwards the token and the backend enforces access.
- **Session expiry**: when `/api/auth/me` answers 401 (token expired or revoked), the middleware clears the cookie.
  A protected page redirects to `/login?next=<path>&expired=1` and the login page shows a fixed "Your session has ended"
  notice; a public page just renders as logged out. `handleLoadError` does the same for a 401 from any later backend call.
  The login page only shows fixed notices chosen by flag (`expired=1`, `loggedOut=all`, `registered=1`), never query text.
  The account page does the same with `/account?notice=changed|profile` (shown after a redirect only, never after a failed post).
- **Login throttling**: a 429 from `/api/auth/login` is shown as "Too many failed attempts. Try again in N minutes."
  (from `Retry-After`, captured as `ApiError.retryAfterSeconds`; formatting in `src/lib/loginMessages.ts`: minutes, or hours beyond 120 minutes).
- **Account page** (`/account`): three forms posting a hidden `intent` to the same page.
  - *Edit profile* sends `PATCH /api/auth/me` (full name; department only for employees, blank clears it).
  - *Change password* revokes the old token, so the page calls `startSession` with the new token from the response:
    the cookie is replaced and the user stays logged in.
  - *Log out of all devices* calls `POST /api/auth/logout-all`, clears the cookie and redirects to
    `/login?loggedOut=all`. Plain "Log out" is per-device (it only clears this browser's cookie); log-out-all revokes every session.
  - The form logic lives in `src/lib/accountForms.ts` and the `intent` dispatch in `src/lib/intent.ts` (also used by the
    admin user page); errors appear on the card of the form that failed, other errors in a page-level alert.
    Confirmation prompts use `data-confirm="..."` on a form, handled by `public/scripts/confirm.js` (no inline handlers;
    without JavaScript the form simply submits; the button's own `data-confirm` wins over the form's, and repeated submits are
    ignored for 3 seconds. A programmatic `form.submit()` fires no submit event and bypasses both).
  - "Previous login" (`previousLoginAt`, or "This is your first login") is shown on the account page. Admins see "Last login"
    (`lastLoginAt`, "Never" if none) in the users list (date, full time on hover) and the user detail page.
    An admin password reset or deactivation also logs that user out.
- **Protecting a new page**: add a rule to `ROUTE_RULES` in `src/lib/access.ts`. Paths that match no rule are public.

  ```ts
  { prefix: '/incidents' },                                // any logged-in user
  { prefix: '/technician', roles: ['TECHNICIAN', 'ADMIN'] } // only these roles
  ```

  Not logged in: redirect to `/login?next=<path>` (`next` is checked by `safeNext`, so only local paths are followed).
  Wrong role: the 403 page. Also add a link in `src/components/Nav.astro` if the page belongs in the menu.
- **Calling the backend from a page**: use the typed client; it adds the user's token and throws `ApiError`
  (`status`, `title`, `detail`, `fieldErrors`) for any non-2xx response.

  ```astro
  ---
  import { backendFor } from '../lib/backend';
  import { ApiError } from '../lib/errors';
  try {
    const users = await backendFor(Astro.cookies).listUsers({ role: 'TECHNICIAN' });
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
    // e.status, e.detail, e.fieldErrors
  }
  ---
  ```

  Forms are plain HTML forms that `POST` to their own page and are handled in the frontmatter
  (`Astro.request.method === 'POST'`), then redirect (post/redirect/get). They work without JavaScript.
  Browser-side code calls `/api/...`; the proxy adds `Authorization: Bearer` from the cookie.
- **Adding a page that calls the backend**: four small steps.
  1. Protect the route: add a line to `ROUTE_RULES` in `src/lib/access.ts` (for example `{ prefix: '/incidents' }`).
  2. Add a method to `BackendClient` in `src/lib/backend.ts` (and the request/response types in `src/lib/types.ts`).
  3. Write the page in `src/pages/`: call the client through `backendFor(Astro.cookies)`, pass failures to `handleLoadError`
     and, for forms, turn them into messages with `formErrorState`.
  4. Add a link in `src/components/Nav.astro` and a test for any new pure logic in `frontend/test/`.

  ```astro
  ---
  // src/pages/incidents/index.astro
  import Layout from '../../layouts/Layout.astro';
  import Alert from '../../components/Alert.astro';
  import { backendFor } from '../../lib/backend';
  import { handleLoadError } from '../../lib/loadError';

  let incidents = null;
  let error: string | null = null;
  try {
    incidents = await backendFor(Astro.cookies).listIncidents(); // the new BackendClient method
  } catch (e) {
    const r = await handleLoadError(e, Astro);
    if (r instanceof Response) return r; // 404 / 403 / 503 page, or redirect to login
    error = r.userMessage;
  }
  ---
  <Layout title="Incidents · I-ITSM">
    <h1>Incidents</h1>
    {error && <Alert kind="error">{error}</Alert>}
    {incidents?.content.map((i) => <p>{i.title}</p>)}
  </Layout>
  ```

  For a form page, catch the error with `handleLoadError(e, Astro, { inlineServerErrors: true })`, then
  `const { message, fieldErrors } = formErrorState(r)` and pass `fieldErrors.<name>` to each `FormField`.
- **Backend failures**: every backend call times out after `BACKEND_TIMEOUT_MS` (default 5000) and is then treated as unreachable;
  an answer that is not JSON (or a `/me` or login answer that is not a user) becomes a 502 `ApiError`. The `/api` proxy gives the
  backend 15 seconds (504 on a timeout), forwards only `Accept`, `Accept-Language`, `Content-Type`, `If-None-Match`,
  `If-Modified-Since` and the `Authorization` header, and rejects request bodies over 1 MiB with a 413.
- **CSRF**: the cookie is `SameSite=Lax`; Astro's `security.checkOrigin` (kept on in `astro.config.mjs`) rejects cross-origin
  form posts to pages; and the `/api` proxy rejects non-GET/HEAD requests whose `Origin` header differs from its own
  with a 403. The proxy never forwards the browser's `Cookie` header to the backend and never passes back a backend `Set-Cookie`.
  The proxy also rejects unsafe requests with `Sec-Fetch-Site: cross-site` or `same-site`, or (without `Origin`) a foreign `Referer`;
  requests with none of these headers (curl) are allowed. Only a short allow-list of backend response headers is passed back.
  When scripting with curl, send an `Origin` header that matches the site (for example `Origin: http://localhost:4321`) on form posts.
- **Proxy path rules**: `/api/...` paths containing `\`, `%`, `?`, `#`, control characters or an empty, `.` or `..` segment get a 400
  (`src/lib/proxy.ts`), so a request can never leave the backend's `/api/` tree.
- **Behind a TLS-terminating reverse proxy**: the proxy must forward the original `Host` and `X-Forwarded-Proto` headers so Astro sees
  the public origin (CSRF origin checks compare against it), and you may need to set Astro's `security.allowedDomains` in
  `astro.config.mjs` to your public host name(s). Also set `COOKIE_SECURE=true` when served over HTTPS.
- **Error handling in pages**: use `handleLoadError(e, Astro)` (`src/lib/loadError.ts`) for failed backend calls
  (404, 401 to login, 403, 5xx to the 503 page) and `formErrorState(e)` (`src/lib/forms.ts`) for form errors. Server-side error details
  are never shown to users (`ApiError.userMessage`).
- **Admin user management** (admins only): `/admin/users` (search, filter by role and status, paging), `/admin/users/new`, and
  `/admin/users/<id>` (edit details, activate or deactivate, reset password).
- **`?notice=` parameters**: after a successful action the pages redirect with a fixed code that is mapped to a message and never echoed:
  `/admin/users/<id>?notice=created|updated|reset`, `/account?changed=1`, and `/login?registered=1` (shown when automatic login after
  registering fails).

## Conventions

- **Schema changes** go in a new Flyway file `backend/src/main/resources/db/migration/V<yyyymmdd_hhmm>__<description>.sql`
  (timestamps, so five people don't create two `V3`s). Never edit a migration after it has been merged. Hibernate runs with `ddl-auto: validate`,
  so entities must match the migrations.
- **Errors**: domain exceptions extend `ItsmException` (with an HTTP status).
  `GlobalExceptionHandler` turns them into RFC 9457 `application/problem+json` responses.
- **Security**: see [Authentication](#authentication).

## Troubleshooting

- **Backend fails with "JWT secret is missing or too short"**: set `JWT_SECRET` to at least
  32 bytes (see step 3 above).

- **Gradle wrapper download times out**: `services.gradle.org` can be slow. The wrapper
  timeout is set to 120 s with 3 retries in `backend/gradle/wrapper/gradle-wrapper.properties`.
- **pnpm wants to delete and reinstall `node_modules`**: an older pnpm (< 12) is first on your
  `PATH`. Check with `which -a pnpm` and remove or reorder the old one.
- **`ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION`**: pnpm 12 refuses packages published less
  than a day ago. Wait a day, or pin the dependency to an older version.
- **Docker builds can't reach npm/Maven**: check that containers have outbound internet,
  e.g. `docker run --rm alpine wget -qO- https://registry.npmjs.org > /dev/null`.
