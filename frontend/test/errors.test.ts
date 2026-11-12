import { describe, expect, it } from 'vitest';
import { ApiError, problemResponse } from '../src/lib/errors';

const json = (status: number, body: unknown, statusText = '') =>
  new Response(JSON.stringify(body), { status, statusText, headers: { 'Content-Type': 'application/problem+json' } });

describe('ApiError.fromResponse', () => {
  it('parses problem+json', async () => {
    const e = await ApiError.fromResponse(json(401, { title: 'Unauthorized', status: 401, detail: 'Invalid credentials' }));
    expect(e).toBeInstanceOf(ApiError);
    expect(e.status).toBe(401);
    expect(e.title).toBe('Unauthorized');
    expect(e.detail).toBe('Invalid credentials');
    expect(e.message).toBe('Invalid credentials');
    expect(e.fieldErrors).toEqual({});
  });

  it('parses validation errors', async () => {
    const e = await ApiError.fromResponse(
      json(400, { title: 'ValidationError', status: 400, detail: 'Validation failed', errors: { email: 'must be valid', password: 'too short' } }),
    );
    expect(e.title).toBe('ValidationError');
    expect(e.fieldErrors).toEqual({ email: 'must be valid', password: 'too short' });
    expect(e.fieldError('email')).toBe('must be valid');
    expect(e.fieldError('nope')).toBeUndefined();
  });

  it('falls back when the body is not JSON', async () => {
    const e = await ApiError.fromResponse(new Response('<html>Bad gateway</html>', { status: 502, statusText: 'Bad Gateway' }));
    expect(e.status).toBe(502);
    expect(e.title).toBe('Bad Gateway');
    expect(e.detail).toBe('Request failed with status 502');
    expect(e.fieldErrors).toEqual({});
  });

  it('falls back on an empty body', async () => {
    const e = await ApiError.fromResponse(new Response(null, { status: 500 }));
    expect(e.status).toBe(500);
    expect(e.title).toBe('HttpError');
  });

  it('tolerates JSON of the wrong shape', async () => {
    expect((await ApiError.fromResponse(json(500, ['x']))).detail).toBe('Request failed with status 500');
    const e = await ApiError.fromResponse(json(400, { title: 5, detail: null, errors: ['a'] }));
    expect(e.title).toBe('HttpError');
    expect(e.fieldErrors).toEqual({});
  });

  it('prefers the HTTP status over the body status', async () => {
    expect((await ApiError.fromResponse(json(403, { status: 200, title: 'Forbidden' }))).status).toBe(403);
  });
});

describe('ApiError.userMessage', () => {
  const generic = 'Something went wrong on the server. Try again.';
  it('shows the detail for client errors', () => {
    expect(new ApiError(400, 'x', 'Bad input').userMessage).toBe('Bad input');
    expect(new ApiError(409, 'x', 'Email taken').userMessage).toBe('Email taken');
  });
  it('is generic for 5xx and an unreachable backend', () => {
    expect(new ApiError(500, 'x', 'SQL at db:5432 failed').userMessage).toBe(generic);
    expect(new ApiError(503, 'x', 'down').userMessage).toBe(generic);
    const u = ApiError.unreachable();
    expect(u.unreachable).toBe(true);
    expect(u.isServerError).toBe(true);
    expect(u.userMessage).toBe(generic);
  });
});

describe('problemResponse', () => {
  it('builds problem+json', async () => {
    const res = problemResponse(403, 'Forbidden', 'nope');
    expect(res.status).toBe(403);
    expect(res.headers.get('content-type')).toBe('application/problem+json');
    expect(await res.json()).toEqual({ title: 'Forbidden', status: 403, detail: 'nope' });
  });
});
