package ch.hsg.simulation.api;

import java.util.Map;
import java.util.Objects;

/**
 * How a layout is drawn: a canvas size, an optional background image URL and a position per
 * component. Components without a position are placed by the UI.
 */
public record LayoutView(int width, int height, String backgroundImage, Map<String, Point> positions) {

    public record Point(int x, int y) {
    }

    public LayoutView {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Layout size must be positive");
        }
        positions = Map.copyOf(Objects.requireNonNull(positions, "positions"));
    }
}
