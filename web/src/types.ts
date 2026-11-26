// Shapes of the control API (ControlController), as Jackson writes them. Durations are ISO-8601 ("PT5S").

export type Status = 'CREATED' | 'RUNNING' | 'STOPPED';

export interface Point { x: number; y: number }

export interface Arc { place: string; variable: string | null }

export interface Place { name: string; capacity: number; initial: number; module: string | null }

export interface Transition {
  name: string;
  inputs: Arc[];
  outputs: Arc[];
  duration: string;
  durationParameter: string | null;
  durationPerUnit: string | null;
  guard: Record<string, string>;
  inhibitors: string[];
  result: Record<string, string>;
  priority: number;
  module: string | null;
}

export type SensorKind = 'OCCUPIED' | 'COUNT' | 'ATTRIBUTE' | 'RUNNING';

export interface Sensor {
  name: string;
  kind: SensorKind;
  place: string;
  attribute: string | null;
  values: Record<string, unknown>;
  /** RUNNING: transition → text shown while it fires */
  running: Record<string, string>;
}

/** A value an application can change through the API, e.g. a motor speed. */
export interface Setting { name: string; initial: number; min: number; max: number; module: string | null }

export interface Parameter {
  name: string;
  required: boolean;
  values: string[];
  defaultValue: string | null;
  integer: boolean;
}

export interface Route {
  name: string;
  kind: 'COMMAND' | 'QUERY' | 'SETTING';
  method: string;
  path: string;
  parameters: Parameter[];
  command: string | null;
  returnsAfter: string[];
  timeout: string | null;
  reads: string | null;
  body: string | null;
  sets: Record<string, string>;
  resets: string[];
}

export interface EventTopic { protocol: string; topic: string; every: string | null; payload: string }

export interface Image { url: string; width: number; height: number; positions: Record<string, Point> }

export interface TokenTemplate { name: string; attributes: Record<string, unknown> }

export interface NetDefinition {
  id: string;
  name: string;
  description: string;
  places: Place[];
  transitions: Transition[];
  sensors: Sensor[];
  http: { envelope: string | null; routes: Route[] };
  events: EventTopic[];
  image: Image | null;
  tokenTemplates: TokenTemplate[];
  settings: Setting[];
}

export interface Token { id: string; attributes: Record<string, unknown>; commandId: string | null }

export interface RunningFiring {
  transition: string;
  tokens: Token[];
  commandId: string | null;
  started: string;
  ends: string;
}

export type SensorFaultType = 'STUCK' | 'DROPOUT' | 'DELAYED' | 'WRONG_VALUE';
export type NetFaultType = 'BLOCKED' | 'DELAYED' | 'TOKEN_LOSS' | 'MISROUTE';

export interface SensorFault { type: SensorFaultType; value: unknown; delay: string | null }
export interface NetFault { type: NetFaultType; factor: number; place: string | null }

export interface Snapshot {
  id: string;
  layoutId: string;
  status: Status;
  time: string;
  marking: Record<string, Token[]>;
  running: RunningFiring[];
  sensors: Record<string, unknown>;
  sensorFaults: Record<string, SensorFault>;
  netFaults: Record<string, NetFault>;
  activeModules: string[];
  speed: number | null;
  settings: Record<string, number>;
}

export type EventType =
  | 'SIMULATION_STARTED' | 'SIMULATION_STOPPED' | 'SIMULATION_RESET'
  | 'COMMAND_RECEIVED' | 'COMMAND_COMPLETED'
  | 'TRANSITION_STARTED' | 'TRANSITION_COMPLETED'
  | 'TOKEN_PLACED' | 'TOKEN_REMOVED' | 'TOKEN_LOST'
  | 'FAULT_ACTIVATED' | 'FAULT_CLEARED' | 'MODULES_CHANGED' | 'SETTING_CHANGED';

export interface SimEvent {
  index: number;
  time: string;
  type: EventType;
  subject: string | null;
  commandId: string | null;
  origin: string | null;
  data: Record<string, unknown>;
}

/** What the inspector shows: a place, a transition or a sensor of the net. */
export type Selection = { kind: 'place' | 'transition' | 'sensor'; name: string };
