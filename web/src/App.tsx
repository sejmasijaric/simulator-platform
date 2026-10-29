import { useEffect, useState } from 'react';
import { StartScreen } from './StartScreen';
import { SimulationScreen } from './SimulationScreen';

function currentSimulation(): string | null {
  const match = /^#\/simulations\/([\w-]+)/.exec(window.location.hash);
  return match ? match[1] : null;
}

export function App() {
  const [simulationId, setSimulationId] = useState(currentSimulation);

  useEffect(() => {
    const onChange = () => setSimulationId(currentSimulation());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  const open = (id: string | null) => {
    window.location.hash = id ? `/simulations/${id}` : '';
  };

  return simulationId
    ? <SimulationScreen key={simulationId} simulationId={simulationId} onLeave={() => open(null)} />
    : <StartScreen onOpen={open} />;
}
