import { describe, expect, it } from 'vitest';
import {
  LIST_NOTICES,
  byUrgency,
  capacityText,
  clusterSummary,
  deadlineBadgeClass,
  deadlineLabel,
  formatSpan,
  loadPercent,
  markersFromWorkload,
  minutesBetween,
  parseAvailabilityForm,
  remainingText,
  statusCounts,
} from '../src/lib/ops';
import type { SlaRiskItem, WorkloadIncident } from '../src/lib/incidentTypes';

const fd = (o: Record<string, string>) => {
  const f = new FormData();
  for (const [k, v] of Object.entries(o)) f.set(k, v);
  return f;
};

describe('formatSpan / remainingText', () => {
  it.each([
    [0, 'less than a minute'],
    [-5, 'less than a minute'],
    [35, '35 min'],
    [60, '1 h 0 min'],
    [125, '2 h 5 min'],
    [48 * 60, '2 days'],
    [24 * 60 * 3 + 100, '3 days'],
  ])('formats %d minutes', (m, text) => expect(formatSpan(m)).toBe(text));

  it('words remaining and overdue time', () => {
    expect(remainingText(90)).toBe('1 h 30 min left');
    expect(remainingText(0)).toBe('due now');
    expect(remainingText(-15)).toBe('overdue by 15 min');
  });
});

describe('minutesBetween', () => {
  const now = new Date('2026-11-18T10:00:00Z');
  it('is signed and floored', () => {
    expect(minutesBetween(now, '2026-11-18T11:30:30Z')).toBe(90);
    expect(minutesBetween(now, '2026-11-18T09:45:00Z')).toBe(-15);
  });
  it('gives null for nothing or garbage', () => {
    expect(minutesBetween(now, null)).toBeNull();
    expect(minutesBetween(now, 'soon')).toBeNull();
  });
});

describe('badges and capacity', () => {
  it('maps states to classes and labels', () => {
    expect(deadlineBadgeClass('OVERDUE')).toBe('badge badge-danger');
    expect(deadlineBadgeClass('BREACHED')).toBe('badge badge-danger');
    expect(deadlineBadgeClass('AT_RISK')).toBe('badge badge-warn');
    expect(deadlineBadgeClass('ON_TRACK')).toBe('badge badge-neutral');
    expect(deadlineLabel('BREACHED')).toBe('Overdue');
    expect(deadlineLabel('AT_RISK')).toBe('At risk');
  });
  it('computes the load percentage safely', () => {
    expect(loadPercent(2, 5)).toBe(40);
    expect(loadPercent(7, 5)).toBe(100);
    expect(loadPercent(0, 5)).toBe(0);
    expect(loadPercent(3, 0)).toBe(0);
  });
  it('writes the capacity line', () => {
    expect(capacityText({ activeCount: 3, capacity: 5 })).toBe('3 / 5 active incidents');
  });
  it('lists non-zero status counts in order', () => {
    expect(statusCounts({ countsByStatus: { ASSIGNED: 2, ACKNOWLEDGED: 0, IN_PROGRESS: 1 } })).toEqual([
      { status: 'ASSIGNED', count: 2 },
      { status: 'IN_PROGRESS', count: 1 },
    ]);
  });
});

describe('availability form', () => {
  it('reads true and false only', () => {
    expect(parseAvailabilityForm(fd({ available: 'true' }))).toBe(true);
    expect(parseAvailabilityForm(fd({ available: 'false' }))).toBe(false);
    expect(parseAvailabilityForm(fd({ available: 'yes' }))).toBeUndefined();
    expect(parseAvailabilityForm(fd({}))).toBeUndefined();
  });
  it('has a notice for both outcomes', () => {
    expect(Object.keys(LIST_NOTICES)).toEqual(['availability-on', 'availability-off']);
  });
});

describe('markersFromWorkload', () => {
  const row = (id: number, deadlineState: WorkloadIncident['deadlineState']): WorkloadIncident => ({
    id, number: `INC-${id}`, title: 't', status: 'ASSIGNED', priority: 'P2', responseDueAt: null, resolutionDueAt: null, deadlineState, riskReason: null,
  });
  it('keeps only rows that need attention', () => {
    expect(markersFromWorkload({ incidents: [row(1, 'OVERDUE'), row(2, 'ON_TRACK'), row(3, 'AT_RISK')] })).toEqual({ 1: 'OVERDUE', 3: 'AT_RISK' });
  });
  it('copes with no workload', () => {
    expect(markersFromWorkload(null)).toEqual({});
  });
});

describe('clusterSummary and byUrgency', () => {
  it('describes a service cluster and a category cluster', () => {
    expect(clusterSummary({ count: 5, label: 'VPN', kind: 'SERVICE', windowMinutes: 120 })).toBe('5 open incidents on VPN in the last 2 h 0 min');
    expect(clusterSummary({ count: 3, label: 'NETWORK', kind: 'CATEGORY', windowMinutes: 30 })).toBe('3 open incidents in category NETWORK in the last 30 min');
  });
  it('sorts by least time left without changing the input', () => {
    const item = (incidentId: number, minutesRemaining: number) => ({ incidentId, minutesRemaining }) as SlaRiskItem;
    const input = [item(1, 30), item(2, -90), item(3, -90), item(4, 5)];
    expect(byUrgency(input).map((i) => i.incidentId)).toEqual([2, 3, 4, 1]);
    expect(input.map((i) => i.incidentId)).toEqual([1, 2, 3, 4]);
  });
});
