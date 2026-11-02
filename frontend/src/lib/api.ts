/**
 * Base URL of the Spring Boot backend, as seen from the Astro *server*.
 * - local dev:   http://localhost:8080
 * - docker:      http://backend:8080 (set via API_URL in docker-compose.yml)
 *
 * Browser code should call the same-origin `/api/...` path instead; it is
 * forwarded to the backend by `src/pages/api/[...path].ts`.
 */
export const API_URL = process.env.API_URL ?? 'http://localhost:8080';

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(new URL(path, API_URL), {
    ...init,
    headers: { Accept: 'application/json', ...init?.headers },
  });
  if (!res.ok) {
    throw new Error(`API ${res.status} ${res.statusText} for ${path}`);
  }
  return res.json() as Promise<T>;
}
