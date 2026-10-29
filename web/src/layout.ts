import type { ComponentView, Definition, Point, Snapshot } from './types';

export type Kind = 'position' | 'machine' | 'sensor';

export function kindOf(component: ComponentView): Kind {
  if (component.capabilities.length > 0) return 'machine';
  if (component.capacity !== null) return 'position';
  return 'sensor';
}

export interface FloorLayout {
  width: number;
  height: number;
  background: string | null;
  positions: Record<string, Point>;
}

/**
 * Uses the definition's layout view where available. Other components are placed automatically:
 * positions left to right in connection order, sensors above, machines below.
 */
export function floorLayout(snapshot: Snapshot, definition: Definition | undefined): FloorLayout {
  const view = definition?.layoutView;
  const positions: Record<string, Point> = { ...(view?.positions ?? {}) };
  const missing = snapshot.components.filter((c) => !positions[c.id]);
  if (missing.length === 0 && view) {
    return { width: view.width, height: view.height, background: view.backgroundImage, positions };
  }

  const pads = orderByConnections(snapshot).filter((id) => !positions[id]);
  const machines = missing.filter((c) => kindOf(c) === 'machine').map((c) => c.id);
  const sensors = missing.filter((c) => kindOf(c) === 'sensor').map((c) => c.id);
  const columns = Math.max(pads.length, machines.length, sensors.length, 1);
  const width = Math.max(view?.width ?? 0, 160 + columns * 180);
  const baseY = view?.height ?? 0;
  const row = (ids: string[], y: number) =>
    ids.forEach((id, index) => (positions[id] = { x: 110 + index * 180, y: baseY + y }));
  row(sensors, 70);
  row(pads, 190);
  row(machines, 320);
  return { width, height: baseY + 400, background: view?.backgroundImage ?? null, positions };
}

function orderByConnections(snapshot: Snapshot): string[] {
  const pads = snapshot.components.filter((c) => kindOf(c) === 'position').map((c) => c.id);
  const incoming = new Set(snapshot.connections.map((c) => c.to));
  const ordered: string[] = [];
  const visit = (id: string) => {
    if (ordered.includes(id) || !pads.includes(id)) return;
    ordered.push(id);
    snapshot.connections.filter((c) => c.from === id).forEach((c) => visit(c.to));
  };
  pads.filter((id) => !incoming.has(id)).forEach(visit);
  pads.forEach(visit);
  return ordered;
}

const NAMED: Record<string, string> = {
  red: '#D85A30',
  white: '#F1EFE8',
  blue: '#378ADD',
  green: '#639922',
  yellow: '#EF9F27',
  black: '#2C2C2A',
};

export function entityColor(attributes: Record<string, unknown>): string {
  const color = attributes?.color;
  return typeof color === 'string' ? (NAMED[color.toLowerCase()] ?? color) : '#888780';
}
