import type { Role, UserResponse } from './types';

/**
 * Route protection rules. Add a line to ROUTE_RULES to protect a new area; the first matching
 * rule wins and a path that matches no rule is public. A rule matches its `prefix` itself and
 * everything below it (`/admin` matches `/admin` and `/admin/users/3`, not `/administrator`).
 *
 *   { prefix: '/incidents', roles: ['EMPLOYEE', 'ADMIN'] }   // only these roles
 *   { prefix: '/dashboard' }                                 // any logged-in user
 */
export interface RouteRule {
  prefix: string;
  /** Omit for "any logged-in user". */
  roles?: readonly Role[];
}

export const ROUTE_RULES: readonly RouteRule[] = [
  { prefix: '/admin', roles: ['ADMIN'] },
  { prefix: '/dashboard' },
  { prefix: '/account' },
  // { prefix: '/incidents' },
  // { prefix: '/technician', roles: ['TECHNICIAN'] },
];

/** Never protected by the page middleware: the proxy forwards the token and the backend enforces access. */
export const PUBLIC_PREFIXES: readonly string[] = ['/api'];

/** Pages for logged-out visitors; logged-in users are sent to the dashboard instead. */
export const GUEST_ONLY_PATHS: readonly string[] = ['/login', '/register'];

export type AccessDecision =
  | { type: 'allow' }
  | { type: 'login' } // not logged in: send to /login?next=...
  | { type: 'forbidden' } // logged in with the wrong role
  | { type: 'home' }; // logged in but visiting a guest-only page

/** Normalises a request path for matching: decodes, collapses repeated slashes, drops a trailing slash, lowercases. */
export function normalizePath(pathname: string): string {
  let p = pathname;
  try {
    p = decodeURIComponent(p);
  } catch {
    // keep the raw path
  }
  p = p.replace(/\/{2,}/g, '/').toLowerCase();
  // Resolve `.` and `..` segments (e.g. a decoded `/api/../admin`) so a path cannot pose as a public prefix.
  try {
    p = new URL(p.replace(/\\/g, '/'), 'http://path.invalid').pathname;
  } catch {
    // keep as is
  }
  if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1);
  return p || '/';
}

function matches(path: string, prefix: string): boolean {
  return path === prefix || path.startsWith(`${prefix}/`);
}

export function findRule(pathname: string, rules: readonly RouteRule[] = ROUTE_RULES): RouteRule | undefined {
  const path = normalizePath(pathname);
  return rules.find((r) => matches(path, r.prefix));
}

export function decideAccess(
  pathname: string,
  user: Pick<UserResponse, 'role'> | null,
  rules: readonly RouteRule[] = ROUTE_RULES,
): AccessDecision {
  const path = normalizePath(pathname);
  if (PUBLIC_PREFIXES.some((p) => matches(path, p))) return { type: 'allow' };
  if (user && GUEST_ONLY_PATHS.includes(path)) return { type: 'home' };
  const rule = rules.find((r) => matches(path, r.prefix));
  if (!rule) return { type: 'allow' };
  if (!user) return { type: 'login' };
  if (rule.roles && !rule.roles.includes(user.role)) return { type: 'forbidden' };
  return { type: 'allow' };
}
