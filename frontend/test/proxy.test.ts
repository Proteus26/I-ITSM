import { describe, expect, it } from 'vitest';
import { filterResponseHeaders, proxyTarget } from '../src/lib/proxy';

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
