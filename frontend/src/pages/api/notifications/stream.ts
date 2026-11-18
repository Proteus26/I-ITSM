import type { APIRoute } from 'astro';
import { API_URL } from '../../../lib/api';
import { endSession, readToken } from '../../../lib/session';

/** The only backend URL this route ever calls; nothing from the request is added to it. */
const STREAM_URL = new URL('/api/notifications/stream', API_URL);

const NO_STORE = { 'Cache-Control': 'no-store' } as const;

/**
 * Live notifications (Server-Sent Events). The generic /api proxy has a 15 s timeout and a body limit, so a stream
 * cannot go through it; this dedicated route is more specific than `[...path].ts`, so Astro uses it first.
 * It reads the token from the session cookie like the proxy does, then pipes the backend's event stream to the
 * browser with no timeout. Only fixed headers are sent back (never the backend's), and when the browser
 * disconnects, `request.signal` aborts the upstream connection too. Read-only (GET), so no CSRF check is needed;
 * every other method gets 405.
 */
export const GET: APIRoute = async ({ cookies, request }) => {
  const token = readToken(cookies);
  if (!token) return new Response(null, { status: 401, headers: NO_STORE });

  let upstream: Response;
  try {
    upstream = await fetch(STREAM_URL, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
      redirect: 'manual',
      signal: request.signal,
    });
  } catch (e) {
    // A browser that went away aborts the fetch; anything else is logged without the token.
    if (!request.signal.aborted) console.error('[notification stream] backend unreachable:', e instanceof Error ? e.message : e);
    return new Response(null, { status: 502, headers: NO_STORE });
  }

  if (upstream.status === 401) {
    // The token is no longer valid: do not keep sending a stale cookie (same as the proxy).
    endSession(cookies);
    await upstream.body?.cancel().catch(() => undefined);
    return new Response(null, { status: 401, headers: NO_STORE });
  }
  const isStream = upstream.headers.get('content-type')?.toLowerCase().startsWith('text/event-stream') ?? false;
  if (!upstream.ok || !upstream.body || !isStream) {
    await upstream.body?.cancel().catch(() => undefined);
    return new Response(null, { status: upstream.status === 403 ? 403 : 502, headers: NO_STORE });
  }

  return new Response(upstream.body, {
    headers: {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      'X-Accel-Buffering': 'no', // tell nginx-style proxies not to buffer the stream
    },
  });
};

export const ALL: APIRoute = () =>
  new Response(null, { status: 405, headers: { Allow: 'GET', ...NO_STORE } });
