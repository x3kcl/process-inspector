package io.inspector.api;

import io.inspector.audit.AuditService;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.ScopeGrant;
import io.inspector.security.mapping.MappingSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/access-review} (IDP-SECURITY.md §5/§10, R-SAFE-07) — the effective-grant export,
 * the release-gate "who can do what across the fleet" artifact (R-GOV-02). The full mapping
 * expansion (group → grant rows) with a <b>grant-type column (ladder|fleet)</b> + a {@code source}
 * tag, plus the caller's own effective session grants. {@code ACCESS_ADMIN}-gated at the door AND
 * an <b>audited read</b> (a security-relevant disclosure — a recon oracle, §7). Emits JSON (default),
 * CSV, or Markdown via {@code ?format=}.
 */
@RestController
@PreAuthorize("@rbac.canAdministerAccess(authentication)")
public class AccessReviewController {

    private final MappingSource mappingSource;
    private final RbacAuthorizer rbac;
    private final AuditService audit;

    public AccessReviewController(MappingSource mappingSource, RbacAuthorizer rbac, AuditService audit) {
        this.mappingSource = mappingSource;
        this.rbac = rbac;
        this.audit = audit;
    }

    @GetMapping("/api/access-review")
    public ResponseEntity<?> review(@RequestParam(defaultValue = "json") String format, Authentication authentication) {
        // The read itself is audited fail-closed — reading the whole grant map is a recon oracle (§7).
        audit.recordConfigEvent(
                "access-review-read",
                authentication.getName(),
                true,
                Map.of("format", format, "surface", "access-review"));

        List<Row> rows = new ArrayList<>();
        for (MappingSource.LadderGrantRow r : mappingSource.allLadderGrants()) {
            rows.add(new Row("ladder", r.group(), r.role().name(), r.engineId(), r.tenantId(), r.source()));
        }
        for (MappingSource.FleetGrantRow r : mappingSource.allFleetGrants()) {
            rows.add(new Row("fleet", r.group(), r.grant().name(), "-", "-", r.source()));
        }
        List<String> callerGrants = rbac.grantsFor(authentication).stream()
                .map(ScopeGrant::toString)
                .sorted()
                .toList();

        return switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "csv" -> text(csv(rows), "text/csv", "access-review.csv");
            case "md", "markdown" -> text(markdown(rows, callerGrants), "text/markdown", "access-review.md");
            default -> ResponseEntity.ok(new AccessReview(rows, callerGrants));
        };
    }

    private static ResponseEntity<String> text(String body, String contentType, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType + ";charset=UTF-8"))
                .body(body);
    }

    private static String csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("grantType,group,role,engineId,tenantId,source\n");
        for (Row r : rows) {
            sb.append(csvCell(r.grantType()))
                    .append(',')
                    .append(csvCell(r.group()))
                    .append(',')
                    .append(csvCell(r.role()))
                    .append(',')
                    .append(csvCell(r.engineId()))
                    .append(',')
                    .append(csvCell(r.tenantId()))
                    .append(',')
                    .append(csvCell(r.source()))
                    .append('\n');
        }
        return sb.toString();
    }

    /** RFC-4180 escaping — quote + double any cell with a comma/quote/newline (injection-safe). */
    private static String csvCell(String v) {
        String s = v == null ? "" : v;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String markdown(List<Row> rows, List<String> callerGrants) {
        StringBuilder sb = new StringBuilder("# Access review\n\n");
        sb.append("| Grant type | Group | Role/Kind | Engine | Tenant | Source |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| ")
                    .append(r.grantType())
                    .append(" | ")
                    .append(r.group())
                    .append(" | ")
                    .append(r.role())
                    .append(" | ")
                    .append(r.engineId())
                    .append(" | ")
                    .append(r.tenantId())
                    .append(" | ")
                    .append(r.source())
                    .append(" |\n");
        }
        sb.append("\n## Your effective grants\n\n");
        callerGrants.forEach(g -> sb.append("- ").append(g).append('\n'));
        return sb.toString();
    }

    public record AccessReview(List<Row> grants, List<String> callerGrants) {}

    /** grantType distinguishes a ladder grant from a fleet grant (⚠️ UX — never blurs the two). */
    public record Row(String grantType, String group, String role, String engineId, String tenantId, String source) {}
}
