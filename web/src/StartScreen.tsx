import { useState } from 'react';
import { api } from './api';
import { usePolling } from './hooks';
import { modulesOf } from './net';
import { clock, seconds } from './time';
import type { NetDefinition } from './types';

export function StartScreen({ onOpen }: { onOpen: (id: string) => void }) {
  const layouts = usePolling(api.layouts, 10000, []);
  const runs = usePolling(api.simulations, 2000, []);
  const [error, setError] = useState<string | null>(null);

  const launch = async (net: NetDefinition) => {
    try {
      const { id } = await api.create(net.id);
      await api.start(id);
      await api.setSpeed(id, 1);
      onOpen(id);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const remove = async (id: string, running: boolean) => {
    try {
      if (running) await api.stop(id);
      await api.remove(id);
      runs.refresh();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const nameOf = (layoutId: string) => layouts.data?.find((l) => l.id === layoutId)?.name ?? layoutId;

  return (
    <div className="start">
      <h1>Choose a layout</h1>
      <p className="muted">
        Each layout is a timed, coloured Petri net declared in YAML. The simulator runs the net and
        offers the physical layout's own API, so your application talks to it like to the real one.
      </p>
      {error && <p className="error">{error}</p>}
      {layouts.error && <p className="error">Can't reach the simulator: {layouts.error}</p>}

      <div className="layouts">
        {layouts.data?.map((net) => (
          <div key={net.id} className="card layout-card">
            {net.image
              ? <img src={net.image.url} alt="" className="preview" />
              : <div className="preview placeholder mono">{net.id}.yaml</div>}
            <h2>{net.name}</h2>
            <p className="muted small" style={{ margin: 0, flex: 1 }}>{net.description}</p>
            <div className="stats small">
              <span><b>{net.places.length}</b> places</span>
              <span><b>{net.transitions.length}</b> transitions</span>
              <span><b>{net.http.routes.length}</b> API routes</span>
              <span><b>{modulesOf(net).length}</b> modules</span>
            </div>
            <div><button className="primary" onClick={() => launch(net)}>Open simulation</button></div>
          </div>
        ))}
      </div>

      {runs.data && runs.data.length > 0 && (
        <>
          <h2 style={{ marginBottom: 8 }}>Simulations</h2>
          <div className="runs">
            {runs.data.map((run) => (
              <div className="run" key={run.id}>
                <span style={{ flex: 1 }}>{nameOf(run.layoutId)} <span className="mono small muted">{run.id.slice(0, 8)}</span></span>
                <span className={`chip ${run.status.toLowerCase()}`}>{run.status.toLowerCase()}</span>
                <span className="mono small muted">{clock(seconds(run.time))}</span>
                <button onClick={() => onOpen(run.id)}>Open</button>
                <button className="ghost" onClick={() => remove(run.id, run.status === 'RUNNING')}>Remove</button>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
