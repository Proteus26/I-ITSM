import { describe, expect, it } from 'vitest';
import {
  AUDIT_ACTIONS,
  cleanAuditQuery,
  cleanSearchQuery,
  humanize,
  safeInternalLink,
  toQueryString,
} from '../src/lib/p4';

describe('safeInternalLink', () => {
  it.each(['/incidents/12', '/notifications?page=2', '/search?q=a%20b#top', '/'])('keeps %s', (l) => {
    expect(safeInternalLink(l)).toBe(l);
  });

  it.each([
    '//evil.com',
    '//evil.com/x',
    '/\\evil.com',
    '/\\/evil.com',
    '/\t/evil.com',
    '/\n/evil.com',
    '/ /evil.com',
    '\\\\evil.com',
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    ' /incidents/1',
    'https://evil.com',
    'http://evil.com/incidents/1',
    'incidents/1',
    'data:text/html,x',
    '',
  ])('rejects %j', (l) => {
    expect(safeInternalLink(l)).toBeNull();
  });

  it('rejects null, undefined and non-strings', () => {
    expect(safeInternalLink(null)).toBeNull();
    expect(safeInternalLink(undefined)).toBeNull();
    expect(safeInternalLink(5 as unknown as string)).toBeNull();
  });
});

describe('toQueryString', () => {
  it('leaves out empty values and encodes the rest', () => {
    expect(toQueryString({ a: 1, b: '', c: undefined, d: null, e: 'x y&z', f: false })).toBe('a=1&e=x+y%26z&f=false');
  });
  it('appends the page, including page 0', () => {
    expect(toQueryString({}, 0)).toBe('page=0');
    expect(toQueryString({ q: 'a' }, 3)).toBe('q=a&page=3');
  });
  it('is empty for nothing', () => {
    expect(toQueryString({})).toBe('');
  });
});

describe('humanize', () => {
  it('turns codes into sentence case', () => {
    expect(humanize('STATUS_CHANGED')).toBe('Status changed');
    expect(humanize('NEW')).toBe('New');
  });
});

describe('cleanSearchQuery', () => {
  const q = (s: string) => cleanSearchQuery(new URLSearchParams(s));

  it('keeps valid values', () => {
    expect(q('number=INC-1&status=NEW&priority=P1&service=3&from=2026-01-02&slaStatus=BREACHED&sort=createdAt,asc')).toMatchObject({
      number: 'INC-1',
      status: 'NEW',
      priority: 'P1',
      service: 3,
      from: '2026-01-02',
      slaStatus: 'BREACHED',
      sort: 'createdAt,asc',
    });
  });

  it('drops invalid values', () => {
    const r = q('status=HACKED&priority=P9&service=-1&assignee=abc&reporter=0&from=yesterday&sort=password,asc&q=%20%20');
    expect(r).toEqual({
      number: undefined, q: undefined, from: undefined, to: undefined, status: undefined, priority: undefined,
      category: undefined, service: undefined, assignee: undefined, reporter: undefined, slaStatus: undefined, sort: undefined,
    });
  });

  it('trims and caps text', () => {
    expect(q(`q=${'a'.repeat(500)}`).q).toHaveLength(200);
    expect(q('number=%20INC-2%20').number).toBe('INC-2');
  });
});

describe('cleanAuditQuery', () => {
  it('accepts only known actions and positive ids', () => {
    expect(cleanAuditQuery(new URLSearchParams(`action=${AUDIT_ACTIONS[0]}&actor=4&incident=9`))).toMatchObject({
      action: AUDIT_ACTIONS[0],
      actor: 4,
      incident: 9,
    });
    const bad = cleanAuditQuery(new URLSearchParams('action=DROP_TABLE&actor=1e3&incident=99999999999999999999&to=2026-1-1'));
    expect(Object.values(bad).every((v) => v === undefined)).toBe(true);
  });
});
