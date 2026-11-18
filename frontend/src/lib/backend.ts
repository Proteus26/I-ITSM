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
  UpdateProfileRequest,
  UpdateUserRequest,
  UserResponse,
} from './types';
import { ROLES } from './types';
import type {
  AuditEntry,
  AuditQuery,
  IncidentSearchQuery,
  IncidentSearchResult,
  MttrRow,
  NotificationItem,
  OverviewReport,
  ServiceCount,
  SlaCompliance,
  TrendPoint,
  WorkloadRow,
} from './p4Types';
import { toQueryString } from './p4';
import type {
  AddNoteRequest,
  AdminCategory,
  AdminService,
  AssignRequest,
  CategorySuggestion,
  CategorySummary,
  ClassifyRequest,
  CreateCategoryRequest,
  CreateIncidentRequest,
  FeedbackRequest,
  IncidentDetail,
  IncidentNote,
  IncidentSummary,
  ReasonRequest,
  RecommendationQuery,
  RequestInfoRequest,
  ResolveRequest,
  ResumeRequest,
  ServiceSummary,
  SettingItem,
  SimilarIncident,
  SimilarQuery,
  TechnicianRecommendation,
  UpdateCategoryRequest,
  UpdateServiceRequest,
} from './incidentTypes';

export const DEFAULT_TIMEOUT_MS = 5000;

/** `BACKEND_TIMEOUT_MS` as a positive integer, or the default. */
export function backendTimeoutMs(env: Record<string, string | undefined> = process.env): number {
  const n = Number(env.BACKEND_TIMEOUT_MS);
  return Number.isInteger(n) && n > 0 ? n : DEFAULT_TIMEOUT_MS;
}

/** `?a=1&b=2` for the defined values of `params`, or an empty string. */
function queryString(params: object): string {
  const qs = toQueryString(params);
  return qs ? `?${qs}` : '';
}

function invalidResponse(): ApiError {
  return new ApiError(502, 'BadGateway', 'Invalid response from server');
}

