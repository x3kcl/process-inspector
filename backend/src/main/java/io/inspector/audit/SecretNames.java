package io.inspector.audit;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The secret-name rule (R-AUD-03, #741): a variable/field name whose VALUE must be masked.
 *
 * <ul>
 *   <li>Long names match as a case-insensitive substring.
 *   <li>Short names (and every configured addition) match only as a whole word or a camelCase /
 *       snake_case / kebab-case / digit-boundary segment, so {@code userPin} hits and {@code
 *       shipping} does not.
 * </ul>
 *
 * Built-ins cannot be removed; {@code inspector.secret-names.additional} only adds.
 */
public final class SecretNames {
    private static final List<String> LONG = List.of(
            "password",
            "passwd",
            "secret",
            "token",
            "credential",
            "apikey",
            "api_key",
            "api-key",
            "authorization",
            "bearer",
            "cookie",
            "private_key",
            "privatekey");

    private static final Set<String> SEGMENTS = Set.of("auth", "pin", "pwd", "session");

    /** Boundaries: any non-alphanumeric run, lower→Upper, ACRONYM→Word, letter↔digit. */
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+|(?<=\\p{Ll})(?=\\p{Lu})"
            + "|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})");

    private static volatile List<String> additional = List.of();

    private SecretNames() {}

    /** Boot hook: installs the validated {@code inspector.secret-names.additional}. */
    public static void configure(List<String> names) {
        additional = names == null ? List.of() : List.copyOf(names);
    }

    public static boolean isSecret(String name) {
        return matches(name, additional);
    }

    public static boolean matches(String name, List<String> extra) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (LONG.stream().anyMatch(lower::contains)) {
            return true;
        }
        List<String> segments = segments(name);
        return segments.stream().anyMatch(SEGMENTS::contains)
                || extra.stream()
                        .anyMatch(e -> containsRun(segments, segments(e))
                                && (isWord(e) || lower.contains(e.toLowerCase(Locale.ROOT))));
    }

    /** A separator-free addition is one segment; one with a separator must also appear verbatim. */
    private static boolean isWord(String e) {
        return e.matches("[A-Za-z0-9]+");
    }

    private static List<String> segments(String name) {
        return java.util.Arrays.stream(SPLIT.split(name))
                .map(x -> x.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static boolean containsRun(List<String> segments, List<String> run) {
        return java.util.Collections.indexOfSubList(segments, run) >= 0;
    }
}
