import { useEffect, useState } from 'react';
import type { NetDefinition, NetFaultType, Selection, SensorFaultType, Snapshot, Transition } from './types';
import {
  durationText, formatValue, guardText, isTemplate, KIND_LABEL, type PlaceKind, resultText,
  routeForCommandPlace, routesWaitingFor, tokenFill, tokenLabel,
} from './net';
import { seconds } from './time';

export interface InspectorActions {
  placeToken: (place: string, attributes: Record<string, unknown>) => void;
  removeToken: (tokenId: string) => void;
  setNetFault: (transition: string, type: NetFaultType | null, factor: number | null, place: string | null) => void;
  setSensorFault: (sensor: string, type: SensorFaultType | null, value: unknown, delay: string | null) => void;
  select: (selection: Selection) => void;
}

interface Props {
  net: NetDefinition;
  kinds: Record<string, PlaceKind>;
  snapshot: Snapshot;
  selection: Selection;
  now: number;
  actions: InspectorActions;
  onClose: () => void;
}

const SUBTITLE: Record<Selection['kind'], string> = {
  place: 'Place',
  transition: 'Step a machine can do (transition)',
  sensor: 'Sensor',
};

/** Details of what was clicked: what it is now first, the Petri net and faults folded away. */
export function Inspector(props: Props) {
  const { selection } = props;
  return (
    <div className="panel">
      <div className="panel-head">
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="eyebrow">{SUBTITLE[selection.kind]}</div>
          <h2 className="mono title-wrap">{selection.name}</h2>
        </div>
        <button className="ghost" onClick={props.onClose} aria-label="Close">✕</button>
      </div>
      {selection.kind === 'place' && <PlacePanel {...props} />}
      {selection.kind === 'transition' && <TransitionPanel {...props} />}
      {selection.kind === 'sensor' && <SensorPanel {...props} />}
    </div>
  );
}

function Link({ selection, actions }: { selection: Selection; actions: InspectorActions }) {
  return <a href="#" className="mono" onClick={(e) => { e.preventDefault(); actions.select(selection); }}>{selection.name}</a>;
}

function Links({ names, kind, actions }: { names: string[]; kind: Selection['kind']; actions: InspectorActions }) {
  return <>{names.map((name, i) => <span key={name}>{i > 0 && ', '}{isTemplate(name) ? <code>{name}</code> : <Link selection={{ kind, name }} actions={actions} />}</span>)}</>;
}

// ---------------------------------------------------------------- place

function PlacePanel({ net, kinds, snapshot, selection, actions }: Props) {
  const place = net.places.find((p) => p.name === selection.name);
  if (!place) return <p className="muted small">Unknown place.</p>;
  const kind = kinds[place.name];
  const tokens = snapshot.marking[place.name] ?? [];
  const route = routeForCommandPlace(net, place.name);
  const full = place.capacity > 0 && tokens.length >= place.capacity;
  const takenBy = net.transitions.filter((t) => t.inputs.some((a) => a.place === place.name)).map((t) => t.name);
  const putBy = net.transitions.filter((t) => t.outputs.some((a) => a.place === place.name)).map((t) => t.name);
  const inhibits = net.transitions.filter((t) => t.inhibitors.includes(place.name)).map((t) => t.name);
  const sensors = net.sensors.filter((s) => s.place === place.name).map((s) => s.name);

  return (
    <>
      <p className="lead">
        {KIND_LABEL[kind]}{place.module ? <> · station <b>{place.module.toUpperCase()}</b></> : ''}
        {route && <><br />filled by <span className="mono">{route.method.toUpperCase()} {route.path}</span></>}
      </p>

      <div className="block">
        <div className="block-title">
          Holds {tokens.length}{place.capacity ? ` of ${place.capacity}` : ''}
        </div>
        {tokens.length === 0 && <div className="small muted">Empty</div>}
        <div className="token-list">
          {tokens.map((token) => (
            <div key={token.id} className="token-chip">
              <span className="dot" style={{ background: tokenFill(token, kind) }} />
              <span>{tokenLabel(token, kind)}</span>
              <span className="mono muted tiny">{token.id}</span>
              <button className="ghost icon" onClick={() => actions.removeToken(token.id)} aria-label={`Remove ${token.id}`} title="Take it out">✕</button>
            </div>
          ))}
        </div>
        {!full && (kind === 'position' || kind === 'resource') && (
          <div className="row wrap" style={{ marginTop: 8 }}>
            {kind === 'position' && net.tokenTemplates.map((template) => (
              <button key={template.name} className="small" onClick={() => actions.placeToken(place.name, template.attributes)}>
                <span className="dot" style={{ background: tokenFill({ id: '', attributes: template.attributes, commandId: null }, kind) }} />
                + {String(template.attributes.color ?? template.name)}
              </button>
            ))}
            {kind === 'resource' && (
              <button className="small" onClick={() => actions.placeToken(place.name, {})}>+ make ready</button>
            )}
          </div>
        )}
      </div>

      {sensors.length > 0 && (
        <p className="small">Seen by sensor{sensors.length > 1 ? 's' : ''} <Links names={sensors} kind="sensor" actions={actions} /></p>
      )}

      <details className="fold">
        <summary>Petri net details</summary>
        <dl className="kv">
          {takenBy.length > 0 && <><dt>taken by</dt><dd><Links names={takenBy} kind="transition" actions={actions} /></dd></>}
          {putBy.length > 0 && <><dt>filled by</dt><dd><Links names={putBy} kind="transition" actions={actions} /></dd></>}
          {inhibits.length > 0 && <><dt>must be empty for</dt><dd><Links names={inhibits} kind="transition" actions={actions} /></dd></>}
          <dt>capacity</dt><dd>{place.capacity || 'unlimited'}</dd>
          <dt>at start</dt><dd>{place.initial} token{place.initial === 1 ? '' : 's'}</dd>
        </dl>
      </details>
    </>
  );
}

