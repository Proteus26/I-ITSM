import { defineMiddleware } from 'astro:middleware';
import { decideAccess } from './lib/access';
import { BackendClient } from './lib/backend';
import { ApiError } from './lib/errors';
import { loginUrl } from './lib/redirect';
import { endSession, readToken } from './lib/session';

/**
 * Runs for every request:
 *  1. if a session cookie is present, asks the backend who the user is (`/api/auth/me`, once) and
 *     exposes the answer as `Astro.locals.user`; a 401 clears the cookie (logged out);
 *  2. applies the route rules in src/lib/access.ts (add new protected areas there).
 *
 * `/api/**` skips step 1: the proxy forwards the token and the backend does the checking.
 */
export const onRequest = defineMiddleware(async (context, next) => {
  const { locals, cookies, url } = context;

  // A rewrite (to /403 or /503) re-enters the middleware; keep what was already resolved.
  if (Object.hasOwn(locals, 'user')) return next();

  locals.user = null;
  locals.backendDown = false;

  const token = readToken(cookies);
  if (token && !url.pathname.startsWith('/api/')) {
    try {
      locals.user = await new BackendClient(token).me();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        endSession(cookies);
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
      return context.redirect(loginUrl(url.pathname + url.search));
    case 'forbidden':
      return context.rewrite('/403');
    case 'home':
      return context.redirect('/dashboard');
    default:
      return next();
  }
});
