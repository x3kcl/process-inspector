package io.inspector.api;

import io.inspector.catalog.BpmnDiffResponse;
import io.inspector.catalog.DefinitionCatalogResponse;
import io.inspector.catalog.DefinitionCatalogService;
import io.inspector.catalog.DefinitionDetailResponse;
import io.inspector.catalog.DefinitionDetailService;
import io.inspector.catalog.DefinitionJobsResponse;
import io.inspector.catalog.DefinitionSubscriptionsResponse;
import io.inspector.catalog.DefinitionXmlResponse;
import io.inspector.security.ReadScopeGate;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Definition catalog, detail, and version diff (#483). VIEWER-floor reads. Scope-filtered the
 * same way as {@link SearchController}: {@code readableEngineIds} is resolved on the request
 * thread, never inside the fan-out.
 */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final DefinitionCatalogService catalog;
    private final DefinitionDetailService detail;
    private final ReadScopeGate readScope;

    public CatalogController(
            DefinitionCatalogService catalog, DefinitionDetailService detail, ReadScopeGate readScope) {
        this.catalog = catalog;
        this.detail = detail;
        this.readScope = readScope;
    }

    @GetMapping("/definitions")
    public DefinitionCatalogResponse definitions(
            @RequestParam(required = false) Set<String> engineIds,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "false") boolean latestOnly,
            Authentication auth) {
        return catalog.list(engineIds, key, name, latestOnly, readScope.readableEngineIds(auth));
    }

    @GetMapping("/definitions/{engineId}/{definitionId}")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public DefinitionDetailResponse definition(@PathVariable String engineId, @PathVariable String definitionId) {
        return detail.detail(engineId, definitionId);
    }

    @GetMapping("/definitions/{engineId}/{definitionId}/xml")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public DefinitionXmlResponse xml(@PathVariable String engineId, @PathVariable String definitionId) {
        return detail.xml(engineId, definitionId);
    }

    @GetMapping("/definitions/{engineId}/{definitionId}/subscriptions")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public DefinitionSubscriptionsResponse subscriptions(
            @PathVariable String engineId, @PathVariable String definitionId) {
        return detail.subscriptions(engineId, definitionId);
    }

    @GetMapping("/definitions/{engineId}/{definitionId}/jobs")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public DefinitionJobsResponse jobs(@PathVariable String engineId, @PathVariable String definitionId) {
        return detail.jobs(engineId, definitionId);
    }

    @GetMapping("/definitions/{engineId}/{definitionId}/diff")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public BpmnDiffResponse diff(
            @PathVariable String engineId, @PathVariable String definitionId, @RequestParam String against) {
        return detail.diff(engineId, definitionId, against);
    }
}
