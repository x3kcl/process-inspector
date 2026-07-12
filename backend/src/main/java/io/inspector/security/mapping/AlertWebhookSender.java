package io.inspector.security.mapping;

import io.inspector.security.SecurityProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The real alert egress behind {@link SecurityAlertChannel} (S3). Where the channel's default is a
 * greppable {@code log.warn} marker, this POSTs the same alert to a configured webhook (Slack /
 * PagerDuty / a router), so an {@code ACCESS_ADMIN} change or a break-glass login actually pages
 * someone rather than landing only in a log nobody watches during an incident.
 *
 * <p>The URL is an ENV REF (iron rule): {@code inspector.security.alert-webhook-url-ref} names the
 * env var; the value (often itself a secret, e.g. a Slack token in the path) is resolved from the
 * {@link Environment} at boot, never stored in config. Blank/unresolvable ⇒ disabled (log-only), and
 * under the {@code oidc} profile that absence is a BOOT WARNING — a prod deploy that pages nobody is
 * a config gap, not a silent default.
 *
 * <p>Sending is FIRE-AND-FORGET on a virtual thread with short timeouts and every failure swallowed
 * (logged, never rethrown): a slow or dead webhook must NEVER block or fail the security flow it is
 * reporting on (a break-glass login must complete even if the pager is down).
 */
@Component
public class AlertWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookSender.class);

    private final String url; // resolved once at boot; null = disabled (log-only)
    private final RestClient client;
    private final ExecutorService dispatch = Executors.newVirtualThreadPerTaskExecutor();

    public AlertWebhookSender(SecurityProperties properties, Environment environment) {
        String ref = properties.alertWebhookUrlRefOrNull();
        String resolved = ref != null ? environment.getProperty(ref) : null;
        this.url = resolved != null && !resolved.isBlank() ? resolved : null;

        HttpClient http = HttpClient.newBuilder()
                .version(
                        HttpClient.Version.HTTP_1_1) // webhooks (Slack/PagerDuty) speak HTTP/1.1; avoid h2c negotiation
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
        rf.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestFactory(rf).build();

        if (this.url == null && environment.acceptsProfiles(Profiles.of("oidc"))) {
            log.warn("SECURITY_ALERT_WEBHOOK_UNCONFIGURED — ACCESS_ADMIN changes and break-glass logins alert to the "
                    + "log ONLY. Set inspector.security.alert-webhook-url-ref to an env var holding the webhook URL.");
        } else if (this.url != null) {
            log.info("Security-alert webhook configured (env ref {}).", ref);
        }
    }

    /** True when a webhook URL resolved (alerts are POSTed in addition to the log marker). */
    public boolean isEnabled() {
        return url != null;
    }

    /** Fire-and-forget POST of one alert; a no-op (log-only) when unconfigured; never throws. */
    public void post(String event, String detail) {
        if (url == null) {
            return;
        }
        dispatch.execute(() -> {
            try {
                client.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("event", event, "detail", detail))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RuntimeException e) {
                // A down/slow pager must never surface into the security flow — log and move on.
                log.warn("SECURITY_ALERT_WEBHOOK_FAILED event={} — {}", event, e.toString());
            }
        });
    }
}
