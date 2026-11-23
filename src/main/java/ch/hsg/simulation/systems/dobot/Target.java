package ch.hsg.simulation.systems.dobot;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A {@code start} or {@code end} value of the lab API, parsed like {@code parse_position} in the
 * lab's {@code app.py}: a stone position name ({@code pos1} … {@code pos8}, any case), {@code factory},
 * or {@code x,y,z,r}. {@code pose} is null for {@code factory}, which the lab code treats as a route,
 * not a pose.
 */
public record Target(String input, Pose pose) {

    public static final String FACTORY = "factory";

    /** Python {@code float()} literals without {@code inf}, {@code nan} and underscores. */
    private static final Pattern PYTHON_FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    public boolean isFactory() {
        return pose == null;
    }

    /**
     * @throws IllegalArgumentException with the lab API's message, e.g.
     *                                  {@code Invalid position format: could not convert string to float: 'foo'}
     */
    public static Target parse(String input, Map<String, Pose> named) {
        String lower = input.toLowerCase(Locale.ROOT);
        if (named.containsKey(lower)) {
            return new Target(input, named.get(lower));
        }
        if (lower.equals(FACTORY)) {
            return new Target(input, null);
        }
        List<String> parts = Arrays.asList(input.split(",", -1));
        double[] values = new double[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i).strip();
            if (!PYTHON_FLOAT.matcher(part).matches()) {
                throw new IllegalArgumentException("Invalid position format: could not convert string to float: '" + parts.get(i) + "'");
            }
            values[i] = Double.parseDouble(part);
        }
        if (values.length != 4) {
            throw new IllegalArgumentException("Invalid position format: Exactly 4 values (x,y,z,r) required");
        }
        return new Target(input, new Pose(values[0], values[1], values[2], values[3]));
    }
}
