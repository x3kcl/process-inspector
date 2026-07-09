package io.inspector.api;

import io.inspector.audit.LegalHoldService;
import io.inspector.audit.LegalHoldService.LegalHoldDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legal-hold admin surface (M4-CLOSEOUT §5c / S5b): place / release / list audit legal holds that
 * suspend the retention purge for overlapping partitions (enforced in the DB by {@code
 * purge_audit()}). ADMIN-gated; every set/release is an audited fail-closed config event carrying
 * the acting human.
 */
@RestController
@RequestMapping("/api/admin/legal-holds")
public class LegalHoldController {

    private final LegalHoldService service;

    public LegalHoldController(LegalHoldService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'ADMIN')")
    public List<LegalHoldDto> active() {
        return service.listActive();
    }

    @PostMapping
    @PreAuthorize("@rbac.atLeast(authentication, 'ADMIN')")
    public Map<String, String> set(@RequestBody LegalHoldRequest request, Authentication authentication) {
        UUID id = service.set(
                request.engineId(),
                request.tenantId(),
                request.fromTs(),
                request.toTs(),
                request.reason(),
                authentication.getName());
        return Map.of("id", id.toString());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@rbac.atLeast(authentication, 'ADMIN')")
    public void release(@PathVariable UUID id, Authentication authentication) {
        service.release(id, authentication.getName());
    }

    /** Create-hold body. {@code engineId}/{@code tenantId} null = fleet-wide; window is [fromTs, toTs). */
    public record LegalHoldRequest(String engineId, String tenantId, Instant fromTs, Instant toTs, String reason) {}
}
