package ch.hsg.simulation.core.event;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only trace of one simulation plus fan-out to listeners (the events port). A failing
 * listener is logged and never affects the simulation.
 */
public final class EventLog {

    private static final Logger LOG = Logger.getLogger(EventLog.class.getName());

    private final List<SimulationEvent> events = new ArrayList<>();
    private final List<Consumer<SimulationEvent>> listeners = new CopyOnWriteArrayList<>();

    public SimulationEvent append(Duration time, EventType type, String subject, UUID commandId, String origin,
                                  Map<String, ?> data) {
        SimulationEvent event;
        synchronized (events) {
            event = new SimulationEvent(events.size(), time, type, subject, commandId, origin,
                    data == null ? Map.of() : new LinkedHashMap<String, Object>(data));
            events.add(event);
        }
        for (Consumer<SimulationEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException exception) {
                LOG.log(Level.WARNING, "Event listener failed for " + event.type(), exception);
            }
        }
        return event;
    }

    /** Events from index {@code from} on. */
    public List<SimulationEvent> events(int from) {
        synchronized (events) {
            int start = Math.max(0, Math.min(from, events.size()));
            return List.copyOf(events.subList(start, events.size()));
        }
    }

    public void clear() {
        synchronized (events) {
            events.clear();
        }
    }

    public void addListener(Consumer<SimulationEvent> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<SimulationEvent> listener) {
        listeners.remove(listener);
    }
}
