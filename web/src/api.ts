import type { NetDefinition, NetFaultType, SensorFaultType, SimEvent, Snapshot } from './types';

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let data: unknown;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    data = text;
  }
  if (!response.ok) {
    const message = (data as { error?: string; message?: string } | undefined);
    throw new Error(message?.error ?? message?.message ?? `Request failed (${response.status})`);
  }
  return data as T;
}

const sim = (id: string) => `/simulations/${id}`;

/** The simulator's control API (ControlController). Applications use the layout's own API instead. */
export const api = {
  layouts: () => request<NetDefinition[]>('GET', '/layouts'),
  simulations: () => request<Snapshot[]>('GET', '/simulations'),
  create: (layoutId: string) => request<{ id: string }>('POST', '/simulations', { layoutId }),
  snapshot: (id: string) => request<Snapshot>('GET', sim(id)),
  remove: (id: string) => request<void>('DELETE', sim(id)),
  start: (id: string) => request<Snapshot>('POST', `${sim(id)}/start`),
  stop: (id: string) => request<Snapshot>('POST', `${sim(id)}/stop`),
  reset: (id: string) => request<Snapshot>('POST', `${sim(id)}/reset`),
  step: (id: string) => request<{ stepped: boolean; time: string }>('POST', `${sim(id)}/step`),
  setSpeed: (id: string, factor: number) => request<Snapshot>('PUT', `${sim(id)}/speed`, { factor }),
  stopRealTime: (id: string) => request<Snapshot>('DELETE', `${sim(id)}/speed`),
  events: (id: string, from: number) => request<SimEvent[]>('GET', `${sim(id)}/events?from=${from}`),

  placeToken: (id: string, place: string, attributes: Record<string, unknown>) =>
    request<{ id: string }>('POST', `${sim(id)}/tokens`, { place, attributes }),
  removeToken: (id: string, tokenId: string) => request<Snapshot>('DELETE', `${sim(id)}/tokens/${tokenId}`),

  setSensorFault: (id: string, sensor: string, type: SensorFaultType, value: unknown, delay: string | null) =>
    request<Snapshot>('PUT', `${sim(id)}/faults/sensors/${sensor}`, { type, value, delay }),
  clearSensorFault: (id: string, sensor: string) => request<Snapshot>('DELETE', `${sim(id)}/faults/sensors/${sensor}`),
  setNetFault: (id: string, transition: string, type: NetFaultType, factor: number | null, place: string | null) =>
    request<Snapshot>('PUT', `${sim(id)}/faults/transitions/${transition}`, { type, factor, place }),
  clearNetFault: (id: string, transition: string) =>
    request<Snapshot>('DELETE', `${sim(id)}/faults/transitions/${transition}`),

  setSetting: (id: string, setting: string, value: number) =>
    request<Snapshot>('PUT', `${sim(id)}/settings/${setting}`, { value }),
  resetSetting: (id: string, setting: string) => request<Snapshot>('DELETE', `${sim(id)}/settings/${setting}`),

  setModules: (id: string, modules: string[]) => request<Snapshot>('PUT', `${sim(id)}/modules`, { modules }),

  /** Puts a request token into a command place, as the layout's API would, without waiting for it. */
  command: (id: string, place: string, parameters: Record<string, unknown>, returnsAfter: string[]) =>
    request<{ commandId: string }>('POST', `${sim(id)}/commands`, { place, parameters, returnsAfter, await: false }),
};
