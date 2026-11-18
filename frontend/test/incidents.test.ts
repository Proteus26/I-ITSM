import { describe, expect, it } from 'vitest';
import {
  ACTION_INTENT,
  EMPTY_REPORT_FORM,
  MAX_FEEDBACK_COMMENT,
  MAX_ROOT_CAUSE,
  MAX_TEXT,
  MAX_TITLE,
  allowedNoteTypes,
  dueInfo,
  incidentHref,
  isFinished,
  parseActionForm,
  parseEditForm,
  parseNoteForm,
  parseReportForm,
  parseTab,
  priorityBadgeClass,
  statusBadgeClass,
  statusLabel,
  visibleActions,
} from '../src/lib/incidents';
import { INCIDENT_ACTIONS } from '../src/lib/incidentTypes';

const fd = (o: Record<string, string>) => {
  const f = new FormData();
  for (const [k, v] of Object.entries(o)) f.set(k, v);
  return f;
};
const valid = { ...EMPTY_REPORT_FORM, title: 'VPN down', description: 'Cannot connect' };

describe('parseReportForm', () => {
  it('builds the request, defaulting affectedUsers and leaving out blank optionals', () => {
    expect(parseReportForm({ ...valid, affectedUsers: '' })).toEqual({
      ok: true,
      value: { title: 'VPN down', description: 'Cannot connect', impact: 'INDIVIDUAL', urgency: 'MEDIUM', affectedUsers: 1 },
    });
  });
  it('passes service and category', () => {
    const r = parseReportForm({ ...valid, affectedUsers: '12', serviceId: '3', categoryCode: 'NETWORK' });
    expect(r).toMatchObject({ ok: true, value: { affectedUsers: 12, serviceId: 3, categoryCode: 'NETWORK' } });
  });
  it('mirrors the backend limits', () => {
    expect(parseReportForm({ ...valid, title: '' })).toMatchObject({ ok: false, errors: { title: expect.any(String) } });
    expect(parseReportForm({ ...valid, title: 'a'.repeat(MAX_TITLE) }).ok).toBe(true);
    expect(parseReportForm({ ...valid, title: 'a'.repeat(MAX_TITLE + 1) }).ok).toBe(false);
    expect(parseReportForm({ ...valid, description: 'a'.repeat(MAX_TEXT) }).ok).toBe(true);
    expect(parseReportForm({ ...valid, description: 'a'.repeat(MAX_TEXT + 1) }).ok).toBe(false);
    expect(parseReportForm({ ...valid, description: '' }).ok).toBe(false);
  });
  it.each(['0', '-1', 'abc', '1.5', '99999999999'])('rejects affectedUsers %s', (n) => {
    expect(parseReportForm({ ...valid, affectedUsers: n })).toMatchObject({ ok: false, errors: { affectedUsers: expect.any(String) } });
  });
  it('rejects unknown enums, services and category codes', () => {
    const r = parseReportForm({ ...valid, impact: 'HUGE', urgency: 'NOW', serviceId: 'x', categoryCode: 'drop table' });
    expect(r).toMatchObject({ ok: false });
    expect(Object.keys((r as { errors: object }).errors).sort()).toEqual(['categoryCode', 'impact', 'serviceId', 'urgency']);
  });
});

