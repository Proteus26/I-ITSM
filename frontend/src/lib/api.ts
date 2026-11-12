/**
 * Base URL of the Spring Boot backend, as seen from the Astro *server*.
 * - local dev:   http://localhost:8080
 * - docker:      http://backend:8080 (set via API_URL in docker-compose.yml)
 *
 * Browser code should call the same-origin `/api/...` path instead; it is
 * forwarded to the backend by `src/pages/api/[...path].ts`.
 */
export const API_URL = process.env.API_URL ?? 'http://localhost:8080';
