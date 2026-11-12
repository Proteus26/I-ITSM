import { ApiError, SERVER_ERROR_MESSAGE } from './errors';
import { ROLES, TECHNICIAN_LEVELS, type Role, type TechnicianLevel } from './types';

/** A form field as a string. Trimmed unless `trim` is false (use that for passwords). Missing -> "". */
export function formString(form: FormData, name: string, trim = true): string {
  const v = form.get(name);
  if (typeof v !== 'string') return '';
  return trim ? v.trim() : v;
}

/** Empty string -> undefined, so optional fields can simply be left out of a request. */
export function optional(value: string): string | undefined {
  return value === '' ? undefined : value;
}

/** "a, b ,a,,c" -> ["a","b","c"] (trimmed, de-duplicated, order kept). */
export function parseSkills(input: string): string[] {
  return [...new Set(input.split(/[,\n]/).map((s) => s.trim()).filter(Boolean))];
}

export const INT32_MIN = -2147483648;
export const INT32_MAX = 2147483647;

export interface IntBounds {
  min?: number;
  max?: number;
}

/** Backend rule for maxActiveIncidents: at least 1, and an Int (no other upper limit). */
export const CAPACITY_BOUNDS: Required<IntBounds> = { min: 1, max: INT32_MAX };
/** Page index cap for list pages, well below anything that could overflow offset arithmetic. */
export const PAGE_BOUNDS: Required<IntBounds> = { min: 0, max: 1_000_000 };

/** Parses an integer within the bounds (and always within Int32); blank, invalid or out of range -> undefined. */
export function parseOptionalInt(input: string, bounds: IntBounds = {}): number | undefined {
  const text = input.trim();
  if (!/^[+-]?\d{1,16}$/.test(text)) return undefined;
  const n = Number(text);
  if (!Number.isSafeInteger(n)) return undefined;
  const min = Math.max(bounds.min ?? INT32_MIN, INT32_MIN);
  const max = Math.min(bounds.max ?? INT32_MAX, INT32_MAX);
  return n >= min && n <= max ? n : undefined;
}

/** Like {@link parseOptionalInt}, but tells a blank input (fine, no value) from an invalid one (error message). */
export function parseIntField(input: string, bounds: IntBounds = {}): { value?: number; error?: string } {
  if (input.trim() === '') return {};
  const value = parseOptionalInt(input, bounds);
  if (value !== undefined) return { value };
  const min = Math.max(bounds.min ?? INT32_MIN, INT32_MIN);
  const max = Math.min(bounds.max ?? INT32_MAX, INT32_MAX);
  return { error: `Enter a whole number between ${min} and ${max}.` };
}

export function parseRole(input: string): Role | undefined {
  return (ROLES as readonly string[]).includes(input) ? (input as Role) : undefined;
}

export function parseLevel(input: string): TechnicianLevel | undefined {
  return (TECHNICIAN_LEVELS as readonly string[]).includes(input) ? (input as TechnicianLevel) : undefined;
}

/** Message for the confirm field, or undefined when the two passwords match. */
export function confirmMismatch(password: string, confirm: string): string | undefined {
  return password === confirm ? undefined : 'Passwords do not match.';
}

export function formatDate(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toISOString().slice(0, 10);
}

export function roleLabel(role: Role): string {
  return { EMPLOYEE: 'Employee', TECHNICIAN: 'Technician', ADMIN: 'Administrator' }[role];
}

export function levelLabel(level: TechnicianLevel): string {
  return { L1: 'L1', L2: 'L2', TEAM_LEAD: 'Team lead' }[level];
}

export interface FormErrorState {
  /** Summary for the Alert at the top of the form. */
  message: string;
  fieldErrors: Record<string, string>;
}

/**
 * Turns a failed backend call into what a form page shows: per-field errors, and a summary message.
 * A 409 about an email goes to the `email` field; 5xx and unreachable show a generic message.
 */
export function formErrorState(e: ApiError): FormErrorState {
  const fieldErrors: Record<string, string> = { ...e.fieldErrors };
  if (e.status === 409 && /email/i.test(e.detail) && !fieldErrors.email) fieldErrors.email = e.detail;
  if (Object.keys(fieldErrors).length > 0) return { message: 'Please fix the highlighted fields.', fieldErrors };
  return { message: e.isServerError ? SERVER_ERROR_MESSAGE : e.detail, fieldErrors };
}
