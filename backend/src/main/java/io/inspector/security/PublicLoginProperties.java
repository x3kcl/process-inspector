package io.inspector.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Public demo logins on the dev ({@code !oidc}) chain: the users named in {@code public-users}
 * sign in with {@code public-password}; every other ladder user keeps {@code dev-password}. Both
 * unset = one shared password, as before. Env: {@code INSPECTOR_SECURITY_PUBLICPASSWORD},
 * {@code INSPECTOR_SECURITY_PUBLICUSERS} (comma list). The rules are in {@link DevLogins}.
 */
@ConfigurationProperties(prefix = "inspector.security")
public record PublicLoginProperties(String publicPassword, List<String> publicUsers) {}
