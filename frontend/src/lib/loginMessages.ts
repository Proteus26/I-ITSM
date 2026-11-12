import type { ApiError } from './errors';

/** Fixed messages for the login page, picked by query flag. Query text itself is never shown. */
export const EXPIRED_NOTICE = 'Your session has ended. Please log in again.';
export const LOGGED_OUT_ALL_NOTICE = 'You have been logged out on all devices.';
export const REGISTERED_NOTICE = 'Account created, please log in.';

export interface LoginNotice {
  text: string;
  kind: 'success' | 'info';
}

/** The notice for the login page's query string, or null. */
export function loginNotice(params: URLSearchParams): LoginNotice | null {
  if (params.get('expired') === '1') return { text: EXPIRED_NOTICE, kind: 'info' };
  if (params.get('loggedOut') === 'all') return { text: LOGGED_OUT_ALL_NOTICE, kind: 'info' };
  if (params.get('registered') === '1') return { text: REGISTERED_NOTICE, kind: 'success' };
  return null;
}

/**
 * "less than a minute", "1 minute", "N minutes" (rounded up) from a Retry-After in seconds, "N hours" (rounded up)
 * beyond 120 minutes; "a few minutes" if unknown.
 */
export function formatRetryAfter(seconds: number | undefined): string {
  if (seconds === undefined || !Number.isFinite(seconds) || seconds <= 0) return 'a few minutes';
  if (seconds < 60) return 'less than a minute';
  const minutes = Math.ceil(seconds / 60);
  if (minutes > 120) return `${Math.ceil(seconds / 3600)} hours`;
  return minutes === 1 ? '1 minute' : `${minutes} minutes`;
}

/** "Too many failed attempts. Try again in 3 minutes." */
export function tooManyAttemptsMessage(seconds: number | undefined): string {
  return `Too many failed attempts. Try again in ${formatRetryAfter(seconds)}.`;
}

/** The error shown on the login form for a failed login. */
export function loginErrorMessage(e: ApiError): string {
  if (e.status === 429) return tooManyAttemptsMessage(e.retryAfterSeconds);
  if (e.status === 400) return 'Enter a valid email address and your password.';
  return e.userMessage;
}
