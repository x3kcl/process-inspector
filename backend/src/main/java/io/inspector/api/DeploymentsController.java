package io.inspector.api;

import io.inspector.catalog.DeleteDeploymentRequest;
import io.inspector.catalog.DeleteDeploymentResponse;
import io.inspector.catalog.DeploymentCatalogResponse;
import io.inspector.catalog.DeploymentCatalogService;
import io.inspector.catalog.DeploymentDetailResponse;
import io.inspector.security.ReadScopeGate;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Deployment browser and the tier-3 delete verb (#483 S4). Upload stays out (decision #505). */
@RestController
@RequestMapping("/api")
public class DeploymentsController {

    private final DeploymentCatalogService deployments;
    private final ReadScopeGate readScope;

    public DeploymentsController(DeploymentCatalogService deployments, ReadScopeGate readScope) {
        this.deployments = deployments;
        this.readScope = readScope;
    }

    @GetMapping("/deployments")
    public DeploymentCatalogResponse list(
            @RequestParam(required = false) Set<String> engineIds,
            @RequestParam(required = false) String name,
            Authentication auth) {
        return deployments.list(engineIds, name, readScope.readableEngineIds(auth));
    }

    @GetMapping("/deployments/{engineId}/{deploymentId}")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public DeploymentDetailResponse detail(@PathVariable String engineId, @PathVariable String deploymentId) {
        return deployments.detail(engineId, deploymentId);
    }

    @PostMapping("/deployments/{engineId}/{deploymentId}/delete")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'ADMIN', #engineId)")
    public DeleteDeploymentResponse delete(
            @PathVariable String engineId,
            @PathVariable String deploymentId,
            @RequestBody DeleteDeploymentRequest request,
            Authentication authentication) {
        return deployments.delete(engineId, deploymentId, request, authentication);
    }
}
