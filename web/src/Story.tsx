import { useState } from 'react';
import type { NetDefinition, Selection, SimEvent } from './types';
import { actorOf, isMinor, sentence } from './sentences';
import { clock, seconds } from './time';

type Filter = 'all' | 'requests' | 'faults';

const REQUEST_TYPES = ['COMMAND_RECEIVED', 'COMMAND_COMPLETED'];
const FAULT_TYPES = ['FAULT_ACTIVATED', 'FAULT_CLEARED', 'TOKEN_LOST'];

interface Props {
  events: SimEvent[];
  net: NetDefinition;
  onHover: (name: string | null) => void;
  onSelect: (selection: Selection) => void;
}

/** The trace: what the net did and who caused it. Lines of one request share a colour bar. */
export function Story({ events, net, onHover, onSelect }: Props) {
  const [filter, setFilter] = useState<Filter>('all');
  const [details, setDetails] = useState(false);
  const transitions = new Set(net.transitions.map((t) => t.name));
  const places = new Set(net.places.map((p) => p.name));
  const sensors = new Set(net.sensors.map((s) => s.name));
  const commands = [...new Set(events.map((e) => e.commandId).filter(Boolean))] as string[];

  const visible = events
    .filter((e) => details || !isMinor(e))
    .filter((e) => filter === 'all' || (filter === 'requests' && REQUEST_TYPES.includes(e.type))
      || (filter === 'faults' && FAULT_TYPES.includes(e.type)))
    .slice(-300)
    .reverse();

  const select = (event: SimEvent) => {
    const name = event.subject;
    if (!name) return;
    if (transitions.has(name)) onSelect({ kind: 'transition', name });
    else if (places.has(name)) onSelect({ kind: 'place', name });
    else if (sensors.has(name)) onSelect({ kind: 'sensor', name });
  };

  return (
    <div className="tab-body story-tab">
      <div className="row" style={{ padding: '0 0 6px' }}>
        <select value={filter} onChange={(e) => setFilter(e.target.value as Filter)} aria-label="Filter">
          <option value="all">Everything</option>
          <option value="requests">Requests and answers</option>
          <option value="faults">Faults and losses</option>
        </select>
        <span style={{ flex: 1 }} />
        <label className="small muted row"><input type="checkbox" checked={details} onChange={(e) => setDetails(e.target.checked)} /> token details</label>
      </div>
      <div className="story">
        {!events.some((e) => e.type === 'COMMAND_RECEIVED') && (
          <div className="empty">
            No requests yet. Start your application (e.g. the Camunda process) against this simulator,
            or send a command from the API tab. You can put workpieces into places and switch on faults first.
          </div>
        )}
        {visible.map((event) => {
          const actor = actorOf(event);
          const group = event.commandId ? commands.indexOf(event.commandId) % 6 : -1;
          return (
            <div key={event.index}
                 className={`line ${isMinor(event) ? 'minor' : ''} ${group >= 0 ? `group-${group}` : ''} ${event.type.toLowerCase()}`}
                 onMouseEnter={() => onHover(event.subject)} onMouseLeave={() => onHover(null)}
                 onClick={() => select(event)}>
              <span className="t">{clock(seconds(event.time))}</span>
              {isMinor(event) ? <span /> : <span className={`chip ${actor}`}>{actor}</span>}
              <span>{sentence(event, net)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
