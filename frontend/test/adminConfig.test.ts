import { describe, expect, it } from 'vitest';
import {
  formatMinutes,
  groupSlaPolicies,
  normalizeServiceCode,
  parseCreateService,
  parseCreateSlaPolicy,
  parseEditService,
  parseSlaTimes,
  skillChoices,
  skillsFromForm,
} from '../src/lib/adminConfig';
import type { SlaPolicy } from '../src/lib/adminTypes';

const fd = (o: Record<string, string | string[]>) => {
  const f = new FormData();
  for (const [k, v] of Object.entries(o)) for (const x of Array.isArray(v) ? v : [v]) f.append(k, x);
  return f;
};
const policy = (id: number, priority: SlaPolicy['priority'], serviceId: number | null, serviceName: string | null): SlaPolicy => ({
  id, priority, serviceId, serviceName, serviceCode: serviceName?.toUpperCase() ?? null,
  responseMinutes: 5, resolutionMinutes: 30, warningThresholdPct: 75, isDefault: serviceId === null,
});

describe('formatMinutes', () => {
  it('formats days, hours and minutes', () => {
    expect(formatMinutes(0)).toBe('0 min');
    expect(formatMinutes(45)).toBe('45 min');
    expect(formatMinutes(90)).toBe('1 h 30 min');
    expect(formatMinutes(1440)).toBe('1 d');
    expect(formatMinutes(1500)).toBe('1 d 1 h');
  });
});

describe('groupSlaPolicies', () => {
  it('orders defaults by priority and groups overrides by service name', () => {
    const { defaults, groups } = groupSlaPolicies([
      policy(1, 'P3', null, null), policy(2, 'P1', null, null),
      policy(3, 'P2', 8, 'Vpn'), policy(4, 'P1', 8, 'Vpn'), policy(5, 'P4', 7, 'Email'),
    ]);
    expect(defaults.map((p) => p.priority)).toEqual(['P1', 'P3']);
    expect(groups.map((g) => g.serviceName)).toEqual(['Email', 'Vpn']);
    expect(groups[1]!.policies.map((p) => p.priority)).toEqual(['P1', 'P2']);
  });
});

describe('parseSlaTimes', () => {
  it('accepts valid times, with and without a warning', () => {
    expect(parseSlaTimes(fd({ responseMinutes: '5', resolutionMinutes: '30', warningThresholdPct: '80' }), true)).toEqual({
      ok: true, value: { responseMinutes: 5, resolutionMinutes: 30, warningThresholdPct: 80 },
    });
    expect(parseSlaTimes(fd({ responseMinutes: '5', resolutionMinutes: '5' }), false)).toEqual({ ok: true, value: { responseMinutes: 5, resolutionMinutes: 5 } });
  });
  it('rejects blank, zero, reversed and out of range values', () => {
    expect(parseSlaTimes(fd({ responseMinutes: '', resolutionMinutes: '30' }), false)).toMatchObject({ ok: false, errors: { responseMinutes: expect.any(String) } });
    expect(parseSlaTimes(fd({ responseMinutes: '0', resolutionMinutes: '30' }), false)).toMatchObject({ ok: false, errors: { responseMinutes: expect.any(String) } });
    expect(parseSlaTimes(fd({ responseMinutes: '40', resolutionMinutes: '30' }), false)).toMatchObject({ ok: false, errors: { resolutionMinutes: expect.any(String) } });
    expect(parseSlaTimes(fd({ responseMinutes: '5', resolutionMinutes: '30', warningThresholdPct: '100' }), false)).toMatchObject({ ok: false, errors: { warningThresholdPct: expect.any(String) } });
    expect(parseSlaTimes(fd({ responseMinutes: '5', resolutionMinutes: '30' }), true)).toMatchObject({ ok: false, errors: { warningThresholdPct: expect.any(String) } });
  });
});

describe('parseCreateSlaPolicy', () => {
  it('needs a service and a known priority', () => {
    expect(parseCreateSlaPolicy(fd({ serviceId: '3', priority: 'P2', responseMinutes: '10', resolutionMinutes: '60' }))).toEqual({
      ok: true, value: { serviceId: 3, priority: 'P2', responseMinutes: 10, resolutionMinutes: 60 },
    });
    expect(parseCreateSlaPolicy(fd({ serviceId: '', priority: 'P9', responseMinutes: '10', resolutionMinutes: '60' }))).toMatchObject({
      ok: false, errors: { serviceId: expect.any(String), priority: expect.any(String) },
    });
  });
});

describe('services', () => {
  it('upper-cases the code', () => expect(normalizeServiceCode(' vpn ')).toBe('VPN'));
  it('validates a new service', () => {
    expect(parseCreateService(fd({ code: 'my_svc', name: ' My ', criticality: '3', ownerTeam: ' ', description: '' }))).toEqual({
      ok: true, value: { code: 'MY_SVC', name: 'My', criticality: 3 },
    });
    expect(parseCreateService(fd({ code: '1x', name: '', criticality: '9' }))).toMatchObject({
      ok: false, errors: { code: expect.any(String), name: expect.any(String), criticality: expect.any(String) },
    });
  });
  it('sends blank optional fields as empty strings when editing so they are cleared', () => {
    expect(parseEditService(fd({ serviceId: '4', name: 'X', criticality: '2', description: '', ownerTeam: '' }))).toEqual({
      ok: true, value: { id: 4, request: { name: 'X', description: '', ownerTeam: '', criticality: 2 } },
    });
    expect(parseEditService(fd({ serviceId: 'x', name: 'X', criticality: '2' })).ok).toBe(false);
  });
});

describe('skills', () => {
  const cats = [{ code: 'NETWORK', name: 'Network', description: null }, { code: 'HARDWARE', name: 'Hardware', description: null }];
  it('lists active categories and keeps selected unknown codes', () => {
    const rows = skillChoices(cats, ['network', 'LEGACY']);
    expect(rows.map((r) => [r.value, r.checked])).toEqual([['HARDWARE', false], ['LEGACY', true], ['NETWORK', true]]);
  });
  it('joins checked boxes and the fallback text field', () => {
    expect(skillsFromForm(fd({ skill: ['NETWORK', 'HARDWARE'] }))).toBe('NETWORK,HARDWARE');
    expect(skillsFromForm(fd({ skills: 'a, b' }))).toBe('a, b');
    expect(skillsFromForm(fd({}))).toBe('');
  });
});
