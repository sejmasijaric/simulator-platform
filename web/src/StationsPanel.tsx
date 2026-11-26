import type { EventTopic, NetDefinition, Snapshot } from './types';
import { formatValue, modulesOf, moduleOfSensor, sensorsIn } from './net';

interface Props {
  net: NetDefinition;
  snapshot: Snapshot;
  onSelectSensor: (sensor: string) => void;
  onModules: (modules: string[]) => void;
}

interface Field { key: string; value: unknown; sensor: string | null }

const GROUPS: { title: string; test: (key: string) => boolean }[] = [
  { title: 'Inputs · light barriers, switches, sensors', test: (k) => /^i\d+_/.test(k) },
  { title: 'Outputs · valves, compressor', test: (k) => /^o\d+_/.test(k) },
  { title: 'Motors', test: (k) => /^m\d+_/.test(k) },
  { title: 'Task and position', test: (k) => /^(current|target)_/.test(k) },
];
const HEADER = ['id', 'station', 'timestamp'];

function now(): string {
  const d = new Date();
  const p = (n: number, l = 2) => String(n).padStart(l, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(Math.floor(d.getMilliseconds() / 10))}`;
}

/** The message as the simulator publishes it now: placeholders filled with the current sensor values. */
function render(event: EventTopic, sensors: Record<string, unknown>): { fields: Field[]; raw: string } {
  const text = event.payload.replace(/\$\{([^}]+)}/g, (_, expression: string) => {
    if (expression.startsWith('sensor.')) {
      const value = sensors[expression.slice(7)];
      return value === null || value === undefined ? 'null' : String(value);
    }
    if (expression === 'uuid') return '…';
    if (expression.startsWith('now')) return now();
    return '';
  });
  const bySensor = new Map<string, string>();
  for (const match of event.payload.matchAll(/"([\w-]+)"\s*:\s*"?\$\{sensor\.([\w-]+)}/g)) bySensor.set(match[1], match[2]);
  try {
    const data = JSON.parse(text) as Record<string, unknown>;
    return { fields: Object.entries(data).map(([key, value]) => ({ key, value, sensor: bySensor.get(key) ?? null })), raw: JSON.stringify(data, null, 2) };
  } catch {
    return { fields: [], raw: text };
  }
}

/**
 * One card per station of the physical factory: the MQTT message it publishes, with every
 * component grouped as the factory reports them, plus the station's other sensors.
 */
export function StationsPanel({ net, snapshot, onSelectSensor, onModules }: Props) {
  const modules = modulesOf(net);
  const published = new Set(net.events.flatMap((e) => sensorsIn(e.payload)));
  const topicModule = (event: EventTopic) => {
    const counts = new Map<string, number>();
    sensorsIn(event.payload).forEach((s) => {
      const m = moduleOfSensor(net, s);
      if (m) counts.set(m, (counts.get(m) ?? 0) + 1);
    });
    return [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;
  };
  const all = snapshot.activeModules.length === 0;
  const isOn = (m: string) => all || snapshot.activeModules.includes(m);
  const toggle = (m: string) => {
    const current = all ? modules : snapshot.activeModules;
    const next = current.includes(m) ? current.filter((x) => x !== m) : [...current, m];
    onModules(next.length === modules.length ? [] : next);
  };
  const cards = [...modules, null].map((module) => ({
    module,
    topics: net.events.filter((e) => topicModule(e) === module),
    others: net.sensors.filter((s) => moduleOfSensor(net, s.name) === module && !published.has(s.name)),
  })).filter((card) => card.module !== null || card.topics.length > 0 || card.others.length > 0);

  return (
    <div className="tab-body stations">
      <p className="small muted" style={{ marginTop: 0 }}>
        Each station publishes its full state over MQTT (broker <span className="mono">localhost:1883</span>).
        <span className="live-dot" /> values come from the simulation; grey ones are fixed in the layout file.
        Click a live value to break that sensor.
      </p>
      {cards.map(({ module, topics, others }) => {
        const state = topics.map((t) => render(t, snapshot.sensors).fields.find((f) => f.key === 'current_state')?.value).find((v) => v !== undefined);
        return (
          <details key={module ?? 'other'} className={`station ${module && !isOn(module) ? 'off' : ''}`}>
            <summary>
              <b>{module ? module.toUpperCase() : 'Other'}</b>
              <span className="mono tiny muted">{topics.map((t) => t.topic).join(', ')}</span>
              <span style={{ flex: 1 }} />
              {state !== undefined && <span className={`chip ${state === 'ready' ? 'running' : 'paused'}`}>{formatValue(state)}</span>}
              {module && (
                <label className="switch-label" onClick={(e) => e.stopPropagation()} title="Switch the station off to test the others on their own">
                  <input type="checkbox" checked={isOn(module)} onChange={() => toggle(module)} /> runs
                </label>
              )}
            </summary>

            {topics.map((topic) => {
              const { fields, raw } = render(topic, snapshot.sensors);
              const header = fields.filter((f) => HEADER.includes(f.key));
              const rest = fields.filter((f) => !HEADER.includes(f.key));
              const sections = [...GROUPS.map((g) => ({ title: g.title, fields: rest.filter((f) => g.test(f.key)) })),
                { title: 'Other', fields: rest.filter((f) => !GROUPS.some((g) => g.test(f.key))) }].filter((s) => s.fields.length > 0);
              return (
                <div key={topic.topic} className="message">
                  <div className="tiny muted">
                    MQTT <span className="mono">{topic.topic}</span>
                    {topic.every ? ` · every ${topic.every.replace(/^PT/, '').toLowerCase()}` : ''}
                    {header.length > 0 && ` · ${header.map((f) => `${f.key} ${formatValue(f.value)}`).join(' · ')}`}
                  </div>
                  {sections.map((section) => (
                    <div key={section.title} className="field-group">
                      <div className="field-group-title">{section.title}</div>
                      <div className="fields">
                        {section.fields.map((f) => <FieldRow key={f.key} field={f} snapshot={snapshot} onSelect={onSelectSensor} />)}
                      </div>
                    </div>
                  ))}
                  <details className="fold raw">
                    <summary>Message as sent</summary>
                    <pre className="wire body">{raw}</pre>
                  </details>
                </div>
              );
            })}

            {others.length > 0 && (
              <div className="field-group">
                <div className="field-group-title">Other sensors · read over HTTP</div>
                <div className="fields">
                  {others.map((s) => (
                    <FieldRow key={s.name} field={{ key: s.name, value: snapshot.sensors[s.name], sensor: s.name }} snapshot={snapshot} onSelect={onSelectSensor} />
                  ))}
                </div>
              </div>
            )}
          </details>
        );
      })}
    </div>
  );
}

function FieldRow({ field, snapshot, onSelect }: { field: Field; snapshot: Snapshot; onSelect: (sensor: string) => void }) {
  const fault = field.sensor ? snapshot.sensorFaults[field.sensor] : undefined;
  const content = (
    <>
      <span className="mono key">{field.key}</span>
      <span className="mono value">{field.value === '' ? '""' : formatValue(field.value)}</span>
      {fault && <span className="chip warn">{fault.type.toLowerCase()}</span>}
    </>
  );
  return field.sensor
    ? <button className={`field live ${fault ? 'faulty' : ''}`} onClick={() => onSelect(field.sensor!)} title={`from sensor ${field.sensor}`}><span className="live-dot" />{content}</button>
    : <div className="field fixed"><span className="live-dot off" />{content}</div>;
}
