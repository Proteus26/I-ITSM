import type { APIRoute } from 'astro';
import { API_URL } from '../../lib/api';
import { isCrossOriginWrite } from '../../lib/csrf';
import { problemResponse } from '../../lib/errors';
import { filterResponseHeaders, proxyTarget } from '../../lib/proxy';
import { endSession, readToken } from '../../lib/session';

/** Headers that must not be copied from the browser request to the backend. */
const DROP_REQUEST_HEADERS = ['host', 'cookie', 'connection', 'content-length'];

/**
 * Same-origin proxy: browser -> /api/** (Astro) -> backend /api/**.
 * The browser never sees the JWT: it is in an httpOnly cookie, and this route turns it into an
 * `Authorization: Bearer` header. The browser's Cookie header is never forwarded to the backend,
 * and only allow-listed backend response headers are passed back (never `Set-Cookie`).
 */
export const ALL: APIRoute = async ({ request, params, cookies }) => {
  const incoming = new URL(request.url);

  if (isCrossOriginWrite(request.method, request.headers, incoming.origin)) {
    return problemResponse(403, 'Forbidden', 'Cross-origin request rejected.');
  }

  const target = proxyTarget(params.path, incoming.search);
  if (!target) return problemResponse(400, 'BadRequest', 'Invalid API path.');

  const headers = new Headers(request.headers);
  for (const h of DROP_REQUEST_HEADERS) headers.delete(h);
  const token = readToken(cookies);
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const hasBody = !['GET', 'HEAD'].includes(request.method);
  let res: Response;
  try {
    res = await fetch(target, {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: 'manual',
    });
  } catch (e) {
    // Log the real cause on the server only (never the token); the browser gets a generic message.
    console.error(`[api proxy] backend unreachable for ${request.method} ${target.pathname}:`, e instanceof Error ? e.message : e);
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
