import type { APIRoute } from 'astro';
import { API_URL } from '../../../lib/api';
import { readToken } from '../../../lib/session';

/**
 * Live notifications (Server-Sent Events). The generic /api proxy has a 15 s timeout and a body limit, so a stream
 * cannot go through it; this dedicated route is more specific than `[...path].ts`, so Astro uses it first.
 * It adds the token from the session cookie, then pipes the backend's event stream straight to the browser with no
 * timeout. When the browser disconnects, `request.signal` aborts the upstream connection too.
 */
export const GET: APIRoute = async ({ cookies, request }) => {
  const token = readToken(cookies);
  if (!token) return new Response(null, { status: 401 });

  let upstream: Response;
  try {
    upstream = await fetch(new URL('/api/notifications/stream', API_URL), {
      headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
      redirect: 'manual',
      signal: request.signal,
    });
  } catch {
    return new Response(null, { status: 502 });
  }
  if (!upstream.ok || !upstream.body) return new Response(null, { status: upstream.status === 401 ? 401 : 502 });

  return new Response(upstream.body, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      'X-Accel-Buffering': 'no', // tell nginx-style proxies not to buffer the stream
    },
  });
};
