package io.inspector.api;

import io.inspector.config.AuditProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/meta} — the SPA's small config channel (OPERATIONS §5). For now it carries the
 * boot-validated {@code ticket-url-template} (R-AUD-07) the audit surfaces linkify a ticketId with;
 * it is null when no template is configured (the SPA then renders plain text). Any authenticated
 * user may read it — it is non-sensitive config, never a secret.
 */
@RestController
@RequestMapping("/api")
public class MetaController {

    private final AuditProperties audit;

    public MetaController(AuditProperties audit) {
        this.audit = audit;
    }

    @GetMapping("/meta")
    public MetaDto meta() {
        return new MetaDto(audit.ticketUrlTemplate());
    }

    public record MetaDto(String ticketUrlTemplate) {}
}
