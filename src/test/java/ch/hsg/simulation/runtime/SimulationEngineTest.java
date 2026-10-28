package ch.hsg.simulation.runtime;

import ch.hsg.simulation.event.EventType;
import ch.hsg.simulation.event.SimulationEvent;
import ch.hsg.simulation.execution.ComponentAction;
import ch.hsg.simulation.execution.ComponentExecutionResult;
import ch.hsg.simulation.execution.ExecutionStatus;
import ch.hsg.simulation.model.NotFoundException;
import ch.hsg.simulation.model.SimulationSnapshot;
import ch.hsg.simulation.model.SimulationStateException;
import ch.hsg.simulation.testlayout.LightBarrier;
import ch.hsg.simulation.testlayout.TestLayoutDefinition;
import ch.hsg.simulation.testlayout.Transporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationEngineTest {

    private SimulationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SimulationEngine(new TestLayoutDefinition().create(UUID.randomUUID()));
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    private static ComponentAction transport(String from, String to) {
        return ComponentAction.of(Transporter.TRANSPORT, Map.of("from", from, "to", to));
    }

    private String locationOf(String entityId) {
        return engine.snapshot().entity(entityId).orElseThrow().locationComponentId();
    }

    @Test
    void timedActionCommitsPhasesAtTheirSimulatedTimes() {
        ComponentExecutionResult result = engine.execute("T", transport("IN", "OUT"));

        assertThat(result.status()).isEqualTo(ExecutionStatus.ACCEPTED);
        assertThat(result.duration()).isEqualTo(Duration.ofSeconds(4));
        assertThat(engine.snapshot().component("T").orElseThrow().busy()).isTrue();
        assertThat(engine.snapshot().component("T").orElseThrow().state().attributes()).containsEntry("currentTask", "transport");
        assertThat(locationOf("wp-1")).isEqualTo("IN");

        assertThat(engine.step()).isTrue();
        assertThat(engine.now()).isEqualTo(Duration.ofSeconds(2));
        assertThat(locationOf("wp-1")).isEqualTo("HOLD");

        assertThat(engine.step()).isTrue();
        assertThat(engine.now()).isEqualTo(Duration.ofSeconds(4));
        assertThat(locationOf("wp-1")).isEqualTo("OUT");
        assertThat(engine.snapshot().component("T").orElseThrow().busy()).isFalse();
        assertThat(engine.step()).isFalse();
    }

    @Test
    void completionFutureCompletesWithTheLastPhase() throws Exception {
        ComponentAction action = transport("IN", "OUT");
        engine.execute("T", action);
        CompletableFuture<SimulationEvent> completion = engine.completionOf(action.commandId());

        engine.runUntil(Duration.ofSeconds(3));
        assertThat(completion).isNotDone();

        engine.runUntil(Duration.ofSeconds(4));
        SimulationEvent completed = completion.get(1, TimeUnit.SECONDS);
        assertThat(completed.type()).isEqualTo(EventType.ACTION_COMPLETED);
        assertThat(completed.timestamp()).isEqualTo(Duration.ofSeconds(4));
        assertThat(completed.commandId()).isEqualTo(action.commandId());
    }

    @Test
    void busyComponentRejectsSecondAction() {
        engine.execute("T", transport("IN", "OUT"));

        ComponentExecutionResult second = engine.execute("T", transport("IN", "OUT"));

        assertThat(second.status()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(second.errorMessage()).contains("T is busy");
    }

    @Test
    void unsupportedCapabilityIsRejectedWithoutStateChange() {
        SimulationSnapshot before = engine.snapshot();

        ComponentExecutionResult result = engine.execute("T", ComponentAction.of("fly"));

        assertThat(result.status()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(engine.snapshot()).isEqualTo(before);
        assertThat(engine.events()).extracting(SimulationEvent::type).contains(EventType.ACTION_REJECTED);
    }

    @Test
    void invalidParametersAreRejected() {
        assertThat(engine.execute("T", ComponentAction.of(Transporter.TRANSPORT, Map.of("from", "IN"))).errorMessage())
                .hasValueSatisfying(message -> assertThat(message).contains("Missing parameter 'to'"));
        assertThat(engine.execute("T", ComponentAction.of(Transporter.TRANSPORT, Map.of("from", "IN", "to", 3))).errorMessage())
                .hasValueSatisfying(message -> assertThat(message).contains("must be of type STRING"));
        assertThat(engine.execute("T", ComponentAction.of(Transporter.TRANSPORT, Map.of("from", "IN", "to", "OUT", "speed", 2))).errorMessage())
                .hasValueSatisfying(message -> assertThat(message).contains("Unknown parameter 'speed'"));
    }

    @Test
    void unknownComponentRaisesNotFound() {
        assertThatThrownBy(() -> engine.execute("nope", transport("IN", "OUT")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void moveWithoutConnectionIsRejectedAndLogged() {
        engine.execute("T", transport("IN", "X"));
        engine.runUntilIdle();

        assertThat(locationOf("wp-1")).isEqualTo("HOLD");
        assertThat(engine.events()).extracting(SimulationEvent::type)
                .contains(EventType.CHANGE_REJECTED, EventType.ACTION_COMPLETED);
        assertThat(engine.snapshot().component("T").orElseThrow().state().attributes())
                .as("the failed phase is atomic: the idle state of the same phase was not applied either")
                .containsEntry("currentTask", "transport");
    }

    @Test
    void itemNotMovedFaultIsSilentOnTheTaskButVisibleInTheTrace() {
        engine.setFault("T", Transporter.ITEM_NOT_MOVED, true);
        ComponentAction action = transport("IN", "OUT");

        ComponentExecutionResult result = engine.execute("T", action);
        engine.runUntilIdle();

        assertThat(result.status()).isEqualTo(ExecutionStatus.ACCEPTED);
        assertThat(engine.completionOf(action.commandId())).isCompleted();
        assertThat(locationOf("wp-1")).isEqualTo("IN");
        assertThat(engine.snapshot().component("T").orElseThrow().activeFaults()).containsExactly(Transporter.ITEM_NOT_MOVED);
        assertThat(engine.events()).extracting(SimulationEvent::type).contains(EventType.FAULT_ACTIVATED);
    }

    @Test
    void undeclaredFaultIsRejected() {
        assertThatThrownBy(() -> engine.setFault("T", "MELTS", true)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void sensorObservesSlotAndCanBeStuck() {
        engine.execute("T", transport("IN", "OUT"));
        engine.runUntilIdle();
        assertThat(engine.snapshot().component("LB").orElseThrow().state().attributes()).containsEntry("interrupted", true);

        engine.setFault("LB", LightBarrier.SENSOR_STUCK, true);
        engine.execute("T", transport("OUT", "IN"));
        engine.runUntilIdle();

        assertThat(engine.snapshot().component("LB").orElseThrow().state().attributes()).containsEntry("interrupted", true);
    }

    @Test
    void clockNeverGoesBackwards() {
        engine.runUntil(Duration.ofSeconds(10));
        engine.runUntil(Duration.ofSeconds(5));

        assertThat(engine.now()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void commandsRequireRunningSimulationAndStopPauses() {
        engine.execute("T", transport("IN", "OUT"));
        engine.stop();

        assertThatThrownBy(() -> engine.execute("T", transport("IN", "OUT"))).isInstanceOf(SimulationStateException.class);
        assertThatThrownBy(engine::step).isInstanceOf(SimulationStateException.class);

        engine.start();
        engine.runUntilIdle();
        assertThat(locationOf("wp-1")).isEqualTo("OUT");
    }

    @Test
    void failingListenerDoesNotCorruptState() {
        engine.addListener(event -> {
            throw new IllegalStateException("broker down");
        });

        engine.execute("T", transport("IN", "OUT"));
        engine.runUntilIdle();

        assertThat(locationOf("wp-1")).isEqualTo("OUT");
    }

    @Test
    void concurrentCommandsAreSerialized() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<ComponentExecutionResult>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> {
                go.await();
                return engine.execute("T", transport("IN", "OUT"));
            });
        }
        List<Future<ComponentExecutionResult>> futures = calls.stream().map(pool::submit).toList();
        go.countDown();

        long accepted = 0;
        for (Future<ComponentExecutionResult> future : futures) {
            if (future.get(5, TimeUnit.SECONDS).status() == ExecutionStatus.ACCEPTED) {
                accepted++;
            }
        }
        pool.shutdown();

        assertThat(accepted).isEqualTo(1);
    }

    @Test
    void eventsAreTimestampedAndOrdered() {
        engine.execute("T", transport("IN", "OUT"));
        engine.runUntilIdle();

        List<Duration> times = engine.events().stream().map(SimulationEvent::timestamp).toList();
        assertThat(times).isSorted().doesNotContainNull();
    }
}
