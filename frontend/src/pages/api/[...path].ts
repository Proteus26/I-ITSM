import type { APIRoute } from 'astro';
import { API_URL } from '../../lib/api';

/**
 * Same-origin proxy: browser -> /api/** (Astro) -> backend /api/**.
 * Keeps a single origin for the browser (no CORS, cookies just work).
 */
export const ALL: APIRoute = async ({ request, params }) => {
  const incoming = new URL(request.url);
  const target = new URL(`/api/${params.path ?? ''}${incoming.search}`, API_URL);

  const headers = new Headers(request.headers);
  headers.delete('host');

  const hasBody = !['GET', 'HEAD'].includes(request.method);
  let res: Response;
  try {
    res = await fetch(target, {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: 'manual',
    });
  } catch {
    return Response.json(
      { title: 'BadGateway', status: 502, detail: `Backend unreachable at ${API_URL}` },
      { status: 502 },
    );
  }

  const outHeaders = new Headers(res.headers);
  outHeaders.delete('content-encoding');
  outHeaders.delete('content-length');
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers: outHeaders });
};
