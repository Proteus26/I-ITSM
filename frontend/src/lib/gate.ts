import type { APIContext, MiddlewareNext } from 'astro';
import { decideAccess } from './access';
import { BackendClient } from './backend';
import { ApiError } from './errors';
import { loginUrl } from './redirect';
import { endSession, readToken } from './session';
import type { UserResponse } from './types';

/** The part of the request context the gate needs (a stub is enough in tests). */
export type GateContext = Pick<APIContext, 'locals' | 'cookies' | 'url' | 'redirect' | 'rewrite'>;

/** Anything with `me()`; lets tests stub the backend. */
export type MeClient = { me(): Promise<UserResponse> };

/**
 * The body of src/middleware.ts, kept free of `astro:middleware` so it can be unit tested.
 *  1. if a session cookie is present, asks the backend who the user is (`/api/auth/me`, once) and
 *     exposes the answer as `locals.user`; a 401 clears the cookie (logged out);
 *  2. applies the route rules in src/lib/access.ts.
 *
 * `/api/**` skips step 1: the proxy forwards the token and the backend does the checking.
 */
export async function gate(
  context: GateContext,
  next: MiddlewareNext,
  clientFor: (token: string) => MeClient = (token) => new BackendClient(token),
): Promise<Response> {
  const { locals, cookies, url } = context;

  // A rewrite (to /403 or /503) re-enters the middleware; keep what was already resolved.
  if (Object.hasOwn(locals, 'user')) return next();

  locals.user = null;
  locals.backendDown = false;

  let expired = false;
  const token = readToken(cookies);
  if (token && !url.pathname.startsWith('/api/')) {
    try {
      locals.user = await clientFor(token).me();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        endSession(cookies);
        expired = true;
      } else {
        locals.backendDown = true; // unreachable or failing: not logged in as far as we know
      }
    }
  }

  const decision = decideAccess(url.pathname, locals.user);
  switch (decision.type) {
    case 'login':
      // Do not bounce to /login when the real problem is that the backend is down.
      if (locals.backendDown) return context.rewrite('/503');
      return context.redirect(loginUrl(url.pathname + url.search, { expired }));
    case 'forbidden':
      return context.rewrite('/403');
    case 'home':
      return context.redirect('/dashboard');
    default:
      return next();
  }
}
