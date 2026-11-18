import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackendClient } from '../src/lib/backend';

const base = 'http://backend:8080';
let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchMock = vi.fn(async () => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const api = () => new BackendClient('tok', base);
const last = () => {
  const [url, init] = fetchMock.mock.calls.at(-1)!;
  const i = init as RequestInit;
  return { url: String(url), method: i.method, body: i.body === undefined ? undefined : JSON.parse(i.body as string) };
};

describe('incident endpoints', () => {
  it.each([
    ['createIncident', () => api().createIncident({ title: 't', description: 'd', impact: 'TEAM', urgency: 'HIGH', affectedUsers: 3, serviceId: 2 }), 'POST', '/api/incidents', { title: 't', description: 'd', impact: 'TEAM', urgency: 'HIGH', affectedUsers: 3, serviceId: 2 }],
    ['myIncidents', () => api().myIncidents({ page: 1, size: 15 }), 'GET', '/api/incidents/mine?page=1&size=15', undefined],
    ['myIncidents (no query)', () => api().myIncidents(), 'GET', '/api/incidents/mine', undefined],
    ['assignedIncidents', () => api().assignedIncidents({ page: 0, size: 15 }), 'GET', '/api/incidents/assigned?page=0&size=15', undefined],
    ['getIncident', () => api().getIncident(9), 'GET', '/api/incidents/9', undefined],
    ['incidentNotes', () => api().incidentNotes(9), 'GET', '/api/incidents/9/notes', undefined],
    ['addIncidentNote', () => api().addIncidentNote(9, { type: 'COMMENT', body: 'hi' }), 'POST', '/api/incidents/9/notes', { type: 'COMMENT', body: 'hi' }],
    ['acknowledgeIncident', () => api().acknowledgeIncident(9), 'POST', '/api/incidents/9/acknowledge', undefined],
    ['startIncident', () => api().startIncident(9), 'POST', '/api/incidents/9/start', undefined],
    ['requestInfo', () => api().requestInfo(9, { question: 'q?' }), 'POST', '/api/incidents/9/request-info', { question: 'q?' }],
    ['resumeIncident', () => api().resumeIncident(9, { response: 'r' }), 'POST', '/api/incidents/9/resume', { response: 'r' }],
    ['resumeIncident (empty)', () => api().resumeIncident(9), 'POST', '/api/incidents/9/resume', {}],
    ['resolveIncident', () => api().resolveIncident(9, { summary: 's', rootCause: 'c' }), 'POST', '/api/incidents/9/resolve', { summary: 's', rootCause: 'c' }],
    ['verifyIncident', () => api().verifyIncident(9), 'POST', '/api/incidents/9/verify', undefined],
    ['reopenIncident', () => api().reopenIncident(9, { reason: 'again' }), 'POST', '/api/incidents/9/reopen', { reason: 'again' }],
    ['closeIncident', () => api().closeIncident(9), 'POST', '/api/incidents/9/close', undefined],
    ['cancelIncident', () => api().cancelIncident(9), 'POST', '/api/incidents/9/cancel', {}],
    ['submitFeedback', () => api().submitFeedback(9, { rating: 4, comment: 'ok' }), 'POST', '/api/incidents/9/feedback', { rating: 4, comment: 'ok' }],
    ['classifyIncident', () => api().classifyIncident(9, { categoryCode: 'NETWORK' }), 'POST', '/api/admin/incidents/9/classify', { categoryCode: 'NETWORK' }],
    ['assignIncident', () => api().assignIncident(9, { technicianId: 4 }), 'POST', '/api/admin/incidents/9/assign', { technicianId: 4 }],
  ])('%s', async (_name, run, method, path, body) => {
    await run();
    expect(last()).toEqual({ url: `${base}${path}`, method, body });
  });
});

describe('categories, services, settings, intelligence', () => {
  it.each([
    ['listCategories', () => api().listCategories(), 'GET', '/api/categories', undefined],
    ['adminCategories', () => api().adminCategories(), 'GET', '/api/admin/categories', undefined],
    ['createCategory', () => api().createCategory({ code: 'NET', name: 'Network' }), 'POST', '/api/admin/categories', { code: 'NET', name: 'Network' }],
    ['updateCategory', () => api().updateCategory('NET', { active: false }), 'PATCH', '/api/admin/categories/NET', { active: false }],
    ['updateCategory (encoded)', () => api().updateCategory('A/B', { name: 'x' }), 'PATCH', '/api/admin/categories/A%2FB', { name: 'x' }],
    ['listServices', () => api().listServices(), 'GET', '/api/services', undefined],
    ['adminServices', () => api().adminServices(), 'GET', '/api/admin/services', undefined],
    ['updateService', () => api().updateService(5, { criticality: 4 }), 'PATCH', '/api/admin/services/5', { criticality: 4 }],
    ['suggestCategory', () => api().suggestCategory('VPN & more', 'a b'), 'GET', '/api/intelligence/suggest-category?title=VPN+%26+more&description=a+b', undefined],
    ['similarIncidents', () => api().similarIncidents({ title: 't', description: 'd', excludeId: 3 }), 'GET', '/api/intelligence/similar?title=t&description=d&excludeId=3', undefined],
    ['assignmentRecommendations', () => api().assignmentRecommendations({ category: 'NETWORK', priority: 'P1' }), 'GET', '/api/admin/assignment/recommendations?category=NETWORK&priority=P1', undefined],
    ['assignmentRecommendations (none)', () => api().assignmentRecommendations(), 'GET', '/api/admin/assignment/recommendations', undefined],
    ['createService', () => api().createService({ code: 'X', name: 'X' }), 'POST', '/api/admin/services', { code: 'X', name: 'X' }],
    ['slaPolicies', () => api().slaPolicies(), 'GET', '/api/admin/sla-policies', undefined],
    ['createSlaPolicy', () => api().createSlaPolicy({ serviceId: 1, priority: 'P1', responseMinutes: 1, resolutionMinutes: 2 }), 'POST', '/api/admin/sla-policies', { serviceId: 1, priority: 'P1', responseMinutes: 1, resolutionMinutes: 2 }],
    ['updateSlaPolicy', () => api().updateSlaPolicy(4, { warningThresholdPct: 60 }), 'PATCH', '/api/admin/sla-policies/4', { warningThresholdPct: 60 }],
    ['deleteSlaPolicy', () => api().deleteSlaPolicy(4), 'DELETE', '/api/admin/sla-policies/4', undefined],
    ['getSettings', () => api().getSettings(), 'GET', '/api/admin/settings', undefined],
    ['updateSetting', () => api().updateSetting('priority.strategy', 'matrix'), 'PUT', '/api/admin/settings/priority.strategy', { value: 'matrix' }],
  ])('%s', async (_name, run, method, path, body) => {
    await run();
    expect(last()).toEqual({ url: `${base}${path}`, method, body });
  });

  it('suggestCategory answers undefined for 204', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(api().suggestCategory('t')).resolves.toBeUndefined();
  });
});
