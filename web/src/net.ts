import type { NetDefinition, Route, RunningFiring, Snapshot, Token, Transition } from './types';
import { human, seconds } from './time';

/** What a place stands for. Same rules as scripts/render-petri-nets.py, so UI and docs agree. */
export type PlaceKind = 'position' | 'resource' | 'command' | 'job';

export const KIND_LABEL: Record<PlaceKind, string> = {
  position: 'position of a workpiece',
  resource: 'machine ready (resource)',
  command: 'request queue (from the API)',
  job: 'request in progress (job)',
};

export const isTemplate = (name: string) => name.includes('${');

export function placeKinds(net: NetDefinition): Record<string, PlaceKind> {
  const commands = new Set(net.http.routes.map((r) => r.command).filter(Boolean) as string[]);
  const kinds: Record<string, PlaceKind> = {};
  for (const place of net.places) {
    kinds[place.name] = commands.has(place.name) ? 'command'
      : place.initial > 0 && place.capacity === 1 ? 'resource' : 'position';
  }
  let changed = true;
  while (changed) {
    changed = false;
    for (const transition of net.transitions) {
      const requestVariables = new Set(transition.inputs
        .filter((arc) => arc.variable && (kinds[arc.place] === 'command' || kinds[arc.place] === 'job'))
        .map((arc) => arc.variable));
      for (const arc of transition.outputs) {
        if (kinds[arc.place] === 'position' && arc.variable && requestVariables.has(arc.variable)) {
          kinds[arc.place] = 'job';
          changed = true;
        }
      }
    }
  }
  return kinds;
}

export function modulesOf(net: NetDefinition): string[] {
  const seen: string[] = [];
  for (const item of [...net.places, ...net.transitions]) {
    if (item.module && !seen.includes(item.module)) seen.push(item.module);
  }
  return seen;
}

/** Fills ${name} placeholders; null if one is missing. */
export function render(template: string, values: Record<string, unknown>): string | null {
  let missing = false;
  const text = template.replace(/\$\{([^}]+)}/g, (_, key: string) => {
    const value = values[key];
    if (value === undefined || value === null) missing = true;
    return String(value);
  });
  return missing ? null : text;
}

/** The request parameters of a firing: the tokens' attributes, the earlier arc winning. */
export function firingValues(tokens: Token[]): Record<string, unknown> {
  const values: Record<string, unknown> = {};
  for (const token of [...tokens].reverse()) Object.assign(values, token.attributes);
  return values;
}

/** Where each token of a running firing will be put, by matching arc variables. */
export function destinations(firing: RunningFiring, transition: Transition): { token: Token; place: string }[] {
  const values = firingValues(firing.tokens);
  const result: { token: Token; place: string }[] = [];
  transition.inputs.forEach((arc, index) => {
    const token = firing.tokens[index];
    if (!token || !arc.variable) return;
    const out = transition.outputs.find((o) => o.variable === arc.variable);
    const place = out ? (isTemplate(out.place) ? render(out.place, values) : out.place) : null;
    if (place) result.push({ token, place });
  });
  return result;
}

export function durationText(transition: Transition): string {
  const base = seconds(transition.duration);
  const parts = [];
  if (base > 0 || !transition.durationPerUnit) parts.push(human(base));
  if (transition.durationPerUnit && transition.durationParameter) {
    parts.push(`${human(seconds(transition.durationPerUnit))} × ${transition.durationParameter}`);
  }
  return parts.join(' + ');
}

export function guardText(transition: Transition): string {
  return Object.entries(transition.guard)
    .map(([key, value]) => value.startsWith('!') ? `${key} ≠ ${value.slice(1)}` : `${key} = ${value}`)
    .join(', ');
}

export function resultText(transition: Transition): string {
  return Object.entries(transition.result).map(([key, value]) => `${key} := ${value}`).join(', ');
}

export function routeForCommandPlace(net: NetDefinition, place: string | null): Route | undefined {
  return place ? net.http.routes.find((route) => route.command === place) : undefined;
}

