package ch.hsg.simulation.core.net;

import ch.hsg.simulation.core.NotFoundException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The state of one simulation: which tokens sit in which place, in arrival order (first in,
 * first taken). Not thread-safe; only the engine loop touches it.
 */
public final class Marking {

    private final Map<String, List<Token>> tokens = new LinkedHashMap<>();

    /** Every place of the net, empty except for its initial tokens ({@code <place>-1}, …). */
    public static Marking initial(NetDefinition net) {
        Marking marking = new Marking();
        for (NetDefinition.Place place : net.places()) {
            List<Token> list = new ArrayList<>();
            for (int index = 1; index <= place.initial(); index++) {
                list.add(new Token(place.name() + "-" + index, Map.of(), null));
            }
            marking.tokens.put(place.name(), list);
        }
        return marking;
    }

    public boolean hasPlace(String place) {
        return tokens.containsKey(place);
    }

    public List<Token> tokensAt(String place) {
        return Collections.unmodifiableList(requirePlace(place));
    }

    public int count(String place) {
        return requirePlace(place).size();
    }

    public boolean occupied(String place) {
        return !requirePlace(place).isEmpty();
    }

    public void add(String place, Token token) {
        requirePlace(place).add(token);
    }

    /** Removes the token wherever it is; returns its place. */
    public String remove(String tokenId) {
        for (Map.Entry<String, List<Token>> entry : tokens.entrySet()) {
            if (entry.getValue().removeIf(token -> token.id().equals(tokenId))) {
                return entry.getKey();
            }
        }
        throw new NotFoundException("Unknown token: " + tokenId);
    }

    /** The token and its place, if it is in the net (not in flight inside a transition). */
    public Optional<Map.Entry<String, Token>> find(String tokenId) {
        for (Map.Entry<String, List<Token>> entry : tokens.entrySet()) {
            for (Token token : entry.getValue()) {
                if (token.id().equals(tokenId)) {
                    return Optional.of(Map.entry(entry.getKey(), token));
                }
            }
        }
        return Optional.empty();
    }

    /** Copy for snapshots: place → tokens. */
    public Map<String, List<Token>> view() {
        Map<String, List<Token>> copy = new LinkedHashMap<>();
        tokens.forEach((place, list) -> copy.put(place, List.copyOf(list)));
        return Collections.unmodifiableMap(copy);
    }

    private List<Token> requirePlace(String place) {
        List<Token> list = tokens.get(place);
        if (list == null) {
            throw new NotFoundException("Unknown place: " + place);
        }
        return list;
    }
}