describe('actions', () => {
  it('maps every action to a unique intent', () => {
    expect(new Set(Object.values(ACTION_INTENT)).size).toBe(INCIDENT_ACTIONS.length);
    expect(ACTION_INTENT.REQUEST_INFO).toBe('request-info');
  });
  it('keeps known actions in a fixed order and drops unknown ones', () => {
    expect(visibleActions(['CLOSE', 'NOPE', 'ACKNOWLEDGE'])).toEqual(['ACKNOWLEDGE', 'CLOSE']);
    expect(visibleActions(undefined)).toEqual([]);
  });
  it('filters note types', () => {
    expect(allowedNoteTypes(['COMMENT', 'INVESTIGATION', 'X'])).toEqual(['INVESTIGATION', 'COMMENT']);
  });

  it('needs no body for simple actions', () => {
    for (const a of ['ACKNOWLEDGE', 'START', 'VERIFY', 'CLOSE'] as const) expect(parseActionForm(a, fd({}))).toEqual({ ok: true, value: undefined });
  });
  it('request-info needs a question', () => {
    expect(parseActionForm('REQUEST_INFO', fd({ question: ' ' })).ok).toBe(false);
    expect(parseActionForm('REQUEST_INFO', fd({ question: ' Which PC? ' }))).toEqual({ ok: true, value: { question: 'Which PC?' } });
  });
  it('resume sends the response only when given', () => {
    expect(parseActionForm('RESUME', fd({}))).toEqual({ ok: true, value: {} });
    expect(parseActionForm('RESUME', fd({ response: 'It is PC-4' }))).toEqual({ ok: true, value: { response: 'It is PC-4' } });
  });
  it('resolve needs a summary, root cause is optional', () => {
    expect(parseActionForm('RESOLVE', fd({ rootCause: 'x' }))).toMatchObject({ ok: false, errors: { summary: expect.any(String) } });
    expect(parseActionForm('RESOLVE', fd({ summary: 'Restarted' }))).toEqual({ ok: true, value: { summary: 'Restarted' } });
    expect(parseActionForm('RESOLVE', fd({ summary: 'Restarted', rootCause: 'Leak' }))).toEqual({ ok: true, value: { summary: 'Restarted', rootCause: 'Leak' } });
  });
  it('reopen and cancel take an optional reason', () => {
    expect(parseActionForm('REOPEN', fd({}))).toEqual({ ok: true, value: {} });
    expect(parseActionForm('CANCEL', fd({ reason: 'Duplicate' }))).toEqual({ ok: true, value: { reason: 'Duplicate' } });
    expect(parseActionForm('CANCEL', fd({ reason: 'a'.repeat(MAX_TEXT + 1) })).ok).toBe(false);
  });
  it('feedback needs a rating from 1 to 5', () => {
    for (const r of ['', '0', '6', 'x']) expect(parseActionForm('FEEDBACK', fd({ rating: r })).ok).toBe(false);
    expect(parseActionForm('FEEDBACK', fd({ rating: '5', comment: 'Great' }))).toEqual({ ok: true, value: { rating: 5, comment: 'Great' } });
    expect(parseActionForm('FEEDBACK', fd({ rating: '3' }))).toEqual({ ok: true, value: { rating: 3 } });
  });
  it('classify needs a category code and assign a technician id', () => {
    expect(parseActionForm('CLASSIFY', fd({ categoryCode: '' })).ok).toBe(false);
    expect(parseActionForm('CLASSIFY', fd({ categoryCode: 'NETWORK' }))).toEqual({ ok: true, value: { categoryCode: 'NETWORK' } });
    expect(parseActionForm('ASSIGN', fd({ technicianId: '' })).ok).toBe(false);
    expect(parseActionForm('ASSIGN', fd({ technicianId: '0' })).ok).toBe(false);
    expect(parseActionForm('ASSIGN', fd({ technicianId: '8' }))).toEqual({ ok: true, value: { technicianId: 8 } });
  });
});

describe('parseNoteForm', () => {
  it('accepts only the allowed types', () => {
    expect(parseNoteForm(fd({ type: 'INVESTIGATION', body: 'x' }), ['COMMENT']).ok).toBe(false);
    expect(parseNoteForm(fd({ type: 'COMMENT', body: 'hello' }), ['COMMENT', 'INVESTIGATION'])).toEqual({ ok: true, value: { type: 'COMMENT', body: 'hello' } });
  });
  it('uses the only allowed type when none is posted, and needs a body', () => {
    expect(parseNoteForm(fd({ body: 'hi' }), ['COMMENT'])).toEqual({ ok: true, value: { type: 'COMMENT', body: 'hi' } });
    expect(parseNoteForm(fd({ type: 'COMMENT', body: '  ' }), ['COMMENT'])).toMatchObject({ ok: false, errors: { body: expect.any(String) } });
    expect(parseNoteForm(fd({ body: 'hi' }), []).ok).toBe(false);
  });
});

describe('badges', () => {
  it('maps statuses to tones', () => {
    expect(statusBadgeClass('PENDING_INFO')).toBe('badge badge-warn');
    expect(statusBadgeClass('IN_PROGRESS')).toBe('badge badge-info');
    expect(statusBadgeClass('RESOLVED')).toBe('badge badge-success');
    expect(statusBadgeClass('CLOSED')).toBe('badge badge-neutral');
    expect(statusBadgeClass('toString')).toBe('badge badge-neutral');
    expect(statusLabel('PENDING_INFO')).toBe('Pending info');
  });
  it('maps priorities to tones', () => {
    expect(priorityBadgeClass('P1')).toBe('badge badge-danger');
    expect(priorityBadgeClass('P2')).toBe('badge badge-warn');
    expect(priorityBadgeClass('P3')).toBe('badge badge-info');
    expect(priorityBadgeClass('P4')).toBe('badge badge-neutral');
    expect(priorityBadgeClass(null)).toBe('badge badge-neutral');
    expect(priorityBadgeClass('constructor')).toBe('badge badge-neutral');
  });
});

describe('dueInfo', () => {
  const now = new Date('2026-11-18T12:00:00Z');
  it('marks a past deadline of unfinished work as overdue', () => {
    expect(dueInfo('2026-11-18T10:00:00Z', false, now)).toEqual({ text: '18 Nov 2026, 10:00 UTC', overdue: true });
  });
  it('does not mark future or finished deadlines', () => {
    expect(dueInfo('2026-11-18T14:00:00Z', false, now).overdue).toBe(false);
    expect(dueInfo('2026-11-18T10:00:00Z', true, now).overdue).toBe(false);
  });
  it('handles missing and invalid dates', () => {
    expect(dueInfo(null, false, now)).toEqual({ text: 'Not set', overdue: false });
    expect(dueInfo('garbage', false, now)).toEqual({ text: 'garbage', overdue: false });
  });
  it('knows finished statuses', () => {
    expect(['RESOLVED', 'VERIFIED', 'CLOSED', 'CANCELLED'].every(isFinished)).toBe(true);
    expect(['NEW', 'IN_PROGRESS', 'PENDING_INFO'].some(isFinished)).toBe(false);
  });
});

