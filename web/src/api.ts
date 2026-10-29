import type { Definition, RealTime, SimEvent, Snapshot } from './types';

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    throw new Error(data?.error ?? `Request failed (${response.status})`);
  }
  return data as T;
}

const sim = (id: string) => `/simulations/${id}`;

export const api = {
  definitions: () => request<Definition[]>('GET', '/simulation-definitions'),
  simulations: () => request<Snapshot[]>('GET', '/simulations'),
  create: (definitionId: string) => request<{ id: string }>('POST', '/simulations', { definitionId }),
  snapshot: (id: string) => request<Snapshot>('GET', sim(id)),
  remove: (id: string) => request<void>('DELETE', sim(id)),
  start: (id: string) => request<Snapshot>('POST', `${sim(id)}/start`),
  stop: (id: string) => request<Snapshot>('POST', `${sim(id)}/stop`),
  reset: (id: string) => request<Snapshot>('POST', `${sim(id)}/reset`),
  step: (id: string) => request<{ stepped: boolean; time: string }>('POST', `${sim(id)}/step`),
  events: (id: string, from: number) => request<SimEvent[]>('GET', `${sim(id)}/events?from=${from}`),
  realTime: (id: string) => request<RealTime>('GET', `${sim(id)}/real-time`),
  enableRealTime: (id: string, speedFactor: number) =>
    request<RealTime>('PUT', `${sim(id)}/real-time`, { speedFactor }),
  disableRealTime: (id: string) => request<RealTime>('DELETE', `${sim(id)}/real-time`),
  setFault: (id: string, componentId: string, faultId: string, active: boolean) =>
    request<Snapshot>('PUT', `${sim(id)}/components/${componentId}/faults/${faultId}`, { active }),
  placeEntity: (id: string, type: string, attributes: Record<string, unknown>, componentId: string) =>
    request<Snapshot>('POST', `${sim(id)}/entities`, { type, attributes, componentId }),
  removeEntity: (id: string, entityId: string) => request<Snapshot>('DELETE', `${sim(id)}/entities/${entityId}`),
};
