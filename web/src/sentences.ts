import type { EntityView, SimEvent } from './types';

export type Actor = 'application' | 'you' | 'system';

export function actorOf(event: SimEvent): Actor {
  if (event.origin === 'application') return 'application';
  if (event.origin === 'control') return 'you';
  return 'system';
}

/** Minor events are shown smaller; they describe consequences rather than steps. */
export function isMinor(event: SimEvent): boolean {
  return event.type === 'COMPONENT_STATE_CHANGED';
}

function describeValue(value: unknown): string {
  if (typeof value === 'boolean') return value ? 'yes' : 'no';
  if (value === '' || value === null || value === undefined) return '–';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function list(values: Record<string, unknown> | undefined): string {
  if (!values) return '';
  return Object.entries(values).map(([key, value]) => `${key} ${describeValue(value)}`).join(', ');
}

export function entityLabel(entityId: unknown, entities: EntityView[]): string {
  const entity = entities.find((e) => e.id === entityId);
  const color = entity?.attributes?.color;
  return color ? `${color} ${entity?.type ?? 'item'} (${entityId})` : String(entityId);
}

export function sentence(event: SimEvent, entities: EntityView[]): string {
  const source = event.sourceComponentId ?? '';
  const p = event.payload;
  switch (event.type) {
    case 'SIMULATION_STARTED':
      return 'Simulation started';
    case 'SIMULATION_STOPPED':
      return 'Simulation stopped';
    case 'SIMULATION_RESET':
      return 'Simulation reset to its initial state';
    case 'ACTION_ACCEPTED': {
      const params = list(p.parameters as Record<string, unknown>);
      return `${source} starts ${p.capability}${params ? ` (${params})` : ''}`;
    }
    case 'ACTION_COMPLETED':
      return `${source} finished`;
    case 'ACTION_REJECTED':
      return `${source} refused ${p.capability}: ${p.reason}`;
    case 'ACTION_FAILED':
      return `${source} failed: ${p.reason}`;
    case 'CHANGE_REJECTED':
      return `${source} could not complete a step: ${p.reason}`;
    case 'ENTITY_CREATED':
      return `${entityLabel(p.entityId, entities)} placed at ${p.location}`;
    case 'ENTITY_MOVED':
      return p.from
        ? `${entityLabel(p.entityId, entities)} moved from ${p.from} to ${p.to}`
        : `${entityLabel(p.entityId, entities)} arrived at ${p.to}`;
    case 'ENTITY_REMOVED':
      return `${p.entityId} removed`;
    case 'ENTITY_UPDATED': {
      const { entityId, ...changes } = p;
      return `${entityLabel(entityId, entities)} changed: ${list(changes)}`;
    }
    case 'COMPONENT_STATE_CHANGED':
      return `${source}: ${list(p)}`;
    case 'FAULT_ACTIVATED':
      return `Fault ${p.fault} switched on for ${source}`;
    case 'FAULT_CLEARED':
      return `Fault ${p.fault} switched off for ${source}`;
    default:
      return `${event.type.toLowerCase().replaceAll('_', ' ')} ${source}`.trim();
  }
}