/** Minimal runtime check that a backend answer looks like a UserResponse. */
export function isUserResponse(v: unknown): v is UserResponse {
  if (typeof v !== 'object' || v === null) return false;
  const u = v as Record<string, unknown>;
  const optionalDate = (x: unknown) => x === undefined || x === null || typeof x === 'string';
  return (
    typeof u.id === 'number' &&
    typeof u.email === 'string' &&
    (ROLES as readonly string[]).includes(u.role as string) &&
    optionalDate(u.lastLoginAt) &&
    optionalDate(u.previousLoginAt)
  );
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

  private async tokenRequest(path: string, body: unknown): Promise<LoginResponse> {
    const res = await this.request<LoginResponse>('POST', path, body);
    if (typeof res?.token !== 'string' || !res.token || !isUserResponse(res.user)) throw invalidResponse();
    return res;
  }

  login(req: LoginRequest): Promise<LoginResponse> {
    return this.tokenRequest('/api/auth/login', req);
  }

  register(req: RegisterRequest): Promise<UserResponse> {
    return this.request('POST', '/api/auth/register', req);
  }

  async me(): Promise<UserResponse> {
    const user = await this.request<UserResponse>('GET', '/api/auth/me');
    if (!isUserResponse(user)) throw invalidResponse();
    return user;
  }

  /** Revokes the current token and answers with a new one: the caller must start a session with it. */
  changePassword(req: ChangePasswordRequest): Promise<LoginResponse> {
    return this.tokenRequest('/api/auth/change-password', req);
  }

  /** Revokes every token of the user, including the current one (204). */
  logoutAll(): Promise<void> {
    return this.request('POST', '/api/auth/logout-all');
  }

  async updateProfile(req: UpdateProfileRequest): Promise<UserResponse> {
    const user = await this.request<UserResponse>('PATCH', '/api/auth/me', req);
    if (!isUserResponse(user)) throw invalidResponse();
    return user;
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
  // ---- P4: notifications, search, audit, reports ----

  listNotifications(query: { unread?: boolean; page?: number; size?: number } = {}): Promise<PageResponse<NotificationItem>> {
    return this.request('GET', `/api/notifications${queryString(query)}`);
  }

  unreadCount(): Promise<{ unread: number }> {
    return this.request('GET', '/api/notifications/unread-count');
  }

  markNotificationRead(id: number): Promise<NotificationItem> {
    return this.request('POST', `/api/notifications/${id}/read`);
  }

  markAllNotificationsRead(): Promise<{ updated: number }> {
    return this.request('POST', '/api/notifications/read-all');
  }

  searchIncidents(query: IncidentSearchQuery = {}): Promise<PageResponse<IncidentSearchResult>> {
    return this.request('GET', `/api/incidents/search${queryString(query)}`);
  }

  incidentHistory(id: number): Promise<AuditEntry[]> {
    return this.request('GET', `/api/incidents/${id}/history`);
  }

  searchAudit(query: AuditQuery = {}): Promise<PageResponse<AuditEntry>> {
    return this.request('GET', `/api/admin/audit${queryString(query)}`);
  }

  reportOverview(): Promise<OverviewReport> {
    return this.request('GET', '/api/reports/overview');
  }

  reportWorkload(): Promise<WorkloadRow[]> {
    return this.request('GET', '/api/reports/technician-workload');
  }

  reportByService(): Promise<ServiceCount[]> {
    return this.request('GET', '/api/reports/by-service');
  }

  reportSlaCompliance(): Promise<SlaCompliance> {
    return this.request('GET', '/api/reports/sla-compliance');
  }

  reportMttr(): Promise<MttrRow[]> {
    return this.request('GET', '/api/reports/mttr');
  }

  reportTrend(days = 30): Promise<TrendPoint[]> {
    return this.request('GET', `/api/reports/trend${queryString({ days })}`);
  }

  // ---- P2: incidents, categories; P3/P5: services, settings, assignment, intelligence ----

  createIncident(req: CreateIncidentRequest): Promise<IncidentDetail> {
    return this.request('POST', '/api/incidents', req);
  }

  myIncidents(query: { page?: number; size?: number } = {}): Promise<PageResponse<IncidentSummary>> {
    return this.request('GET', `/api/incidents/mine${queryString(query)}`);
  }

  assignedIncidents(query: { page?: number; size?: number } = {}): Promise<PageResponse<IncidentSummary>> {
    return this.request('GET', `/api/incidents/assigned${queryString(query)}`);
  }

  getIncident(id: number): Promise<IncidentDetail> {
    return this.request('GET', `/api/incidents/${id}`);
  }

  incidentNotes(id: number): Promise<IncidentNote[]> {
    return this.request('GET', `/api/incidents/${id}/notes`);
  }

  addIncidentNote(id: number, req: AddNoteRequest): Promise<IncidentNote> {
    return this.request('POST', `/api/incidents/${id}/notes`, req);
  }

  acknowledgeIncident(id: number): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/acknowledge`);
  }

  startIncident(id: number): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/start`);
  }

  requestInfo(id: number, req: RequestInfoRequest): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/request-info`, req);
  }

  resumeIncident(id: number, req: ResumeRequest = {}): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/resume`, req);
  }

  resolveIncident(id: number, req: ResolveRequest): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/resolve`, req);
  }

  verifyIncident(id: number): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/verify`);
  }

  reopenIncident(id: number, req: ReasonRequest = {}): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/reopen`, req);
  }

  closeIncident(id: number): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/close`);
  }

  cancelIncident(id: number, req: ReasonRequest = {}): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/cancel`, req);
  }

  submitFeedback(id: number, req: FeedbackRequest): Promise<IncidentDetail> {
    return this.request('POST', `/api/incidents/${id}/feedback`, req);
  }

  classifyIncident(id: number, req: ClassifyRequest): Promise<IncidentDetail> {
    return this.request('POST', `/api/admin/incidents/${id}/classify`, req);
  }

  assignIncident(id: number, req: AssignRequest): Promise<IncidentDetail> {
    return this.request('POST', `/api/admin/incidents/${id}/assign`, req);
  }

  /** Active categories, for any logged-in user. */
  listCategories(): Promise<CategorySummary[]> {
    return this.request('GET', '/api/categories');
  }

  adminCategories(): Promise<AdminCategory[]> {
    return this.request('GET', '/api/admin/categories');
  }

  createCategory(req: CreateCategoryRequest): Promise<AdminCategory> {
    return this.request('POST', '/api/admin/categories', req);
  }

  updateCategory(code: string, req: UpdateCategoryRequest): Promise<AdminCategory> {
    return this.request('PATCH', `/api/admin/categories/${encodeURIComponent(code)}`, req);
  }

  /** Active services, for any logged-in user. */
  listServices(): Promise<ServiceSummary[]> {
    return this.request('GET', '/api/services');
  }

  adminServices(): Promise<AdminService[]> {
    return this.request('GET', '/api/admin/services');
  }

  updateService(id: number, req: UpdateServiceRequest): Promise<AdminService> {
    return this.request('PATCH', `/api/admin/services/${id}`, req);
  }

  /** The suggestion, or undefined when nothing matches (204). */
  suggestCategory(title: string, description = ''): Promise<CategorySuggestion | undefined> {
    return this.request('GET', `/api/intelligence/suggest-category${queryString({ title, description })}`);
  }

  similarIncidents(query: SimilarQuery): Promise<SimilarIncident[]> {
    return this.request('GET', `/api/intelligence/similar${queryString(query)}`);
  }

  assignmentRecommendations(query: RecommendationQuery = {}): Promise<TechnicianRecommendation[]> {
    return this.request('GET', `/api/admin/assignment/recommendations${queryString(query)}`);
  }

  getSettings(): Promise<SettingItem[]> {
    return this.request('GET', '/api/admin/settings');
  }

  updateSetting(key: string, value: string): Promise<SettingItem> {
    return this.request('PUT', `/api/admin/settings/${encodeURIComponent(key)}`, { value });
  }
}

/** A client authenticated with the session cookie of the current request (anonymous if there is none). */
export function backendFor(cookies: AstroCookies): BackendClient {
  return new BackendClient(readToken(cookies));
}
