import { describe, expect, it } from 'vitest';
import { decideAccess, normalizePath, type RouteRule } from '../src/lib/access';

const employee = { role: 'EMPLOYEE' } as const;
const tech = { role: 'TECHNICIAN' } as const;
const admin = { role: 'ADMIN' } as const;

describe('decideAccess', () => {
  it.each(['/', '/login', '/register', '/logout', '/api/auth/me', '/api', '/some-unknown-page', '/administrator'])(
    '%s is public',
    (p) => {
      expect(decideAccess(p, null)).toEqual({ type: 'allow' });
    },
  );

  it.each(['/dashboard', '/account', '/admin/users'])('%s needs login', (p) => {
    expect(decideAccess(p, null)).toEqual({ type: 'login' });
  });

  it('lets any logged-in user into auth-only pages', () => {
    for (const u of [employee, tech, admin]) {
      expect(decideAccess('/dashboard', u)).toEqual({ type: 'allow' });
      expect(decideAccess('/account', u)).toEqual({ type: 'allow' });
    }
  });

  it('allows only admins under /admin', () => {
    expect(decideAccess('/admin', admin)).toEqual({ type: 'allow' });
    expect(decideAccess('/admin/users/5', admin)).toEqual({ type: 'allow' });
    expect(decideAccess('/admin/users', employee)).toEqual({ type: 'forbidden' });
    expect(decideAccess('/admin/users/new', tech)).toEqual({ type: 'forbidden' });
  });

  it('sends logged-in users away from login and register', () => {
    expect(decideAccess('/login', employee)).toEqual({ type: 'home' });
    expect(decideAccess('/register', admin)).toEqual({ type: 'home' });
  });

  it('is not fooled by case, trailing or repeated slashes, or encoding', () => {
    for (const p of ['/Admin/users', '/admin/', '//admin/users', '/admin//users', '/%61dmin/users', '/ADMIN']) {
      expect(decideAccess(p, employee)).toEqual({ type: 'forbidden' });
      expect(decideAccess(p, null)).toEqual({ type: 'login' });
    }
  });

  it('supports custom rules with role lists', () => {
    const rules: RouteRule[] = [{ prefix: '/technician', roles: ['TECHNICIAN', 'ADMIN'] }, { prefix: '/incidents' }];
    expect(decideAccess('/technician/queue', tech, rules)).toEqual({ type: 'allow' });
    expect(decideAccess('/technician/queue', employee, rules)).toEqual({ type: 'forbidden' });
    expect(decideAccess('/incidents/3', employee, rules)).toEqual({ type: 'allow' });
    expect(decideAccess('/incidents/3', null, rules)).toEqual({ type: 'login' });
  });
});

describe('normalizePath', () => {
  it('normalises', () => {
    expect(normalizePath('/A//b/')).toBe('/a/b');
    expect(normalizePath('/')).toBe('/');
    expect(normalizePath('/%E0%A4%A')).toBe('/%e0%a4%a'); // malformed escape: left as is
  });
});

describe('dot segments cannot disguise a protected path', () => {
  it('resolves . and .. after decoding', () => {
    expect(normalizePath('/api%2F..%2Fadmin%2Fusers')).toBe('/admin/users');
    expect(normalizePath('/api/./../admin')).toBe('/admin');
    expect(normalizePath('/api%5C..%5Cadmin')).toBe('/admin');
  });
  it('treats an encoded escape from /api as the protected area', () => {
    expect(decideAccess('/api%2F..%2Fadmin%2Fusers', null)).toEqual({ type: 'login' });
    expect(decideAccess('/api%2F..%2Fadmin', { role: 'EMPLOYEE' })).toEqual({ type: 'forbidden' });
  });
});
