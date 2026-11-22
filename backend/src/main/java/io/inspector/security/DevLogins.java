package io.inspector.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Per-user passwords of the dev ladder ({@code !oidc}). With {@code public-password} and
 * {@code public-users} unset every user has {@code dev-password} (default "dev"), as before. With
 * both set, the listed users sign in with the public password ONLY and every other user with the
 * private one ONLY. Misconfiguration refuses startup rather than guessing.
 */
final class DevLogins {

    static final String DEFAULT_PASSWORD = "dev";

    private DevLogins() {}

    static Map<String, String> passwords(
            List<String> users, String devPassword, String publicPassword, List<String> publicUsers) {
        boolean privateSet = devPassword != null && !devPassword.isBlank();
        String privatePassword = privateSet ? devPassword : DEFAULT_PASSWORD;
        Set<String> listed = publicUsers == null
                ? Set.of()
                : publicUsers.stream()
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(u -> !u.isEmpty())
                        .collect(Collectors.toSet());
        boolean passwordSet = publicPassword != null && !publicPassword.isBlank();
        if (passwordSet != !listed.isEmpty()) {
            throw new IllegalStateException(
                    "inspector.security.public-password and inspector.security.public-users must be set together");
        }
        if (passwordSet) {
            if (!privateSet) {
                throw new IllegalStateException(
                        "inspector.security.public-password needs an explicit inspector.security.dev-password");
            }
            if (ENCODER.matches(publicPassword, privatePassword)) {
                throw new IllegalStateException(
                        "inspector.security.public-password must differ from inspector.security.dev-password");
            }
            listed.stream().filter(u -> !users.contains(u)).sorted().findFirst().ifPresent(u -> {
                throw new IllegalStateException("inspector.security.public-users names an unknown user: " + u);
            });
        }
        Map<String, String> passwords = new LinkedHashMap<>();
        users.forEach(u -> passwords.put(u, listed.contains(u) ? publicPassword : privatePassword));
        return passwords;
    }

    /**
     * Plain-text in memory like {@code {noop}}, but compared in constant time: both sides are
     * hashed first, so neither the content nor the length of the stored password shows in timing.
     */
    static final PasswordEncoder ENCODER = new PasswordEncoder() {
        @Override
        public String encode(CharSequence raw) {
            return raw.toString();
        }

        @Override
        public boolean matches(CharSequence raw, String stored) {
            return raw != null && stored != null && MessageDigest.isEqual(sha256(raw.toString()), sha256(stored));
        }
    };

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
