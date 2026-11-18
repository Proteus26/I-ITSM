// Pure helpers for the incident pages: form parsing/validation (mirrors the backend rules), the mapping from the
// backend's `actions` to forms, badge classes and deadline formatting. No Astro or fetch in here, so it is unit tested.
import { formString, formatDateTime, optional, parseIntField, parseOptionalInt } from './forms';
import { humanize } from './p4';
import {
  IMPACTS,
  INCIDENT_ACTIONS,
  NOTE_TYPES,
  URGENCIES,
  type AddNoteRequest,
  type AssignRequest,
  type ClassifyRequest,
  type CreateIncidentRequest,
  type FeedbackRequest,
  type Impact,
  type IncidentAction,
  type IncidentStatus,
  type NoteType,
  type Priority,
  type ReasonRequest,
  type RequestInfoRequest,
  type ResolveRequest,
  type ResumeRequest,
  type Urgency,
} from './incidentTypes';
import type { Role } from './types';

/** Backend limits (Incident.MAX_TITLE / MAX_TEXT). */
export const MAX_TITLE = 200;
export const MAX_TEXT = 4000;

export interface Option {
  value: string;
  label: string;
}

export const IMPACT_OPTIONS: Option[] = [
  { value: 'INDIVIDUAL', label: 'Only me' },
  { value: 'TEAM', label: 'My team' },
  { value: 'DEPARTMENT', label: 'My department' },
  { value: 'ORGANISATION', label: 'The whole organisation' },
];
export const IMPACT_HINT = 'How many people are affected by the problem.';

export const URGENCY_OPTIONS: Option[] = [
  { value: 'LOW', label: 'Low: it can wait' },
  { value: 'MEDIUM', label: 'Medium: work is slowed down' },
  { value: 'HIGH', label: 'High: work is blocked' },
  { value: 'CRITICAL', label: 'Critical: business stopped, no workaround' },
];
export const URGENCY_HINT = 'How quickly it needs to be fixed.';

export function impactLabel(v: string): string {
  return IMPACT_OPTIONS.find((o) => o.value === v)?.label ?? humanize(v);
}
export function urgencyLabel(v: string): string {
  return URGENCY_OPTIONS.find((o) => o.value === v)?.label.split(':')[0] ?? humanize(v);
}

// ---------------------------------------------------------------- report form

export interface ReportFormValues {
  title: string;
  description: string;
  impact: string;
  urgency: string;
  affectedUsers: string;
  serviceId: string;
  categoryCode: string;
}

export const EMPTY_REPORT_FORM: ReportFormValues = {
  title: '',
  description: '',
  impact: 'INDIVIDUAL',
  urgency: 'MEDIUM',
  affectedUsers: '1',
  serviceId: '',
  categoryCode: '',
};

export function readReportForm(form: FormData): ReportFormValues {
  return {
    title: formString(form, 'title'),
    description: formString(form, 'description'),
    impact: formString(form, 'impact'),
    urgency: formString(form, 'urgency'),
    affectedUsers: formString(form, 'affectedUsers'),
    serviceId: formString(form, 'serviceId'),
    categoryCode: formString(form, 'categoryCode'),
  };
}

export type ParseResult<T> = { ok: true; value: T } | { ok: false; errors: Record<string, string> };

/** Same rules as the backend: title 1..200, description 1..4000, affectedUsers >= 1; blank affectedUsers means 1. */
export function parseReportForm(v: ReportFormValues): ParseResult<CreateIncidentRequest> {
  const errors: Record<string, string> = {};
  if (!v.title) errors.title = 'Enter a title.';
  else if (v.title.length > MAX_TITLE) errors.title = `The title can have at most ${MAX_TITLE} characters.`;
  if (!v.description) errors.description = 'Describe the problem.';
  else if (v.description.length > MAX_TEXT) errors.description = `The description can have at most ${MAX_TEXT} characters.`;
  if (!(IMPACTS as readonly string[]).includes(v.impact)) errors.impact = 'Choose the impact.';
  if (!(URGENCIES as readonly string[]).includes(v.urgency)) errors.urgency = 'Choose the urgency.';

  const users = parseIntField(v.affectedUsers, { min: 1 });
  if (users.error) errors.affectedUsers = 'Enter a whole number, 1 or more.';

  let serviceId: number | undefined;
  if (v.serviceId) {
    serviceId = parseOptionalInt(v.serviceId, { min: 1 });
    if (serviceId === undefined) errors.serviceId = 'Choose a service from the list.';
  }
  const categoryCode = optional(v.categoryCode);
  if (categoryCode !== undefined && !CATEGORY_CODE.test(categoryCode)) errors.categoryCode = 'Choose a category from the list.';

  if (Object.keys(errors).length > 0) return { ok: false, errors };
  const value: CreateIncidentRequest = {
    title: v.title,
    description: v.description,
    impact: v.impact as Impact,
    urgency: v.urgency as Urgency,
    affectedUsers: users.value ?? 1,
  };
  if (serviceId !== undefined) value.serviceId = serviceId;
  if (categoryCode !== undefined) value.categoryCode = categoryCode;
  return { ok: true, value };
}

