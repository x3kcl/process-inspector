package io.inspector.security.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The detective backstop against four-eyes collusion (IDP-SECURITY.md §9, ⚠️ sixth-seat): a
 * ≤25-user org can't sustain a fully segregated approver class, so every {@code ACCESS_ADMIN}-grant
 * create/modify/remove ALWAYS fires the security-alert channel <b>in addition to</b> four-eyes. This
 * default implementation emits a stable, greppable marker for log-based alerting (the R-OPS-03 alert
 * routing binds to it, like {@code audit_insert_failures_total}); a deploy can replace the bean to
 * page/webhook.
 */
@Component
public class SecurityAlertChannel {

    private static final Logger log = LoggerFactory.getLogger(SecurityAlertChannel.class);

    /** Fire a security alert. {@code event} is a stable slug; {@code detail} is human context. */
    public void fire(String event, String detail) {
        log.warn("SECURITY_ALERT event={} detail={}", event, detail);
    }
}
