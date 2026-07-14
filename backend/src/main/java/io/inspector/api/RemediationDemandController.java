package io.inspector.api;

import io.inspector.audit.RemediationDemandAnalysisService;
import io.inspector.audit.RemediationDemandAnalysisService.RemediationDemandAnalysis;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue #106 S0: the "honest gating" evidence check for the v2 remediation-playbooks build
 * trigger (R-GOV-08). Read-only, computed on demand from the audit golden master — nothing
 * here is persisted or scheduled; it's a measurement tool for a one-time product decision,
 * not a running feature. ADMIN-gated: the sequence findings surface cross-engine operator
 * behavior patterns, a more sensitive view than the raw per-row operations log.
 */
@RestController
@RequestMapping("/api/admin/remediation-demand")
public class RemediationDemandController {

    private final RemediationDemandAnalysisService service;

    public RemediationDemandController(RemediationDemandAnalysisService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'ADMIN')")
    public RemediationDemandAnalysis analyze() {
        return service.analyze();
    }
}
