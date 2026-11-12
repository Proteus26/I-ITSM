import type { AstroCookies } from 'astro';
import { API_URL } from './api';
import { ApiError } from './errors';
import { readToken } from './session';
import type {
  ChangePasswordRequest,
  CreateUserRequest,
  ListUsersQuery,
  LoginRequest,
  LoginResponse,
  PageResponse,
  Ping,
  RegisterRequest,
  UpdateUserRequest,
  UserResponse,
} from './types';
import { ROLES } from './types';

export const DEFAULT_TIMEOUT_MS = 5000;

/** `BACKEND_TIMEOUT_MS` as a positive integer, or the default. */
export function backendTimeoutMs(env: Record<string, string | undefined> = process.env): number {
  const n = Number(env.BACKEND_TIMEOUT_MS);
  return Number.isInteger(n) && n > 0 ? n : DEFAULT_TIMEOUT_MS;
}

function invalidResponse(): ApiError {
  return new ApiError(502, 'BadGateway', 'Invalid response from server');
}

/** Minimal runtime check that a backend answer looks like a UserResponse. */
export function isUserResponse(v: unknown): v is UserResponse {
  if (typeof v !== 'object' || v === null) return false;
  const u = v as Record<string, unknown>;
  return typeof u.id === 'number' && typeof u.email === 'string' && (ROLES as readonly string[]).includes(u.role as string);
}

/**
 * Typed, server-side client for the Spring Boot API. Construct it with the user's token (or none for
 * the public endpoints); every method throws {@link ApiError} on a non-2xx response or a network failure.
 *
 *   const api = backendFor(Astro.cookies);
 *   const users = await api.listUsers({ role: 'TECHNICIAN' });
 */
export class BackendClient {
  constructor(
    private readonly token?: string,
    private readonly baseUrl: string = API_URL,
    private readonly timeoutMs: number = backendTimeoutMs(),
  ) {}

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (this.token) headers.Authorization = `Bearer ${this.token}`;

    let res: Response;
    try {
      res = await fetch(new URL(path, this.baseUrl), {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        redirect: 'manual',
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch {
      // network failure, timeout or abort
      throw ApiError.unreachable();
    }
    if (!res.ok) throw await ApiError.fromResponse(res);
    if (res.status === 204) return undefined as T;
    try {
      return (await res.json()) as T;
    } catch {
      throw invalidResponse();
    }
  }

  ping(): Promise<Ping> {
    return this.request('GET', '/api/ping');
  }

  async login(req: LoginRequest): Promise<LoginResponse> {
    const res = await this.request<LoginResponse>('POST', '/api/auth/login', req);
    if (typeof res?.token !== 'string' || !res.token || !isUserResponse(res.user)) throw invalidResponse();
    return res;
  }

  register(req: RegisterRequest): Promise<UserResponse> {
    return this.request('POST', '/api/auth/register', req);
  }

  async me(): Promise<UserResponse> {
    const user = await this.request<UserResponse>('GET', '/api/auth/me');
    if (!isUserResponse(user)) throw invalidResponse();
    return user;
  }

  changePassword(req: ChangePasswordRequest): Promise<void> {
    return this.request('POST', '/api/auth/change-password', req);
  }

  listUsers(query: ListUsersQuery = {}): Promise<PageResponse<UserResponse>> {
    const params = new URLSearchParams();
    if (query.role) params.set('role', query.role);
    if (query.active !== undefined) params.set('active', String(query.active));
    if (query.q) params.set('q', query.q);
    if (query.page !== undefined) params.set('page', String(query.page));
    if (query.size !== undefined) params.set('size', String(query.size));
    const qs = params.toString();
    return this.request('GET', `/api/admin/users${qs ? `?${qs}` : ''}`);
  }

  getUser(id: number): Promise<UserResponse> {
    return this.request('GET', `/api/admin/users/${id}`);
  }

  createUser(req: CreateUserRequest): Promise<UserResponse> {
    return this.request('POST', '/api/admin/users', req);
  }

  updateUser(id: number, req: UpdateUserRequest): Promise<UserResponse> {
    return this.request('PATCH', `/api/admin/users/${id}`, req);
  }

  resetPassword(id: number, newPassword: string): Promise<void> {
    return this.request('POST', `/api/admin/users/${id}/reset-password`, { newPassword });
  }
}

/** A client authenticated with the session cookie of the current request (anonymous if there is none). */
export function backendFor(cookies: AstroCookies): BackendClient {
  return new BackendClient(readToken(cookies));
}
