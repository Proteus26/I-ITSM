import { describe, expect, it } from 'vitest';
import {
  bufferedBodyTooLarge,
  buildBackendRequestHeaders,
  declaredBodyTooLarge,
  filterResponseHeaders,
  MAX_BODY_BYTES,
  proxyTarget,
  readBodyCapped,
} from '../src/lib/proxy';

const base = 'http://backend:8080';

describe('proxyTarget', () => {
  it.each([
    ['..\\..\\actuator'],
    ['../x'],
    ['a/../../x'],
    ['%2e%2e'],
    ['%2e%2e/x'],
    ['//evil.com'],
    ['/evil.com'],
    ['foo\\bar'],
    ['a/./b'],
    ['a//b'],
    ['a/'],
    [''],
    ['x?y=1'],
    ['x#y'],
    ['x\u0000y'],
    ['x\ny'],
    ['x\ty'],
    ['..'],
    ['.'],
  ])('rejects %j', (path) => {
    expect(proxyTarget(path, '', base)).toBeNull();
  });

  it('rejects an undefined path and a malformed search', () => {
    expect(proxyTarget(undefined, '', base)).toBeNull();
    expect(proxyTarget('auth/me', 'x=1', base)).toBeNull();
  });

  it('accepts normal paths and keeps the query string', () => {
    expect(proxyTarget('auth/me', '', base)?.href).toBe('http://backend:8080/api/auth/me');
    expect(proxyTarget('admin/users/3', '', base)?.href).toBe('http://backend:8080/api/admin/users/3');
    expect(proxyTarget('admin/users', '?role=ADMIN&page=2', base)?.href).toBe('http://backend:8080/api/admin/users?role=ADMIN&page=2');
  });

  it('always stays on the backend origin under /api/', () => {
    const t = proxyTarget('auth/me', '?next=http://evil.com', base)!;
    expect(t.origin).toBe(base);
    expect(t.pathname.startsWith('/api/')).toBe(true);
  });
});

describe('filterResponseHeaders', () => {
  const origin = base;

  it('keeps only allow-listed headers', () => {
    const out = filterResponseHeaders(
      new Headers({
        'content-type': 'application/json',
        'cache-control': 'no-store',
        etag: '"a"',
        'last-modified': 'x',
        'content-disposition': 'attachment',
        'www-authenticate': 'Bearer',
        'retry-after': '5',
        vary: 'Origin',
        'set-cookie': 'a=b',
        server: 'nginx',
        'x-powered-by': 'x',
        'access-control-allow-origin': '*',
      }),
      origin,
    );
    expect([...out.keys()].sort()).toEqual(
      ['cache-control', 'content-disposition', 'content-type', 'etag', 'last-modified', 'retry-after', 'vary', 'www-authenticate'].sort(),
    );
    expect(out.get('set-cookie')).toBeNull();
  });

  it('keeps a relative Location', () => {
    expect(filterResponseHeaders(new Headers({ location: '/api/x?y=1' }), origin).get('location')).toBe('/api/x?y=1');
  });

  it('rewrites a backend-absolute Location to its path', () => {
    expect(filterResponseHeaders(new Headers({ location: 'http://backend:8080/api/x?y=1#z' }), origin).get('location')).toBe('/api/x?y=1#z');
  });

  it.each(['http://evil.example/x', '//evil.example/x', 'https://backend:8080/x', '\\\\evil.example', '/x\ny'])('drops Location %j', (loc) => {
    const headers = new Headers();
    try {
      headers.set('location', loc);
    } catch {
      return; // not even representable as a header value
    }
    expect(filterResponseHeaders(headers, origin).get('location')).toBeNull();
  });
});

describe('buildBackendRequestHeaders', () => {
  const incoming = new Headers({
    accept: 'application/json',
    'accept-language': 'en-GB',
    'content-type': 'application/json',
    'if-none-match': '"abc"',
    'if-modified-since': 'Wed, 01 Jan 2025 00:00:00 GMT',
    cookie: 'itsm_session=secret',
    host: 'localhost:4321',
    origin: 'http://localhost:4321',
    referer: 'http://localhost:4321/x',
    'sec-fetch-site': 'same-origin',
    'sec-fetch-mode': 'cors',
    'x-forwarded-for': '1.2.3.4',
    connection: 'keep-alive',
    'content-length': '12',
  });

  it('forwards only the allow-listed headers', () => {
    const out = buildBackendRequestHeaders(incoming, undefined);
    expect([...out.keys()].sort()).toEqual(['accept', 'accept-language', 'content-type', 'if-modified-since', 'if-none-match']);
  });

  it('uses the cookie token as Authorization, overriding the client header', () => {
    const h = new Headers(incoming);
    h.set('authorization', 'Bearer client');
    expect(buildBackendRequestHeaders(h, 'cookie-token').get('authorization')).toBe('Bearer cookie-token');
  });

  it('keeps the client Authorization only when there is no cookie token', () => {
    const h = new Headers({ authorization: 'Bearer client' });
    expect(buildBackendRequestHeaders(h, undefined).get('authorization')).toBe('Bearer client');
    expect(buildBackendRequestHeaders(new Headers(), undefined).has('authorization')).toBe(false);
  });
});

describe('body size cap', () => {
  it('flags a declared Content-Length above 1 MiB', () => {
    expect(declaredBodyTooLarge(String(MAX_BODY_BYTES))).toBe(false);
    expect(declaredBodyTooLarge(String(MAX_BODY_BYTES + 1))).toBe(true);
    expect(declaredBodyTooLarge(null)).toBe(false);
    expect(declaredBodyTooLarge('abc')).toBe(false);
  });

  it('flags a buffered body above the cap', () => {
    expect(bufferedBodyTooLarge(new ArrayBuffer(10), 10)).toBe(false);
    expect(bufferedBodyTooLarge(new ArrayBuffer(11), 10)).toBe(true);
  });

  const streamOf = (...chunks: number[]) =>
    new ReadableStream<Uint8Array>({
      start(c) {
        for (const n of chunks) c.enqueue(new Uint8Array(n).fill(1));
        c.close();
      },
    });

  it('reads a body within the cap', async () => {
    const out = await readBodyCapped(streamOf(4, 6), 10);
    expect(out?.byteLength).toBe(10);
    expect((await readBodyCapped(null, 10))?.byteLength).toBe(0);
  });

  it('gives up on a body that grows past the cap even without a Content-Length', async () => {
    expect(await readBodyCapped(streamOf(6, 6), 10)).toBeNull();
  });
});
