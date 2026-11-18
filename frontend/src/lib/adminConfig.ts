// Pure helpers for /admin/sla, /admin/services and the skill picker of the user pages.
import type { CreateSlaPolicyRequest, CreateServiceRequest, SlaPolicy, UpdateSlaPolicyRequest } from './adminTypes';
import { formString, optional, parseIntField } from './forms';
import type { CategorySummary, UpdateServiceRequest } from './incidentTypes';
import type { Option, ParseResult } from './incidents';
import type { Priority } from './p4Types';

export const SLA_PRIORITIES: readonly Priority[] = ['P1', 'P2', 'P3', 'P4'];
export const PRIORITY_OPTIONS: Option[] = SLA_PRIORITIES.map((p) => ({ value: p, label: p }));

export const MAX_SLA_MINUTES = 525_600;
export const DEFAULT_WARNING_PCT = 75;

/** 90 -> "1 h 30 min", 1440 -> "1 d", 45 -> "45 min". */
export function formatMinutes(minutes: number): string {
  if (!Number.isFinite(minutes) || minutes < 0) return String(minutes);
  const d = Math.floor(minutes / 1440);
  const h = Math.floor((minutes % 1440) / 60);
  const m = minutes % 60;
  const parts = [d > 0 ? `${d} d` : '', h > 0 ? `${h} h` : '', m > 0 || minutes === 0 ? `${m} min` : ''].filter(Boolean);
  return parts.join(' ');
}

export interface SlaGroup {
  serviceId: number;
  serviceName: string;
  serviceCode: string;
  policies: SlaPolicy[];
}

/** Defaults ordered P1 to P4, and the service overrides grouped by service (by name), each group ordered by priority. */
export function groupSlaPolicies(policies: readonly SlaPolicy[]): { defaults: SlaPolicy[]; groups: SlaGroup[] } {
  const rank = (p: Priority) => SLA_PRIORITIES.indexOf(p);
  const defaults = policies.filter((p) => p.isDefault).sort((a, b) => rank(a.priority) - rank(b.priority));
  const byService = new Map<number, SlaGroup>();
  for (const p of policies) {
    if (p.isDefault || p.serviceId === null) continue;
    const group = byService.get(p.serviceId) ?? { serviceId: p.serviceId, serviceName: p.serviceName ?? `Service ${p.serviceId}`, serviceCode: p.serviceCode ?? '', policies: [] };
    group.policies.push(p);
    byService.set(p.serviceId, group);
  }
  const groups = [...byService.values()].sort((a, b) => a.serviceName.localeCompare(b.serviceName));
  for (const g of groups) g.policies.sort((a, b) => rank(a.priority) - rank(b.priority));
  return { defaults, groups };
}

const WHOLE_MINUTES = { min: 1, max: MAX_SLA_MINUTES };

/** The times and warning threshold of an SLA form; the warning is optional on create (blank = default). */
export function parseSlaTimes(form: FormData, warningRequired: boolean): ParseResult<UpdateSlaPolicyRequest> {
  const errors: Record<string, string> = {};
  const response = parseIntField(formString(form, 'responseMinutes'), WHOLE_MINUTES);
  const resolution = parseIntField(formString(form, 'resolutionMinutes'), WHOLE_MINUTES);
  const warning = parseIntField(formString(form, 'warningThresholdPct'), { min: 1, max: 99 });
  if (response.value === undefined) errors.responseMinutes = response.error ?? 'Enter the response time in minutes.';
  if (resolution.value === undefined) errors.resolutionMinutes = resolution.error ?? 'Enter the resolution time in minutes.';
  if (warning.error) errors.warningThresholdPct = 'Enter a whole number between 1 and 99.';
  else if (warning.value === undefined && warningRequired) errors.warningThresholdPct = 'Enter a warning threshold between 1 and 99.';
  if (!errors.responseMinutes && !errors.resolutionMinutes && response.value! > resolution.value!) {
    errors.resolutionMinutes = 'The resolution time must not be shorter than the response time.';
  }
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  const value: UpdateSlaPolicyRequest = { responseMinutes: response.value!, resolutionMinutes: resolution.value! };
  if (warning.value !== undefined) value.warningThresholdPct = warning.value;
  return { ok: true, value };
}

