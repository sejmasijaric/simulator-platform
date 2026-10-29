import { useState } from 'react';
import { api } from './api';
import { usePolling } from './hooks';
import { clock, seconds } from './time';
import type { Definition } from './types';

export function StartScreen({ onOpen }: { onOpen: (id: string) => void }) {
  const definitions = usePolling(api.definitions, 10000, []);
  const runs = usePolling(api.simulations, 2000, []);
  const [error, setError] = useState<string | null>(null);

  const launch = async (definition: Definition) => {
    try {
      const { id } = await api.create(definition.id);
      await api.start(id);
      await api.enableRealTime(id, 1);
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

  return (
    <div className="start">
      <h1>Choose a lab</h1>
      <p className="muted">Each lab is a simulated copy of a physical layout. Your application talks to it like to the real one.</p>
      {error && <p className="error">{error}</p>}
      {definitions.error && <p className="error">Can't reach the simulator: {definitions.error}</p>}

      <div className="layouts">
        {definitions.data?.map((definition) => (
          <div key={definition.id} className="card layout-card">
            <Preview definition={definition} />
            <h2>{definition.name}</h2>
            <p className="muted small" style={{ margin: 0, flex: 1 }}>{definition.description}</p>
            <div><button className="primary" onClick={() => launch(definition)}>Open lab</button></div>
          </div>
        ))}
      </div>

      {runs.data && runs.data.length > 0 && (
        <>
          <h2 style={{ marginBottom: 8 }}>Running simulations</h2>
          <div className="runs">
            {runs.data.map((run) => (
              <div className="run" key={run.id}>
                <span style={{ flex: 1 }}>{run.name}</span>
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

function Preview({ definition }: { definition: Definition }) {
  const view = definition.layoutView;
  if (!view) return null;
  const points = Object.values(view.positions);
  return (
    <svg viewBox={`0 0 ${view.width} ${view.height}`} width="100%" height="110" preserveAspectRatio="xMidYMid meet">
      {points.map((point, index) => (
        <circle key={index} cx={point.x} cy={point.y} r={18} fill="var(--accent-bg)" stroke="var(--accent)" strokeWidth={2} />
      ))}
    </svg>
  );
}
