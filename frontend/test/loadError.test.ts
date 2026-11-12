import { describe, expect, it } from 'vitest';
import { ApiError } from '../src/lib/errors';
import { handleLoadError, type LoadErrorContext } from '../src/lib/loadError';

function ctx() {
  const deleted: string[] = [];
  const c = {
    cookies: { delete: (name: string) => void deleted.push(name) },
    url: new URL('http://localhost:4321/admin/users/3?x=1'),
    redirect: (path: string) => new Response(null, { status: 302, headers: { location: path } }),
    rewrite: async (path: string) => new Response(path, { status: 200 }),
  } as unknown as LoadErrorContext;
  return { c, deleted };
}
const err = (status: number) => new ApiError(status, 'T', 'detail');

describe('handleLoadError', () => {
  it('404 -> /404 page', async () => {
    const r = (await handleLoadError(err(404), ctx().c)) as Response;
    expect(await r.text()).toBe('/404');
  });
  it('403 -> /403 page', async () => {
    expect(await ((await handleLoadError(err(403), ctx().c)) as Response).text()).toBe('/403');
  });
  it('401 -> ends the session and redirects to login with next', async () => {
    const { c, deleted } = ctx();
    const r = (await handleLoadError(err(401), c)) as Response;
    expect(deleted).toEqual(['itsm_session']);
    expect(r.headers.get('location')).toBe('/login?next=%2Fadmin%2Fusers%2F3%3Fx%3D1&expired=1');
  });
  it('a 401 from a POST handler (form submission) also clears the cookie and goes to login', async () => {
    const { c, deleted } = ctx();
    const r = (await handleLoadError(err(401), c, { inlineServerErrors: true })) as Response;
    expect(deleted).toEqual(['itsm_session']);
    expect(r.status).toBe(302);
    expect(r.headers.get('location')).toContain('expired=1');
  });
  it('5xx and unreachable -> /503', async () => {
    expect(await ((await handleLoadError(err(500), ctx().c)) as Response).text()).toBe('/503');
    expect(await ((await handleLoadError(ApiError.unreachable(), ctx().c)) as Response).text()).toBe('/503');
  });
  it('can return server errors inline', async () => {
    const e = err(500);
    expect(await handleLoadError(e, ctx().c, { inlineServerErrors: true })).toBe(e);
  });
  it('returns other ApiErrors for display', async () => {
    const e = err(400);
    expect(await handleLoadError(e, ctx().c)).toBe(e);
  });
  it('rethrows unknown errors', async () => {
    await expect(handleLoadError(new TypeError('bug'), ctx().c)).rejects.toThrow('bug');
  });
});
