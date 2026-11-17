package io.inspector.audit;

import java.util.List;
import java.util.Locale;

/** Secret-name matcher (R-AUD-03, #741). STUB for the RED commit: today's eight-name substring rule. */
public final class SecretNames {
    private static final List<String> LONG =
            List.of("password", "passwd", "secret", "token", "credential", "apikey", "api_key", "api-key");

    private SecretNames() {}

    public static boolean matches(String name, List<String> additional) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return LONG.stream().anyMatch(lower::contains);
    }

    public static boolean isSecret(String name) {
        return matches(name, List.of());
    }
}
