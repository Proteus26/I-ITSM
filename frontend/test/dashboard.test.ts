import { describe, expect, it } from 'vitest';
import { ApiError } from '../src/lib/errors';
import type { IncidentSummary } from '../src/lib/incidentTypes';
import {
  awaitingResponse,
  awaitingVerification,
  compactDuration,
  expiredSession,
  formatPercent,
  loadPercent,
  openIncidents,
  relativeDue,
  settle,
  slaTone,
  sortByDue,
} from '../src/lib/dashboard';

const NOW = new Date('2026-11-18T12:00:00Z');
const at = (min: number) => new Date(NOW.getTime() + min * 60000).toISOString();

function inc(over: Partial<IncidentSummary>): IncidentSummary {
  return {
    id: 1, number: 'INC-1', title: 't', status: 'ASSIGNED', priority: 'P2', categoryCode: null, serviceName: null,
    reporter: { id: 1, fullName: 'R' }, assignee: null, createdAt: at(-60), resolutionDueAt: at(60), ...over,
  };
}

describe('settle', () => {
  it('wraps a value and a failure', async () => {
    expect(await settle(async () => 3)).toEqual({ ok: true, value: 3 });
    const r = await settle(async () => {
      throw new ApiError(503, 'Unavailable', 'down');
    });
    expect(r.ok).toBe(false);
  });
  it('finds an expired session', async () => {
    const bad = await settle(async () => {
      throw new ApiError(401, 'Unauthorized', 'x');
    });
    const other = await settle(async () => {
      throw new ApiError(500, 'Internal', 'x');
    });
    expect(expiredSession([other])).toBeUndefined();
    expect(expiredSession([other, bad])).toBeDefined();
  });
});

describe('incident lists', () => {
  const list = [
    inc({ id: 1, number: 'INC-1', status: 'NEW' }),
    inc({ id: 2, number: 'INC-2', status: 'PENDING_INFO' }),
    inc({ id: 3, number: 'INC-3', status: 'RESOLVED' }),
    inc({ id: 4, number: 'INC-4', status: 'CLOSED' }),
    inc({ id: 5, number: 'INC-5', status: 'CANCELLED' }),
  ];
  it('filters', () => {
    expect(openIncidents(list).map((i) => i.id)).toEqual([1, 2]);
    expect(awaitingResponse(list).map((i) => i.id)).toEqual([2]);
    expect(awaitingVerification(list).map((i) => i.id)).toEqual([3]);
  });
  it('sorts by deadline with missing deadlines last', () => {
    const sorted = sortByDue([
      inc({ id: 1, number: 'INC-1', resolutionDueAt: null }),
      inc({ id: 2, number: 'INC-2', resolutionDueAt: at(90) }),
      inc({ id: 3, number: 'INC-3', resolutionDueAt: at(-10) }),
      inc({ id: 4, number: 'INC-0', resolutionDueAt: null }),
    ]);
    expect(sorted.map((i) => i.id)).toEqual([3, 2, 4, 1]);
  });
});

describe('slaTone', () => {
  it('is overdue after the deadline', () => expect(slaTone(inc({ resolutionDueAt: at(-1) }), NOW)).toBe('overdue'));
  it('is at risk after 75% of the window', () => {
    expect(slaTone(inc({ createdAt: at(-80), resolutionDueAt: at(20) }), NOW)).toBe('at-risk');
    expect(slaTone(inc({ createdAt: at(-50), resolutionDueAt: at(50) }), NOW)).toBe('ok');
  });
  it('ignores finished incidents and missing deadlines', () => {
    expect(slaTone(inc({ status: 'RESOLVED', resolutionDueAt: at(-100) }), NOW)).toBe('none');
    expect(slaTone(inc({ resolutionDueAt: null }), NOW)).toBe('none');
  });
});

describe('formatting', () => {
  it('compacts durations', () => {
    expect(compactDuration(30_000)).toBe('<1m');
    expect(compactDuration(35 * 60000)).toBe('35m');
    expect(compactDuration(125 * 60000)).toBe('2h 5m');
    expect(compactDuration(120 * 60000)).toBe('2h');
    expect(compactDuration(50 * 3600000)).toBe('2d 2h');
  });
  it('describes deadlines', () => {
    expect(relativeDue(at(35), NOW)).toBe('due in 35m');
    expect(relativeDue(at(-125), NOW)).toBe('2h 5m overdue');
    expect(relativeDue(null, NOW)).toBe('No deadline');
  });
  it('computes load and percentages', () => {
    expect(loadPercent(2, 5)).toBe(40);
    expect(loadPercent(7, 5)).toBe(100);
    expect(loadPercent(1, 0)).toBe(0);
    expect(formatPercent(100)).toBe('100%');
    expect(formatPercent(93.33)).toBe('93.3%');
    expect(formatPercent(Number.NaN)).toBe('—');
  });
});
