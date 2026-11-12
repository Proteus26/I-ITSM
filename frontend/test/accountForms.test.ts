import { describe, expect, it, vi } from 'vitest';
import { buildProfileRequest, cardForIntent, processAccountPost, type AccountApi, type AccountDeps } from '../src/lib/accountForms';
import { ApiError } from '../src/lib/errors';
import type { LoadErrorContext } from '../src/lib/loadError';
import { SESSION_COOKIE } from '../src/lib/session';

function form(fields: Record<string, string>) {
  const f = new FormData();
  for (const [k, v] of Object.entries(fields)) f.set(k, v);
  return f;
}

function setup(api: Partial<AccountApi>, role: 'EMPLOYEE' | 'TECHNICIAN' = 'EMPLOYEE') {
  const calls: string[] = [];
  const cookies = {
    set: (name: string, value: string) => void calls.push(`set:${name}=${value}`),
    delete: (name: string) => void calls.push(`delete:${name}`),
  };
  const ctx = {
    cookies,
    url: new URL('http://localhost:4321/account'),
    redirect: (to: string) => {
      calls.push(`redirect:${to}`);
      return new Response(null, { status: 302, headers: { location: to } });
    },
    rewrite: async (to: string) => new Response(to),
  } as unknown as LoadErrorContext;
  const deps = {
    user: { role, fullName: 'Ann Lee', department: 'IT' },
    api: api as AccountApi,
    cookies,
    ctx,
  } as unknown as AccountDeps;
  return { deps, calls };
}

const err = (status: number, detail = 'bad', errors?: Record<string, string>) => new ApiError(status, 'T', detail, errors ?? {});
const loc = (r: Awaited<ReturnType<typeof processAccountPost>>) => (r.kind === 'response' ? r.response.headers.get('location') : null);

describe('buildProfileRequest', () => {
  it('sends department for employees only, trimmed, blank allowed', () => {
    expect(buildProfileRequest('EMPLOYEE', form({ fullName: ' Ann ', department: ' ' }))).toEqual({ fullName: 'Ann', department: '' });
    expect(buildProfileRequest('TECHNICIAN', form({ fullName: 'Ann', department: 'X' }))).toEqual({ fullName: 'Ann' });
  });
});

describe('processAccountPost', () => {
  it('change-password starts the new session before it redirects', async () => {
    const changePassword = vi.fn(async () => ({ token: 'NEW', tokenType: 'Bearer', expiresAt: '2026-12-01T00:00:00Z', user: {} as never }));
    const { deps, calls } = setup({ changePassword });
    const r = await processAccountPost(form({ intent: 'change-password', currentPassword: 'old', newPassword: 'newpass12', confirmPassword: 'newpass12' }), deps);

    expect(changePassword).toHaveBeenCalledWith({ currentPassword: 'old', newPassword: 'newpass12' });
    expect(calls).toEqual([`set:${SESSION_COOKIE}=NEW`, 'redirect:/account?notice=changed']);
    expect(loc(r)).toBe('/account?notice=changed');
  });

  it('a password mismatch stays on the password card without calling the backend', async () => {
    const changePassword = vi.fn();
    const { deps } = setup({ changePassword });
    const r = await processAccountPost(form({ intent: 'change-password', currentPassword: 'a', newPassword: 'b', confirmPassword: 'c' }), deps);

    expect(changePassword).not.toHaveBeenCalled();
    expect(r).toMatchObject({ kind: 'state', status: 400 });
    if (r.kind === 'state') expect(r.state.passwordFieldErrors.confirmPassword).toBe('Passwords do not match.');
  });

  it('errors go to the card of the form that failed', async () => {
    const { deps } = setup({
      updateProfile: async () => Promise.reject(err(400, 'Validation failed', { fullName: 'must not be blank' })),
      changePassword: async () => Promise.reject(err(400, 'Current password is incorrect')),
      logoutAll: async () => Promise.reject(err(500, 'x')),
    });
    const profile = await processAccountPost(form({ intent: 'update-profile', fullName: 'Bob', department: 'HR' }), deps);
    if (profile.kind !== 'state') throw new Error('expected state');
    expect(profile.state.errors).toEqual({ profile: 'Please fix the highlighted fields.', password: null, sessions: null, page: null });
    expect(profile.state.profileFieldErrors.fullName).toBe('must not be blank');
    expect(profile.state.shownName).toBe('Bob');
    expect(profile.state.shownDepartment).toBe('HR');

    const pw = await processAccountPost(form({ intent: 'change-password', currentPassword: 'x', newPassword: 'y', confirmPassword: 'y' }), deps);
    if (pw.kind !== 'state') throw new Error('expected state');
    expect(pw.state.errors.password).toBe('Current password is incorrect');
    expect(pw.status).toBe(400);

    const out = await processAccountPost(form({ intent: 'logout-all' }), deps);
    if (out.kind !== 'state') throw new Error('expected state');
    expect(out.state.errors.sessions).toBeTruthy();
    expect(out.state.errors.profile).toBeNull();
    expect(out.status).toBe(500);
  });

  it('an unknown intent is a page-level error, not a card error', async () => {
    const { deps } = setup({});
    for (const intent of ['bogus', '', 'toString']) {
      const r = await processAccountPost(form({ intent }), deps);
      expect(r).toMatchObject({ kind: 'state', status: 400 });
      if (r.kind === 'state') expect(r.state.errors).toEqual({ profile: null, password: null, sessions: null, page: 'Unknown action.' });
    }
  });

  it('logout-all clears the cookie and goes to the signed-out login page', async () => {
    const { deps, calls } = setup({ logoutAll: async () => undefined });
    const r = await processAccountPost(form({ intent: 'logout-all' }), deps);
    expect(calls).toEqual([`delete:${SESSION_COOKIE}`, 'redirect:/login?signedOut=all']);
    expect(loc(r)).toBe('/login?signedOut=all');
  });

  it('a 401 from the backend ends the session and redirects to login', async () => {
    const { deps, calls } = setup({ updateProfile: async () => Promise.reject(err(401)) });
    const r = await processAccountPost(form({ intent: 'update-profile', fullName: 'A' }), deps);
    expect(r.kind).toBe('response');
    expect(calls[0]).toBe(`delete:${SESSION_COOKIE}`);
    expect(loc(r)).toContain('/login?');
  });

  it('cardForIntent', () => {
    expect(cardForIntent('update-profile')).toBe('profile');
    expect(cardForIntent('constructor')).toBe('page');
  });
});
