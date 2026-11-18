// Types for the P4 endpoints (notifications, search, audit, reports). They mirror the Kotlin DTOs.

export interface NotificationItem {
  id: number;
  title: string;
  body: string;
  /** App-relative link built by the backend, e.g. /incidents/12. */
  link: string | null;
  incidentId: number | null;
  createdAt: string;
  readAt: string | null;
  read: boolean;
}

export const INCIDENT_STATUSES = [
  'NEW', 'CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO',
  'RESOLVED', 'VERIFIED', 'CLOSED', 'CANCELLED',
] as const;
export type IncidentStatus = (typeof INCIDENT_STATUSES)[number];

export const PRIORITIES = ['P1', 'P2', 'P3', 'P4'] as const;
export type Priority = (typeof PRIORITIES)[number];

export const SLA_STATUSES = ['ON_TRACK', 'AT_RISK', 'BREACHED'] as const;
export type SlaStatus = (typeof SLA_STATUSES)[number];

export interface IncidentSearchQuery {
  number?: string;
  q?: string;
  from?: string; // yyyy-mm-dd
  to?: string;
  status?: IncidentStatus;
  priority?: Priority;
  category?: string;
  service?: number;
  assignee?: number;
  reporter?: number;
  slaStatus?: SlaStatus;
  sort?: string;
  page?: number;
  size?: number;
}

export interface IncidentSearchResult {
  id: number;
  number: string;
  title: string;
  status: IncidentStatus;
  priority: Priority | null;
  categoryCode: string | null;
  serviceId: number | null;
  serviceName: string | null;
  assigneeId: number | null;
  assigneeName: string | null;
  reporterId: number;
  reporterName: string | null;
  createdAt: string;
  resolvedAt: string | null;
}

export interface AuditEntry {
  id: number | null;
  occurredAt: string;
  actorId: number | null;
  action: string;
  incidentId: number | null;
  fromState: string | null;
  toState: string | null;
  description: string;
}

export interface AuditQuery {
  actor?: number;
  action?: string;
  incident?: number;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface OverviewReport {
  total: number;
  open: number;
  critical: number;
  resolved: number;
  slaViolations: number;
}

export interface WorkloadRow {
  technicianId: number;
  technicianName: string;
  activeIncidents: number;
}

export interface ServiceCount {
  serviceId: number | null;
  serviceName: string;
  incidents: number;
}

export interface SlaCompliance {
  total: number;
  withinSla: number;
  breached: number;
  percentage: number;
}

export interface MttrRow {
  priority: Priority;
  resolvedCount: number;
  meanMinutes: number | null;
}

export interface TrendPoint {
  date: string; // yyyy-mm-dd
  count: number;
}
