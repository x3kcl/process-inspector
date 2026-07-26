package io.inspector.api;

import io.inspector.audit.AuditChainVerifier;
import io.inspector.audit.AuditChainVerifier.AuditIntegrityReport;
import io.inspector.audit.AuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue #304: the chain-walk verifier the tamper-evidence claim never had (see
 * {@link AuditChainVerifier}'s javadoc for exactly what it does and does not prove). Fleet-ADMIN
 * only, like {@code /api/admin/remediation-demand} — the finding surfaces cross-engine ops
 * behavior via the mismatch/gap detail. It is a READ, but an AUDITED one (mirrors
 * {@code AdminAccessController#list}'s {@code access-read} config event): running the check is
 * itself an accountable act, and the outcome (intact or not) belongs in the golden master it just
 * verified.
 */
@RestController
@RequestMapping("/api/admin/audit/integrity")
public class AuditIntegrityController {

    private final AuditChainVerifier verifier;
    private final AuditService audit;

    public AuditIntegrityController(AuditChainVerifier verifier, AuditService audit) {
        this.verifier = verifier;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'ADMIN')")
    public AuditIntegrityReport check(Authentication authentication) {
        AuditIntegrityReport report = verifier.verify();
        audit.recordConfigEvent("audit-integrity-check", authentication.getName(), true, summary(report));
        return report;
    }

    /** A bounded skeleton for the audit row — the full findings list stays in the HTTP response only. */
    private static Map<String, Object> summary(AuditIntegrityReport report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intact", report.intact());
        payload.put("rowsChecked", report.rowsChecked());
        payload.put("partitionsSpanned", report.partitionsSpanned());
        payload.put("lastSeqChecked", report.lastSeqChecked());
        payload.put("firstProblemSeq", report.firstProblemSeq());
        payload.put("findingsTotal", report.findingsTotal());
        payload.put("rowCapHit", report.rowCapHit());
        return payload;
    }
}
