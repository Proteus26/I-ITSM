import type { APIRoute } from 'astro';
import { API_URL } from '../../lib/api';
import { isCrossOriginWrite } from '../../lib/csrf';
import { problemResponse } from '../../lib/errors';
import {
  buildBackendRequestHeaders,
  DEFAULT_PROXY_TIMEOUT_MS,
  declaredBodyTooLarge,
  filterResponseHeaders,
  proxyTarget,
  readBodyCapped,
} from '../../lib/proxy';
import { endSession, readToken } from '../../lib/session';

const TOO_LARGE = 'Request body too large.';

/** `PROXY_TIMEOUT_MS` (default 15000) as a positive integer. */
function proxyTimeoutMs(): number {
  const n = Number(process.env.PROXY_TIMEOUT_MS);
  return Number.isInteger(n) && n > 0 ? n : DEFAULT_PROXY_TIMEOUT_MS;
}

/**
 * Same-origin proxy: browser -> /api/** (Astro) -> backend /api/**.
 * The browser never sees the JWT: it is in an httpOnly cookie, and this route turns it into an
 * `Authorization: Bearer` header. The browser's Cookie header is never forwarded to the backend,
 * and only allow-listed backend response headers are passed back (never `Set-Cookie`).
 * Request headers are allow-listed too, bodies are capped at 1 MiB and the backend call times out.
 */
export const ALL: APIRoute = async ({ request, params, cookies }) => {
  const incoming = new URL(request.url);

  if (isCrossOriginWrite(request.method, request.headers, incoming.origin)) {
    return problemResponse(403, 'Forbidden', 'Cross-origin request rejected.');
  }

  const target = proxyTarget(params.path, incoming.search);
  if (!target) return problemResponse(400, 'BadRequest', 'Invalid API path.');

  const token = readToken(cookies);
  const headers = buildBackendRequestHeaders(request.headers, token);

  let body: Uint8Array<ArrayBuffer> | undefined;
  if (!['GET', 'HEAD'].includes(request.method)) {
    if (declaredBodyTooLarge(request.headers.get('content-length'))) return problemResponse(413, 'PayloadTooLarge', TOO_LARGE);
    const buffered = await readBodyCapped(request.body);
    if (buffered === null) return problemResponse(413, 'PayloadTooLarge', TOO_LARGE);
    body = buffered;
  }

  let res: Response;
  try {
    res = await fetch(target, {
      method: request.method,
      headers,
      body,
      redirect: 'manual',
      signal: AbortSignal.timeout(proxyTimeoutMs()),
    });
  } catch (e) {
    const timedOut = e instanceof Error && (e.name === 'TimeoutError' || e.name === 'AbortError');
    // Log the real cause on the server only (never the token); the browser gets a generic message.
    console.error(`[api proxy] backend ${timedOut ? 'timed out' : 'unreachable'} for ${request.method} ${target.pathname}:`, e instanceof Error ? e.message : e);
    if (timedOut) return problemResponse(504, 'GatewayTimeout', 'The server took too long to respond. Please try again later.');
    return problemResponse(502, 'BadGateway', 'The server is unreachable. Please try again later.');
  }

  // The backend says the token is no longer valid: do not keep sending a stale cookie.
  if (res.status === 401 && token) endSession(cookies);

  return new Response(res.body, {
    status: res.status,
    statusText: res.statusText,
    headers: filterResponseHeaders(res.headers, new URL(API_URL).origin),
  });
};
