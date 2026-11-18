import type { Priority } from './p4Types';

/** GET /api/admin/sla-policies. Service fields are null for a default policy. */
export interface SlaPolicy {
  id: number;
  serviceId: number | null;
  serviceCode: string | null;
  serviceName: string | null;
  priority: Priority;
  responseMinutes: number;
  resolutionMinutes: number;
  warningThresholdPct: number;
  isDefault: boolean;
}

export interface CreateSlaPolicyRequest {
  serviceId: number;
  priority: Priority;
  responseMinutes: number;
  resolutionMinutes: number;
  warningThresholdPct?: number;
}

export interface UpdateSlaPolicyRequest {
  responseMinutes?: number;
  resolutionMinutes?: number;
  warningThresholdPct?: number;
}

export interface CreateServiceRequest {
  code: string;
  name: string;
  description?: string;
  criticality?: number;
  ownerTeam?: string;
}