/** Form of "add override": a service, a priority and the times. */
export function parseCreateSlaPolicy(form: FormData): ParseResult<CreateSlaPolicyRequest> {
  const times = parseSlaTimes(form, false);
  const errors: Record<string, string> = times.ok ? {} : { ...times.errors };
  const serviceId = parseIntField(formString(form, 'serviceId'), { min: 1 }).value;
  if (serviceId === undefined) errors.serviceId = 'Choose a service.';
  const priority = formString(form, 'priority');
  if (!(SLA_PRIORITIES as readonly string[]).includes(priority)) errors.priority = 'Choose a priority.';
  if (Object.keys(errors).length > 0 || !times.ok) return { ok: false, errors };
  return { ok: true, value: { ...times.value, serviceId: serviceId!, priority: priority as Priority } as CreateSlaPolicyRequest };
}

// ---------------------------------------------------------------- services

export const MAX_SERVICE_NAME = 100;
export const MAX_SERVICE_DESCRIPTION = 500;
export const MAX_SERVICE_TEAM = 100;
const SERVICE_CODE = /^[A-Z][A-Z0-9_]{0,49}$/;

/** "vpn " -> "VPN": service codes are stored upper-case. */
export function normalizeServiceCode(input: string): string {
  return input.trim().toUpperCase();
}

function serviceTextErrors(name: string, description: string, ownerTeam: string, errors: Record<string, string>): void {
  if (!name) errors.name = 'Enter a name.';
  else if (name.length > MAX_SERVICE_NAME) errors.name = `The name can have at most ${MAX_SERVICE_NAME} characters.`;
  if (description.length > MAX_SERVICE_DESCRIPTION) errors.description = `The description can have at most ${MAX_SERVICE_DESCRIPTION} characters.`;
  if (ownerTeam.length > MAX_SERVICE_TEAM) errors.ownerTeam = `The owner team can have at most ${MAX_SERVICE_TEAM} characters.`;
}

function criticalityOf(form: FormData, errors: Record<string, string>): number | undefined {
  const c = parseIntField(formString(form, 'criticality'), { min: 1, max: 4 }).value;
  if (c === undefined) errors.criticality = 'Choose a criticality from 1 to 4.';
  return c;
}

export function parseCreateService(form: FormData): ParseResult<CreateServiceRequest> {
  const errors: Record<string, string> = {};
  const code = normalizeServiceCode(formString(form, 'code'));
  if (!SERVICE_CODE.test(code)) errors.code = 'Use capital letters, digits and underscores, starting with a letter (up to 50 characters).';
  const name = formString(form, 'name');
  const description = formString(form, 'description');
  const ownerTeam = formString(form, 'ownerTeam');
  serviceTextErrors(name, description, ownerTeam, errors);
  const criticality = criticalityOf(form, errors);
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  const value: CreateServiceRequest = { code, name, criticality: criticality! };
  const d = optional(description);
  if (d !== undefined) value.description = d;
  const t = optional(ownerTeam);
  if (t !== undefined) value.ownerTeam = t;
  return { ok: true, value };
}

/** Edit form; a blank description or owner team is sent as "" so it is cleared. */
export function parseEditService(form: FormData): ParseResult<{ id: number; request: UpdateServiceRequest }> {
  const errors: Record<string, string> = {};
  const id = parseIntField(formString(form, 'serviceId'), { min: 1 }).value;
  const name = formString(form, 'name');
  const description = formString(form, 'description');
  const ownerTeam = formString(form, 'ownerTeam');
  serviceTextErrors(name, description, ownerTeam, errors);
  const criticality = criticalityOf(form, errors);
  if (id === undefined) errors.name = errors.name ?? 'Invalid request.';
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, value: { id: id!, request: { name, description, ownerTeam, criticality: criticality! } } };
}

// ---------------------------------------------------------------- skills

/**
 * Checkbox choices for a technician's skills: the active categories, plus any selected code that is not (or no longer)
 * an active category, so saving the form never drops a skill by accident. Sorted by label.
 */
export function skillChoices(categories: readonly CategorySummary[], selected: readonly string[]): { value: string; label: string; checked: boolean }[] {
  const chosen = new Set(selected.map((s) => s.trim().toUpperCase()).filter(Boolean));
  const known = new Set(categories.map((c) => c.code));
  const rows = categories.map((c) => ({ value: c.code, label: `${c.name} (${c.code})`, checked: chosen.has(c.code) }));
  for (const code of chosen) if (!known.has(code)) rows.push({ value: code, label: `${code} (not an active category)`, checked: true });
  return rows.sort((a, b) => a.label.localeCompare(b.label));
}

/** The skills of a form as the comma-separated string the user forms use: checked boxes (`skill`) plus the text field (`skills`). */
export function skillsFromForm(form: FormData): string {
  const picked = form.getAll('skill').filter((v): v is string => typeof v === 'string');
  const typed = formString(form, 'skills');
  return [...picked, ...(typed ? [typed] : [])].join(',');
}
