package io.inspector.api;

import io.inspector.dto.DefinitionHeat;
import io.inspector.heat.DefinitionHeatService;
import io.inspector.security.ReadScopeGate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Definition heat (#784, DEFINITION-HEATMAP.md §6): one definition version, a sample of its newest
 * finished instances. VIEWER on that engine, read scope respected; a read, so no audit rail. No
 * variable value in the response.
 */
@RestController
@RequestMapping("/api")
public class DefinitionHeatController {

    private final DefinitionHeatService heat;
    private final ReadScopeGate readScope;

    public DefinitionHeatController(DefinitionHeatService heat, ReadScopeGate readScope) {
        this.heat = heat;
        this.readScope = readScope;
    }

    @GetMapping("/definitions/{engineId}/{definitionId}/heat")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public DefinitionHeat heat(
            @PathVariable String engineId,
            @PathVariable String definitionId,
            @RequestParam(required = false) Integer n,
            @RequestParam(defaultValue = "false") boolean refresh,
            Authentication auth) {
        if (readScope.enforced() && !readScope.canRead(auth, engineId)) {
            throw new AccessDeniedException("Access Denied"); // the @PreAuthorize refusal, verbatim
        }
        return heat.heat(engineId, definitionId, n, refresh);
    }
}
