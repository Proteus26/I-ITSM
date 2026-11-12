import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ALL } from '../src/pages/api/[...path]';
import { SESSION_COOKIE } from '../src/lib/session';

let fetchMock: ReturnType<typeof vi.fn>;
let deleted: string[];

beforeEach(() => {
  fetchMock = vi.fn();
  deleted = [];
  vi.stubGlobal('fetch', fetchMock);
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function call(path: string, init: RequestInit & { token?: string } = {}, url = 'http://localhost:4321') {
  const { token, ...rest } = init;
  const request = new Request(`${url}/api/${path}`, rest);
  const cookies = {
    get: (n: string) => (n === SESSION_COOKIE && token ? { value: token } : undefined),
    delete: (n: string) => void deleted.push(n),
  };
  return ALL({ request, params: { path }, cookies } as never) as Promise<Response>;
}
const sent = () => {
  const [target, init] = fetchMock.mock.calls[0]!;
  return { target: target as URL, init: init as RequestInit & { headers: Headers } };
};

describe('/api proxy route', () => {
  it('injects the token from the cookie and does not forward cookies or browser-only headers', async () => {
    fetchMock.mockResolvedValue(new Response('{}', { status: 200 }));
    await call('auth/me', {
      token: 'secret',
      headers: { cookie: `${SESSION_COOKIE}=secret`, authorization: 'Bearer evil', origin: 'http://localhost:4321', accept: 'application/json' },
    });
    const { target, init } = sent();
    expect(target.pathname).toBe('/api/auth/me');
    expect(init.headers.get('authorization')).toBe('Bearer secret');
    expect(init.headers.has('cookie')).toBe(false);
    expect(init.headers.has('origin')).toBe(false);
    expect(init.headers.get('accept')).toBe('application/json');
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('forwards the body of writes', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await call('admin/users', {
      method: 'POST',
      token: 't',
      headers: { origin: 'http://localhost:4321', 'content-type': 'application/json' },
      body: '{"a":1}',
    });
    expect(new TextDecoder().decode(sent().init.body as Uint8Array)).toBe('{"a":1}');
  });

  it('clears the session when the backend answers 401 for a cookie token', async () => {
    fetchMock.mockResolvedValue(new Response('{}', { status: 401 }));
    const res = await call('auth/me', { token: 'stale' });
    expect(res.status).toBe(401);
    expect(deleted).toEqual([SESSION_COOKIE]);
  });

  it('does not touch the session on a 401 without a cookie', async () => {
    fetchMock.mockResolvedValue(new Response('{}', { status: 401 }));
    await call('auth/me');
    expect(deleted).toEqual([]);
  });

  it('answers 400 for path traversal without calling the backend', async () => {
    const res = await call('../actuator');
    expect(res.status).toBe(400);
    expect(res.headers.get('content-type')).toContain('application/problem+json');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('answers 403 for a cross-origin write', async () => {
    const res = await call('admin/users', { method: 'POST', headers: { origin: 'http://evil.example' }, body: '{}' });
    expect(res.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('filters response headers (no Set-Cookie or Server)', async () => {
    fetchMock.mockResolvedValue(
      new Response('{}', { status: 200, headers: { 'content-type': 'application/json', 'set-cookie': 'x=1', server: 'Jetty', 'x-powered-by': 'y', etag: '"1"' } }),
    );
    const res = await call('ping');
    expect(res.headers.get('content-type')).toBe('application/json');
    expect(res.headers.get('etag')).toBe('"1"');
    expect(res.headers.has('set-cookie')).toBe(false);
    expect(res.headers.has('server')).toBe(false);
    expect(res.headers.has('x-powered-by')).toBe(false);
  });

  it('answers 502 when the backend is unreachable', async () => {
    fetchMock.mockRejectedValue(new TypeError('fetch failed'));
    const res = await call('ping');
    expect(res.status).toBe(502);
  });

  it('answers 504 with a generic message on a timeout', async () => {
    fetchMock.mockRejectedValue(new DOMException('The operation was aborted due to timeout', 'TimeoutError'));
    const res = await call('ping');
    expect(res.status).toBe(504);
    expect(res.headers.get('content-type')).toContain('application/problem+json');
    const body = await res.json();
    expect(body.status).toBe(504);
    expect(body.detail).not.toMatch(/aborted|operation/i);
  });

  it('answers 413 when Content-Length is above 1 MiB', async () => {
    const res = await call('admin/users', {
      method: 'POST',
      headers: { origin: 'http://localhost:4321', 'content-length': String(1024 * 1024 + 1) },
      body: '{}',
    });
    expect(res.status).toBe(413);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('answers 413 when a body without Content-Length grows past 1 MiB', async () => {
    const chunk = new Uint8Array(512 * 1024);
    const body = new ReadableStream<Uint8Array>({
      start(c) {
        for (let i = 0; i < 3; i++) c.enqueue(chunk);
        c.close();
      },
    });
    const request = new Request('http://localhost:4321/api/admin/users', {
      method: 'POST',
      headers: { origin: 'http://localhost:4321' },
      body,
      duplex: 'half',
    } as RequestInit);
    expect(request.headers.get('content-length')).toBeNull();
    const res = (await ALL({ request, params: { path: 'admin/users' }, cookies: { get: () => undefined, delete: () => undefined } } as never)) as Response;
    expect(res.status).toBe(413);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
