package ch.hsg.simulation.core.net;

import ch.hsg.simulation.core.fault.Faults;
import ch.hsg.simulation.core.net.NetDefinition.Arc;
import ch.hsg.simulation.core.net.NetDefinition.Transition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The semantics of a timed, coloured Petri net, without side effects: which transition can fire
 * next and what its firing produces. The engine applies the result.
 *
 * <p>Rules:
 * <ul>
 *   <li>Transitions are tried by priority (high first), then in declaration order.</li>
 *   <li>A transition is enabled when every input arc finds a token, its inhibitor places are
 *       empty, its guard holds and every output place keeps within its capacity.</li>
 *   <li>Each input arc takes the oldest token of its place that satisfies the guard conditions
 *       on its variable.</li>
 *   <li>The request parameters of a firing are the attributes of the tokens it consumes, the
 *       earlier arc winning; template places ({@code ${start}}), guard values and results are
 *       filled from them, and {@code ${variable.attribute}} reads a bound token.</li>
 *   <li>The firing belongs to the command of the first consumed token that has one.</li>
 * </ul>
 */
public final class NetInterpreter {

    /** A token taken from a place by a firing. */
    public record Consumed(String place, Token token, String variable) {
    }

    /** A token a firing will put into a place when it ends. */
    public record Produced(String place, Token token) {
    }

    /** One firing: what it takes now, what it gives back after {@code duration}, what it loses. */
    public record Firing(Transition transition, List<Consumed> consumed, List<Produced> produced,
                         List<Token> lost, Duration duration, UUID commandId, Map<String, Object> parameters,
                         Map<String, Object> result) {
        public Firing {
            consumed = List.copyOf(consumed);
            produced = List.copyOf(produced);
            lost = List.copyOf(lost);
            parameters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            result = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
        }
    }

    private final NetDefinition net;
    private final List<Transition> order;

    public NetInterpreter(NetDefinition net) {
        this.net = net;
        List<Transition> sorted = new ArrayList<>(net.transitions());
        sorted.sort(Comparator.comparingInt(Transition::priority).reversed()); // stable: keeps declaration order
        this.order = List.copyOf(sorted);
    }

    public NetDefinition net() {
        return net;
    }

    /**
     * The next firing, if any transition is enabled. {@code activeModules} empty means all
     * modules; {@code newTokenId} names tokens created by output arcs without a variable.
     */
    public Optional<Firing> next(Marking marking, Faults faults, Set<String> activeModules,
                                 Supplier<String> newTokenId) {
        for (Transition transition : order) {
            if (faults.isBlocked(transition.name()) || !isActive(transition, activeModules)) {
                continue;
            }
            Optional<Firing> firing = tryBind(transition, marking, faults, newTokenId);
            if (firing.isPresent()) {
                return firing;
            }
        }
        return Optional.empty();
    }

    private static boolean isActive(Transition transition, Set<String> activeModules) {
        return activeModules.isEmpty() || transition.module() == null || activeModules.contains(transition.module());
    }

    private Optional<Firing> tryBind(Transition transition, Marking marking, Faults faults,
                                     Supplier<String> newTokenId) {
        for (String inhibitor : transition.inhibitors()) {
            if (!Templates.isTemplate(inhibitor) && marking.occupied(inhibitor)) {
                return Optional.empty();
            }
        }

        List<Consumed> consumed = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        Map<String, Token> variables = new HashMap<>();
        Map<String, Integer> leaving = new HashMap<>();
        Function<String, Object> lookup = key -> lookup(key, parameters, variables);

        for (Arc arc : transition.inputs()) {
            Optional<String> place = resolve(arc.place(), lookup);
            if (place.isEmpty() || !marking.hasPlace(place.get())) {
                return Optional.empty();
            }
            Optional<Token> token = marking.tokensAt(place.get()).stream()
                    .filter(candidate -> !taken.contains(candidate.id()))
                    .filter(candidate -> arc.variable() == null
                            || guardHoldsFor(transition, arc.variable(), candidate, lookup))
                    .findFirst();
            if (token.isEmpty()) {
                return Optional.empty();
            }
            taken.add(token.get().id());
            leaving.merge(place.get(), 1, Integer::sum);
            consumed.add(new Consumed(place.get(), token.get(), arc.variable()));
            if (arc.variable() != null) {
                variables.put(arc.variable(), token.get());
            }
            token.get().attributes().forEach(parameters::putIfAbsent);
        }
        if (!parameterGuardHolds(transition, lookup)) {
            return Optional.empty();
        }
        for (String inhibitor : transition.inhibitors()) {
            if (Templates.isTemplate(inhibitor)) {
                Optional<String> place = resolve(inhibitor, lookup);
                if (place.isEmpty() || !marking.hasPlace(place.get()) || marking.occupied(place.get())) {
                    return Optional.empty();
                }
            }
        }

        UUID commandId = consumed.stream().map(entry -> entry.token().commandId())
                .filter(Objects::nonNull).findFirst().orElse(null);
        Optional<String> misroute = faults.misrouteTarget(transition.name());
        boolean losesTokens = faults.losesTokens(transition.name());

        List<Produced> produced = new ArrayList<>();
        List<Token> lost = new ArrayList<>();
        Map<String, Integer> arriving = new HashMap<>();
        for (Arc arc : transition.outputs()) {
            Optional<String> place = resolve(arc.place(), lookup);
            if (place.isEmpty() || !marking.hasPlace(place.get())) {
                return Optional.empty();
            }
            Token token;
            String target = place.get();
            if (arc.variable() != null) {
                token = variables.get(arc.variable()).withCommand(commandId);
                if (losesTokens) {
                    lost.add(token);
                    continue;
                }
                if (misroute.isPresent()) {
                    target = misroute.get();
                    if (!marking.hasPlace(target)) {
                        return Optional.empty();
                    }
                }
            } else {
                token = new Token(newTokenId.get(), Map.of(), commandId);
            }
            arriving.merge(target, 1, Integer::sum);
            produced.add(new Produced(target, token));
        }

        for (Map.Entry<String, Integer> entry : arriving.entrySet()) {
            int capacity = net.capacityOf(entry.getKey());
            int after = marking.count(entry.getKey()) - leaving.getOrDefault(entry.getKey(), 0) + entry.getValue();
            if (capacity != NetDefinition.UNLIMITED && after > capacity) {
                return Optional.empty();
            }
        }

        Duration duration = scaled(durationOf(transition, parameters), faults.durationFactor(transition.name()));
        Map<String, Object> result = new LinkedHashMap<>();
        transition.result().forEach((key, template) -> result.put(key, renderLenient(template, lookup)));
        return Optional.of(new Firing(transition, consumed, produced, lost, duration, commandId, parameters, result));
    }

