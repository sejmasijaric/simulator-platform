import type { ComponentView, EntityView, Snapshot } from './types';
import { entityColor, kindOf, type FloorLayout } from './layout';
import { seconds } from './time';

const PAD = { w: 96, h: 56 };
const MACHINE = { w: 170, h: 72 };

interface Props {
  snapshot: Snapshot;
  layout: FloorLayout;
  now: number;
  selectedId: string | null;
  highlightId: string | null;
  placing: boolean;
  faultMode: boolean;
  onSelectComponent: (id: string) => void;
  onSelectEntity: (id: string) => void;
}

/** Smaller markers drawn on top of a photo of the real layout. */
const PHOTO = { pad: 15, machine: { w: 76, h: 26 }, working: { w: 150, h: 40 }, sensor: 7, puck: 17 };

export function Floor(props: Props) {
  const { snapshot, layout } = props;
  const at = (id: string) => layout.positions[id] ?? { x: 0, y: 0 };
  const photo = layout.background !== null;

  return (
    <svg className={photo ? 'floor photo' : 'floor'} viewBox={`0 0 ${layout.width} ${layout.height}`} role="img" aria-label="Lab floor">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M2 1L8 5L2 9" fill="none" stroke="var(--line-strong)" strokeWidth="1.5" strokeLinecap="round" />
        </marker>
      </defs>
      {layout.background && (
        <image href={layout.background} x={0} y={0} width={layout.width} height={layout.height} preserveAspectRatio="none" />
      )}

      {!photo && snapshot.connections.map((connection) => {
        const a = at(connection.from);
        const b = at(connection.to);
        const length = Math.hypot(b.x - a.x, b.y - a.y) || 1;
        const shrink = (p: { x: number; y: number }, q: { x: number; y: number }) => ({
          x: p.x + ((q.x - p.x) / length) * 56,
          y: p.y + ((q.y - p.y) / length) * 40,
        });
        const start = shrink(a, b);
        const end = shrink(b, a);
        return (
          <path key={`${connection.from}-${connection.to}`} d={`M${start.x} ${start.y} L${end.x} ${end.y}`}
                stroke="var(--line-strong)" strokeWidth={2} strokeDasharray="5 7" fill="none" markerEnd="url(#arrow)" />
        );
      })}

      {snapshot.components.map((component) => {
        const kind = kindOf(component);
        const point = at(component.id);
        const classes = ['node',
          props.selectedId === component.id ? 'selected' : '',
          props.highlightId === component.id ? 'highlight' : '',
          props.placing && kind === 'position' ? 'target' : ''].join(' ');
        return (
          <g key={component.id} className={classes} onClick={() => props.onSelectComponent(component.id)}>
            {kind === 'position' && (photo ? <PhotoPad component={component} x={point.x} y={point.y} /> : <Pad component={component} x={point.x} y={point.y} />)}
            {kind === 'machine' && (photo
              ? <PhotoMachine component={component} x={point.x} y={point.y} now={props.now} />
              : <Machine component={component} x={point.x} y={point.y} now={props.now} />)}
            {kind === 'sensor' && (photo ? <PhotoSensor component={component} x={point.x} y={point.y} /> : <Sensor component={component} x={point.x} y={point.y} />)}
            {props.faultMode && component.faultModes.length > 0 && (
              <FaultBadge component={component} x={point.x} y={point.y} kind={kind} photo={photo} />
            )}
            {!props.faultMode && component.activeFaults.length > 0 && (
              <FaultBadge component={component} x={point.x} y={point.y} kind={kind} photo={photo} />
            )}
          </g>
        );
      })}

      {snapshot.entities.map((entity) => (
        <Puck key={entity.id} entity={entity} snapshot={snapshot} layout={layout} onSelect={props.onSelectEntity}
              radius={photo ? PHOTO.puck : 16} />
      ))}
    </svg>
  );
}

