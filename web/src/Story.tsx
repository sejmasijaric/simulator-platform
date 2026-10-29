import { useState } from 'react';
import type { SimEvent, Snapshot } from './types';
import { actorOf, isMinor, sentence } from './sentences';
import { clock, seconds } from './time';

type Filter = 'all' | 'application' | 'faults';

const FAULT_TYPES = ['FAULT_ACTIVATED', 'FAULT_CLEARED', 'CHANGE_REJECTED', 'ACTION_REJECTED', 'ACTION_FAILED'];

interface Props {
  events: SimEvent[];
  snapshot: Snapshot;
  onHover: (componentId: string | null) => void;
  onSelect: (componentId: string) => void;
}

export function Story({ events, snapshot, onHover, onSelect }: Props) {
  const [filter, setFilter] = useState<Filter>('all');
  const [details, setDetails] = useState(false);

  const visible = events
    .filter((e) => details || !isMinor(e))
    .filter((e) => filter === 'all'
      || (filter === 'application' && actorOf(e) === 'application')
      || (filter === 'faults' && FAULT_TYPES.includes(e.type)))
    .slice(-300)
    .reverse();

  const waiting = !events.some((e) => actorOf(e) === 'application');

  return (
    <>
      <div className="story-head">
        <h2 style={{ flex: 1 }}>What happened</h2>
        <select value={filter} onChange={(e) => setFilter(e.target.value as Filter)} aria-label="Filter">
          <option value="all">Everything</option>
          <option value="application">Application</option>
          <option value="faults">Faults and refusals</option>
        </select>
        <label className="small muted" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <input type="checkbox" checked={details} onChange={(e) => setDetails(e.target.checked)} /> details
        </label>
      </div>
      <div className="story">
        {waiting && (
          <div className="empty">
            Waiting for your application. Start the process (e.g. in Camunda) against this simulator.
            You can place workpieces or switch on faults before it starts.
          </div>
        )}
        {visible.map((event) => {
          const actor = actorOf(event);
          return (
            <div key={event.id}
                 className={`line ${isMinor(event) ? 'minor' : ''} ${event.commandId ? 'grouped' : ''}`}
                 onMouseEnter={() => onHover(event.sourceComponentId)}
                 onMouseLeave={() => onHover(null)}
                 onClick={() => event.sourceComponentId && onSelect(event.sourceComponentId)}>
              <span className="t">{clock(seconds(event.timestamp))}</span>
              {isMinor(event) ? <span /> : <span className={`chip ${actor}`}>{actor}</span>}
              <span>
                {sentence(event, snapshot.entities)}
                {event.type === 'FAULT_ACTIVATED' && <span className="hidden">hidden from the application</span>}
              </span>
            </div>
          );
        })}
      </div>
    </>
  );
}