/** Category codes are upper-case words such as NETWORK or ACCESS_MGMT. */
export const CATEGORY_CODE = /^[A-Z][A-Z0-9_]{0,49}$/;

// ---------------------------------------------------------------- actions

/** The form intent for each backend action; the intent is also what the hidden field of the form carries. */
export const ACTION_INTENT: Record<IncidentAction, string> = {
  ACKNOWLEDGE: 'acknowledge',
  START: 'start',
  REQUEST_INFO: 'request-info',
  RESUME: 'resume',
  RESOLVE: 'resolve',
  VERIFY: 'verify',
  REOPEN: 'reopen',
  CLOSE: 'close',
  CANCEL: 'cancel',
  FEEDBACK: 'feedback',
  ASSIGN: 'assign',
  CLASSIFY: 'classify',
};

export const ACTION_LABEL: Record<IncidentAction, string> = {
  ACKNOWLEDGE: 'Acknowledge',
  START: 'Start work',
  REQUEST_INFO: 'Request information',
  RESUME: 'Resume work',
  RESOLVE: 'Resolve',
  VERIFY: 'Confirm the fix',
  REOPEN: 'Reopen',
  CLOSE: 'Close incident',
  CANCEL: 'Cancel incident',
  FEEDBACK: 'Send feedback',
  ASSIGN: 'Assign',
  CLASSIFY: 'Classify',
};

/** Actions that ask for confirmation (data-confirm, see public/scripts/confirm.js). */
export const CONFIRM_MESSAGE: Partial<Record<IncidentAction, string>> = {
  CANCEL: 'Cancel this incident? This cannot be undone.',
  CLOSE: 'Close this incident? It cannot be changed afterwards.',
};

/** Actions that need no input: a button only. */
export const SIMPLE_ACTIONS: readonly IncidentAction[] = ['ACKNOWLEDGE', 'START', 'VERIFY', 'CLOSE'];

export function isIncidentAction(v: string): v is IncidentAction {
  return (INCIDENT_ACTIONS as readonly string[]).includes(v);
}

/** The known actions out of what the backend sent, in the fixed order of INCIDENT_ACTIONS (unknown ones are dropped). */
export function visibleActions(actions: readonly string[] | null | undefined): IncidentAction[] {
  const set = new Set(actions ?? []);
  return INCIDENT_ACTIONS.filter((a) => set.has(a));
}

/** The note types the viewer may add, known ones only, in a fixed order. */
export function allowedNoteTypes(types: readonly string[] | null | undefined): NoteType[] {
  const set = new Set(types ?? []);
  return NOTE_TYPES.filter((t) => set.has(t));
}

export const NOTE_TYPE_LABEL: Record<NoteType, string> = {
  INVESTIGATION: 'Internal investigation note',
  INFO_REQUEST: 'Request for information',
  INFO_RESPONSE: 'Information from the reporter',
  COMMENT: 'Comment',
};

function textField(form: FormData, name: string, label: string, required: boolean, errors: Record<string, string>): string | undefined {
  const text = formString(form, name);
  if (!text) {
    if (required) errors[name] = `${label} is required.`;
    return undefined;
  }
  if (text.length > MAX_TEXT) errors[name] = `${label} can have at most ${MAX_TEXT} characters.`;
  return text;
}

export type ActionBody =
  | undefined
  | RequestInfoRequest
  | ResumeRequest
  | ResolveRequest
  | ReasonRequest
  | FeedbackRequest
  | ClassifyRequest
  | AssignRequest;

/** Validates the form of an action and builds its request body (undefined when the call has no body). */
export function parseActionForm(action: IncidentAction, form: FormData): ParseResult<ActionBody> {
  const errors: Record<string, string> = {};
  let body: ActionBody;
  switch (action) {
    case 'REQUEST_INFO': {
      const question = textField(form, 'question', 'The question', true, errors);
      body = { question: question ?? '' };
      break;
    }
    case 'RESUME': {
      const response = textField(form, 'response', 'The response', false, errors);
      body = response === undefined ? {} : { response };
      break;
    }
    case 'RESOLVE': {
      const summary = textField(form, 'summary', 'The summary', true, errors);
      const rootCause = textField(form, 'rootCause', 'The root cause', false, errors);
      body = { summary: summary ?? '', ...(rootCause === undefined ? {} : { rootCause }) };
      break;
    }
    case 'REOPEN':
    case 'CANCEL': {
      const reason = textField(form, 'reason', 'The reason', false, errors);
      body = reason === undefined ? {} : { reason };
      break;
    }
    case 'FEEDBACK': {
      const rating = parseOptionalInt(formString(form, 'rating'), { min: 1, max: 5 });
      if (rating === undefined) errors.rating = 'Choose a rating from 1 to 5.';
      const comment = textField(form, 'comment', 'The comment', false, errors);
      body = { rating: rating ?? 0, ...(comment === undefined ? {} : { comment }) };
      break;
    }
    case 'CLASSIFY': {
      const categoryCode = formString(form, 'categoryCode');
      if (!CATEGORY_CODE.test(categoryCode)) errors.categoryCode = 'Choose a category.';
      body = { categoryCode };
      break;
    }
    case 'ASSIGN': {
      const technicianId = parseOptionalInt(formString(form, 'technicianId'), { min: 1 });
      if (technicianId === undefined) errors.technicianId = 'Choose a technician.';
      body = { technicianId: technicianId ?? 0 };
      break;
    }
    default:
      body = undefined; // ACKNOWLEDGE, START, VERIFY, CLOSE
  }
  return Object.keys(errors).length > 0 ? { ok: false, errors } : { ok: true, value: body };
}

