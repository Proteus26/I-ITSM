import { describe, expect, it } from 'vitest';
import { isCrossOriginWrite } from '../src/lib/csrf';

const own = 'http://localhost:4321';
const h = (o: Record<string, string>) => new Headers(o);

describe('isCrossOriginWrite', () => {
  it('rejects a foreign Origin on writes', () => {
    expect(isCrossOriginWrite('POST', h({ origin: 'http://evil.example' }), own)).toBe(true);
    expect(isCrossOriginWrite('DELETE', h({ origin: 'null' }), own)).toBe(true);
  });
  it('rejects cross-site and same-site Sec-Fetch-Site', () => {
    expect(isCrossOriginWrite('POST', h({ 'sec-fetch-site': 'cross-site' }), own)).toBe(true);
    expect(isCrossOriginWrite('PATCH', h({ 'sec-fetch-site': 'same-site', origin: own }), own)).toBe(true);
    expect(isCrossOriginWrite('POST', h({ 'sec-fetch-site': 'same-origin', origin: own }), own)).toBe(false);
    expect(isCrossOriginWrite('POST', h({ 'sec-fetch-site': 'none' }), own)).toBe(false);
  });
  it('falls back to the Referer origin when Origin is absent', () => {
    expect(isCrossOriginWrite('POST', h({ referer: `${own}/admin/users?x=1` }), own)).toBe(false);
    expect(isCrossOriginWrite('POST', h({ referer: 'http://evil.example/page' }), own)).toBe(true);
    expect(isCrossOriginWrite('POST', h({ referer: 'not a url' }), own)).toBe(true);
  });
  it('prefers Origin over Referer', () => {
    expect(isCrossOriginWrite('POST', h({ origin: own, referer: 'http://evil.example/' }), own)).toBe(false);
  });
  it('allows requests with neither header, and safe methods', () => {
    expect(isCrossOriginWrite('POST', h({}), own)).toBe(false);
    expect(isCrossOriginWrite('GET', h({ origin: 'http://evil.example', 'sec-fetch-site': 'cross-site' }), own)).toBe(false);
    expect(isCrossOriginWrite('HEAD', h({ origin: 'http://evil.example' }), own)).toBe(false);
  });
});
