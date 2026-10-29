import { useEffect, useMemo, useState } from 'react';
import { api } from './api';
import { Floor } from './Floor';
import { Inspector } from './Inspector';
import { Story } from './Story';
import { useEvents, useFrame, usePolling, useRealTime } from './hooks';
import { entityColor, floorLayout, kindOf } from './layout';
import { clock, seconds } from './time';
import type { EntityTemplate } from './types';

const SPEEDS = [0.5, 1, 2, 5, 10];

interface Props {
  simulationId: string;
  onLeave: () => void;
}

export function SimulationScreen({ simulationId, onLeave }: Props) {
  const snapshotPoll = usePolling(() => api.snapshot(simulationId), 250, [simulationId]);
  const definitions = usePolling(api.definitions, 60000, []);
  const snapshot = snapshotPoll.data;
  const { events, clear } = useEvents(simulationId, snapshot);
  const [realTime, setRealTime] = useRealTime(simulationId);

  const [selectedComponent, setSelectedComponent] = useState<string | null>(null);
  const [selectedEntity, setSelectedEntity] = useState<string | null>(null);
  const [highlight, setHighlight] = useState<string | null>(null);
  const [faultMode, setFaultMode] = useState(false);
  const [placing, setPlacing] = useState<EntityTemplate | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const running = snapshot?.status === 'RUNNING';
  const ticking = running && realTime.enabled;
  useFrame(ticking);

  const definition = definitions.data?.find((d) => d.id === snapshot?.definitionId);
  const layout = useMemo(() => (snapshot ? floorLayout(snapshot, definition) : null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [snapshot?.components.length, snapshot?.connections.length, definition]);

  const base = seconds(snapshot?.time);
  const now = ticking
    ? base + ((performance.now() - snapshotPoll.receivedAt) / 1000) * (realTime.speedFactor ?? 1)
    : base;

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setPlacing(null);
        setSelectedComponent(null);
        setSelectedEntity(null);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const attempt = async (action: () => Promise<unknown>) => {
    try {
      setMessage(null);
      await action();
      await snapshotPoll.refresh();
    } catch (e) {
      setMessage((e as Error).message);
    }
  };

  if (!snapshot || !layout) {
    return (
      <div className="start">
        {snapshotPoll.error
          ? <><p className="error">{snapshotPoll.error}</p><button onClick={onLeave}>Back to labs</button></>
          : <p className="muted">Loading…</p>}
      </div>
    );
  }

  const selectComponent = (id: string) => {
    const component = snapshot.components.find((c) => c.id === id);
    if (placing && component && kindOf(component) === 'position') {
      const template = placing;
      attempt(() => api.placeEntity(simulationId, template.type, template.attributes, id));
      return;
    }
    setSelectedEntity(null);
    setSelectedComponent(id);
  };

  const togglePlay = () => attempt(async () => {
    if (realTime.enabled) setRealTime(await api.disableRealTime(simulationId));
    else setRealTime(await api.enableRealTime(simulationId, realTime.speedFactor ?? 1));
  });

  const changeSpeed = (speed: number) => attempt(async () => {
    setRealTime(await api.enableRealTime(simulationId, speed));
  });

  const step = () => attempt(async () => {
    if (realTime.enabled) setRealTime(await api.disableRealTime(simulationId));
    const result = await api.step(simulationId);
    if (!result.stepped) setMessage('Nothing is scheduled. Time moves on when a machine is working.');
  });

  const reset = () => attempt(async () => {
    await api.reset(simulationId);
    clear();
    setSelectedComponent(null);
    setSelectedEntity(null);
    await api.start(simulationId);
    if (!realTime.enabled) setRealTime(await api.enableRealTime(simulationId, realTime.speedFactor ?? 1));
  });

  const start = () => attempt(async () => {
    await api.start(simulationId);
    if (!realTime.enabled) setRealTime(await api.enableRealTime(simulationId, realTime.speedFactor ?? 1));
  });

  const statusLabel = !running ? snapshot.status.toLowerCase() : realTime.enabled ? 'running' : 'paused';
  const templates = definition?.entityTemplates ?? [];
  const showInspector = selectedComponent !== null || selectedEntity !== null;

  return (
    <div className="screen">
      <header className="topbar">
        <button className="ghost" onClick={onLeave} aria-label="Back to labs">←</button>
        <h2>{snapshot.name}</h2>
        <span className={`chip ${statusLabel}`}>{statusLabel}</span>
        <span style={{ flex: 1 }} />
        {message && <span className="error">{message}</span>}
        <button className={faultMode ? 'toggled' : ''} onClick={() => setFaultMode(!faultMode)}
                title="Show which components can fail">
          {faultMode ? 'Fault mode on' : 'Fault mode'}
        </button>
      </header>

      <div className="main">
        <div className={`floor-wrap ${faultMode ? 'faults' : ''}`}>
          <Floor snapshot={snapshot} layout={layout} now={now}
                 selectedId={selectedComponent} highlightId={highlight}
                 placing={placing !== null} faultMode={faultMode}
                 onSelectComponent={selectComponent}
                 onSelectEntity={(id) => { setSelectedComponent(null); setSelectedEntity(id); }} />

          {templates.length > 0 && (
            <div className="tray">
              <span className="muted">Place</span>
              {templates.map((template) => (
                <button key={template.label}
                        className={placing?.label === template.label ? 'selected' : ''}
                        onClick={() => setPlacing(placing?.label === template.label ? null : template)}
                        title={template.label}>
                  <span className="dot" style={{ background: entityColor(template.attributes) }} />
                  {String(template.attributes.color ?? template.label)}
                </button>
              ))}
            </div>
          )}

          {placing && <div className="hint">Click a free position to place the {placing.label.toLowerCase()} · Esc to cancel</div>}
          {!placing && faultMode && <div className="hint">Click a component with a ? to break it. The application only sees the effect.</div>}
          {!placing && !faultMode && running && !realTime.enabled && (
            <div className="hint">Time is paused. Commands from the application wait until you continue.</div>
          )}
        </div>

        <aside className="side">
          {showInspector && (
            <Inspector snapshot={snapshot} events={events} now={now}
                       componentId={selectedComponent} entityId={selectedEntity}
                       onClose={() => { setSelectedComponent(null); setSelectedEntity(null); }}
                       onFault={(componentId, faultId, active) => attempt(() => api.setFault(simulationId, componentId, faultId, active))}
                       onRemoveEntity={(entityId) => attempt(async () => {
                         await api.removeEntity(simulationId, entityId);
                         setSelectedEntity(null);
                       })}
                       onSelectEntity={(id) => { setSelectedComponent(null); setSelectedEntity(id); }} />
          )}
          <Story events={events} snapshot={snapshot} onHover={setHighlight} onSelect={selectComponent} />
        </aside>
      </div>

      <footer className="timebar">
        {running ? (
          <button className="primary" onClick={togglePlay} style={{ minWidth: 92 }}>
            {realTime.enabled ? 'Pause' : 'Continue'}
          </button>
        ) : (
          <button className="primary" onClick={start} style={{ minWidth: 92 }}>Start</button>
        )}
        <button onClick={step} disabled={!running} title="Pause and jump to the next scheduled step">Next step</button>
        <label className="small muted" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          Speed
          <select value={realTime.speedFactor ?? 1} onChange={(e) => changeSpeed(Number(e.target.value))}>
            {SPEEDS.map((speed) => <option key={speed} value={speed}>{speed}×</option>)}
          </select>
        </label>
        <span style={{ flex: 1 }} />
        <span className="clock mono">{clock(now)}</span>
        <button className="ghost" onClick={reset} title="Rebuild the lab from its initial state">Reset</button>
      </footer>
    </div>
  );
}
