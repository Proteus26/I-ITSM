// Pure helpers for the incident-operations views: technician workload, SLA monitor and incident correlation.
// No Astro or fetch in here, so it is unit tested.
import { formString, parseActive, pluralize } from './forms';
import type { DeadlineState, IncidentCluster, SlaRiskItem, TechnicianWorkload } from './incidentTypes';

/** "2 h 5 min", "35 min", "3 days": a length of time for people (rounded down, never negative). */
export function formatSpan(minutes: number): string {
  const m = Math.max(0, Math.floor(minutes));
  if (m < 1) return 'less than a minute';
  if (m < 60) return `${m} min`;
  if (m < 48 * 60) return `${Math.floor(m / 60)} h ${m % 60} min`;
  return pluralize(Math.floor(m / (24 * 60)), 'day');
}

/** "2 h 5 min left" or "overdue by 15 min" for the signed minutes the backend sends. */
export function remainingText(minutes: number): string {
  return minutes < 0 ? `overdue by ${formatSpan(-minutes)}` : minutes === 0 ? 'due now' : `${formatSpan(minutes)} left`;
}

/** Minutes between two instants (to - from), floored; NaN-safe (0 for an invalid date). */
export function minutesBetween(from: Date, to: string | null | undefined): number | null {
  if (!to) return null;
  const t = new Date(to).getTime();
  return Number.isNaN(t) ? null : Math.floor((t - from.getTime()) / 60000);
}

/** CSS class of the badge for a deadline state. */
export function deadlineBadgeClass(state: DeadlineState | 'BREACHED'): string {
  return state === 'OVERDUE' || state === 'BREACHED' ? 'badge badge-danger' : state === 'AT_RISK' ? 'badge badge-warn' : 'badge badge-neutral';
}

export function deadlineLabel(state: DeadlineState | 'BREACHED'): string {
  return state === 'OVERDUE' || state === 'BREACHED' ? 'Overdue' : state === 'AT_RISK' ? 'At risk' : 'On track';
}

/** Share of capacity in use for a progress display, 0..100 (capped; 0 when the capacity is not positive). */
export function loadPercent(active: number, capacity: number): number {
  if (!(capacity > 0) || active <= 0) return 0;
  return Math.min(100, Math.round((active / capacity) * 100));
}

/** "3 / 5 active incidents" */
export function capacityText(w: Pick<TechnicianWorkload, 'activeCount' | 'capacity'>): string {
  return `${w.activeCount} / ${w.capacity} active incidents`;
}

/** The status counts of a workload as a list in lifecycle order, zeros left out. */
export function statusCounts(w: Pick<TechnicianWorkload, 'countsByStatus'>): { status: string; count: number }[] {
  return Object.entries(w.countsByStatus)
    .filter(([, n]) => n > 0)
    .map(([status, count]) => ({ status, count }));
}

/** The availability form: hidden `available` = "true" | "false". Anything else is invalid. */
export function parseAvailabilityForm(form: FormData): boolean | undefined {
  return parseActive(formString(form, 'available'));
}

/** `?notice=` messages of the incident list. */
export const LIST_NOTICES: Record<string, string> = {
  'availability-on': 'You are available again: new incidents can be assigned to you automatically.',
  'availability-off': 'You are marked unavailable: no new incidents will be assigned to you automatically. Your current incidents stay yours.',
};

/** Map incident id -> deadline state for the rows of the "assigned to me" list (ON_TRACK ones are left out). */
export function markersFromWorkload(w: Pick<TechnicianWorkload, 'incidents'> | null | undefined): Record<number, DeadlineState> {
  const out: Record<number, DeadlineState> = {};
  for (const i of w?.incidents ?? []) if (i.deadlineState !== 'ON_TRACK') out[i.id] = i.deadlineState;
  return out;
}

/** One line for a cluster: "5 open incidents on VPN in the last 2 hours". */
export function clusterSummary(c: Pick<IncidentCluster, 'count' | 'label' | 'kind' | 'windowMinutes'>): string {
  const where = c.kind === 'SERVICE' ? `on ${c.label}` : `in category ${c.label}`;
  return `${pluralize(c.count, 'open incident')} ${where} in the last ${formatSpan(c.windowMinutes)}`;
}

/** Sorts SLA items the way the monitor shows them: least time left (most overdue) first. */
export function byUrgency(items: readonly SlaRiskItem[]): SlaRiskItem[] {
  return [...items].sort((a, b) => a.minutesRemaining - b.minutesRemaining || a.incidentId - b.incidentId);
}
