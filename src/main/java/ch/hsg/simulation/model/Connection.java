package ch.hsg.simulation.model;

/** Directed connection: entities may move from {@code from} to {@code to}. */
public record Connection(String from, String to) {
}
