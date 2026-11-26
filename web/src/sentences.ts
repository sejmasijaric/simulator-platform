import type { NetDefinition, SimEvent } from './types';
import { formatValue, routeForCommandPlace } from './net';
import { human, seconds } from './time';

export type Actor = 'application' | 'you' | 'system';

export function actorOf(event: SimEvent): Actor {
  if (event.origin === 'application') return 'application';
  if (event.origin === 'control') return 'you';
  return 'system';
}

/** Token movements inside a firing are details; requests, answers and faults are the story. */
export function isMinor(event: SimEvent): boolean {
  return event.type === 'TRANSITION_COMPLETED';
}

function list(values: unknown): string {
  if (!values || typeof values !== 'object') return '';
  return Object.entries(values as Record<string, unknown>).map(([k, v]) => `${k}=${formatValue(v)}`).join(', ');
}

function moves(entries: unknown): string {
  if (!Array.isArray(entries)) return '';
  return entries.map((e: { place?: string; token?: string }) => `${e.token} ${e.place ? `@ ${e.place}` : ''}`.trim()).join(', ');
}

/** The node an event is about, for highlighting it in the views. */
export function subjectOf(event: SimEvent): string | null {
  return event.subject;
}

export function sentence(event: SimEvent, net: NetDefinition | undefined): string {
  const subject = event.subject ?? '';
  const data = event.data;
  switch (event.type) {
    case 'SIMULATION_STARTED':
      return 'Simulation started';
    case 'SIMULATION_STOPPED':
      return 'Simulation paused';
    case 'SIMULATION_RESET':
      return 'Back to the initial marking';
    case 'COMMAND_RECEIVED': {
      const route = net && routeForCommandPlace(net, subject);
      const params = list(data.parameters);
      return route
        ? `${route.method.toUpperCase()} ${route.path}${params ? `?${params.replaceAll(', ', '&')}` : ''} → request in ${subject}`
        : `Request${params ? ` (${params})` : ''} put into ${subject}`;
    }
    case 'COMMAND_COMPLETED': {
      const result = list(data.result);
      return `Answered after ${subject}${result ? `: ${result}` : ''}`;
    }
    case 'TRANSITION_STARTED':
      return `${subject} fires (${human(seconds(String(data.duration ?? 'PT0S')))}) · takes ${moves(data.consumed)}`;
    case 'TRANSITION_COMPLETED':
      return `${subject} done · puts ${moves(data.produced) || 'nothing'}${data.result ? ` · ${list(data.result)}` : ''}`;
    case 'TOKEN_PLACED':
      return `Token ${data.token} put into ${subject}${list(data.attributes) ? ` (${list(data.attributes)})` : ''}`;
    case 'TOKEN_REMOVED':
      return `Token ${data.token} taken out of ${subject}`;
    case 'TOKEN_LOST':
      return `Token ${subject} lost in ${data.transition}`;
    case 'FAULT_ACTIVATED':
      return `${data.level === 'sensor' ? 'Sensor' : 'Transition'} fault ${String(data.fault).toLowerCase()} on ${subject}`;
    case 'FAULT_CLEARED':
      return `${data.level === 'sensor' ? 'Sensor' : 'Transition'} fault cleared on ${subject}`;
    case 'MODULES_CHANGED':
      return Array.isArray(data.modules) && data.modules.length ? `Only modules ${data.modules.join(', ')} run` : 'All modules run';
    default:
      return `${String(event.type).toLowerCase().replaceAll('_', ' ')} ${subject}`.trim();
  }
}
