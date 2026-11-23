package ch.hsg.simulation.core.net;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code ${name}} placeholders, e.g. in arc places ({@code ${start}}) and response
 * templates ({@code {"interrupted": ${oven_light_barrier}}}). Deliberately not an expression
 * language: a placeholder is a name looked up as it is.
 */
public final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private Templates() {
    }

    public static boolean isTemplate(String text) {
        return text != null && PLACEHOLDER.matcher(text).find();
    }

    /**
     * Replaces each placeholder with {@code lookup.apply(name)}. Unknown names (null from the
     * lookup) raise an {@link IllegalArgumentException} naming them.
     */
    public static String render(String template, Function<String, Object> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            Object value = lookup.apply(key);
            if (value == null) {
                throw new IllegalArgumentException("No value for ${" + key + "}");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
