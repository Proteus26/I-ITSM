// Pure helpers for the role dashboards: counting and sorting incident lists, SLA tone, relative deadlines and
// the "each block degrades on its own" wrapper. No Astro in here, so it is unit tested.
import { ApiError } from './errors';
import type { IncidentSummary } from './incidentTypes';
import { isFinished } from './incidents';

/** A block of the dashboard: its data, or why it could not be loaded (the other blocks still render). */
export type Loaded<T> = { ok: true; value: T } | { ok: false; error: ApiError | Error };

/** Runs one backend call and turns a failure into a value, so one broken block never takes the page down. */
export async function settle<T>(call: () => Promise<T>): Promise<Loaded<T>> {
  try {
    return { ok: true, value: await call() };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e : new Error(String(e)) };
  }
}

/** The first failure that means "log in again" (401), if any. */
export function expiredSession(results: readonly Loaded<unknown>[]): ApiError | undefined {
  for (const r of results) if (!r.ok && r.error instanceof ApiError && r.error.status === 401) return r.error;
  return undefined;
}

/** A short, user-facing reason for a failed block. */
export function failureText(e: Error): string {
  return e instanceof ApiError ? e.userMessage : 'Unexpected error.';
}

/** Incidents still being worked on or waiting for someone (not resolved, verified, closed or cancelled). */
export function openIncidents(list: readonly IncidentSummary[]): IncidentSummary[] {
  return list.filter((i) => !isFinished(i.status));
}

/** Incidents where the technician waits for the reporter's answer. */
export function awaitingResponse(list: readonly IncidentSummary[]): IncidentSummary[] {
  return list.filter((i) => i.status === 'PENDING_INFO');
}

/** Resolved but not yet confirmed by the reporter: the reporter should verify the fix (and rate it). */
export function awaitingVerification(list: readonly IncidentSummary[]): IncidentSummary[] {
  return list.filter((i) => i.status === 'RESOLVED');
}

/** Earliest resolution deadline first; incidents without a deadline last, ties by number. */
export function sortByDue(list: readonly IncidentSummary[]): IncidentSummary[] {
  const key = (i: IncidentSummary) => (i.resolutionDueAt ? Date.parse(i.resolutionDueAt) : Number.POSITIVE_INFINITY);
  return [...list].sort((a, b) => {
    const ka = key(a);
    const kb = key(b);
    if (ka !== kb) return ka < kb ? -1 : 1;
    return a.number.localeCompare(b.number);
  });
}

export type SlaTone = 'overdue' | 'at-risk' | 'ok' | 'none';

/** Share of the resolution window after which an incident counts as "at risk" (the SLA policies' default warning threshold). */
export const AT_RISK_SHARE = 0.75;

/**
 * Where an incident stands against its resolution deadline: overdue once past it, at risk once 75% of the window
 * (creation to deadline) has elapsed, none when there is no deadline or the incident is finished.
 */
export function slaTone(i: Pick<IncidentSummary, 'status' | 'createdAt' | 'resolutionDueAt'>, now: Date = new Date()): SlaTone {
  if (!i.resolutionDueAt || isFinished(i.status)) return 'none';
  const due = Date.parse(i.resolutionDueAt);
  if (Number.isNaN(due)) return 'none';
  const t = now.getTime();
  if (t > due) return 'overdue';
  const start = Date.parse(i.createdAt);
  if (!Number.isNaN(start) && due > start && (t - start) / (due - start) >= AT_RISK_SHARE) return 'at-risk';
  return 'ok';
}

/** "2h 5m overdue" / "due in 35m": a compact distance between now and a deadline. */
export function relativeDue(due: string | null | undefined, now: Date = new Date()): string {
  if (!due) return 'No deadline';
  const ms = Date.parse(due) - now.getTime();
  if (Number.isNaN(ms)) return 'No deadline';
  const text = compactDuration(Math.abs(ms));
  return ms < 0 ? `${text} overdue` : `due in ${text}`;
}

/** 45s -> "<1m", 35 min -> "35m", 125 min -> "2h 5m", 50 h -> "2d 2h". */
export function compactDuration(ms: number): string {
  const minutes = Math.floor(ms / 60000);
  if (minutes < 1) return '<1m';
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return minutes % 60 ? `${hours}h ${minutes % 60}m` : `${hours}h`;
  const days = Math.floor(hours / 24);
  return hours % 24 ? `${days}d ${hours % 24}h` : `${days}d`;
}

/** Workload as a whole percentage, capped to 0..100 (an overloaded technician shows a full bar). */
export function loadPercent(active: number, max: number): number {
  if (!(max > 0) || !(active > 0)) return 0;
  return Math.min(100, Math.round((active / max) * 100));
}

/** "93.3%" for a 0..100 percentage, one decimal, trailing ".0" dropped. */
export function formatPercent(p: number): string {
  if (!Number.isFinite(p)) return '—';
  const r = Math.round(p * 10) / 10;
  return `${Number.isInteger(r) ? r.toFixed(0) : r.toFixed(1)}%`;
}
