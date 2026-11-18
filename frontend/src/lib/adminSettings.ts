// Pure helpers for /admin/categories and /admin/settings.
import { formString, optional, parseIntField } from './forms';
import type { CreateCategoryRequest, SettingItem, UpdateCategoryRequest } from './incidentTypes';
import { CATEGORY_CODE, type Option, type ParseResult } from './incidents';

export const MAX_CATEGORY_NAME = 100;
export const MAX_CATEGORY_DESCRIPTION = 500;

/** "network " -> "NETWORK": codes are stored upper-case. */
export function normalizeCategoryCode(input: string): string {
  return input.trim().toUpperCase();
}

export function parseCreateCategory(form: FormData): ParseResult<CreateCategoryRequest> {
  const errors: Record<string, string> = {};
  const code = normalizeCategoryCode(formString(form, 'code'));
  if (!CATEGORY_CODE.test(code)) errors.code = 'Use capital letters, digits and underscores, starting with a letter (up to 50 characters).';
  const name = formString(form, 'name');
  if (!name) errors.name = 'Enter a name.';
  else if (name.length > MAX_CATEGORY_NAME) errors.name = `The name can have at most ${MAX_CATEGORY_NAME} characters.`;
  const description = formString(form, 'description');
  if (description.length > MAX_CATEGORY_DESCRIPTION) errors.description = `The description can have at most ${MAX_CATEGORY_DESCRIPTION} characters.`;
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  const value: CreateCategoryRequest = { code, name };
  const d = optional(description);
  if (d !== undefined) value.description = d;
  return { ok: true, value };
}

/** Rename/describe form. A blank description is sent as "" so it can be cleared. */
export function parseEditCategory(form: FormData): ParseResult<UpdateCategoryRequest> {
  const errors: Record<string, string> = {};
  const name = formString(form, 'name');
  if (!name) errors.name = 'Enter a name.';
  else if (name.length > MAX_CATEGORY_NAME) errors.name = `The name can have at most ${MAX_CATEGORY_NAME} characters.`;
  const description = formString(form, 'description');
  if (description.length > MAX_CATEGORY_DESCRIPTION) errors.description = `The description can have at most ${MAX_CATEGORY_DESCRIPTION} characters.`;
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, value: { name, description } };
}

// ---------------------------------------------------------------- settings

export const PRIORITY_STRATEGY_KEY = 'priority.strategy';
export const ASSIGNMENT_STRATEGY_KEY = 'assignment.strategy';

export interface SettingDef {
  key: string;
  field: string;
  label: string;
  hint: string;
  options: Option[];
}

export const SETTING_DEFS: readonly SettingDef[] = [
  {
    key: PRIORITY_STRATEGY_KEY,
    field: 'priorityStrategy',
    label: 'Priority strategy',
    hint: 'How the priority (P1 to P4) of a new incident is calculated.',
    options: [
      { value: 'weighted', label: 'Weighted score (impact, urgency, service, affected users)' },
      { value: 'matrix', label: 'Impact and urgency matrix' },
    ],
  },
  {
    key: ASSIGNMENT_STRATEGY_KEY,
    field: 'assignmentStrategy',
    label: 'Assignment strategy',
    hint: 'How a technician is chosen automatically.',
    options: [
      { value: 'skill-least-loaded', label: 'Matching skill, least loaded' },
      { value: 'round-robin', label: 'Round robin' },
    ],
  },
];

/** The stored value of a setting, or undefined when the backend did not list it. */
export function settingValue(items: readonly SettingItem[], key: string): string | undefined {
  return items.find((i) => i.key === key)?.value;
}

/** The options of a setting, plus the current value when it is not a known one (so the form never hides it). */
export function optionsFor(def: SettingDef, current: string | undefined): Option[] {
  if (current === undefined || def.options.some((o) => o.value === current)) return def.options;
  return [...def.options, { value: current, label: current }];
}

/**
 * Reads the settings form and returns only the changes (`updates`), so an untouched setting is not written.
 * A value that is not one of the known options is an error.
 */
export function parseSettingsForm(form: FormData, current: readonly SettingItem[]): ParseResult<{ key: string; value: string }[]> {
  const errors: Record<string, string> = {};
  const updates: { key: string; value: string }[] = [];
  for (const def of SETTING_DEFS) {
    const value = formString(form, def.field);
    if (!def.options.some((o) => o.value === value)) {
      errors[def.field] = 'Choose one of the available strategies.';
    } else if (value !== settingValue(current, def.key)) {
      updates.push({ key: def.key, value });
    }
  }
  return Object.keys(errors).length > 0 ? { ok: false, errors } : { ok: true, value: updates };
}

export const CRITICALITY_OPTIONS: Option[] = [
  { value: '1', label: '1 (lowest)' },
  { value: '2', label: '2' },
  { value: '3', label: '3' },
  { value: '4', label: '4 (highest)' },
];

/** The service edit form: `{ id, criticality }` or an error. */
export function parseServiceCriticality(form: FormData): ParseResult<{ id: number; criticality: number }> {
  const id = parseIntField(formString(form, 'serviceId'), { min: 1 }).value;
  const criticality = parseIntField(formString(form, 'criticality'), { min: 1, max: 4 }).value;
  if (id === undefined || criticality === undefined) return { ok: false, errors: { criticality: 'Choose a criticality from 1 to 4.' } };
  return { ok: true, value: { id, criticality } };
}