export function routesWaitingFor(net: NetDefinition, transition: string): Route[] {
  return net.http.routes.filter((route) => route.returnsAfter.includes(transition));
}

export function tokensAt(snapshot: Snapshot, place: string): Token[] {
  return snapshot.marking[place] ?? [];
}

/** Is the transition allowed to fire in the current module selection? */
export function isActive(snapshot: Snapshot, module: string | null): boolean {
  return snapshot.activeModules.length === 0 || module === null || snapshot.activeModules.includes(module);
}

const NAMED: Record<string, string> = {
  red: '#D85A30',
  white: '#F1EFE8',
  blue: '#378ADD',
  green: '#639922',
  yellow: '#EF9F27',
  black: '#2C2C2A',
};

export function colorOf(attributes: Record<string, unknown> | undefined): string | null {
  const color = attributes?.color;
  return typeof color === 'string' ? (NAMED[color.toLowerCase()] ?? color) : null;
}

/** A workpiece shows its colour, a request is blue-ish, a plain (resource) token is dark. */
export function tokenFill(token: Token, kind: PlaceKind | undefined): string {
  return colorOf(token.attributes)
    ?? (kind === 'command' || kind === 'job' ? 'var(--request)' : 'var(--token)');
}

export function describeToken(token: Token): string {
  const attributes = Object.entries(token.attributes).map(([k, v]) => `${k} ${String(v)}`).join(', ');
  return attributes ? `${token.id} (${attributes})` : token.id;
}

/** Sensor value derived from the marking, before faults: lights up a marker on the floor. */
export function sensorLit(net: NetDefinition, snapshot: Snapshot, sensorName: string): boolean {
  const sensor = net.sensors.find((s) => s.name === sensorName);
  if (!sensor) return false;
  const tokens = tokensAt(snapshot, sensor.place);
  return sensor.kind === 'ATTRIBUTE' ? tokens[0]?.attributes[sensor.attribute ?? ''] != null : tokens.length > 0;
}

export function formatValue(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

// ---- grouping by station (module) ----

export function moduleOfPlace(net: NetDefinition, place: string | null | undefined): string | null {
  return net.places.find((p) => p.name === place)?.module ?? null;
}

export function moduleOfSensor(net: NetDefinition, sensor: string): string | null {
  return moduleOfPlace(net, net.sensors.find((s) => s.name === sensor)?.place);
}

/** The station a route belongs to: its command place, or the sensor it reads. */
export function moduleOfRoute(net: NetDefinition, route: Route): string | null {
  if (route.command) return moduleOfPlace(net, route.command);
  if (!route.reads) return null;
  const parameter = route.parameters.find((p) => route.reads!.includes(`\${${p.name}}`));
  const sensor = parameter && parameter.values.length > 0
    ? render(route.reads, { [parameter.name]: parameter.values[0] })
    : route.reads;
  return sensor ? moduleOfSensor(net, sensor) : null;
}

/** Sensors a payload template uses, in order: ${sensor.ov_i5} → ov_i5. */
export function sensorsIn(template: string): string[] {
  return [...new Set([...template.matchAll(/\$\{sensor\.([\w-]+)}/g)].map((m) => m[1]))];
}

/** A token in words: "red workpiece", "request (time 5)", "machine ready". */
export function tokenLabel(token: Token, kind: PlaceKind | undefined): string {
  const color = token.attributes.color;
  if (typeof color === 'string') return `${color} workpiece`;
  if (kind === 'command' || kind === 'job') {
    const parameters = Object.entries(token.attributes).map(([k, v]) => `${k} ${String(v)}`).join(', ');
    return parameters ? `request (${parameters})` : 'request';
  }
  if (kind === 'resource') return 'machine ready';
  const attributes = Object.entries(token.attributes).map(([k, v]) => `${k} ${String(v)}`).join(', ');
  return attributes ? `token (${attributes})` : 'token';
}