// ---------------------------------------------------------------- transition

const NET_FAULTS: FaultOption<NetFaultType>[] = [
  { type: 'BLOCKED', label: 'Blocked', help: 'Never fires: the machine is broken and requests wait.' },
  { type: 'DELAYED', label: 'Slow', help: 'Takes longer by a factor.', input: 'factor' },
  { type: 'TOKEN_LOSS', label: 'Loses the workpiece', help: 'Runs, but the workpiece it passes on disappears.' },
  { type: 'MISROUTE', label: 'Misroutes', help: 'The workpiece ends up in another place.', input: 'place' },
];

function TransitionPanel({ net, snapshot, selection, now, actions }: Props) {
  const transition = net.transitions.find((t) => t.name === selection.name);
  if (!transition) return <p className="muted small">Unknown transition.</p>;
  const firings = snapshot.running.filter((f) => f.transition === transition.name);
  const fault = snapshot.netFaults[transition.name];
  const routes = routesWaitingFor(net, transition.name);

  return (
    <>
      <p className="lead">
        Takes {durationText(transition)}{transition.module ? <> · station <b>{transition.module.toUpperCase()}</b></> : ''}
        {routes.length > 0 && <><br />answers {routes.map((r) => <span key={r.name} className="mono">{r.method.toUpperCase()} {r.path}</span>)}</>}
      </p>

      <div className="block">
        {firings.length === 0 && <div className="small muted">Not running right now.</div>}
        {firings.map((firing, index) => {
          const start = seconds(firing.started);
          const end = seconds(firing.ends);
          const progress = Math.min(1, Math.max(0, (now - start) / Math.max(end - start, 0.001)));
          return (
            <div key={index}>
              <div className="row small"><b>Running</b><span style={{ flex: 1 }} /><span className="mono">{Math.max(0, end - now).toFixed(1)} s left</span></div>
              <div className="progress"><div style={{ width: `${progress * 100}%` }} /></div>
              <div className="tiny muted">holds {firing.tokens.map((t) => t.id).join(', ')}</div>
            </div>
          );
        })}
      </div>

      <details className="fold">
        <summary>Petri net details</summary>
        <TransitionDetails transition={transition} actions={actions} />
      </details>

      <FaultBox<NetFaultType>
        options={NET_FAULTS} current={fault?.type ?? null}
        places={net.places.map((p) => p.name)}
        onApply={(type, extra) => actions.setNetFault(transition.name, type,
          type === 'DELAYED' ? Number(extra) || 2 : null, type === 'MISROUTE' ? extra : null)}
        onClear={() => actions.setNetFault(transition.name, null, null, null)} />
    </>
  );
}

function TransitionDetails({ transition, actions }: { transition: Transition; actions: InspectorActions }) {
  const guard = guardText(transition);
  const result = resultText(transition);
  const arcs = (list: Transition['inputs']) => list.map((arc, i) => (
    <span key={i}>{i > 0 && ', '}{isTemplate(arc.place) ? <code>{arc.place}</code> : <Link selection={{ kind: 'place', name: arc.place }} actions={actions} />}
      {arc.variable && <span className="muted"> as {arc.variable}</span>}</span>
  ));
  return (
    <dl className="kv">
      <dt>takes</dt><dd>{arcs(transition.inputs)}</dd>
      {transition.outputs.length > 0 && <><dt>puts</dt><dd>{arcs(transition.outputs)}</dd></>}
      {transition.inhibitors.length > 0 && <><dt>only if empty</dt><dd><Links names={transition.inhibitors} kind="place" actions={actions} /></dd></>}
      {guard && <><dt>guard</dt><dd>{guard}</dd></>}
      {result && <><dt>answers with</dt><dd>{result}</dd></>}
      {transition.priority !== 0 && <><dt>priority</dt><dd>{transition.priority}</dd></>}
    </dl>
  );
}

// ---------------------------------------------------------------- sensor

