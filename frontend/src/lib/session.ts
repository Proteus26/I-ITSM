import type { AstroCookies } from 'astro';
import type { LoginResponse } from './types';

export const SESSION_COOKIE = 'itsm_session';

export interface SessionCookieOptions {
  httpOnly: true;
  sameSite: 'lax';
  path: '/';
  secure: boolean;
  /** Omitted (a browser-session cookie) when the backend's expiry cannot be parsed. */
  expires?: Date;
}

/** `COOKIE_SECURE=true` (or `1`) marks the cookie Secure; default false so http://localhost works. */
export function isCookieSecure(env: Record<string, string | undefined> = process.env): boolean {
  const v = env.COOKIE_SECURE?.trim().toLowerCase();
  return v === 'true' || v === '1';
}

/** Cookie attributes for the session. The token is httpOnly: client-side JS can never read it. */
export function sessionCookieOptions(
  expiresAt: string | Date,
  env: Record<string, string | undefined> = process.env,
): SessionCookieOptions {
  const base = { httpOnly: true, sameSite: 'lax', path: '/', secure: isCookieSecure(env) } as const;
  const expires = new Date(expiresAt);
  // Never hand an Invalid Date to the cookie serializer: fall back to a session cookie.
  return Number.isNaN(expires.getTime()) ? base : { ...base, expires };
}

export function readToken(cookies: AstroCookies): string | undefined {
  return cookies.get(SESSION_COOKIE)?.value || undefined;
}

export function startSession(cookies: AstroCookies, login: Pick<LoginResponse, 'token' | 'expiresAt'>): void {
  cookies.set(SESSION_COOKIE, login.token, sessionCookieOptions(login.expiresAt));
}

export function endSession(cookies: AstroCookies): void {
  cookies.delete(SESSION_COOKIE, { path: '/' });
}
