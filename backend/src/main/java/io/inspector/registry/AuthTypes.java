package io.inspector.registry;

import io.inspector.config.InspectorProperties.Auth;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Auth-type wire spelling and the registry write checks (#490). No secret values. */
public final class AuthTypes {

    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Set<String> FORBIDDEN_HEADERS =
            Set.of("authorization", "cookie", "host", "content-type", "content-length", "transfer-encoding");

    private AuthTypes() {}

    public static void check(RegistryWrite write) {
        Auth.Type type = Auth.Type.fromWire(write.authType());
        switch (type) {
            case oauth2_client_credentials -> {
                require(write.oauthTokenUrl(), "oauth token url");
                require(write.oauthClientId(), "oauth client id");
                require(write.oauthClientSecretRef(), "oauth client secret ref");
            }
            case custom_header -> {
                require(checkHeaderName(write.headerName()), "auth header name");
                require(write.headerSecretRef(), "auth header secret ref");
            }
            case basic, bearer, none -> {
                /* existing types */
            }
        }
    }

    /** Returns the name when it is safe to send. Throws when it is not. */
    public static String checkHeaderName(String name) {
        if (name == null || !HEADER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("auth header name must be 1-64 letters, digits, or hyphens");
        }
        if (FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("auth header name '" + name + "' is not allowed");
        }
        return name;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
