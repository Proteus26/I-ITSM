import { describe, expect, it, vi } from 'vitest';
import { gate, type GateContext } from '../src/lib/gate';
import { ApiError } from '../src/lib/errors';
import { SESSION_COOKIE } from '../src/lib/session';
import type { UserResponse } from '../src/lib/types';

const user = (role: UserResponse['role']): UserResponse => ({
  id: 1,
  email: 'a@b.c',
  fullName: 'A',
  role,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
});

function setup(path: string, token?: string) {
  const deleted: string[] = [];
  const locals = {} as App.Locals;
  const context = {
    locals,
    url: new URL(`http://localhost:4321${path}`),
    cookies: {
      get: (name: string) => (name === SESSION_COOKIE && token ? { value: token } : undefined),
      delete: (name: string) => void deleted.push(name),
    },
    redirect: (to: string) => new Response(null, { status: 302, headers: { Location: to } }),
    rewrite: vi.fn(async (to: string) => new Response(`rewrite:${to}`)),
  } as unknown as GateContext;
  const next = vi.fn(async () => new Response('next'));
  return { context, locals, next, deleted };
}

describe('gate (middleware body)', () => {
  it('without a cookie: user is null, no backend call, public pages pass', async () => {
    const me = vi.fn();
    const { context, locals, next } = setup('/', undefined);
    const res = await gate(context, next, () => ({ me }));
    expect(await res.text()).toBe('next');
    expect(locals.user).toBeNull();
    expect(locals.backendDown).toBe(false);
    expect(me).not.toHaveBeenCalled();
  });

  it('without a cookie a protected page redirects to login with next', async () => {
    const { context, next } = setup('/admin/users?role=ADMIN');
    const res = await gate(context, next, () => ({ me: vi.fn() }));
    expect(res.status).toBe(302);
    expect(res.headers.get('Location')).toBe('/login?next=' + encodeURIComponent('/admin/users?role=ADMIN'));
  });

  it('a valid cookie sets locals.user', async () => {
    const me = vi.fn(async () => user('EMPLOYEE'));
    const clientFor = vi.fn(() => ({ me }));
    const { context, locals, next } = setup('/dashboard', 'tok');
    const res = await gate(context, next, clientFor);
    expect(await res.text()).toBe('next');
    expect(clientFor).toHaveBeenCalledWith('tok');
    expect(locals.user?.role).toBe('EMPLOYEE');
  });

  it('a 401 clears the cookie and sends a protected page to login', async () => {
    const { context, locals, next, deleted } = setup('/dashboard', 'stale');
    const res = await gate(context, next, () => ({ me: async () => Promise.reject(new ApiError(401, 'Unauthorized', 'x')) }));
    expect(deleted).toEqual([SESSION_COOKIE]);
    expect(locals.user).toBeNull();
    expect(locals.backendDown).toBe(false);
    expect(res.headers.get('Location')).toBe('/login?next=%2Fdashboard&expired=1');
  });

  it('a 401 on a public page clears the cookie and continues without redirecting', async () => {
    const { context, locals, next, deleted } = setup('/', 'stale');
    const res = await gate(context, next, () => ({ me: async () => Promise.reject(new ApiError(401, 'Unauthorized', 'x')) }));
    expect(await res.text()).toBe('next');
    expect(deleted).toEqual([SESSION_COOKIE]);
    expect(locals.user).toBeNull();
  });

  it('a 401 keeps next safe (encoded, query preserved)', async () => {
    const { context, next } = setup('/admin/users?q=a&b=//evil.example', 'stale');
    const res = await gate(context, next, () => ({ me: async () => Promise.reject(new ApiError(401, 'Unauthorized', 'x')) }));
    expect(res.headers.get('Location')).toBe(`/login?next=${encodeURIComponent('/admin/users?q=a&b=//evil.example')}&expired=1`);
  });

  it('no cookie: the redirect carries no expired flag', async () => {
    const { context, next } = setup('/dashboard');
    const res = await gate(context, next, () => ({ me: vi.fn() }));
    expect(res.headers.get('Location')).toBe('/login?next=%2Fdashboard');
  });

  it('backend down on a protected path rewrites to /503 and keeps the cookie', async () => {
    const { context, locals, next, deleted } = setup('/dashboard', 'tok');
    const res = await gate(context, next, () => ({ me: async () => Promise.reject(ApiError.unreachable()) }));
    expect(await res.text()).toBe('rewrite:/503');
    expect(locals.backendDown).toBe(true);
    expect(deleted).toEqual([]);
  });

  it('backend down on a public path still renders the page with backendDown set', async () => {
    const { context, locals, next } = setup('/', 'tok');
    const res = await gate(context, next, () => ({ me: async () => Promise.reject(ApiError.unreachable()) }));
    expect(await res.text()).toBe('next');
    expect(locals.backendDown).toBe(true);
  });

  it('does not call /me twice when a rewrite re-enters the middleware (hasOwn guard)', async () => {
    const me = vi.fn(async () => user('EMPLOYEE'));
    const { context, locals, next } = setup('/admin/users', 'tok');
    context.rewrite = vi.fn(async (to: string) => {
      // what Astro does: run the middleware again with the same locals for the rewritten route
      const again = await gate({ ...context, url: new URL(`http://localhost:4321${to}`) }, next, () => ({ me }));
      return again;
    }) as unknown as GateContext['rewrite'];
    const res = await gate(context, next, () => ({ me }));
    expect(me).toHaveBeenCalledTimes(1);
    expect(await res.text()).toBe('next'); // /403 is public, the rewritten request passes through
    expect(locals.user?.role).toBe('EMPLOYEE');
  });

  it('wrong role rewrites to /403', async () => {
    const { context, next } = setup('/admin/users', 'tok');
    const res = await gate(context, next, () => ({ me: async () => user('TECHNICIAN') }));
    expect(await res.text()).toBe('rewrite:/403');
  });

  it('the right role passes', async () => {
    const { context, next } = setup('/admin/users', 'tok');
    const res = await gate(context, next, () => ({ me: async () => user('ADMIN') }));
    expect(await res.text()).toBe('next');
  });

  it('guest-only pages redirect a logged-in user to /dashboard', async () => {
    const { context, next } = setup('/login', 'tok');
    const res = await gate(context, next, () => ({ me: async () => user('EMPLOYEE') }));
    expect(res.status).toBe(302);
    expect(res.headers.get('Location')).toBe('/dashboard');
  });

  it('/api/** skips the /me lookup', async () => {
    const me = vi.fn();
    const { context, next } = setup('/api/auth/me', 'tok');
    const res = await gate(context, next, () => ({ me }));
    expect(await res.text()).toBe('next');
    expect(me).not.toHaveBeenCalled();
  });
});
