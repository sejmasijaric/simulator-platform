import { useEffect, useMemo, useState } from 'react';
import { api } from './api';
import { Floor, FloorLegend } from './Floor';
import { Inspector, type InspectorActions } from './Inspector';
import { StationsPanel } from './StationsPanel';
import { HttpPanel } from './HttpPanel';
import { Story } from './Story';
import { useEvents, useFrame, usePolling } from './hooks';
import { colorOf, placeKinds } from './net';
import { clock, seconds } from './time';
import type { Selection, TokenTemplate } from './types';

const SPEEDS = [0.5, 1, 2, 5, 10, 50];

type Tab = 'log' | 'http' | 'stations';

interface Props {
  simulationId: string;
  onLeave: () => void;
}

export function SimulationScreen({ simulationId, onLeave }: Props) {
  const snapshotPoll = usePolling(() => api.snapshot(simulationId), 250, [simulationId]);
  const layouts = usePolling(api.layouts, 60000, []);
  const snapshot = snapshotPoll.data;
  const { events, clear } = useEvents(simulationId, snapshot);
  const net = layouts.data?.find((l) => l.id === snapshot?.layoutId);
  const kinds = useMemo(() => (net ? placeKinds(net) : {}), [net]);

  const [selection, setSelection] = useState<Selection | null>(null);
  const [highlight, setHighlight] = useState<string | null>(null);
  const [placing, setPlacing] = useState<TokenTemplate | null>(null);
  const [tab, setTab] = useState<Tab>('log');
  const [message, setMessage] = useState<string | null>(null);
  const [lastSpeed, setLastSpeed] = useState(1);

  const running = snapshot?.status === 'RUNNING';
  const realTime = snapshot?.speed != null;
  const ticking = running && realTime;
  useFrame(ticking || (snapshot?.running.length ?? 0) > 0);

  const base = seconds(snapshot?.time);
  const now = ticking ? base + ((performance.now() - snapshotPoll.receivedAt) / 1000) * (snapshot?.speed ?? 1) : base;

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setPlacing(null);
        setSelection(null);
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

  if (!snapshot || !net) {
    return (
      <div className="start">
        {snapshotPoll.error || layouts.error
          ? <><p className="error">{snapshotPoll.error ?? layouts.error}</p><button onClick={onLeave}>Back to layouts</button></>
          : <p className="muted">Loading…</p>}
      </div>
    );
  }

  const select = (next: Selection) => {
    if (placing && next.kind === 'place') {
      const template = placing;
      setPlacing(null); // one workpiece per click; pick again for the next one
      attempt(() => api.placeToken(simulationId, next.name, template.attributes));
      return;
    }
    setSelection(next);
  };

  const actions: InspectorActions = {
    placeToken: (place, attributes) => attempt(() => api.placeToken(simulationId, place, attributes)),
    removeToken: (tokenId) => attempt(() => api.removeToken(simulationId, tokenId)),
    setNetFault: (transition, type, factor, place) => attempt(() => type
      ? api.setNetFault(simulationId, transition, type, factor, place)
      : api.clearNetFault(simulationId, transition)),
    setSensorFault: (sensor, type, value, delay) => attempt(() => type
      ? api.setSensorFault(simulationId, sensor, type, value, delay)
      : api.clearSensorFault(simulationId, sensor)),
    select: setSelection,
  };

  const togglePlay = () => attempt(async () => {
    if (realTime) await api.stopRealTime(simulationId);
    else await api.setSpeed(simulationId, lastSpeed);
  });
  const changeSpeed = (speed: number) => attempt(async () => {
    setLastSpeed(speed);
    await api.setSpeed(simulationId, speed);
  });
  const step = () => attempt(async () => {
    if (realTime) await api.stopRealTime(simulationId);
    const result = await api.step(simulationId);
    if (!result.stepped) setMessage('Nothing is running. Time moves on once a machine has started.');
  });
  const start = () => attempt(async () => {
    await api.start(simulationId);
    await api.setSpeed(simulationId, lastSpeed);
  });
  const reset = () => attempt(async () => {
    await api.reset(simulationId);
    clear();
    setSelection(null);
    await api.start(simulationId);
    await api.setSpeed(simulationId, lastSpeed);
  });

  const statusLabel = !running ? snapshot.status.toLowerCase() : realTime ? 'running' : 'paused';
  const faults = Object.keys(snapshot.sensorFaults).length + Object.keys(snapshot.netFaults).length;

  return (
    <div className="screen">
      <header className="topbar">
        <button className="ghost" onClick={onLeave} aria-label="Back to layouts">←</button>
        <h2>{net.name}</h2>
        <span className={`chip ${statusLabel}`}>{statusLabel}</span>
        <span className="mono tiny muted" title="Simulation id, e.g. for lab_process.py --simulation">{snapshot.id.slice(0, 8)}</span>
        <span style={{ flex: 1 }} />
        {message && <span className="error">{message}</span>}
      </header>

      <div className="main">
        <div className="stage">
          <div className="floor-toolbar">
            {net.tokenTemplates.length > 0 && (
              <div className="tray">
                <span className="muted">Put</span>
                {net.tokenTemplates.map((template) => (
                  <button key={template.name} className={placing?.name === template.name ? 'selected' : ''}
                          onClick={() => setPlacing(placing?.name === template.name ? null : template)} title={template.name}>
                    <span className="dot" style={{ background: colorOf(template.attributes) ?? 'var(--token)' }} />
                    {String(template.attributes.color ?? template.name)}
                  </button>
                ))}
              </div>
            )}
            <span className="toolbar-hint">
              {placing
                ? <>Click a position for the {placing.name.toLowerCase()} · Esc cancels</>
                : running && !realTime ? 'Time is paused: requests wait until you continue or step.' : ''}
            </span>
            <FloorLegend />
          </div>
          <Floor net={net} kinds={kinds} snapshot={snapshot} now={now} selection={selection}
                 highlight={highlight} placing={placing !== null} onSelect={select} />

        </div>

        <aside className="side">
          {selection && (
            <Inspector net={net} kinds={kinds} snapshot={snapshot} selection={selection} now={now}
                       actions={actions} onClose={() => setSelection(null)} />
          )}
          <div className="tabs" role="tablist">
            <button className={tab === 'log' ? 'on' : ''} onClick={() => setTab('log')}>Activity</button>
            <button className={tab === 'http' ? 'on' : ''} onClick={() => setTab('http')}>HTTP API</button>
            <button className={tab === 'stations' ? 'on' : ''} onClick={() => setTab('stations')}>
              Stations{faults > 0 && <span className="chip warn" title="faults switched on">{faults}</span>}
            </button>
          </div>
          {tab === 'log' && <Story events={events} net={net} onHover={setHighlight} onSelect={setSelection} />}
          {tab === 'http' && <HttpPanel net={net} />}
          {tab === 'stations' && (
            <StationsPanel net={net} snapshot={snapshot}
                           onSelectSensor={(name) => setSelection({ kind: 'sensor', name })}
                           onModules={(list) => attempt(() => api.setModules(simulationId, list))}
                           onSetSetting={(name, value) => attempt(() => api.setSetting(simulationId, name, value))}
                           onResetSetting={(name) => attempt(() => api.resetSetting(simulationId, name))} />
          )}
        </aside>
      </div>

      <footer className="timebar">
        {running ? (
          <button className="primary" onClick={togglePlay} style={{ minWidth: 92 }}>{realTime ? 'Pause' : 'Continue'}</button>
        ) : (
          <button className="primary" onClick={start} style={{ minWidth: 92 }}>Start</button>
        )}
        <button onClick={step} disabled={!running} title="Pause and jump to the end of the next running step">Next step</button>
        <label className="small muted row">
          Speed
          <select value={snapshot.speed ?? lastSpeed} onChange={(e) => changeSpeed(Number(e.target.value))}>
            {SPEEDS.map((speed) => <option key={speed} value={speed}>{speed}×</option>)}
          </select>
        </label>
        <span style={{ flex: 1 }} />
        <span className="clock mono">{clock(now)}</span>
        <button className="ghost" onClick={reset} title="Back to the initial state">Reset</button>
      </footer>
    </div>
  );
}
