export const DEFAULT_LANDING = '/dashboard';

/**
 * Validates a post-login `next` target against open redirects. Only same-origin relative paths
 * are accepted: they must start with a single `/` (not `//` or `/\`) and contain no control
 * characters (browsers strip tabs and newlines, so `/\t/evil.example` would become `//evil.example`).
 */
export function safeNext(next: string | null | undefined, fallback: string = DEFAULT_LANDING): string {
  if (typeof next !== 'string' || next.length === 0) return fallback;
  if (next[0] !== '/' || next[1] === '/' || next[1] === '\\') return fallback;
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f\\]/.test(next)) return fallback;
  try {
    const parsed = new URL(next, 'http://placeholder.invalid');
    if (parsed.origin !== 'http://placeholder.invalid') return fallback;
  } catch {
    return fallback;
  }
  return next;
}

/** `/login?next=<path>`, for sending an unauthenticated visitor to the login page. */
export function loginUrl(returnTo: string, options: { expired?: boolean } = {}): string {
  return `/login?next=${encodeURIComponent(returnTo)}${options.expired ? '&expired=1' : ''}`;
}