describe('lists', () => {
  it('only technicians get the assigned tab, which is their default', () => {
    expect(parseTab(null, 'TECHNICIAN')).toBe('assigned');
    expect(parseTab('mine', 'TECHNICIAN')).toBe('mine');
    expect(parseTab('assigned', 'EMPLOYEE')).toBe('mine');
    expect(parseTab('assigned', 'ADMIN')).toBe('mine');
  });
  it('links to the detail page', () => {
    expect(incidentHref(12)).toBe('/incidents/12');
  });
});

describe('backend length limits', () => {
  it('limits the root cause to 2000 characters', () => {
    expect(parseActionForm('RESOLVE', fd({ summary: 's', rootCause: 'a'.repeat(MAX_ROOT_CAUSE) })).ok).toBe(true);
    expect(parseActionForm('RESOLVE', fd({ summary: 's', rootCause: 'a'.repeat(MAX_ROOT_CAUSE + 1) }))).toMatchObject({ ok: false, errors: { rootCause: expect.any(String) } });
  });
  it('limits the feedback comment to 1000 characters', () => {
    expect(MAX_ROOT_CAUSE).toBe(2000);
    expect(MAX_FEEDBACK_COMMENT).toBe(1000);
    expect(parseActionForm('FEEDBACK', fd({ rating: '4', comment: 'a'.repeat(MAX_FEEDBACK_COMMENT) })).ok).toBe(true);
    expect(parseActionForm('FEEDBACK', fd({ rating: '4', comment: 'a'.repeat(MAX_FEEDBACK_COMMENT + 1) }))).toMatchObject({ ok: false, errors: { comment: expect.any(String) } });
  });
  it('never offers INFO_RESPONSE as a note type', () => {
    expect(allowedNoteTypes(['INFO_RESPONSE', 'COMMENT'])).toEqual(['COMMENT']);
    expect(parseNoteForm(fd({ type: 'INFO_RESPONSE', body: 'x' }), allowedNoteTypes(['INFO_RESPONSE', 'COMMENT'])).ok).toBe(false);
  });
});

describe('parseEditForm', () => {
  const all = ['title', 'description', 'impact', 'urgency', 'affectedUsers', 'serviceId'];
  const form = { title: 'VPN down', description: 'Cannot connect', impact: 'TEAM', urgency: 'HIGH', affectedUsers: '12', serviceId: '3' };

  it('builds a request with every editable field', () => {
    expect(parseEditForm(fd(form), all)).toEqual({
      ok: true,
      value: { title: 'VPN down', description: 'Cannot connect', impact: 'TEAM', urgency: 'HIGH', affectedUsers: 12, serviceId: 3 },
    });
  });
  it('ignores fields the viewer may not edit', () => {
    const r = parseEditForm(fd({ ...form, title: '' }), ['impact', 'urgency', 'affectedUsers', 'serviceId']);
    expect(r).toEqual({ ok: true, value: { impact: 'TEAM', urgency: 'HIGH', affectedUsers: 12, serviceId: 3 } });
  });
  it('turns a blank service into clearService', () => {
    expect(parseEditForm(fd({ serviceId: '' }), ['serviceId'])).toEqual({ ok: true, value: { clearService: true } });
  });
  it('mirrors the backend limits', () => {
    expect(parseEditForm(fd({ ...form, title: '' }), all)).toMatchObject({ ok: false, errors: { title: expect.any(String) } });
    expect(parseEditForm(fd({ ...form, title: 'a'.repeat(MAX_TITLE + 1) }), all).ok).toBe(false);
    expect(parseEditForm(fd({ ...form, description: 'a'.repeat(MAX_TEXT + 1) }), all).ok).toBe(false);
    expect(parseEditForm(fd({ ...form, impact: 'HUGE' }), all)).toMatchObject({ ok: false, errors: { impact: expect.any(String) } });
    expect(parseEditForm(fd({ ...form, urgency: '' }), all)).toMatchObject({ ok: false, errors: { urgency: expect.any(String) } });
    expect(parseEditForm(fd({ ...form, serviceId: 'abc' }), all)).toMatchObject({ ok: false, errors: { serviceId: expect.any(String) } });
  });
  it.each(['0', '-1', 'abc', '1.5', ''])('rejects affectedUsers %s', (n) => {
    expect(parseEditForm(fd({ ...form, affectedUsers: n }), all)).toMatchObject({ ok: false, errors: { affectedUsers: expect.any(String) } });
  });
  it('refuses a form when nothing is editable', () => {
    expect(parseEditForm(fd(form), []).ok).toBe(false);
  });
  it('is reached through parseActionForm for the EDIT action', () => {
    expect(parseActionForm('EDIT', fd(form), ['title'])).toEqual({ ok: true, value: { title: 'VPN down' } });
  });
  it('has an intent and is a known action', () => {
    expect(ACTION_INTENT.EDIT).toBe('edit');
    expect(visibleActions(['EDIT', 'bogus'])).toEqual(['EDIT']);
  });
});
