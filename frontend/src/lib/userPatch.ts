import { CAPACITY_BOUNDS, parseIntField, parseLevel, parseSkills } from './forms';
import type { UpdateUserRequest, UserResponse } from './types';

/** The edit form, as strings (what the admin typed). */
export interface UserForm {
  fullName: string;
  email: string;
  department: string;
  level: string;
  maxActiveIncidents: string;
  available: boolean;
  skills: string;
}

/** Skill codes are compared and sent uppercased, like the backend stores them. */
const normalizeSkills = (skills: readonly string[]): string[] => [...new Set(skills.map((s) => s.trim().toUpperCase()).filter(Boolean))];

/**
 * Compares the edit form with the loaded user and returns a PATCH body with only what changed,
 * plus field errors for invalid input. Fields that do not apply to the user's role are never sent.
 * A cleared department is sent as "" (the backend clears it).
 */
export function buildUserPatch(user: UserResponse, form: UserForm): { patch: UpdateUserRequest; fieldErrors: Record<string, string> } {
  const patch: UpdateUserRequest = {};
  const fieldErrors: Record<string, string> = {};

  if (form.fullName !== user.fullName) patch.fullName = form.fullName;
  if (form.email !== user.email) patch.email = form.email;
  if (user.role === 'EMPLOYEE' && form.department !== (user.department ?? '')) patch.department = form.department;

  const t = user.technician;
  if (user.role === 'TECHNICIAN' && t) {
    const level = parseLevel(form.level);
    if (!level) fieldErrors.level = 'Choose a level.';
    else if (level !== t.level) patch.level = level;

    const max = parseIntField(form.maxActiveIncidents, CAPACITY_BOUNDS);
    if (max.error) fieldErrors.maxActiveIncidents = max.error;
    else if (max.value !== undefined && max.value !== t.maxActiveIncidents) patch.maxActiveIncidents = max.value;

    if (form.available !== t.available) patch.available = form.available;

    const wanted = normalizeSkills(parseSkills(form.skills));
    const current = new Set(normalizeSkills(t.skills));
    if (wanted.length !== current.size || wanted.some((s) => !current.has(s))) patch.skills = wanted;
  }
  return { patch, fieldErrors };
}
