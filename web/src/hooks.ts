import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';
import type { RealTime, SimEvent, Snapshot } from './types';
import { seconds } from './time';

export function usePolling<T>(load: () => Promise<T>, intervalMs: number, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [receivedAt, setReceivedAt] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  const refresh = useCallback(async () => {
    try {
      const value = await loadRef.current();
      setData(value);
      setReceivedAt(performance.now());
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    let active = true;
    let timer: number;
    const tick = async () => {
      await refresh();
      if (active) timer = window.setTimeout(tick, intervalMs);
    };
    tick();
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, error, receivedAt, refresh };
}

/** Appends new events by polling with an index; starts over when the log was cleared (reset). */
export function useEvents(simulationId: string, snapshot: Snapshot | null) {
  const [events, setEvents] = useState<SimEvent[]>([]);
  const eventsRef = useRef(events);
  eventsRef.current = events;
  const lastTime = useRef(0);

  useEffect(() => {
    const now = seconds(snapshot?.time);
    if (now < lastTime.current) setEvents([]);
    lastTime.current = now;
  }, [snapshot?.time]);

  useEffect(() => {
    let active = true;
    let timer: number;
    const tick = async () => {
      try {
        const from = eventsRef.current.length;
        const fresh = await api.events(simulationId, from);
        if (active && fresh.length > 0) {
          if (fresh[0].type === 'SIMULATION_RESET' && from > 0) setEvents(fresh);
          else setEvents((current) => [...current, ...fresh]);
        }
      } catch {
        /* the snapshot poll reports connection problems */
      }
      if (active) timer = window.setTimeout(tick, 400);
    };
    tick();
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [simulationId]);

  return { events, clear: () => setEvents([]) };
}

export function useRealTime(simulationId: string) {
  const [realTime, setRealTime] = useState<RealTime>({ enabled: false, speedFactor: null });
  useEffect(() => {
    api.realTime(simulationId).then(setRealTime).catch(() => undefined);
  }, [simulationId]);
  return [realTime, setRealTime] as const;
}

/** Re-renders every animation frame while {@code active}, so clocks and progress bars move smoothly. */
export function useFrame(active: boolean) {
  const [, setFrame] = useState(0);
  useEffect(() => {
    if (!active) return;
    let id = requestAnimationFrame(function loop() {
      setFrame((f) => f + 1);
      id = requestAnimationFrame(loop);
    });
    return () => cancelAnimationFrame(id);
  }, [active]);
}
