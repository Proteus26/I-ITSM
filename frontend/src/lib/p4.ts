import {
  INCIDENT_STATUSES,
  PRIORITIES,
  SLA_STATUSES,
  type AuditQuery,
  type IncidentSearchQuery,
} from './p4Types';

/** Audit actions the backend writes (AuditDescriber). */
export const AUDIT_ACTIONS = [
  'INCIDENT_REPORTED', 'INCIDENT_UPDATED', 'INCIDENT_CLASSIFIED', 'PRIORITY_CHANGED', 'INCIDENT_ASSIGNED', 'STATUS_CHANGED',
  'NOTE_ADDED', 'INFO_REQUESTED', 'SLA_WARNING', 'SLA_BREACHED', 'INCIDENT_ESCALATED', 'INCIDENT_RESOLVED',
  'INCIDENT_CLOSED', 'USER_LOGGED_IN', 'LOGIN_FAILED',
] as const;

export const SORT_OPTIONS = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'priority,asc', label: 'Priority (P1 first)' },
  { value: 'status,asc', label: 'Status' },
  { value: 'number,asc', label: 'Number' },
] as const;

const DATE = /^\d{4}-\d{2}-\d{2}$/;

function oneOf<T extends string>(list: readonly T[], value: string | null): T | undefined {
  return list.find((x) => x === value);
}

function positiveId(value: string | null): number | undefined {
  return value && /^\d{1,15}$/.test(value) && Number(value) > 0 ? Number(value) : undefined;
}

function date(value: string | null): string | undefined {
  return value && DATE.test(value) ? value : undefined;
}

function text(value: string | null, max = 100): string | undefined {
  const t = (value ?? '').trim().slice(0, max);
  return t || undefined;
}

/** Reads the search filters from the URL, dropping anything that is not valid, so only clean values reach the API. */
export function cleanSearchQuery(p: URLSearchParams): IncidentSearchQuery {
  return {
    number: text(p.get('number'), 30),
    q: text(p.get('q'), 200),
    from: date(p.get('from')),
    to: date(p.get('to')),
    status: oneOf(INCIDENT_STATUSES, p.get('status')),
    priority: oneOf(PRIORITIES, p.get('priority')),
    category: text(p.get('category'), 50),
    service: positiveId(p.get('service')),
    assignee: positiveId(p.get('assignee')),
    reporter: positiveId(p.get('reporter')),
    slaStatus: oneOf(SLA_STATUSES, p.get('slaStatus')),
    sort: SORT_OPTIONS.find((s) => s.value === p.get('sort'))?.value,
  };
}

export function cleanAuditQuery(p: URLSearchParams): AuditQuery {
  return {
    actor: positiveId(p.get('actor')),
    action: oneOf(AUDIT_ACTIONS, p.get('action')),
    incident: positiveId(p.get('incident')),
    from: date(p.get('from')),
    to: date(p.get('to')),
  };
}

/** URL query string for a query object, leaving out empty values. */
export function toQueryString(query: object, page?: number): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined && v !== null && v !== '') sp.set(k, String(v));
  }
  if (page !== undefined) sp.set('page', String(page));
  return sp.toString();
}

// eslint-disable-next-line no-control-regex
const UNSAFE_LINK_CHARS = /[\\\u0000-\u001f\u007f\s]/;
const LINK_BASE = 'http://link.invalid';

/**
 * Only links that stay inside the app are rendered as links (a notification link comes from the backend).
 * Accepts a path that starts with a single `/`; rejects `//host`, `/\host`, `javascript:`, absolute URLs,
 * and anything with backslashes, whitespace or control characters (browsers strip tabs and newlines, so
 * `/\t/evil.com` would otherwise become `//evil.com`).
 */
export function safeInternalLink(link: string | null | undefined): string | null {
  if (typeof link !== 'string' || !link.startsWith('/') || link.startsWith('//')) return null;
  if (UNSAFE_LINK_CHARS.test(link)) return null;
  try {
    const u = new URL(link, LINK_BASE);
    if (u.origin !== LINK_BASE) return null;
  } catch {
    return null;
  }
  return link;
}

/** `STATUS_CHANGED` -> `Status changed`. */
export function humanize(code: string): string {
  return code.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}
