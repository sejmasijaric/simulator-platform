package ch.hsg.simulation.core.runtime;

import ch.hsg.simulation.core.NotFoundException;
import ch.hsg.simulation.core.SimulationException;
import ch.hsg.simulation.core.TestNets;
import ch.hsg.simulation.core.event.EventType;
import ch.hsg.simulation.core.event.SimulationEvent;
import ch.hsg.simulation.core.port.CommandHandle;
import ch.hsg.simulation.core.port.SimulationControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationManagerTest {

    private final SimulationManager manager = new SimulationManager(() -> List.of(TestNets.oven()));
    private final SimulationControl control = manager;

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void layoutsComeFromTheSource() {
        assertEquals(TestNets.LAYOUT, control.layouts().get(0).id());
        assertThrows(NotFoundException.class, () -> control.layout("nope"));
        assertThrows(NotFoundException.class, () -> control.create("nope"));
    }

    @Test
    void aLayoutCannotBeDefinedTwice() {
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationManager(() -> List.of(TestNets.oven(), TestNets.oven())));
    }

    @Test
    void runningFindsTheLatestStartedSimulationOfALayout() {
        UUID first = control.create(TestNets.LAYOUT);
        UUID second = control.create(TestNets.LAYOUT);
        assertEquals(Optional.empty(), control.running(TestNets.LAYOUT));

        control.start(first);
        control.start(second);
        assertEquals(Optional.of(second), control.running(TestNets.LAYOUT));

        control.stop(second);
        assertEquals(Optional.of(first), control.running(TestNets.LAYOUT));
    }

    @Test
    void aRunningSimulationCannotBeRemoved() {
        UUID id = control.create(TestNets.LAYOUT);
        control.start(id);
        assertThrows(SimulationException.class, () -> control.remove(id));
        control.stop(id);
        control.remove(id);
        assertThrows(NotFoundException.class, () -> control.snapshot(id));
    }

    @Test
    void listenersHearEveryEventWithItsSimulation() {
        List<SimulationEvent> heard = new CopyOnWriteArrayList<>();
        control.addListener((simulationId, layoutId, event) -> heard.add(event));
        UUID id = control.create(TestNets.LAYOUT);
        control.start(id);
        control.placeToken(id, "oven_platform", "wp1", Map.of());

        assertEquals(List.of(EventType.SIMULATION_STARTED, EventType.TOKEN_PLACED),
                heard.stream().map(SimulationEvent::type).toList());
    }

    @Test
    void realTimeMovesTheClock() throws Exception {
        UUID id = control.create(TestNets.LAYOUT);
        control.start(id);
        control.placeToken(id, "oven_platform", "wp1", Map.of());
        control.setSpeed(id, 200); // 12 s of burning in about 60 ms

        CommandHandle handle = control.command(id, "cmd_burn", Map.of(), "burn_end", null);
        handle.completion().get(5, TimeUnit.SECONDS);
        assertEquals(200.0, control.snapshot(id).speed());

        control.stopRealTime(id);
        assertEquals(null, control.snapshot(id).speed());
        assertTrue(control.snapshot(id).time().getSeconds() >= 12);
    }
}