function PhotoPad({ component, x, y }: { component: ComponentView; x: number; y: number }) {
  return (
    <>
      <circle className="shape" cx={x} cy={y} r={PHOTO.pad} fill="rgba(20,20,24,0.25)" stroke="#fff" strokeWidth={1.8} strokeDasharray="4 3" />
      <text className="photo-label" x={x} y={y + PHOTO.pad + 16} textAnchor="middle">{component.id}</text>
    </>
  );
}

/** Small chip while idle; grows to show time left and progress while the machine works. */
function PhotoMachine({ component, x, y, now }: { component: ComponentView; x: number; y: number; now: number }) {
  const activity = component.activities[0];
  const start = seconds(activity?.startedAt);
  const end = seconds(activity?.endsAt);
  const progress = activity ? Math.min(1, Math.max(0, (now - start) / Math.max(end - start, 0.001))) : 0;
  const { w, h } = activity ? PHOTO.working : PHOTO.machine;
  const left = x - w / 2;
  const top = y - h / 2;
  const subTask = component.state.attributes.current_sub_task;
  const byApplication = activity?.origin === 'application';
  return (
    <>
      {activity && <rect className="working-glow" x={left - 4} y={top - 4} width={w + 8} height={h + 8} rx={12} />}
      <rect className="shape" x={left} y={top} width={w} height={h} rx={9}
            fill="rgba(20,20,24,0.82)" stroke={activity ? '#afa9ec' : 'rgba(255,255,255,0.4)'} strokeWidth={activity ? 2 : 1.2} />
      <circle cx={left + 13} cy={top + 13} r={4.5} fill={activity ? (byApplication ? '#5dcaa5' : '#afa9ec') : 'rgba(255,255,255,0.45)'} />
      <text className="photo-title" x={left + 23} y={top + 18}>{component.id}</text>
      {activity && (
        <>
          <text className="photo-meta" x={left + w - 10} y={top + 18} textAnchor="end">{Math.max(0, end - now).toFixed(1)} s</text>
          <rect x={left + 10} y={top + 29} width={w - 20} height={4} rx={2} fill="rgba(255,255,255,0.2)" />
          <rect x={left + 10} y={top + 29} width={(w - 20) * progress} height={4} rx={2} fill={byApplication ? '#5dcaa5' : '#afa9ec'} />
        </>
      )}
      <title>{`${component.id} (${component.type})${activity ? `: ${typeof subTask === 'string' && subTask ? subTask : activity.capability}` : ': idle'}`}</title>
    </>
  );
}

function PhotoSensor({ component, x, y }: { component: ComponentView; x: number; y: number }) {
  const lit = Object.values(component.state.attributes).some((value) => value === true);
  return (
    <>
      {lit && <circle cx={x} cy={y} r={PHOTO.sensor + 7} fill="#ffb020" fillOpacity={0.4} />}
      <circle className="shape" cx={x} cy={y} r={PHOTO.sensor} fill={lit ? '#ffb020' : '#22d3ee'} stroke="#083344" strokeWidth={2} />
      <title>{`${component.id}: ${lit ? 'interrupted' : 'free'}`}</title>
    </>
  );
}


function Pad({ component, x, y }: { component: ComponentView; x: number; y: number }) {
  return (
    <>
      <rect className="shape" x={x - PAD.w / 2} y={y - PAD.h / 2} width={PAD.w} height={PAD.h} rx={14}
            fill="var(--surface-2)" stroke="var(--line-strong)" strokeWidth={1.2} />
      <text className="label" x={x} y={y + PAD.h / 2 + 20} textAnchor="middle">{component.id}</text>
    </>
  );
}

