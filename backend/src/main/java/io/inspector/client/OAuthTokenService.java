package io.inspector.client;

import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.RegistryProperties;
import io.inspector.registry.RegistryPinRegistry;
import io.inspector.registry.RegistryUrlValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Client-credentials tokens for registry auth type {@code oauth2-client-credentials} (#490).
 * One cache entry per engine. Refresh is guarded by a {@link ReentrantLock} (virtual threads,
 * issue #306). The token value is never logged. A dead token endpoint fails this engine only.
 */
@Component
public class OAuthTokenService {

    /** Package-visible so tests can skip the SSRF pin (WireMock is loopback). */
    interface EndpointGuard {
        void check(String url, EngineEnvironment environment);
    }

    private final Environment env;
    private final EndpointGuard guard;
    private final Clock clock;
    private final RestClient http = RestClient.create();
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Autowired
    public OAuthTokenService(Environment env, RegistryPinRegistry pins, RegistryProperties registryProperties) {
        this(env, (url, environment) -> {
            RegistryUrlValidator.Result result = pins.validate(url, environment, registryProperties.egressPolicy());
            if (result instanceof RegistryUrlValidator.Rejected rejected) {
                throw new IllegalStateException("token endpoint rejected: " + rejected.message());
            }
        });
    }

    OAuthTokenService(Environment env, EndpointGuard guard) {
        this(env, guard, Clock.systemUTC());
    }

    OAuthTokenService(Environment env, EndpointGuard guard, Clock clock) {
        this.env = env;
        this.guard = guard;
        this.clock = clock;
    }

    public String bearer(EngineConfig engine) {
        ReentrantLock lock = locks.computeIfAbsent(engine.id(), id -> new ReentrantLock());
        lock.lock();
        try {
            Cached cached = cache.get(engine.id());
            if (cached != null && clock.instant().isBefore(cached.refreshAt())) {
                return cached.token();
            }
            return fetch(engine).token();
        } finally {
            lock.unlock();
        }
    }

    /** Drop the cached token so the next call mints a new one. Used after a read 401. */
    public void invalidate(String engineId) {
        cache.remove(engineId);
    }

    private Cached fetch(EngineConfig engine) {
        Auth auth = engine.auth();
        guard.check(auth.oauthTokenUrl(), engine.environment());
        String secret = env.getProperty(auth.oauthClientSecretRef());
        if (secret == null) {
            throw new IllegalStateException("Secret env var not set: " + auth.oauthClientSecretRef());
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", auth.oauthClientId());
        form.add("client_secret", secret);
        if (auth.oauthScope() != null && !auth.oauthScope().isBlank()) {
            form.add("scope", auth.oauthScope());
        }
        if (auth.oauthAudience() != null && !auth.oauthAudience().isBlank()) {
            form.add("audience", auth.oauthAudience());
        }
        TokenResponse body = http.post()
                .uri(auth.oauthTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (body == null || body.access_token() == null || body.access_token().isBlank()) {
            throw new IllegalStateException("token endpoint returned no access_token");
        }
        long expires = body.expires_in() == null || body.expires_in() <= 0 ? 60 : body.expires_in();
        Instant refreshAt = clock.instant().plusSeconds(Math.max(1, (expires * 4) / 5));
        Cached cached = new Cached(body.access_token(), refreshAt);
        cache.put(engine.id(), cached);
        return cached;
    }

    private record Cached(String token, Instant refreshAt) {}

    /** IdP token response. Field names match the wire, not Java style. */
    private record TokenResponse(String access_token, Long expires_in) {}
}
