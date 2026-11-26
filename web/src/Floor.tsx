import type { NetDefinition, Selection, Snapshot } from './types';
import { colorOf, destinations, formatValue, outOfService, type PlaceKind, sensorLit, tokenFill } from './net';
import { seconds } from './time';

interface Props {
  net: NetDefinition;
  kinds: Record<string, PlaceKind>;
  snapshot: Snapshot;
  now: number;
  selection: Selection | null;
  highlight: string | null;
  placing: boolean;
  onSelect: (selection: Selection) => void;
}

/**
 * The same marking drawn on a photo of the physical layout: workpieces where their place is,
 * machines (resource places) as chips that show what they are doing, sensors as dots.
 */
export function Floor(props: Props) {
  const { net, snapshot } = props;
  const image = net.image;
  if (!image) {
    return <div className="empty">This layout has no picture. Add <code>image</code> to its YAML, or use the net view.</div>;
  }
  const transitions = new Map(net.transitions.map((t) => [t.name, t]));
  const sensors = new Set(net.sensors.map((s) => s.name));

  // workpieces on their way: drawn at their destination, faded, while the transition runs
  const inTransit = snapshot.running.flatMap((firing) => {
    const transition = transitions.get(firing.transition);
    return transition ? destinations(firing, transition)
      .filter(({ token }) => colorOf(token.attributes) !== null)
      .map((d) => ({ ...d, firing })) : [];
  });

  const selected = (kind: Selection['kind'], name: string) => props.selection?.kind === kind && props.selection.name === name;

  return (
    <div className="floor-wrap">
      <svg className={`floor ${props.placing ? 'placing' : ''}`} viewBox={`0 0 ${image.width} ${image.height}`} role="img" aria-label="Layout floor">
        <image href={image.url} x={0} y={0} width={image.width} height={image.height} preserveAspectRatio="none" />
        {Object.entries(image.positions).map(([name, point]) => {
          if (sensors.has(name)) {
            const type = sensorType(net, props.kinds, name);
            const lit = sensorLit(net, snapshot, name);
            const fault = snapshot.sensorFaults[name];
            const value = snapshot.sensors[name];
            const colour = type === 'colour' ? colorOf((snapshot.marking[net.sensors.find((x) => x.name === name)!.place] ?? [])[0]?.attributes) : null;
            return (
              <g key={name} className={`node sensor ${type} ${lit ? 'lit' : ''} ${fault ? 'faulty' : ''} ${selected('sensor', name) ? 'selected' : ''} ${props.highlight === name ? 'highlight' : ''}`}
                 transform={`translate(${point.x} ${point.y})`} onClick={() => props.onSelect({ kind: 'sensor', name })}>
                <circle r={16} className="hit" />
                {type === 'barrier' && <>{lit && <circle r={14} className="halo" />}<circle r={7} className="shape" /></>}
                {type === 'colour' && <rect className="shape" x={-9} y={-9} width={18} height={18} transform="rotate(45)" style={{ fill: colour ?? '#444441' }} />}
                {type === 'switch' && <rect className="shape" x={-7} y={-7} width={14} height={14} rx={3} />}
                {type === 'counter' && (
                  <>
                    <rect className="shape" x={-15} y={-11} width={30} height={22} rx={11} />
                    <text className="count" y={5} textAnchor="middle">{formatValue(value)}</text>
                  </>
                )}
                <text className="photo-label" y={type === 'counter' ? -18 : -16} textAnchor="middle">{name} · {SENSOR_TYPE_LABEL[type]} · {formatValue(value)}</text>
                {fault && <title>{`fault: ${fault.type}`}</title>}
              </g>
            );
          }
          const kind = props.kinds[name];
          const tokens = snapshot.marking[name] ?? [];
          if (kind === 'resource') {
            // busy while a firing holds the machine: it took the ready token or will give it back
            const busy = snapshot.running.find((f) => {
              const t = transitions.get(f.transition);
              return t?.inputs.some((arc) => arc.place === name) || t?.outputs.some((arc) => arc.place === name);
            });
            return (
              <Machine key={name} name={name} point={point} busy={busy?.transition ?? null}
                       start={seconds(busy?.started)} end={seconds(busy?.ends)} now={props.now} ready={tokens.length > 0}
                       out={outOfService(net, snapshot, net.places.find((p) => p.name === name)?.module ?? null)}
                       selected={selected('place', name)} highlight={props.highlight === name || props.highlight === busy?.transition}
                       onClick={() => props.onSelect({ kind: 'place', name })} />
            );
          }
          return (
            <g key={name} className={`node spot ${selected('place', name) ? 'selected' : ''} ${props.highlight === name ? 'highlight' : ''}`}
               transform={`translate(${point.x} ${point.y})`} onClick={() => props.onSelect({ kind: 'place', name })}>
              <circle r={15} className="shape" />
              {tokens.map((token, i) => (
                <g key={token.id} className="puck" transform={`translate(${(i - (tokens.length - 1) / 2) * 30} 0)`}>
                  <circle r={16} fill={tokenFill(token, kind)} stroke="rgba(0,0,0,0.55)" strokeWidth={1.5} />
                  <circle r={6} fill="rgba(255,255,255,0.35)" />
                  <title>{`${token.id} ${JSON.stringify(token.attributes)}`}</title>
                </g>
              ))}
              <text className="photo-label" y={32} textAnchor="middle">{name}</text>
            </g>
          );
        })}
        {inTransit.map(({ token, place, firing }) => {
          const point = image.positions[place];
          if (!point) return null;
          const progress = Math.min(1, Math.max(0, (props.now - seconds(firing.started)) / Math.max(seconds(firing.ends) - seconds(firing.started), 0.001)));
          return (
            <g key={`${firing.transition}-${token.id}`} className="puck transit" transform={`translate(${point.x} ${point.y})`}>
              <circle r={16} fill={tokenFill(token, undefined)} opacity={0.35 + 0.4 * progress} strokeDasharray="4 3" stroke="#fff" strokeWidth={2} />
              <title>{`${token.id} arriving via ${firing.transition}`}</title>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

type SensorType = 'barrier' | 'colour' | 'switch' | 'counter';

const SENSOR_TYPE_LABEL: Record<SensorType, string> = {
  barrier: 'light barrier',
  colour: 'colour sensor',
  switch: 'position switch',
  counter: 'stock count',
};

/** What a sensor physically is, from what it reads: a workpiece's colour, a count, a machine state or a position. */
function sensorType(net: NetDefinition, kinds: Record<string, PlaceKind>, name: string): SensorType {
  const sensor = net.sensors.find((s) => s.name === name);
  if (sensor?.kind === 'ATTRIBUTE') return 'colour';
  if (sensor?.kind === 'COUNT') return 'counter';
  return sensor && kinds[sensor.place] === 'resource' ? 'switch' : 'barrier';
}

export function FloorLegend() {
  return (
    <div className="floor-legend">
      <span><i className="lg barrier" /> light barrier</span>
      <span><i className="lg colour" /> colour sensor</span>
      <span><i className="lg switch" /> position switch</span>
      <span><i className="lg counter" /> stock</span>
      <span><i className="lg station" /> station ready</span>
    </div>
  );
}

/**
 * A machine (resource place). Idle: a small status dot, its name on hover. Working: a chip with
 * the step, the time left and a progress bar.
 */
function Machine(props: {
  out?: boolean;
  name: string; point: { x: number; y: number }; busy: string | null; start: number; end: number; now: number;
  ready: boolean; selected: boolean; highlight: boolean; onClick: () => void;
}) {
  const { point, busy } = props;
  const label = props.name.replace(/_idle$/, '');
  const classes = `node machine ${busy ? 'busy' : 'idle'} ${props.selected ? 'selected' : ''} ${props.highlight ? 'highlight' : ''}`;
  if (!busy) {
    return (
      <g className={classes} transform={`translate(${point.x} ${point.y})`} onClick={props.onClick}>
        <circle r={16} className="hit" />
        <circle r={9} className={`shape led-dot ${props.out ? 'out' : props.ready ? 'ready' : ''}`} />
        <text className="photo-label" y={-16} textAnchor="middle">{label} · {props.out ? 'out of service' : props.ready ? 'ready' : 'not ready'}</text>
      </g>
    );
  }
  const w = 170;
  const h = 40;
  const progress = Math.min(1, Math.max(0, (props.now - props.start) / Math.max(props.end - props.start, 0.001)));
  return (
    <g className={classes} transform={`translate(${point.x - w / 2} ${point.y - h / 2})`} onClick={props.onClick}>
      <rect className="working-glow" x={-4} y={-4} width={w + 8} height={h + 8} rx={12} />
      <rect className="shape" width={w} height={h} rx={9} />
      <circle cx={13} cy={13} r={4.5} className="led busy" />
      <text className="photo-title" x={23} y={18}>{busy}</text>
      <text className="photo-meta" x={w - 10} y={18} textAnchor="end">{Math.max(0, props.end - props.now).toFixed(1)} s</text>
      <rect x={10} y={29} width={w - 20} height={4} rx={2} fill="rgba(255,255,255,0.2)" />
      <rect x={10} y={29} width={(w - 20) * progress} height={4} rx={2} className="bar" />
      <title>{`${label}: ${busy}`}</title>
    </g>
  );
}
