import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackendClient } from '../src/lib/backend';
import { ApiError } from '../src/lib/errors';

const base = 'http://backend:8080';
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const jsonRes = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const call = () => {
  const [url, init] = fetchMock.mock.calls[0]!;
  return { url: String(url), init: init as RequestInit & { headers: Record<string, string> } };
};

describe('BackendClient requests', () => {
  it('builds a GET with the bearer token and no body', async () => {
    fetchMock.mockResolvedValue(jsonRes({ id: 1 }));
    await expect(new BackendClient('tok', base).me()).resolves.toEqual({ id: 1 });
    const { url, init } = call();
    expect(url).toBe(`${base}/api/auth/me`);
    expect(init.method).toBe('GET');
    expect(init.headers.Authorization).toBe('Bearer tok');
    expect(init.headers.Accept).toBe('application/json');
    expect(init.headers['Content-Type']).toBeUndefined();
    expect(init.body).toBeUndefined();
  });

  it('sends JSON bodies with a content type, and no Authorization without a token', async () => {
    fetchMock.mockResolvedValue(jsonRes({ token: 't' }));
    await new BackendClient(undefined, base).login({ email: 'a@b.c', password: 'pw' });
    const { url, init } = call();
    expect(url).toBe(`${base}/api/auth/login`);
    expect(init.method).toBe('POST');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(init.headers.Authorization).toBeUndefined();
    expect(JSON.parse(init.body as string)).toEqual({ email: 'a@b.c', password: 'pw' });
  });

  it('uses PATCH for updateUser and the user id in the path', async () => {
    fetchMock.mockResolvedValue(jsonRes({}));
    await new BackendClient('t', base).updateUser(7, { active: false });
    const { url, init } = call();
    expect(url).toBe(`${base}/api/admin/users/7`);
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body as string)).toEqual({ active: false });
  });

  it('puts only the set filters in the list query string', async () => {
    fetchMock.mockResolvedValue(jsonRes({ content: [] }));
    await new BackendClient('t', base).listUsers({ role: 'TECHNICIAN', active: false, page: 0, q: '' });
    expect(call().url).toBe(`${base}/api/admin/users?role=TECHNICIAN&active=false&page=0`);
  });

  it('has no query string when no filter is set', async () => {
    fetchMock.mockResolvedValue(jsonRes({ content: [] }));
    await new BackendClient('t', base).listUsers();
    expect(call().url).toBe(`${base}/api/admin/users`);
  });

  it('pings the backend', async () => {
    fetchMock.mockResolvedValue(jsonRes({ status: 'UP', service: 's', time: 't' }));
    expect((await new BackendClient(undefined, base).ping()).status).toBe('UP');
    expect(call().url).toBe(`${base}/api/ping`);
  });
});

describe('BackendClient responses', () => {
  it('returns undefined for 204', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(new BackendClient('t', base).changePassword({ currentPassword: 'a', newPassword: 'b' })).resolves.toBeUndefined();
  });

  it('turns problem+json into an ApiError', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ title: 'ValidationError', status: 400, detail: 'Validation failed', errors: { email: 'bad' } }), {
        status: 400,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );
    const e = await new BackendClient('t', base).me().catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(400);
    expect(e.fieldErrors).toEqual({ email: 'bad' });
    expect(e.unreachable).toBe(false);
  });

  it('handles non-JSON error bodies', async () => {
    fetchMock.mockResolvedValue(new Response('<html>oops</html>', { status: 500 }));
    const e = await new BackendClient('t', base).me().catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(500);
    expect(e.userMessage).toBe('Something went wrong on the server. Try again.');
  });

  it('maps a network failure to an unreachable ApiError', async () => {
    fetchMock.mockRejectedValue(new TypeError('fetch failed'));
    const e = await new BackendClient('t', base).me().catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e.unreachable).toBe(true);
    expect(e.status).toBe(502);
  });
});
