package ch.hsg.simulation.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only history of committed events (traceability) plus listener fan-out for adapters.
 * A failing listener is logged and never affects the simulation state.
 */
public final class EventLog {

    private static final Logger LOG = Logger.getLogger(EventLog.class.getName());

    private final List<SimulationEvent> events = new ArrayList<>();
    private final List<Consumer<SimulationEvent>> listeners = new CopyOnWriteArrayList<>();

    public void append(SimulationEvent event) {
        synchronized (events) {
            events.add(event);
        }
        for (Consumer<SimulationEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException exception) {
                LOG.log(Level.WARNING, "Event listener failed for " + event.type(), exception);
            }
        }
    }

    public List<SimulationEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
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
