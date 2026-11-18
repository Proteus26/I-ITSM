// Types for the P2 incident endpoints and the P3/P5 settings, services and assignment endpoints.
// They mirror the contract in the backend (incident, settings, service, assignment, intelligence packages).
import type { IncidentStatus, Priority } from './p4Types';

export type { IncidentStatus, Priority };

export const IMPACTS = ['INDIVIDUAL', 'TEAM', 'DEPARTMENT', 'ORGANISATION'] as const;
export type Impact = (typeof IMPACTS)[number];

export const URGENCIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export type Urgency = (typeof URGENCIES)[number];

export const NOTE_TYPES = ['INVESTIGATION', 'INFO_REQUEST', 'INFO_RESPONSE', 'COMMENT'] as const;
export type NoteType = (typeof NOTE_TYPES)[number];

/** What the current viewer may do now (IncidentDetail.actions). Unknown values from a newer backend are ignored. */
export const INCIDENT_ACTIONS = [
  'ACKNOWLEDGE', 'START', 'REQUEST_INFO', 'RESUME', 'RESOLVE', 'VERIFY', 'REOPEN', 'CLOSE', 'CANCEL',
  'FEEDBACK', 'ASSIGN', 'CLASSIFY', 'EDIT',
] as const;
export type IncidentAction = (typeof INCIDENT_ACTIONS)[number];

export interface PersonRef {
  id: number;
  fullName: string;
}

export interface IncidentSummary {
  id: number;
  number: string;
  title: string;
  status: IncidentStatus;
  priority: Priority | null;
  categoryCode: string | null;
  serviceName: string | null;
  reporter: PersonRef;
  assignee: PersonRef | null;
  createdAt: string;
  resolutionDueAt: string | null;
}

export interface IncidentDetail extends IncidentSummary {
  description: string;
  impact: Impact;
  urgency: Urgency;
  affectedUsers: number;
  priorityScore: number | null;
  priorityExplanation: string | null;
  categoryName: string | null;
  serviceId: number | null;
  responseDueAt: string | null;
  acknowledgedAt: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  resolutionSummary: string | null;
  rootCause: string | null;
  feedbackRating: number | null;
  feedbackComment: string | null;
  updatedAt: string;
  version: number;
  actions: string[];
  noteTypes: string[];
  /** Request fields of PATCH /api/incidents/{id} the viewer may change (empty unless EDIT is in `actions`). */
  editableFields?: string[];
}

export interface IncidentNote {
  id: number;
  type: NoteType;
  body: string;
  author: PersonRef;
  createdAt: string;
}

export interface CreateIncidentRequest {
  title: string;
  description: string;
  impact: Impact;
  urgency: Urgency;
  affectedUsers: number;
  serviceId?: number;
  categoryCode?: string;
}

/** PATCH /api/incidents/{id}: only the given fields change; `clearService` removes the service. */
export interface UpdateIncidentRequest {
  title?: string;
  description?: string;
  impact?: Impact;
  urgency?: Urgency;
  affectedUsers?: number;
  serviceId?: number;
  clearService?: boolean;
}

export interface AddNoteRequest {
  type: NoteType;
  body: string;
}
export interface RequestInfoRequest {
  question: string;
}
export interface ResumeRequest {
  response?: string;
}
export interface ResolveRequest {
  summary: string;
  rootCause?: string;
}
export interface ReasonRequest {
  reason?: string;
}
export interface FeedbackRequest {
  rating: number;
  comment?: string;
}
export interface ClassifyRequest {
  categoryCode: string;
}
export interface AssignRequest {
  technicianId: number;
}

export interface CategorySummary {
  code: string;
  name: string;
  description: string | null;
}
export interface AdminCategory extends CategorySummary {
  active: boolean;
}
export interface CreateCategoryRequest {
  code: string;
  name: string;
  description?: string;
}
export interface UpdateCategoryRequest {
  name?: string;
  description?: string;
  active?: boolean;
}

/** GET /api/services (active services). `criticality` is 1 (lowest) to 4 (highest). */
export interface ServiceSummary {
  id: number;
  code: string;
  name: string;
  description: string | null;
  criticality: number;
}
/** GET /api/admin/services. */
export interface AdminService extends ServiceSummary {
  ownerTeam: string | null;
  active: boolean;
}
export interface UpdateServiceRequest {
  name?: string;
  description?: string;
  criticality?: number;
  ownerTeam?: string;
  active?: boolean;
}

/** GET /api/admin/settings answers a list of these; PUT /api/admin/settings/{key} takes `{ value }`. */
export interface SettingItem {
  key: string;
  value: string;
}

export interface CategorySuggestion {
  code: string;
  confidence: number;
  reason: string;
}

export interface SimilarIncident {
  id: number;
  number: string;
  title: string;
  status: IncidentStatus;
  score: number;
}

export interface TechnicianRecommendation {
  technicianId: number;
  technicianName: string;
  strategy: string;
  reason: string;
}

export interface SimilarQuery {
  title: string;
  description?: string;
  excludeId?: number;
}
export interface RecommendationQuery {
  category?: string;
  priority?: Priority;
}

// ---- incident operations: workload, SLA risk, correlation ----

export const DEADLINE_STATES = ['OVERDUE', 'AT_RISK', 'ON_TRACK'] as const;
export type DeadlineState = (typeof DEADLINE_STATES)[number];

export interface WorkloadIncident {
  id: number;
  number: string;
  title: string;
  status: IncidentStatus;
  priority: Priority | null;
  responseDueAt: string | null;
  resolutionDueAt: string | null;
  deadlineState: DeadlineState;
  riskReason: string | null;
}

/** GET /api/incidents/workload (technicians). */
export interface TechnicianWorkload {
  technicianId: number;
  fullName: string;
  available: boolean;
  activeCount: number;
  capacity: number;
  overCapacity: boolean;
  countsByStatus: Record<string, number>;
  nextDueAt: string | null;
  overdueCount: number;
  atRiskCount: number;
  incidents: WorkloadIncident[];
}

export interface SlaRiskItem {
  incidentId: number;
  number: string;
  title: string;
  status: IncidentStatus;
  priority: Priority | null;
  serviceName: string | null;
  assignee: PersonRef | null;
  state: 'BREACHED' | 'AT_RISK';
  riskReason: string;
  reasons: string[];
  dueKind: 'RESPONSE' | 'RESOLUTION';
  dueAt: string;
  /** Negative when the deadline has passed. */
  minutesRemaining: number;
  percentElapsed: number;
}

/** GET /api/admin/sla/at-risk. */
export interface SlaRiskReport {
  generatedAt: string;
  breached: SlaRiskItem[];
  atRisk: SlaRiskItem[];
}

export interface ClusterMember {
  id: number;
  number: string;
  title: string;
  status: IncidentStatus;
  createdAt: string;
}

/** GET /api/admin/correlations: possible major incidents. */
export interface IncidentCluster {
  key: string;
  kind: 'SERVICE' | 'CATEGORY';
  label: string;
  serviceId: number | null;
  categoryCode: string | null;
  count: number;
  firstAt: string;
  lastAt: string;
  windowMinutes: number;
  incidentIds: number[];
  incidentNumbers: string[];
  incidents: ClusterMember[];
}

/** GET /api/incidents/{id}/related (204 when the incident is in no cluster). */
export interface RelatedIncidents {
  key: string;
  kind: 'SERVICE' | 'CATEGORY';
  label: string;
  totalCount: number;
  windowMinutes: number;
  related: ClusterMember[];
}
