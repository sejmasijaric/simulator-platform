import type { ComponentView, EntityView, SimEvent, Snapshot } from './types';
import { entityColor, kindOf } from './layout';
import { sentence } from './sentences';
import { clock, seconds } from './time';

interface Props {
  snapshot: Snapshot;
  events: SimEvent[];
  componentId: string | null;
  entityId: string | null;
  now: number;
  onClose: () => void;
  onFault: (componentId: string, faultId: string, active: boolean) => void;
  onRemoveEntity: (entityId: string) => void;
  onSelectEntity: (entityId: string) => void;
}

export function Inspector(props: Props) {
  const component = props.snapshot.components.find((c) => c.id === props.componentId);
  const entity = props.snapshot.entities.find((e) => e.id === props.entityId);
  if (component) return <ComponentPanel {...props} component={component} />;
  if (entity) return <EntityPanel {...props} entity={entity} />;
  return null;
}

function ComponentPanel(props: Props & { component: ComponentView }) {
  const { component, snapshot } = props;
  const here = snapshot.entities.filter((e) => e.locationComponentId === component.id);
  const recent = props.events.filter((e) => e.sourceComponentId === component.id && e.type !== 'COMPONENT_STATE_CHANGED').slice(-4).reverse();
  return (
    <div className="panel">
      <div className="panel-head">
        <h2 style={{ flex: 1 }}>{component.id}</h2>
        <span className="chip system">{component.type}</span>
        <button className="ghost" onClick={props.onClose} aria-label="Close">✕</button>
      </div>

      {component.activities.map((activity) => {
        const start = seconds(activity.startedAt);
        const end = seconds(activity.endsAt);
        const progress = Math.min(1, Math.max(0, (props.now - start) / Math.max(end - start, 0.001)));
        return (
          <div key={activity.commandId} style={{ marginBottom: 10 }}>
            <div className="small"><b>{activity.capability}</b> <span className={`chip ${activity.origin === 'application' ? 'application' : 'you'}`}>{activity.origin === 'application' ? 'application' : 'you'}</span></div>
            <div className="progress"><div style={{ width: `${progress * 100}%` }} /></div>
            <div className="small muted">{Object.entries(activity.parameters).map(([k, v]) => `${k} ${v}`).join(', ') || 'no parameters'} · ends at {clock(end)}</div>
          </div>
        );
      })}
      {kindOf(component) === 'machine' && component.activities.length === 0 && <p className="small muted" style={{ marginTop: 0 }}>Idle. Waiting for a command from the application.</p>}

      {Object.keys(component.state.attributes).length > 0 && (
        <>
          <div className="section">State</div>
          <dl className="kv">
            {Object.entries(component.state.attributes).map(([key, value]) => (
              <FragmentRow key={key} name={key} value={value} />
            ))}
          </dl>
        </>
      )}

      {component.capacity !== null && (
        <>
          <div className="section">Holds {here.length} of {component.capacity}</div>
          {here.map((e) => (
            <div key={e.id} className="small" style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '3px 0' }}>
              <span className="dot" style={{ background: entityColor(e.attributes) }} />
              <a href="#" onClick={(ev) => { ev.preventDefault(); props.onSelectEntity(e.id); }} style={{ flex: 1, color: 'inherit' }}>{e.id}</a>
              <button className="ghost" onClick={() => props.onRemoveEntity(e.id)}>Remove</button>
            </div>
          ))}
        </>
      )}

      {component.faultModes.length > 0 && (
        <>
          <div className="section">Faults · only visible here, the application only notices the effect</div>
          {component.faultModes.map((fault) => {
            const active = component.activeFaults.includes(fault.id);
            return (
              <div className="fault" key={fault.id}>
                <button className={`switch ${active ? 'on' : ''}`} aria-label={`${active ? 'Clear' : 'Activate'} ${fault.id}`}
                        onClick={() => props.onFault(component.id, fault.id, !active)} />
                <div>
                  <div className="small mono">{fault.id}</div>
                  <div className="small muted">{fault.description}</div>
                </div>
              </div>
            );
          })}
        </>
      )}

      {recent.length > 0 && (
        <>
          <div className="section">Recent</div>
          {recent.map((e) => (
            <div key={e.id} className="small" style={{ padding: '2px 0' }}>
              <span className="mono muted">{clock(seconds(e.timestamp))}</span> {sentence(e, snapshot.entities)}
            </div>
          ))}
        </>
      )}
    </div>
  );
}

function EntityPanel(props: Props & { entity: EntityView }) {
  const { entity } = props;
  return (
    <div className="panel">
      <div className="panel-head">
        <span className="dot" style={{ background: entityColor(entity.attributes), width: 16, height: 16 }} />
        <h2 style={{ flex: 1 }}>{entity.id}</h2>
        <span className="chip system">{entity.type}</span>
        <button className="ghost" onClick={props.onClose} aria-label="Close">✕</button>
      </div>
      <dl className="kv">
        <FragmentRow name="location" value={entity.locationComponentId ?? 'outside'} />
        {Object.entries(entity.attributes).map(([key, value]) => <FragmentRow key={key} name={key} value={value} />)}
      </dl>
      <div style={{ marginTop: 14 }}><button onClick={() => props.onRemoveEntity(entity.id)}>Take it out</button></div>
    </div>
  );
}

function FragmentRow({ name, value }: { name: string; value: unknown }) {
  return (
    <>
      <dt>{name}</dt>
      <dd>{typeof value === 'object' ? JSON.stringify(value) : String(value === '' ? '–' : value)}</dd>
    </>
  );
}
