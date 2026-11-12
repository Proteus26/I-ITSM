/** Types mirroring the backend's JSON (see backend/src/main/kotlin/com/itsm/user). */

export const ROLES = ['EMPLOYEE', 'TECHNICIAN', 'ADMIN'] as const;
export type Role = (typeof ROLES)[number];

export const TECHNICIAN_LEVELS = ['L1', 'L2', 'TEAM_LEAD'] as const;
export type TechnicianLevel = (typeof TECHNICIAN_LEVELS)[number];

export interface TechnicianDetails {
  level: TechnicianLevel;
  maxActiveIncidents: number;
  available: boolean;
  skills: string[];
}

export interface UserResponse {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  active: boolean;
  /** ISO instant */
  createdAt: string;
  /** ISO instant of the last successful login; null (or absent) when the user never logged in. */
  lastLoginAt?: string | null;
  /** ISO instant of the login before the last one; null (or absent) until the second login. */
  previousLoginAt?: string | null;
  department?: string | null;
  technician?: TechnicianDetails | null;
}

export interface LoginResponse {
  token: string;
  tokenType: string;
  /** ISO instant */
  expiresAt: string;
  user: UserResponse;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface RegisterRequest {
  email: string;
  fullName: string;
  password: string;
  department?: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface UpdateProfileRequest {
  fullName?: string;
  /** Employees only; a blank value clears it. */
  department?: string;
}

export interface CreateUserRequest {
  role: Role;
  email: string;
  fullName: string;
  password: string;
  department?: string;
  level?: TechnicianLevel;
  maxActiveIncidents?: number;
  skills?: string[];
}

export interface UpdateUserRequest {
  fullName?: string;
  email?: string;
  active?: boolean;
  department?: string;
  level?: TechnicianLevel;
  maxActiveIncidents?: number;
  available?: boolean;
  skills?: string[];
}

export interface ListUsersQuery {
  role?: Role;
  active?: boolean;
  q?: string;
  page?: number;
  size?: number;
}

export interface Ping {
  status: string;
  service: string;
  time: string;
}
