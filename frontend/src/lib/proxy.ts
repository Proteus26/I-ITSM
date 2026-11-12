import { API_URL } from './api';

/** Anything in the wildcard path that could change which backend URL is reached. */
// eslint-disable-next-line no-control-regex
const FORBIDDEN_PATH_CHARS = /[\\%?#\u0000-\u001f\u007f]/;

/**
 * Builds the backend URL for a proxied request, or returns null when the path is not acceptable.
 * `path` is Astro's (already decoded) wildcard `params.path`, `search` is `url.search`.
 *
 * Rejected: backslashes, `%`, `?`, `#`, control characters, and any empty, `.` or `..` segment.
 * The result is also checked to stay on the backend origin and under `/api/`.
 */
export function proxyTarget(path: string | undefined, search: string, base: string = API_URL): URL | null {
  const p = path ?? '';
  if (FORBIDDEN_PATH_CHARS.test(p)) return null;
  if (p.split('/').some((s) => s === '' || s === '.' || s === '..')) return null;
  if (search !== '' && !search.startsWith('?')) return null;
  let target: URL;
  try {
    target = new URL(`/api/${p}${search}`, base);
  } catch {
    return null;
  }
  if (target.origin !== new URL(base).origin) return null;
  if (!target.pathname.startsWith('/api/')) return null;
  return target;
}

const ALLOWED_RESPONSE_HEADERS = [
  'content-type',
  'cache-control',
  'etag',
  'last-modified',
  'content-disposition',
  'www-authenticate',
  'retry-after',
  'vary',
];

const PLACEHOLDER = 'http://relative.invalid';
// eslint-disable-next-line no-control-regex
const UNSAFE_LOCATION = /[\\\u0000-\u001f\u007f]/;

/** A Location that is safe to hand to the browser: relative as-is, or backend-absolute rewritten to its path. */
function safeLocation(value: string, backendOrigin: string): string | null {
  if (UNSAFE_LOCATION.test(value)) return null;
  let u: URL;
  try {
    u = new URL(value, PLACEHOLDER);
  } catch {
    return null;
  }
  if (u.origin === PLACEHOLDER) return value;
  if (u.origin === backendOrigin) return `${u.pathname}${u.search}${u.hash}`;
  return null;
}

/** Copies only allow-listed headers from a backend response (so no Set-Cookie, Server, etc.). */
export function filterResponseHeaders(headers: Headers, backendOrigin: string = new URL(API_URL).origin): Headers {
  const out = new Headers();
  for (const name of ALLOWED_RESPONSE_HEADERS) {
    const v = headers.get(name);
    if (v !== null) out.set(name, v);
  }
  const location = headers.get('location');
  if (location !== null) {
    const safe = safeLocation(location, backendOrigin);
    if (safe !== null) out.set('location', safe);
  }
  return out;
}

const ALLOWED_REQUEST_HEADERS = ['accept', 'accept-language', 'content-type', 'if-none-match', 'if-modified-since'];

/**
 * Headers sent to the backend: only an allow-list of the browser's (never cookie, host, origin,
 * referer or sec-fetch-*), plus `Authorization: Bearer <token>` from the session cookie. Without a
 * session token the client's own Authorization header is kept.
 */
export function buildBackendRequestHeaders(incoming: Headers, token: string | undefined): Headers {
  const out = new Headers();
  for (const name of ALLOWED_REQUEST_HEADERS) {
    const v = incoming.get(name);
    if (v !== null) out.set(name, v);
  }
  if (token) {
    out.set('Authorization', `Bearer ${token}`);
  } else {
    const auth = incoming.get('authorization');
    if (auth !== null) out.set('Authorization', auth);
  }
  return out;
}

/** Largest request body the proxy buffers and forwards. */
export const MAX_BODY_BYTES = 1024 * 1024;

/** True when a declared Content-Length is above the cap (invalid values are left to the buffered check). */
export function declaredBodyTooLarge(contentLength: string | null, max: number = MAX_BODY_BYTES): boolean {
  if (contentLength === null || !/^\d+$/.test(contentLength.trim())) return false;
  return Number(contentLength.trim()) > max;
}

/** True when an already buffered body is above the cap. */
export function bufferedBodyTooLarge(body: ArrayBuffer, max: number = MAX_BODY_BYTES): boolean {
  return body.byteLength > max;
}

/** Buffers a request body, giving up (null) as soon as it grows past `max` bytes. */
export async function readBodyCapped(body: ReadableStream<Uint8Array> | null, max: number = MAX_BODY_BYTES): Promise<Uint8Array<ArrayBuffer> | null> {
  if (!body) return new Uint8Array(0);
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel().catch(() => undefined);
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    out.set(c, offset);
    offset += c.byteLength;
  }
  return out;
}

export const DEFAULT_PROXY_TIMEOUT_MS = 15000;
