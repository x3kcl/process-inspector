package io.inspector.api;

import io.inspector.migration.DefinitionVersionService;
import io.inspector.migration.DefinitionVersionsResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Definition-scoped reads. The definition-versions on-ramp for instance migration (cohort
 * visibility): per-version RUNNING instance counts. Read-only, count-only (Stage-0); VIEWER floor
 * like every other read.
 */
@RestController
@RequestMapping("/api")
public class DefinitionsController {

    private final DefinitionVersionService versions;

    public DefinitionsController(DefinitionVersionService versions) {
        this.versions = versions;
    }

    @GetMapping("/definitions/{engineId}/{key}/versions")
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public DefinitionVersionsResponse versions(@PathVariable String engineId, @PathVariable String key) {
        return versions.versions(engineId, key);
    }
}
