package io.inspector.security;

import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Per-user passwords of the dev ladder (public demo logins). */
final class DevLogins {

    private DevLogins() {}

    static Map<String, String> passwords(
            List<String> users, String devPassword, String publicPassword, List<String> publicUsers) {
        throw new UnsupportedOperationException("RED stub");
    }

    static final PasswordEncoder ENCODER = new PasswordEncoder() {
        @Override
        public String encode(CharSequence raw) {
            throw new UnsupportedOperationException("RED stub");
        }

        @Override
        public boolean matches(CharSequence raw, String stored) {
            throw new UnsupportedOperationException("RED stub");
        }
    };
}
