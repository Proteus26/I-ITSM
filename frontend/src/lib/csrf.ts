/**
 * CSRF defence for the /api proxy, on top of SameSite=Lax and Astro's `security.checkOrigin`.
 * An unsafe request is rejected when:
 *  - `Sec-Fetch-Site` is `cross-site` or `same-site`;
 *  - it has an `Origin` header that is not our own origin;
 *  - it has no `Origin` but a `Referer` whose origin is not ours (or is unparseable).
 * With neither header it is allowed: non-browser clients (curl) send none and cannot ride on a
 * victim's cookie; browsers are covered by SameSite=Lax and checkOrigin.
 */
const SAFE_METHODS = ['GET', 'HEAD', 'OPTIONS'];

export function isCrossOriginWrite(method: string, headers: Pick<Headers, 'get'>, ownOrigin: string): boolean {
  if (SAFE_METHODS.includes(method.toUpperCase())) return false;

  const site = headers.get('sec-fetch-site')?.trim().toLowerCase();
  if (site === 'cross-site' || site === 'same-site') return true;

  const origin = headers.get('origin');
  if (origin !== null) return origin !== ownOrigin;

  const referer = headers.get('referer');
  if (referer !== null) {
    try {
      return new URL(referer).origin !== ownOrigin;
    } catch {
      return true;
    }
  }
  return false;
}