function Machine({ component, x, y, now }: { component: ComponentView; x: number; y: number; now: number }) {
  const activity = component.activities[0];
  const start = seconds(activity?.startedAt);
  const end = seconds(activity?.endsAt);
  const progress = activity ? Math.min(1, Math.max(0, (now - start) / Math.max(end - start, 0.001))) : 0;
  const left = x - MACHINE.w / 2;
  const top = y - MACHINE.h / 2;
  return (
    <>
      <rect className="shape" x={left} y={top} width={MACHINE.w} height={MACHINE.h} rx={14}
            fill={activity ? 'var(--accent-bg)' : 'var(--surface)'} stroke="var(--line-strong)" strokeWidth={1.2} />
      <text className="title" x={left + 14} y={top + 24}>{component.id}</text>
      {activity?.origin === 'application'
        ? <text className="label" x={left + MACHINE.w - 14} y={top + 24} textAnchor="end" style={{ fill: 'var(--app)' }}>application</text>
        : <text className="label" x={left + MACHINE.w - 14} y={top + 24} textAnchor="end">{component.type}</text>}
      <text className="label" x={left + 14} y={top + 46}>
        {activity ? `${activity.capability} · ${Math.max(0, end - now).toFixed(1)} s` : 'idle'}
      </text>
      <rect x={left + 14} y={top + 56} width={MACHINE.w - 28} height={5} rx={2.5} fill="var(--line)" />
      <rect x={left + 14} y={top + 56} width={(MACHINE.w - 28) * progress} height={5} rx={2.5} fill="var(--accent)" />
    </>
  );
}

function Sensor({ component, x, y }: { component: ComponentView; x: number; y: number }) {
  const lit = Object.values(component.state.attributes).some((value) => value === true);
  return (
    <>
      <circle className="shape" cx={x} cy={y} r={12} fill={lit ? 'var(--warn-line)' : 'var(--surface)'}
              stroke="var(--line-strong)" strokeWidth={1.5} />
      {lit && <circle cx={x} cy={y} r={20} fill="none" stroke="var(--warn-line)" strokeOpacity={0.35} strokeWidth={6} />}
      <text className="label" x={x} y={y - 24} textAnchor="middle">{component.id}</text>
    </>
  );
}

function FaultBadge({ component, x, y, kind, photo }: { component: ComponentView; x: number; y: number; kind: string; photo: boolean }) {
  const active = component.activeFaults.length > 0;
  const machine = photo ? (component.activities.length > 0 ? PHOTO.working : PHOTO.machine) : MACHINE;
  const offset = kind === 'machine' ? { x: machine.w / 2, y: -machine.h / 2 }
    : kind === 'position' ? (photo ? { x: PHOTO.pad, y: -PHOTO.pad } : { x: PAD.w / 2, y: -PAD.h / 2 })
    : { x: photo ? 14 : 24, y: 0 };
  return (
    <g transform={`translate(${x + offset.x}, ${y + offset.y})`}>
      <circle r={11} fill={active ? 'var(--warn-line)' : 'var(--warn-bg)'} stroke="var(--warn-line)" strokeWidth={1.2} />
      <text textAnchor="middle" dy={4} style={{ fontSize: 12, fontWeight: 700, fill: active ? '#fff' : 'var(--warn)' }}>
        {active ? '!' : '?'}
      </text>
    </g>
  );
}

function Puck({ entity, snapshot, layout, onSelect, radius }: {
  entity: EntityView; snapshot: Snapshot; layout: FloorLayout; onSelect: (id: string) => void; radius: number;
}) {
  if (!entity.locationComponentId) return null;
  const point = layout.positions[entity.locationComponentId];
  if (!point) return null;
  const neighbours = snapshot.entities.filter((e) => e.locationComponentId === entity.locationComponentId);
  const index = neighbours.findIndex((e) => e.id === entity.id);
  const dx = (index - (neighbours.length - 1) / 2) * radius * 1.9;
  return (
    <g className="puck" style={{ transform: `translate(${point.x + dx}px, ${point.y}px)` }}
       onClick={(event) => { event.stopPropagation(); onSelect(entity.id); }}>
      <circle r={radius} fill={entityColor(entity.attributes)} stroke="rgba(0,0,0,0.5)" strokeWidth={1.5} />
      <circle r={radius * 0.38} fill="rgba(255,255,255,0.35)" />
      <title>{entity.id}</title>
    </g>
  );
}
