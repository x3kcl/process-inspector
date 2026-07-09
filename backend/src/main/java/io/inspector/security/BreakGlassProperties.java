package io.inspector.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Break-glass config (IDP-SECURITY.md §7, R-SAFE-06/11) under {@code inspector.security.break-glass}.
 * Prod-only (wired in the {@code oidc} chain). The sealed-account PASSWORD is never here — it is an
 * env ref ({@code INSPECTOR_BREAK_GLASS_PASSWORD}, iron rule, rotate-after-use); break-glass is
 * enabled only when that env var is set AND {@code enabled} is true. Grants ADMIN-global (never a
 * fleet grant); 4 h absolute session cap (< the normal 24 h).
 */
@ConfigurationProperties(prefix = "inspector.security.break-glass")
public record BreakGlassProperties(Boolean enabled, String username, Integer sessionCapHours, String sinkFile) {

    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    /** Local tamper-evident audit sink path used only when Postgres is unreachable (§7). */
    public String sinkFileOrDefault() {
        return sinkFile != null && !sinkFile.isBlank() ? sinkFile : "break-glass-audit.jsonl";
    }

    public String usernameOrDefault() {
        return username != null && !username.isBlank() ? username : "break-glass";
    }

    public int sessionCapHoursOrDefault() {
        int h = sessionCapHours != null && sessionCapHours > 0 ? sessionCapHours : 4;
        return Math.min(h, 4); // never longer than 4 h (R-SAFE-11)
    }
}
