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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The semantics of a timed, coloured Petri net, without side effects: which transition can fire
 * next and what its firing produces. The engine applies the result.
 *
 * <p>Rules:
 * <ul>
 *   <li>Transitions are tried by priority (high first), then in declaration order.</li>
 *   <li>Each input arc takes the oldest token of its place. An arc whose place is a template
 *       ({@code ${start}}) is resolved from the parameters of the tokens consumed before it
 *       without a variable, i.e. the command token.</li>
 *   <li>A transition is enabled only if every output place keeps within its capacity.</li>
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
                         List<Token> lost, Duration duration, UUID commandId, Map<String, Object> parameters) {
        public Firing {
            consumed = List.copyOf(consumed);
            produced = List.copyOf(produced);
            lost = List.copyOf(lost);
            parameters = Map.copyOf(parameters);
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
        List<Consumed> consumed = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        Map<String, Object> parameters = new LinkedHashMap<>();
        Map<String, Token> variables = new HashMap<>();
        Map<String, Integer> leaving = new HashMap<>();

        for (Arc arc : transition.inputs()) {
            Optional<String> place = resolve(arc.place(), parameters, variables);
            if (place.isEmpty() || !marking.hasPlace(place.get())) {
                return Optional.empty();
            }
            Optional<Token> token = marking.tokensAt(place.get()).stream()
                    .filter(candidate -> !taken.contains(candidate.id()))
                    .findFirst();
            if (token.isEmpty()) {
                return Optional.empty();
            }
            taken.add(token.get().id());
            leaving.merge(place.get(), 1, Integer::sum);
            consumed.add(new Consumed(place.get(), token.get(), arc.variable()));
            if (arc.variable() != null) {
                variables.put(arc.variable(), token.get());
            } else {
                token.get().attributes().forEach(parameters::putIfAbsent);
            }
        }

        UUID commandId = consumed.stream().map(entry -> entry.token().commandId())
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        Optional<String> misroute = faults.misrouteTarget(transition.name());
        boolean losesTokens = faults.losesTokens(transition.name());

        List<Produced> produced = new ArrayList<>();
        List<Token> lost = new ArrayList<>();
        Map<String, Integer> arriving = new HashMap<>();
        for (Arc arc : transition.outputs()) {
            Optional<String> place = resolve(arc.place(), parameters, variables);
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

        Duration duration = scaled(transition.duration(), faults.durationFactor(transition.name()));
        return Optional.of(new Firing(transition, consumed, produced, lost, duration, commandId, parameters));
    }

    /**
     * Fills a template place: {@code ${name}} from the command's parameters,
     * {@code ${variable.attribute}} from a bound token. Empty if a value is missing.
     */
    private static Optional<String> resolve(String place, Map<String, Object> parameters, Map<String, Token> variables) {
        if (!Templates.isTemplate(place)) {
            return Optional.of(place);
        }
        try {
            return Optional.of(Templates.render(place, key -> {
                int dot = key.indexOf('.');
                if (dot > 0 && variables.containsKey(key.substring(0, dot))) {
                    return variables.get(key.substring(0, dot)).attribute(key.substring(dot + 1));
                }
                return parameters.get(key);
            }));
        } catch (IllegalArgumentException missing) {
            return Optional.empty();
        }
    }

    private static Duration scaled(Duration duration, double factor) {
        if (factor == 1.0) {
            return duration;
        }
        return Duration.ofNanos(Math.round(duration.toNanos() * factor));
    }
}
