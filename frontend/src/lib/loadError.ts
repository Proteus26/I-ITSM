import type { AstroCookies } from 'astro';
import { ApiError } from './errors';
import { loginUrl } from './redirect';
import { endSession } from './session';

/** The part of `Astro` (or an API context) that {@link handleLoadError} needs. */
export interface LoadErrorContext {
  cookies: AstroCookies;
  url: URL;
  redirect(path: string): Response;
  rewrite(path: string): Promise<Response>;
}

/**
 * Standard reaction of a page to a failed backend call. Usage:
 *
 *   try { data = await api.something(); }
 *   catch (e) {
 *     const r = await handleLoadError(e, Astro);
 *     if (r instanceof Response) return r;   // 404 / 403 / 503 page, or redirect to login
 *     message = r.userMessage;               // anything else: show it on the page
 *   }
 *
 * 404 -> /404, 401 -> end the session and go to /login?next=...&expired=1, 403 -> /403,
 * 5xx or unreachable -> /503 (or returned for inline display with `inlineServerErrors`, for form
 * submissions that must keep the form), anything else -> the ApiError itself. Non-ApiErrors are rethrown.
 */
export async function handleLoadError(
  e: unknown,
  ctx: LoadErrorContext,
  options: { inlineServerErrors?: boolean } = {},
): Promise<Response | ApiError> {
  if (!(e instanceof ApiError)) throw e;
  if (e.status === 404) return ctx.rewrite('/404');
  if (e.status === 401) {
    endSession(ctx.cookies);
    return ctx.redirect(loginUrl(ctx.url.pathname + ctx.url.search, { expired: true }));
  }
  if (e.status === 403) return ctx.rewrite('/403');
  if (e.isServerError && !options.inlineServerErrors) return ctx.rewrite('/503');
  return e;
}
