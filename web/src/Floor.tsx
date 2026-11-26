import type { NetDefinition, Selection, Snapshot } from './types';
import { colorOf, destinations, type PlaceKind, sensorLit, tokenFill } from './net';
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
            const lit = sensorLit(net, snapshot, name);
            const fault = snapshot.sensorFaults[name];
            return (
              <g key={name} className={`node sensor ${lit ? 'lit' : ''} ${fault ? 'faulty' : ''} ${selected('sensor', name) ? 'selected' : ''} ${props.highlight === name ? 'highlight' : ''}`}
                 transform={`translate(${point.x} ${point.y})`} onClick={() => props.onSelect({ kind: 'sensor', name })}>
                {lit && <circle r={14} className="halo" />}
                <circle r={7} className="shape" />
                <text className="photo-label" y={-14} textAnchor="middle">{name}</text>
                <title>{`${name}: ${String(snapshot.sensors[name])}${fault ? ` (fault: ${fault.type})` : ''}`}</title>
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

function Machine(props: {
  name: string; point: { x: number; y: number }; busy: string | null; start: number; end: number; now: number;
  ready: boolean; selected: boolean; highlight: boolean; onClick: () => void;
}) {
  const { point, busy } = props;
  const w = busy ? 170 : 92;
  const h = busy ? 40 : 26;
  const progress = busy ? Math.min(1, Math.max(0, (props.now - props.start) / Math.max(props.end - props.start, 0.001))) : 0;
  const label = props.name.replace(/_idle$/, '');
  return (
    <g className={`node machine ${busy ? 'busy' : ''} ${props.selected ? 'selected' : ''} ${props.highlight ? 'highlight' : ''}`}
       transform={`translate(${point.x - w / 2} ${point.y - h / 2})`} onClick={props.onClick}>
      {busy && <rect className="working-glow" x={-4} y={-4} width={w + 8} height={h + 8} rx={12} />}
      <rect className="shape" width={w} height={h} rx={9} />
      <circle cx={13} cy={13} r={4.5} className={busy ? 'led busy' : props.ready ? 'led ready' : 'led'} />
      <text className="photo-title" x={23} y={18}>{busy ?? label}</text>
      {busy && (
        <>
          <text className="photo-meta" x={w - 10} y={18} textAnchor="end">{Math.max(0, props.end - props.now).toFixed(1)} s</text>
          <rect x={10} y={29} width={w - 20} height={4} rx={2} fill="rgba(255,255,255,0.2)" />
          <rect x={10} y={29} width={(w - 20) * progress} height={4} rx={2} className="bar" />
        </>
      )}
      <title>{`${label}: ${busy ? `firing ${busy}` : props.ready ? 'ready' : 'not ready'}`}</title>
    </g>
  );
}
