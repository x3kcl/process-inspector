package io.inspector.security.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The detective backstop against four-eyes collusion (IDP-SECURITY.md §9, ⚠️ sixth-seat): a
 * ≤25-user org can't sustain a fully segregated approver class, so every {@code ACCESS_ADMIN}-grant
 * create/modify/remove ALWAYS fires the security-alert channel <b>in addition to</b> four-eyes; a
 * break-glass login and a break-glass brute-force (S4) fire it too.
 *
 * <p>The always-on leg is a stable, greppable {@code log.warn} marker (the R-OPS-03 alert routing
 * binds to it, like {@code audit_insert_failures_total}). S3 adds a real egress: when an
 * {@link AlertWebhookSender} webhook is configured, every alert is ALSO POSTed there so an incident
 * actually pages someone. The webhook send is fire-and-forget and swallows its own failures, so this
 * method never blocks or fails the security flow it reports on.
 */
@Component
public class SecurityAlertChannel {

    private static final Logger log = LoggerFactory.getLogger(SecurityAlertChannel.class);

    private final AlertWebhookSender webhook;

    public SecurityAlertChannel(AlertWebhookSender webhook) {
        this.webhook = webhook;
    }

    /** Fire a security alert. {@code event} is a stable slug; {@code detail} is human context. */
    public void fire(String event, String detail) {
        log.warn("SECURITY_ALERT event={} detail={}", event, detail);
        webhook.post(event, detail);
    }
}
