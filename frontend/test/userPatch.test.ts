import { describe, expect, it } from 'vitest';
import { buildUserPatch, type UserForm } from '../src/lib/userPatch';
import type { UserResponse } from '../src/lib/types';

const employee: UserResponse = {
  id: 2, email: 'e@x.io', fullName: 'Eve', role: 'EMPLOYEE', active: true, createdAt: '2025-01-01T00:00:00Z', department: 'Sales', technician: null,
};
const tech: UserResponse = {
  id: 3, email: 't@x.io', fullName: 'Tom', role: 'TECHNICIAN', active: true, createdAt: '2025-01-01T00:00:00Z', department: null,
  technician: { level: 'L2', maxActiveIncidents: 5, available: true, skills: ['NETWORK', 'VPN'] },
};
const formOf = (u: UserResponse, over: Partial<UserForm> = {}): UserForm => ({
  fullName: u.fullName, email: u.email, department: u.department ?? '', level: u.technician?.level ?? 'L1',
  maxActiveIncidents: String(u.technician?.maxActiveIncidents ?? ''), available: u.technician?.available ?? false,
  skills: (u.technician?.skills ?? []).join(', '), ...over,
});

describe('buildUserPatch', () => {
  it('sends nothing when nothing changed', () => {
    expect(buildUserPatch(employee, formOf(employee))).toEqual({ patch: {}, fieldErrors: {} });
    expect(buildUserPatch(tech, formOf(tech))).toEqual({ patch: {}, fieldErrors: {} });
  });

  it('sends only changed fields', () => {
    expect(buildUserPatch(employee, formOf(employee, { fullName: 'Eve B' })).patch).toEqual({ fullName: 'Eve B' });
    expect(buildUserPatch(tech, formOf(tech, { email: 'new@x.io', available: false })).patch).toEqual({ email: 'new@x.io', available: false });
  });

  it('never sends role-inapplicable fields', () => {
    const emp = buildUserPatch(employee, formOf(employee, { level: 'L1', maxActiveIncidents: '9', available: true, skills: 'X' }));
    expect(emp.patch).toEqual({});
    const t = buildUserPatch(tech, formOf(tech, { department: 'Ops' }));
    expect(t.patch).toEqual({});
    const admin = buildUserPatch({ ...employee, role: 'ADMIN', department: null }, formOf(employee, { department: 'Ops' }));
    expect(admin.patch).toEqual({});
  });

  it('sends "" for a cleared department', () => {
    expect(buildUserPatch(employee, formOf(employee, { department: '' })).patch).toEqual({ department: '' });
  });

  it('does not send an empty department that was already empty', () => {
    expect(buildUserPatch({ ...employee, department: null }, formOf(employee, { department: '' })).patch).toEqual({});
  });

  it('compares skills case-insensitively and sends them uppercased', () => {
    expect(buildUserPatch(tech, formOf(tech, { skills: 'network, vpn' })).patch).toEqual({});
    expect(buildUserPatch(tech, formOf(tech, { skills: 'vpn, network' })).patch).toEqual({});
    expect(buildUserPatch(tech, formOf(tech, { skills: 'network, printers' })).patch).toEqual({ skills: ['NETWORK', 'PRINTERS'] });
    expect(buildUserPatch(tech, formOf(tech, { skills: '' })).patch).toEqual({ skills: [] });
  });

  it('changes level and capacity', () => {
    expect(buildUserPatch(tech, formOf(tech, { level: 'TEAM_LEAD', maxActiveIncidents: '8' })).patch).toEqual({ level: 'TEAM_LEAD', maxActiveIncidents: 8 });
  });

  it('reports invalid numbers and levels as field errors', () => {
    for (const bad of ['abc', '0', '1.5', '99999999999', '-2']) {
      const r = buildUserPatch(tech, formOf(tech, { maxActiveIncidents: bad }));
      expect(r.fieldErrors.maxActiveIncidents).toBeTruthy();
      expect(r.patch.maxActiveIncidents).toBeUndefined();
    }
    expect(buildUserPatch(tech, formOf(tech, { level: 'GOD' })).fieldErrors.level).toBeTruthy();
  });

  it('treats a blank capacity as unchanged', () => {
    expect(buildUserPatch(tech, formOf(tech, { maxActiveIncidents: '' }))).toEqual({ patch: {}, fieldErrors: {} });
  });
});
