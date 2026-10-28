package ch.hsg.simulation.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventQueueTest {

    @Test
    void ordersByTimeThenInsertion() {
        EventQueue queue = new EventQueue();
        queue.schedule(Duration.ZERO, Duration.ofSeconds(2), null, "b", List.of(), List.of(), false);
        queue.schedule(Duration.ZERO, Duration.ofSeconds(1), null, "a", List.of(), List.of(), false);
        queue.schedule(Duration.ZERO, Duration.ofSeconds(2), null, "c", List.of(), List.of(), false);

        assertThat(List.of(
                queue.poll().orElseThrow().componentId(),
                queue.poll().orElseThrow().componentId(),
                queue.poll().orElseThrow().componentId())).containsExactly("a", "b", "c");
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void rejectsSchedulingInThePast() {
        EventQueue queue = new EventQueue();
        assertThatThrownBy(() -> queue.schedule(Duration.ofSeconds(5), Duration.ofSeconds(1), null, "a", List.of(), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
