import type { APIRoute } from 'astro';
import { backendFor } from '../../../lib/backend';

/** Unread count for the bell. Never throws: the bell just keeps its last value when this fails. */
export const GET: APIRoute = async ({ cookies }) => {
  try {
    const { unread } = await backendFor(cookies).unreadCount();
    return Response.json({ unread }, { headers: { 'Cache-Control': 'no-store' } });
  } catch (e) {
    const status = (e as { status?: number }).status;
    return Response.json({ error: 'unavailable' }, { status: status === 401 ? 401 : 502, headers: { 'Cache-Control': 'no-store' } });
  }
};