/** Validates the "add a note" form; `allowed` is the `noteTypes` the backend returned for the viewer. */
export function parseNoteForm(form: FormData, allowed: readonly NoteType[]): ParseResult<AddNoteRequest> {
  const errors: Record<string, string> = {};
  const type = formString(form, 'type') || (allowed.length === 1 ? allowed[0]! : '');
  if (!(allowed as readonly string[]).includes(type)) errors.type = 'Choose a note type.';
  const body = textField(form, 'body', 'The note', true, errors);
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, value: { type: type as NoteType, body: body! } };
}

// ---------------------------------------------------------------- badges

export type Tone = 'info' | 'warn' | 'success' | 'neutral' | 'danger';

const STATUS_TONE: Record<IncidentStatus, Tone> = {
  NEW: 'info',
  CLASSIFIED: 'info',
  ASSIGNED: 'info',
  ACKNOWLEDGED: 'info',
  IN_PROGRESS: 'info',
  PENDING_INFO: 'warn',
  RESOLVED: 'success',
  VERIFIED: 'success',
  CLOSED: 'neutral',
  CANCELLED: 'neutral',
};

const PRIORITY_TONE: Record<Priority, Tone> = { P1: 'danger', P2: 'warn', P3: 'info', P4: 'neutral' };

/** CSS class (defined in global.css) for a status badge; unknown statuses are neutral. */
export function statusBadgeClass(status: string): string {
  return `badge badge-${Object.hasOwn(STATUS_TONE, status) ? STATUS_TONE[status as IncidentStatus] : 'neutral'}`;
}

/** CSS class for a priority badge; no priority yet is neutral. */
export function priorityBadgeClass(priority: string | null | undefined): string {
  return `badge badge-${priority && Object.hasOwn(PRIORITY_TONE, priority) ? PRIORITY_TONE[priority as Priority] : 'neutral'}`;
}

export function statusLabel(status: string): string {
  return humanize(status);
}

// ---------------------------------------------------------------- deadlines

/** Statuses after which a deadline no longer matters. */
const FINISHED: readonly string[] = ['RESOLVED', 'VERIFIED', 'CLOSED', 'CANCELLED'];

export function isFinished(status: string): boolean {
  return FINISHED.includes(status);
}

export interface DueInfo {
  text: string;
  overdue: boolean;
}

/**
 * A deadline for display. It is overdue when it is in the past and the work it limits is not done:
 * `done` is true for a finished incident (or, for the response deadline, an acknowledged one).
 */
export function dueInfo(due: string | null | undefined, done: boolean, now: Date = new Date()): DueInfo {
  if (!due) return { text: 'Not set', overdue: false };
  const t = new Date(due).getTime();
  return { text: formatDateTime(due), overdue: !done && !Number.isNaN(t) && t < now.getTime() };
}

// ---------------------------------------------------------------- lists

export type IncidentTab = 'mine' | 'assigned';

/** The tab to show: technicians may pick "assigned" (their default), everyone else always sees "mine". */
export function parseTab(raw: string | null, role: Role): IncidentTab {
  if (role !== 'TECHNICIAN') return 'mine';
  return raw === 'mine' ? 'mine' : 'assigned';
}

export function incidentHref(id: number): string {
  return `/incidents/${id}`;
}

/** Success notices of the detail page (`?notice=`), keyed by form intent. */
export const NOTICES: Record<string, string> = {
  reported: 'Your incident was reported.',
  acknowledge: 'Incident acknowledged.',
  start: 'Work started.',
  'request-info': 'Information requested. The incident is waiting for the reporter.',
  resume: 'Work resumed.',
  resolve: 'Incident resolved.',
  verify: 'Fix confirmed.',
  reopen: 'Incident reopened.',
  close: 'Incident closed.',
  cancel: 'Incident cancelled.',
  feedback: 'Thank you for your feedback.',
  assign: 'Incident assigned.',
  classify: 'Incident classified.',
  'add-note': 'Note added.',
};
