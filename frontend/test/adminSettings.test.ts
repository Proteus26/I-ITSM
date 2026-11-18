import { describe, expect, it } from 'vitest';
import {
  normalizeCategoryCode,
  optionsFor,
  parseCreateCategory,
  parseEditCategory,
  parseServiceCriticality,
  parseSettingsForm,
  SETTING_DEFS,
  settingFieldErrors,
  settingValue,
} from '../src/lib/adminSettings';

const fd = (o: Record<string, string>) => {
  const f = new FormData();
  for (const [k, v] of Object.entries(o)) f.set(k, v);
  return f;
};
const current = [
  { key: 'priority.strategy', value: 'weighted' },
  { key: 'assignment.strategy', value: 'skill-least-loaded' },
];

describe('categories', () => {
  it('upper-cases the code', () => {
    expect(normalizeCategoryCode(' network ')).toBe('NETWORK');
  });
  it('validates a new category', () => {
    expect(parseCreateCategory(fd({ code: 'access_mgmt', name: 'Access', description: ' ' }))).toEqual({ ok: true, value: { code: 'ACCESS_MGMT', name: 'Access' } });
    expect(parseCreateCategory(fd({ code: 'a b', name: '' }))).toMatchObject({ ok: false, errors: { code: expect.any(String), name: expect.any(String) } });
    expect(parseCreateCategory(fd({ code: 'NET', name: 'n', description: 'x'.repeat(501) })).ok).toBe(false);
    expect(parseCreateCategory(fd({ code: 'NET', name: 'n'.repeat(101) })).ok).toBe(false);
  });
  it('validates an edit and sends a blank description to clear it', () => {
    expect(parseEditCategory(fd({ name: 'Net', description: '' }))).toEqual({ ok: true, value: { name: 'Net', description: '' } });
    expect(parseEditCategory(fd({ name: ' ' })).ok).toBe(false);
  });
});

describe('settings', () => {
  it('reads a stored value', () => {
    expect(settingValue(current, 'priority.strategy')).toBe('weighted');
    expect(settingValue(current, 'nope')).toBeUndefined();
  });
  it('sends only what changed', () => {
    expect(parseSettingsForm(fd({ priorityStrategy: 'weighted', assignmentStrategy: 'skill-least-loaded' }), current)).toEqual({ ok: true, value: [] });
    expect(parseSettingsForm(fd({ priorityStrategy: 'matrix', assignmentStrategy: 'round-robin' }), current)).toEqual({
      ok: true,
      value: [{ key: 'priority.strategy', value: 'matrix' }, { key: 'assignment.strategy', value: 'round-robin' }],
    });
  });
  it('rejects unknown strategies', () => {
    expect(parseSettingsForm(fd({ priorityStrategy: 'magic', assignmentStrategy: 'round-robin' }), current)).toMatchObject({
      ok: false,
      errors: { priorityStrategy: expect.any(String) },
    });
    expect(parseSettingsForm(fd({}), current).ok).toBe(false);
  });
  it('keeps an unknown current value selectable', () => {
    const def = SETTING_DEFS[0]!;
    expect(optionsFor(def, 'weighted')).toBe(def.options);
    expect(optionsFor(def, 'legacy').map((o) => o.value)).toEqual(['weighted', 'matrix', 'legacy']);
  });
  it('parses the service criticality form', () => {
    expect(parseServiceCriticality(fd({ serviceId: '4', criticality: '3' }))).toEqual({ ok: true, value: { id: 4, criticality: 3 } });
    for (const c of ['0', '5', '', 'x']) expect(parseServiceCriticality(fd({ serviceId: '4', criticality: c })).ok).toBe(false);
    expect(parseServiceCriticality(fd({ criticality: '2' })).ok).toBe(false);
  });
});

describe('settingFieldErrors', () => {
  it('maps a backend value error to the setting field', () => {
    const def = SETTING_DEFS[0]!;
    expect(settingFieldErrors(def, { value: 'Unknown strategy' })).toEqual({ [def.field]: 'Unknown strategy' });
    expect(settingFieldErrors(def, { other: 'x' })).toEqual({ other: 'x' });
  });
});
