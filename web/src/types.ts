export type Status = 'CREATED' | 'RUNNING' | 'STOPPED' | 'FAILED';

export interface Point { x: number; y: number }

export interface LayoutView {
  width: number;
  height: number;
  backgroundImage: string | null;
  positions: Record<string, Point>;
}

export interface EntityTemplate {
  label: string;
  type: string;
  attributes: Record<string, unknown>;
}

export interface Definition {
  id: string;
  name: string;
  description: string;
  layoutView: LayoutView | null;
  entityTemplates: EntityTemplate[];
}

export interface FaultMode { id: string; description: string }

export interface Activity {
  commandId: string;
  capability: string;
  parameters: Record<string, unknown>;
  origin: string;
  startedAt: string;
  endsAt: string;
}

export interface ComponentView {
  id: string;
  type: string;
  state: { attributes: Record<string, unknown> };
  capabilities: string[];
  capacity: number | null;
  faultModes: FaultMode[];
  activeFaults: string[];
  activities: Activity[];
}

export interface EntityView {
  id: string;
  type: string;
  attributes: Record<string, unknown>;
  locationComponentId: string | null;
}

export interface Snapshot {
  id: string;
  definitionId: string;
  name: string;
  status: Status;
  time: string;
  components: ComponentView[];
  connections: { from: string; to: string }[];
  entities: EntityView[];
}

export interface SimEvent {
  id: string;
  timestamp: string;
  sourceComponentId: string | null;
  type: string;
  commandId: string | null;
  origin: string | null;
  payload: Record<string, unknown>;
}

export interface RealTime { enabled: boolean; speedFactor: number | null }
