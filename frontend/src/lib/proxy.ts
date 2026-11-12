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