    /** Guard conditions on {@code variable.attribute} for a candidate token of that variable. */
    private static boolean guardHoldsFor(Transition transition, String variable, Token candidate,
                                         Function<String, Object> lookup) {
        String prefix = variable + ".";
        for (Map.Entry<String, String> condition : transition.guard().entrySet()) {
            if (condition.getKey().startsWith(prefix)) {
                Object actual = candidate.attribute(condition.getKey().substring(prefix.length()));
                if (!matches(condition.getValue(), actual, lookup)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Guard conditions on request parameters, checked once all inputs are bound. */
    private static boolean parameterGuardHolds(Transition transition, Function<String, Object> lookup) {
        for (Map.Entry<String, String> condition : transition.guard().entrySet()) {
            if (condition.getKey().indexOf('.') > 0) {
                continue;
            }
            if (!matches(condition.getValue(), lookup.apply(condition.getKey()), lookup)) {
                return false;
            }
        }
        return true;
    }

    /** {@code expected} equals {@code actual}, or with a leading {@code !} does not. */
    private static boolean matches(String expected, Object actual, Function<String, Object> lookup) {
        boolean negated = expected.startsWith("!");
        Optional<String> value = resolve(negated ? expected.substring(1) : expected, lookup);
        if (value.isEmpty()) {
            return false;
        }
        return negated != value.get().equals(String.valueOf(actual));
    }

    private static Object lookup(String key, Map<String, Object> parameters, Map<String, Token> variables) {
        int dot = key.indexOf('.');
        if (dot > 0 && variables.containsKey(key.substring(0, dot))) {
            return variables.get(key.substring(0, dot)).attribute(key.substring(dot + 1));
        }
        return parameters.get(key);
    }

    /** Fills a template; empty if a value is missing. Plain text is returned as it is. */
    private static Optional<String> resolve(String template, Function<String, Object> lookup) {
        if (!Templates.isTemplate(template)) {
            return Optional.of(template);
        }
        try {
            return Optional.of(Templates.render(template, lookup));
        } catch (IllegalArgumentException missing) {
            return Optional.empty();
        }
    }

    /** Results tolerate missing values: they render as {@code null}. */
    private static Object renderLenient(String template, Function<String, Object> lookup) {
        if (!Templates.isTemplate(template)) {
            return template;
        }
        return Templates.render(template, key -> {
            Object value = lookup.apply(key);
            return value == null ? "null" : value;
        });
    }

    private static Duration durationOf(Transition transition, Map<String, Object> parameters) {
        if (transition.durationParameter() == null) {
            return transition.duration();
        }
        Object value = parameters.get(transition.durationParameter());
        double units;
        try {
            units = value == null ? 0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException notANumber) {
            units = 0;
        }
        long extra = Math.round(transition.durationPerUnit().toNanos() * Math.max(units, 0));
        return transition.duration().plusNanos(extra);
    }

    private static Duration scaled(Duration duration, double factor) {
        if (factor == 1.0) {
            return duration;
        }
        return Duration.ofNanos(Math.round(duration.toNanos() * factor));
    }
}