const SENSOR_FAULTS: FaultOption<SensorFaultType>[] = [
  { type: 'STUCK', label: 'Stuck', help: 'Keeps the value it has now.' },
  { type: 'DROPOUT', label: 'No signal', help: 'Reads null.' },
  { type: 'WRONG_VALUE', label: 'Wrong value', help: 'Reports this value (empty: the opposite).', input: 'value' },
  { type: 'DELAYED', label: 'Delayed', help: 'Reports what it saw this many seconds ago.', input: 'delay' },
];

function SensorPanel({ net, snapshot, selection, actions }: Props) {
  const sensor = net.sensors.find((s) => s.name === selection.name);
  if (!sensor) return <p className="muted small">Unknown sensor.</p>;
  const fault = snapshot.sensorFaults[sensor.name];
  const derives = sensor.kind === 'OCCUPIED' ? 'whether something is in' : sensor.kind === 'COUNT' ? 'how many tokens are in' : `the ${sensor.attribute} of what is in`;
  const routes = net.http.routes.filter((r) => r.reads === sensor.name
    || (r.reads && isTemplate(r.reads) && sensor.name.startsWith(r.reads.split('${')[0])));
  const topics = net.events.filter((e) => e.payload.includes(`\${sensor.${sensor.name}}`));
  const parse = (text: string): unknown => {
    if (text === '') return null;
    if (text === 'true' || text === 'false') return text === 'true';
    return Number.isNaN(Number(text)) ? text : Number(text);
  };

  return (
    <>
      <div className="big-value mono">{formatValue(snapshot.sensors[sensor.name])}{fault && <span className="chip warn">{fault.type.toLowerCase()}</span>}</div>
      <p className="lead">Shows {derives} <Link selection={{ kind: 'place', name: sensor.place }} actions={actions} />.</p>
      {Object.keys(sensor.values).length > 0 && (
        <p className="small muted">Reported as {Object.entries(sensor.values).map(([k, v]) => `${k} → ${formatValue(v)}`).join(' · ')}</p>
      )}
      {(routes.length > 0 || topics.length > 0) && (
        <dl className="kv">
          {routes.length > 0 && <><dt>HTTP</dt><dd>{routes.map((r) => `${r.method.toUpperCase()} ${r.path}`).join(', ')}</dd></>}
          {topics.length > 0 && <><dt>MQTT</dt><dd>{topics.map((t) => t.topic).join(', ')}</dd></>}
        </dl>
      )}
      <FaultBox<SensorFaultType>
        options={SENSOR_FAULTS} current={fault?.type ?? null}
        onApply={(type, extra) => actions.setSensorFault(sensor.name, type,
          type === 'WRONG_VALUE' ? parse(extra) : null, type === 'DELAYED' ? `PT${Number(extra) || 3}S` : null)}
        onClear={() => actions.setSensorFault(sensor.name, null, null, null)} />
    </>
  );
}

// ---------------------------------------------------------------- faults

interface FaultOption<T> { type: T; label: string; help: string; input?: 'factor' | 'place' | 'value' | 'delay' }

function FaultBox<T extends string>({ options, current, places, onApply, onClear }: {
  options: FaultOption<T>[]; current: T | null; places?: string[];
  onApply: (type: T, extra: string) => void; onClear: () => void;
}) {
  const [choice, setChoice] = useState<T>(current ?? options[0].type);
  const [extra, setExtra] = useState('');
  useEffect(() => { if (current) setChoice(current); }, [current]);
  const option = options.find((o) => o.type === choice)!;
  const defaults = { factor: '2', delay: '3', value: '', place: '' };
  const value = extra || defaults[option.input ?? 'value'];
  const active = options.find((o) => o.type === current);

  return (
    <details className="fold fault-box" open={current !== null}>
      <summary>
        Simulate a fault {active ? <span className="chip warn">{active.label.toLowerCase()}</span> : <span className="muted tiny">· hidden from the application</span>}
      </summary>
      <div className="fault-form">
        <select value={choice} onChange={(e) => { setChoice(e.target.value as T); setExtra(''); }}>
          {options.map((o) => <option key={o.type} value={o.type}>{o.label}</option>)}
        </select>
        {option.input === 'factor' && <label className="small">× <input type="number" min="0.1" step="0.5" value={value} onChange={(e) => setExtra(e.target.value)} style={{ width: 60 }} /></label>}
        {option.input === 'delay' && <label className="small"><input type="number" min="0.5" step="0.5" value={value} onChange={(e) => setExtra(e.target.value)} style={{ width: 60 }} /> s</label>}
        {option.input === 'value' && <input value={extra} onChange={(e) => setExtra(e.target.value)} placeholder="value" style={{ width: 90 }} />}
        {option.input === 'place' && (
          <select value={extra} onChange={(e) => setExtra(e.target.value)} style={{ maxWidth: 170 }}>
            <option value="">to place…</option>
            {places?.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
        )}
        <button className="small" disabled={option.input === 'place' && !extra} onClick={() => onApply(choice, value)}>
          {current ? 'Update' : 'Switch on'}
        </button>
        {current && <button className="small ghost" onClick={onClear}>Clear</button>}
      </div>
      <div className="tiny muted">{option.help} The application only notices the effect.</div>
    </details>
  );
}
