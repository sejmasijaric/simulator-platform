package ch.hsg.simulation.api;

public enum ParameterType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN;

    public boolean accepts(Object value) {
        return switch (this) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
        };
    }
}
